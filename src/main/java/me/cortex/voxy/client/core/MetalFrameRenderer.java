package me.cortex.voxy.client.core;

import me.cortex.voxy.client.core.interop.IOSurfaceBridgeCompositor;
import me.cortex.voxy.client.core.rendering.Viewport;
import me.cortex.voxy.client.core.rendering.hierachical.AsyncNodeManager;
import me.cortex.voxy.client.core.rendering.hierachical.HierarchicalOcclusionTraverser;
import me.cortex.voxy.client.core.rendering.hierachical.NodeCleaner;
import me.cortex.voxy.client.core.rendering.section.backend.AbstractSectionRenderer;
import me.cortex.voxy.common.Logger;
import org.lwjgl.system.MemoryUtil;

/** Owns Metal frame resources and submission; the shared pipeline retains its public interface. */
final class MetalFrameRenderer implements AutoCloseable {
    private final AbstractRenderPipeline pipeline;
    private final AsyncNodeManager nodeManager;
    private final NodeCleaner nodeCleaner;
    private final HierarchicalOcclusionTraverser traversal;

    MetalFrameRenderer(AbstractRenderPipeline pipeline, AsyncNodeManager nodeManager,
                       NodeCleaner nodeCleaner, HierarchicalOcclusionTraverser traversal) {
        this.pipeline = pipeline;
        this.nodeManager = nodeManager;
        this.nodeCleaner = nodeCleaner;
        this.traversal = traversal;
    }

    private MetalFrameTargets targets;
    private void ensureTargets(me.cortex.voxy.client.core.metal.MetalRenderBackend backend, MetalFrameTargets.Layout layout) {
        if (this.targets != null && this.targets.layout.equals(layout)) return;
        var next = new MetalFrameTargets(backend, layout, !layout.exportDepth()
                ? me.cortex.voxy.client.core.rendering.section.backend.mdic.TerrainShaderConfiguration.load(this.pipeline, me.cortex.voxy.client.core.gpu.BackendType.METAL) : null);
        var retired = this.targets;
        this.targets = next;
        if (retired != null) retired.close();
    }

    /** Diagnostic counter; rendering identity comes from WorldFrameCapture. */
    private int metalFrame;
    private final MetalFrameDiagnostics diagnostics = new MetalFrameDiagnostics();
    /** VOXY_UNDERWATER_LOD=1 forces LOD draws even when submerged-fog saturates the far field. */
    private static final boolean UNDERWATER_LOD_FORCE = "1".equals(System.getenv("VOXY_UNDERWATER_LOD"));
    /**
     * 2026-07-03 round 3: submersionSkip used to skip the ENTIRE vx-contract
     * translucent block — including the material-plane clears — so going
     * underwater froze metalVxTrans0-2 (+ the trans depth bridge) at the last
     * above-water frame while the GL resolve kept re-compositing the stale
     * planes into the pack's colortex every frame: ghost water squares that
     * toggle with the skip at the waterline. Keep the clear-only maintenance
     * running every contract frame (cleared planes make the resolve discard
     * everything) and gate only the DRAWS on the skip.
     * VOXY_TRANS_SUBMERSION_CLEAR=0 restores the old skip-everything gating.
     */
    private static final boolean TRANS_SUBMERSION_CLEAR = !"0".equals(System.getenv("VOXY_TRANS_SUBMERSION_CLEAR"));

    /** Record traversal, opaque/material terrain, depth export and water, then wait for GL visibility. */
    void render(Viewport<?> viewport, int sourceFrameBuffer) {
        int fbw = viewport.width;
        int fbh = viewport.height;
        if (fbw <= 0 || fbh <= 0) return;
        var backend = me.cortex.voxy.client.core.gpu.RenderBackendFactory.get();
        if (!(backend instanceof me.cortex.voxy.client.core.metal.MetalRenderBackend mrb)) return;

        boolean vxMaterial = this.pipeline.vxMaterialMode();
        boolean vxOpaqueMat = this.pipeline.vxOpaqueMaterialMode();
        boolean vxContract = me.cortex.voxy.client.core.util.IrisUtil.vxContractActive();
        boolean irisGbufferInject = vxMaterial || vxContract || me.cortex.voxy.client.core.util.IrisUtil.irisGbufferInjectMode();
        boolean splitWater = irisGbufferInject && (vxMaterial || vxContract) && !this.pipeline.deferTranslucency;
        this.ensureTargets(mrb, new MetalFrameTargets.Layout(fbw, fbh, vxOpaqueMat, vxMaterial, irisGbufferInject, splitWater));
        // Hi-Z remains zero-initialized; enabling Metal occlusion is outside this refactor.
        viewport.hiZBuffer.ensureAllocated(fbw, fbh);

        // 3) Compute side — copy of innerPrimaryWork's body minus the GL bits
        //    (HiZBuffer.buildMipChain, raw glMemoryBarrier, FrEx loop). Each
        //    sub-stage is already encoder-backed (commits 0b963825, 68734b78,
        //    5dcbc645, 89b35814 for HOT's last raw-GL gaps).
        me.cortex.voxy.client.core.rendering.util.DownloadStream.INSTANCE.tick();
        this.nodeCleaner.beginFrame(viewport.frameId);
        this.nodeManager.tick(this.traversal.getNodeBuffer(), this.nodeCleaner);
        this.nodeCleaner.tick(this.traversal.getNodeBuffer());
        this.traversal.doTraversal(viewport);

        // 4) Per-frame draw-command generation — all 5 MDIC compute prepasses
        //    (prep / cull-stub / commandGen / prefixSum / translucentGen) now
        //    flow through ComputeEncoder (chunks 1–5). Raw cast matches the
        //    GL path (line 119) — the renderer's viewport generic is set at
        //    construction by RenderPipelineFactory and we trust the pairing.
        @SuppressWarnings({"rawtypes", "unchecked"})
        AbstractSectionRenderer rs = (AbstractSectionRenderer) this.pipeline.sectionRenderer;
        rs.buildDrawCalls(viewport);

        // M13 2026-05-14 baseInstance workaround: flush + wait so the compute
        // prepasses (commandGen writes drawCallBuffer's baseInstance field)
        // complete before the render pass starts. MetalRenderEncoder.
        // drawIndexedIndirect needs to CPU-read drawCallBuffer per draw to
        // push baseInstance via setVertexBytes (drawIndexedPrimitives:
        // indirectBuffer: doesn't propagate it natively). Without this
        // submit() the CPU sees stale data from the prior frame.
        if (me.cortex.voxy.client.core.util.FrameTiming.ENABLED) {
            long tFT = System.nanoTime();
            backend.submit();
            me.cortex.voxy.client.core.util.FrameTiming.drawCallFlushNs += System.nanoTime() - tFT;
        } else {
            backend.submit();
        }

        // Clear alpha denotes missing geometry; composition preserves the destination for those pixels.
        float clearR = 0.02f;
        float clearG = 0.02f;
        float clearB = 0.04f;
        if (viewport.fogParameters != null) {
            clearR = viewport.fogParameters.red();
            clearG = viewport.fogParameters.green();
            clearB = viewport.fogParameters.blue();
        }
        // DIAGNOSTIC (2026-05-25): VOXY_BRIDGE_SOLID_TEST=1 fills the bridge
        // with a static bright-green clear and SKIPS all LOD draws below. If
        // the green is rock-stable on screen, the IOSurface bridge + composite
        // + sync path is sound and the flicker lives in the LOD draws/content;
        // if the green itself flickers, the bridge/sync is the culprit.
        boolean bridgeSolidTest = "1".equals(System.getenv("VOXY_BRIDGE_SOLID_TEST"));
        if (bridgeSolidTest) {
            clearR = 0.0f; clearG = 1.0f; clearB = 0.0f;
            if ((this.metalFrame % 600) == 1) {
                Logger.info("[Metal-SOLID-TEST] VOXY_BRIDGE_SOLID_TEST active: bridge=green, LOD draws skipped");
            }
        }
        // Clear alpha 0.0: the alpha-discard composite drops undrawn bridge
        // pixels so MC's own sky/fog shows behind the LODs (kills the
        // whole-far-field fog flash when the eye crosses the water surface).
        // The blit fallback (VOXY_COMPOSITE_BLIT=1) copies raw pixels and
        // needs the M12-stable opaque clear; the solid test must stay visible.
        // Iris-pack mode injects the bridge into Iris's terrain gbuffer with
        // an alpha-discard + depth-write shader — undrawn pixels must carry
        // alpha 0 so only Voxy-drawn pixels write into the pack's colortex.
        // lodExport: TRUE for both LOD-export consumers — the legacy gbuffer
        // injection AND the native vx contract (issue #9); both need the
        // colour bridge with alpha-as-coverage plus the packed depth bridge.
        float clearA = (bridgeSolidTest || (IOSurfaceBridgeCompositor.USE_BLIT && !irisGbufferInject)) ? 1.0f : 0.0f;
        var passBuilder = me.cortex.voxy.client.core.gpu.RenderPassDesc.builder(fbw, fbh);
        if (vxOpaqueMat) {
            passBuilder.clearColor(this.targets.opaque[0].asGpuTexture(), 0f, 0f, 0f, 0f)
                       .clearColor(this.targets.opaque[1].asGpuTexture(), 0f, 0f, 0f, 0f)
                       .clearColor(this.targets.opaque[2].asGpuTexture(), 0f, 0f, 0f, 0f);
        } else {
            passBuilder.clearColor(this.targets.color.asGpuTexture(), clearR, clearG, clearB, clearA);
        }
        var pass = passBuilder.clearDepth(this.targets.depth, 1.0f).build();
        // Submersion far-field skip: with the eye in water/lava the env fog
        // saturates at 24-96 blocks while every LOD fragment sits far beyond
        // it — the whole LOD field is 100% fog colour by construction. Drawing
        // it anyway only exposes artifacts: Sodium's fog-occlusion culling
        // de-renders near seafloor whose pixels then fall through the opaque
        // blit to the LOD field ("sand turns transparent", flooded caverns),
        // and any residual draw nondeterminism strobes. Skip the LOD draws and
        // let the fog-coloured clear stand — visually identical murk, stable
        // by construction. Guarded so tiny render distances (where LOD could
        // outrange the fog) keep drawing. VOXY_UNDERWATER_LOD=1 forces draws.
        boolean submersionSkip = false;
        // useEnvFog() gate: with Voxy fog disabled there is no murk to hide
        // behind — the skip only applies when the far field is provably
        // fog-saturated. (Previously fog-off avoided the skip only by the
        // accident of MixinFogRenderer inflating envEnd to 999999999.)
        // Round 23: inject mode forces useEnvFog() false, which made this
        // skip DEAD CODE under packs — the underwater far-field protection
        // (added specifically against "sand turns transparent / flooded
        // caverns") never engaged while swimming with a pack active. In
        // inject mode the pack's own underwater fog saturates the far field
        // (BSL composites it by depth), so the skip's premise holds there.
        boolean submersionEligible = this.pipeline.useEnvFog()
                || me.cortex.voxy.client.core.util.IrisUtil.irisGbufferInjectMode();
        if (!UNDERWATER_LOD_FORCE && submersionEligible && viewport.fogParameters != null) {
            float envEnd = viewport.fogParameters.environmentalEnd();
            int rdBlocks = net.minecraft.client.Minecraft.getInstance().options.getEffectiveRenderDistance() * 16;
            submersionSkip = (!this.pipeline.vxMaterialMode() || this.pipeline.materialPolicy().legacyWater()) && envEnd < 128.0f && rdBlocks > envEnd * 2.0f;
        }
        try (var enc = backend.beginRenderPass(pass)) {
            enc.setViewport(0, 0, fbw, fbh, 0.0f, 1.0f);
            // M12 close — invoke MDIC's Metal-aware draws in the same order
            // GL runPipeline uses (opaque → temporal → translucent). Iris is
            // GL-gated upstream so on non-GL the section renderer is always
            // an MDICSectionRenderer (and its viewport an MDICViewport —
            // typing follows from the RenderPipelineFactory pairing).
            // postOpaquePreTranslucent (SSAO) is skipped on Metal — SSAO
            // is M13 polish; the LOD result is intelligible without it.
            if (!bridgeSolidTest && !submersionSkip
                    && this.pipeline.sectionRenderer instanceof me.cortex.voxy.client.core.rendering.section.backend.mdic.MDICSectionRenderer mdic
                    && viewport instanceof me.cortex.voxy.client.core.rendering.section.backend.mdic.MDICViewport mv) {
                mdic.renderOpaqueMetal(enc, mv);
                mdic.renderTemporalMetal(enc, mv);
                // Phase D (issue #11): in vx-contract mode translucent LOD
                // renders in its OWN pass below — separate colour bridge
                // (premultiplied accumulation -> the pack's colortex16
                // layer) + separate depth (-> vxDepthTexTrans) so the
                // pack's deferred composites LOD water as WATER instead of
                // shading it as opaque land.
                if (!this.pipeline.deferTranslucency && !vxMaterial
                        && !vxContract && this.targets.prelit == null) {
                    mdic.renderTranslucentMetal(enc, mv);
                }
            }
        }
        if (this.targets.prelit != null && !this.pipeline.deferTranslucency) {
            final boolean drawTerrain = !bridgeSolidTest && !submersionSkip;
            this.targets.prelit.render(this.targets.color.asGpuTexture(), this.targets.depth, (encoder, layer) -> {
                if (drawTerrain
                        && this.pipeline.sectionRenderer instanceof me.cortex.voxy.client.core.rendering.section.backend.mdic.MDICSectionRenderer mdic
                        && viewport instanceof me.cortex.voxy.client.core.rendering.section.backend.mdic.MDICViewport mv) {
                    mdic.renderTranslucentMetal(encoder, mv, layer);
                }
            }, inputs -> this.diagnostics.samplePrelit(mrb, viewport, inputs));
        }
        // Iris gbuffer injection: export the LOD pass's depth into the R32F
        // depth bridge so the GL-side injector can unproject it back into
        // MC clip space and write pack-visible gl_FragDepth. Encoded into the
        // SAME command buffer as the LOD pass (encoder order = the barrier),
        // so the submit() below covers it — no extra waits. Bridge alloc
        // mirrors metalBridge's resize discipline above.
        if (irisGbufferInject) {
            mrb.copyTextureToBuffer(this.targets.depth, this.targets.depthRead, fbw, fbh);
            this.targets.export.render(backend, this.targets.depthRead,
                    this.targets.packedDepth.asGpuTexture(), fbw, fbh);

            // Phase D translucent split (vx contract only): seed a second
            // depth target with the opaque depth (restore pass — the blit
            // buffer already holds it), render translucent LOD into its own
            // premultiplied colour bridge with depth WRITE so the water
            // SURFACE depth lands in vxDepthTexTrans, then blit+export that
            // depth through a second packed bridge. Encoder order keeps it
            // all in this frame's single submit.
            if ((vxMaterial || vxContract)
                    && !bridgeSolidTest && (TRANS_SUBMERSION_CLEAR || !submersionSkip)
                    && this.pipeline.sectionRenderer instanceof me.cortex.voxy.client.core.rendering.section.backend.mdic.MDICSectionRenderer mdicT
                    && viewport instanceof me.cortex.voxy.client.core.rendering.section.backend.mdic.MDICViewport mvT
                    && !this.pipeline.deferTranslucency) {
                this.targets.restore.render(backend, this.targets.depthRead, this.targets.transDepth, fbw, fbh);

                var transPassBuilder = me.cortex.voxy.client.core.gpu.RenderPassDesc.builder(fbw, fbh);
                if (vxMaterial) {
                    transPassBuilder.clearColor(this.targets.translucent[0].asGpuTexture(), 0f, 0f, 0f, 0f)
                                    .clearColor(this.targets.translucent[1].asGpuTexture(), 0f, 0f, 0f, 0f)
                                    .clearColor(this.targets.translucent[2].asGpuTexture(), 0f, 0f, 0f, 0f);
                } else {
                    transPassBuilder.clearColor(this.targets.transColor.asGpuTexture(), 0.0f, 0.0f, 0.0f, 0.0f);
                }
                var transPass = transPassBuilder
                        .depthAttachment(this.targets.transDepth, 0,
                                me.cortex.voxy.client.core.gpu.RenderPassDesc.LoadAction.LOAD,
                                me.cortex.voxy.client.core.gpu.RenderPassDesc.StoreAction.STORE, 1.0f)
                        .build();
                try (var encT = backend.beginRenderPass(transPass)) {
                    encT.setViewport(0, 0, fbw, fbh, 0.0f, 1.0f);
                    // Submerged: run the pass for its clears only (see
                    // TRANS_SUBMERSION_CLEAR) — the far field is fog-saturated,
                    // so skipping the draws over freshly-cleared planes keeps
                    // the resolve dark instead of compositing stale water.
                    if (!submersionSkip) {
                        mdicT.renderTranslucentMetal(encT, mvT);
                    }
                }

                mrb.copyTextureToBuffer(this.targets.transDepth, this.targets.transRead, fbw, fbh);
                this.targets.export.render(backend, this.targets.transRead,
                        this.targets.packedTransDepth.asGpuTexture(), fbw, fbh);
            }
        }
        if (me.cortex.voxy.client.core.util.FrameTiming.ENABLED) {
            long tFT = System.nanoTime();
            backend.submit();
            me.cortex.voxy.client.core.util.FrameTiming.bridgeFlushNs += System.nanoTime() - tFT;
        } else {
            backend.submit();
        }
        if (this.pipeline instanceof MetalVxRenderPipeline material) material.publishMaterialFrame();
        this.metalFrame++;

        var geometry = this.pipeline.sectionRenderer.getGeometryManager();
        this.diagnostics.sample(mrb, viewport, vxOpaqueMat ? this.targets.opaque[0] : this.targets.color,
                this.targets.depth, vxMaterial ? this.targets.translucent[0] : null,
                vxMaterial ? this.targets.transDepth : null,
                geometry instanceof me.cortex.voxy.client.core.rendering.section.geometry.BasicSectionGeometryData data ? data : null,
                this.nodeManager.hasWork(), this.nodeManager.getWorld(), this.traversal.getNodeBuffer(), this.nodeManager.getCurrentMaxNodeId());
        if (me.cortex.voxy.client.core.util.FrameTiming.ENABLED && this.metalFrame % 600 == 0) {
            Logger.info(String.format(java.util.Locale.ROOT,
                    "[Metal-TIMING] frame=%d hotWait=%.3fms drawFlushWait=%.3fms bridgeWait=%.3fms jniDraw=%.3fms draws=%d",
                    this.metalFrame,
                    me.cortex.voxy.client.core.util.FrameTiming.hotReadbackNs/600.0/1e6,
                    me.cortex.voxy.client.core.util.FrameTiming.drawCallFlushNs/600.0/1e6,
                    me.cortex.voxy.client.core.util.FrameTiming.bridgeFlushNs/600.0/1e6,
                    me.cortex.voxy.client.core.util.FrameTiming.jniDrawLoopNs/600.0/1e6,
                    me.cortex.voxy.client.core.util.FrameTiming.jniDrawCount/600));
            me.cortex.voxy.client.core.util.FrameTiming.reset();
        }
    }

    /** Accessor for the compositing mixin so it can grab the bridge's GL texture name. */
    public me.cortex.voxy.client.core.interop.IOSurfaceBridge metalBridge() {
        return this.targets == null ? null : this.targets.color;
    }

    /**
     * RGB-packed depth bridge for the Iris gbuffer injection. Null until the first
     * Metal frame rendered with {@code IrisUtil.irisGbufferInjectMode()} on.
     */
    public me.cortex.voxy.client.core.interop.IOSurfaceBridge metalTransBridge() {
        return this.targets == null ? null : this.targets.transColor;
    }

    public me.cortex.voxy.client.core.interop.IOSurfaceBridge metalDepthTransBridge() {
        return this.targets == null ? null : this.targets.packedTransDepth;
    }

    public me.cortex.voxy.client.core.interop.IOSurfaceBridge metalDepthBridge() {
        return this.targets == null ? null : this.targets.packedDepth;
    }

    // Phase C material g-buffer planes (null unless vxMaterialMode rendered a frame).
    public me.cortex.voxy.client.core.interop.IOSurfaceBridge metalVxOpaque0() { return this.targets == null || this.targets.opaque == null ? null : this.targets.opaque[0]; }
    public me.cortex.voxy.client.core.interop.IOSurfaceBridge metalVxOpaque1() { return this.targets == null || this.targets.opaque == null ? null : this.targets.opaque[1]; }
    public me.cortex.voxy.client.core.interop.IOSurfaceBridge metalVxOpaque2() { return this.targets == null || this.targets.opaque == null ? null : this.targets.opaque[2]; }
    public me.cortex.voxy.client.core.interop.IOSurfaceBridge metalVxTrans0() { return this.targets == null || this.targets.translucent == null ? null : this.targets.translucent[0]; }
    public me.cortex.voxy.client.core.interop.IOSurfaceBridge metalVxTrans1() { return this.targets == null || this.targets.translucent == null ? null : this.targets.translucent[1]; }
    public me.cortex.voxy.client.core.interop.IOSurfaceBridge metalVxTrans2() { return this.targets == null || this.targets.translucent == null ? null : this.targets.translucent[2]; }

    @Override public void close() {
        var retired = this.targets;
        this.targets = null;
        me.cortex.voxy.common.util.ResourceCleanup.run(this.diagnostics::close,
                () -> { if (retired != null) retired.close(); });
    }
}
