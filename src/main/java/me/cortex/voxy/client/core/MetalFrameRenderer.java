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

    /** IOSurface bridge for the Metal render path. Lazy-allocated on first non-GL frame. */
    private me.cortex.voxy.client.core.interop.IOSurfaceBridge metalBridge;
    private int metalBridgeWidth;
    private int metalBridgeHeight;
    /**
     * BGRA8 IOSurface bridge carrying the LOD pass's depth (24-bit RGB-packed)
     * to GL for the Iris gbuffer injection (IrisGbufferInjector). Lazy —
     * allocated only on frames where {@code IrisUtil.irisGbufferInjectMode()}
     * is active, so pack-less runs never pay for the extra surface or the
     * export pass.
     */
    private me.cortex.voxy.client.core.interop.IOSurfaceBridge metalDepthBridge;
    private int metalDepthBridgeWidth;
    private int metalDepthBridgeHeight;
    /** Fullscreen depth→bridge export pass for {@link #metalDepthBridge}. Lazy like the bridge. */
    private me.cortex.voxy.client.core.interop.MetalDepthExport metalDepthExport;
    // --- Phase D translucent split (vx contract; all lazy) ---
    private me.cortex.voxy.client.core.interop.IOSurfaceBridge metalTransBridge;
    private int metalTransBridgeWidth, metalTransBridgeHeight;
    private me.cortex.voxy.client.core.interop.IOSurfaceBridge metalDepthTransBridge;
    private int metalDepthTransBridgeWidth, metalDepthTransBridgeHeight;
    private me.cortex.voxy.client.core.gpu.IGpuTexture metalDepthTransTex;
    private me.cortex.voxy.client.core.gpu.IGpuBuffer metalTransReadBuffer;
    private me.cortex.voxy.client.core.interop.MetalDepthRestore metalDepthRestore;

    // --- Phase C material g-buffer (issue #11; vxMaterialMode; all lazy) ---
    // 3 BGRA8 planes per LOD layer: P0 albedo, P1 tint, P2 misc(light/face/customId).
    private me.cortex.voxy.client.core.interop.IOSurfaceBridge metalVxOpaque0, metalVxOpaque1, metalVxOpaque2;
    private me.cortex.voxy.client.core.interop.IOSurfaceBridge metalVxTrans0, metalVxTrans1, metalVxTrans2;

    /** Lazy-(re)allocate a BGRA8 plane bridge sized to the framebuffer. */
    private static me.cortex.voxy.client.core.interop.IOSurfaceBridge ensurePlaneBridge(
            me.cortex.voxy.client.core.interop.IOSurfaceBridge cur,
            me.cortex.voxy.client.core.metal.MetalRenderBackend mrb, int fbw, int fbh) {
        if (cur == null || cur.width() != fbw || cur.height() != fbh) {
            if (cur != null) cur.close();
            return me.cortex.voxy.client.core.interop.IOSurfaceBridge.create(
                    mrb.device(), fbw, fbh,
                    me.cortex.voxy.client.core.interop.IOSurfaceBridge.IOSurfaceFormat.BGRA8);
        }
        return cur;
    }

    /**
     * Blit destination for {@link #metalDepthTex} (w×h raw D32F floats) and
     * read source of the export pass. Exists because Metal silently reads
     * zeros when a depth-format texture is sampled through the
     * texture2d&lt;float&gt; declaration SPIRV-Cross emits for sampler2D;
     * buffer reads are format-blind. Lazy like the bridge.
     */
    private me.cortex.voxy.client.core.gpu.IGpuBuffer metalDepthReadBuffer;
    /**
     * Depth texture for {@code runPipelineMetal}'s render pass. Lazy-allocated
     * to match the bridge size so depth-tested LOD terrain self-occludes correctly.
     * Lives in Metal-side memory (the bridge's color is shared with GL via
     * IOSurface; the depth has no GL consumer so it stays Metal-private).
     */
    private me.cortex.voxy.client.core.gpu.IGpuTexture metalDepthTex;
    private int metalDepthWidth;
    private int metalDepthHeight;
    /**
     * M13 chunk 3: Shared-storage mirror of MC's main-FBO depth, refreshed
     * per frame via {@code glGetTexImage}. Sourced by
     * {@code HiZBuffer.buildMipChain(IGpuTexture, ...)} so the HiZ pyramid
     * carries real occlusion data instead of the zero-init stub from M12.
     * Lazy — allocated on the first Metal frame that has a non-zero sized
     * source framebuffer.
     */
    private me.cortex.voxy.client.core.rendering.util.DepthMirror metalDepthMirror;
    /** Animation counter for the placeholder Metal render — replaced by real Voxy output incrementally. */
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

    /**
     * Metal-path runPipeline (M12 chunk 6, evolving). Today runs the migrated
     * compute side end-to-end on Metal — every stage in this method is either
     * already encoder-backed or skipped with a Metal-aware substitute — and
     * still clears the IOSurface bridge as visual confirmation that the
     * pipeline executed. Real LOD draws land in subsequent chunk 6 steps when
     * renderTerrain / renderTranslucent migrate to RenderEncoder.
     * <p>
     * Compared to the GL {@link #runPipeline}, the Metal path currently
     * skips: {@link #setup} (depth-stencil FBO copy uses raw GL),
     * {@link me.cortex.voxy.client.core.rendering.util.HiZBuffer#buildMipChain}
     * (partial GL — see chunk 6 step 2), the FrEx work loop wrapper, the
     * raw {@code glMemoryBarrier} inside {@link #innerPrimaryWork}, the
     * post-opaque SSAO compute, and {@link #finish}. The compute pipeline
     * (HOT traversal + buildDrawCalls' 5 prepasses) runs in full.
     */
    void render(Viewport<?> viewport, int sourceFrameBuffer) {
        int fbw = viewport.width;
        int fbh = viewport.height;
        if (fbw <= 0 || fbh <= 0) return;
        var backend = me.cortex.voxy.client.core.gpu.RenderBackendFactory.get();
        if (!(backend instanceof me.cortex.voxy.client.core.metal.MetalRenderBackend mrb)) return;

        // 1) Allocate the IOSurface bridge sized to MC's framebuffer. The
        //    bridge is the cross-context handle: Metal renders into the
        //    backing MTLTexture, IOSurfaceBridgeCompositor blits it into MC's
        //    main RT via a CGL-bound GL_TEXTURE_RECTANGLE source FBO.
        if (this.metalBridge == null || this.metalBridgeWidth != fbw || this.metalBridgeHeight != fbh) {
            if (this.metalBridge != null) this.metalBridge.close();
            this.metalBridge = me.cortex.voxy.client.core.interop.IOSurfaceBridge.create(
                    mrb.device(), fbw, fbh,
                    me.cortex.voxy.client.core.interop.IOSurfaceBridge.IOSurfaceFormat.BGRA8);
            this.metalBridgeWidth  = fbw;
            this.metalBridgeHeight = fbh;
        }

        // 2) Ensure the HiZ texture is allocated so HOT can bind it. The
        //    encoder-driven mip-chain build is wired up but parked: an
        //    initial integration test (2026-05-13) showed LOD chunks
        //    disappearing at the horizon when HOT samples the populated
        //    pyramid — likely a Depth32Float_Stencil8 sampling-as-sampler2D
        //    mismatch versus the GL path's pre-processed depth (see
        //    initDepthStencil's stencil-mask dance that zeros sky regions).
        //    Revert to M12's zero-init pyramid until that's debugged so
        //    HOT trivially passes every frustum-visible section. The
        //    DepthMirror class + MetalNative.mtlTextureNewSubresourceView
        //    JNI + IGpuTexture.createView(level, count) all stay committed
        //    for the follow-up. ensureAllocated zero-fills every mip on
        //    Metal at (re)allocation — MTLTexture contents are otherwise
        //    UNDEFINED and the screenspace.glsl "pointSample <= 0.0" guard
        //    needs real zeros, not luck.
        viewport.hiZBuffer.ensureAllocated(viewport.width, viewport.height);

        // 2b) Lazy-allocate the Metal-side depth texture for our render pass.
        //     PURE depth format (not D24S8): the packed Depth32Float_Stencil8
        //     cannot be reliably sampled as texture2d<float> on Metal — the
        //     Iris-inject depth export read zeros from it, so every injected
        //     LOD pixel discarded (d<=0) and LODs vanished under packs. The
        //     stencil aspect was never used by the LOD pass; pure D32F is the
        //     proven-sampleable format (same fix as HiZ + the chunk-bound
        //     mask) and depth-only attachment of it is validation-clean.
        //     The encoder pass clears it to 1.0 (far plane) each frame.
        if (this.metalDepthTex == null || this.metalDepthWidth != fbw || this.metalDepthHeight != fbh) {
            if (this.metalDepthTex != null) this.metalDepthTex.free();
            this.metalDepthTex = backend.createTexture()
                    .store(org.lwjgl.opengl.GL30C.GL_DEPTH_COMPONENT32F, 1, fbw, fbh)
                    .name("VoxyMetalDepth");
            this.metalDepthWidth = fbw;
            this.metalDepthHeight = fbh;
        }

        // 3) Compute side — copy of innerPrimaryWork's body minus the GL bits
        //    (HiZBuffer.buildMipChain, raw glMemoryBarrier, FrEx loop). Each
        //    sub-stage is already encoder-backed (commits 0b963825, 68734b78,
        //    5dcbc645, 89b35814 for HOT's last raw-GL gaps).
        me.cortex.voxy.client.core.rendering.util.DownloadStream.INSTANCE.tick();
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

        // 5) Render pass against bridge color + Voxy-owned depth. Clears both
        //    each frame (no MC-depth import on Metal yet, so we render every
        //    LOD chunk against a fresh depth buffer — they self-occlude but
        //    don't z-test against MC's foreground terrain). Inside the pass
        //    we call MDIC's Metal-aware renderOpaque equivalent to issue
        //    the actual LOD draws via the RenderEncoder API. 2026-05-14
        //    revert: alpha back to 1.0 (M12-stable) since the alpha-composite
        //    shader path made LOD invisible in-game. With the blit compositor
        //    the clear colour shows through in non-LOD areas (sky no longer
        //    visible through them); Sodium overdraws its near terrain on top.
        // 2026-05-14 diagnostic finding: with magenta clear, user reports
        // "Veo magenta en todo (excepto terreno MC cercano)" — confirming
        // the IOSurface bridge + blit-to-MC-mainRT path works end-to-end.
        // The reason LOD chunks are invisible is the MDIC opaque/temporal/
        // translucent draws are NOT producing visible pixels in the bridge.
        // Likely causes: drawIndexedIndirect counts are zero (HOT culling /
        // commandGen prepass), or vertex shader clips all geometry. Restored
        // dark clear so day-to-day play isn't magenta-flooded; the rendering
        // pipeline diagnosis continues in MDIC + buildDrawCalls.
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
        boolean irisGbufferInject = this.pipeline.vxMaterialMode() || me.cortex.voxy.client.core.util.IrisUtil.vxContractActive()
                || me.cortex.voxy.client.core.util.IrisUtil.irisGbufferInjectMode();
        float clearA = (bridgeSolidTest || (IOSurfaceBridgeCompositor.USE_BLIT && !irisGbufferInject)) ? 1.0f : 0.0f;
        // Phase C material g-buffer (vxMaterialMode): the opaque LOD renders into 3
        // BGRA8 planes (P0 albedo, P1 tint, P2 misc) for the GL resolve to shade,
        // instead of the single composited bridge colour. Lazy-allocate all 6 planes
        // (opaque + translucent) up front so the translucent split below has targets.
        boolean vxMaterial = this.pipeline.vxMaterialMode();
        boolean vxOpaqueMat = this.pipeline.vxOpaqueMaterialMode();
        // Generic contracts own both layers. Only explicit BSL compatibility may
        // keep opaque terrain on the legacy bridge while resolving water separately.
        if (vxOpaqueMat) {
            this.metalVxOpaque0 = ensurePlaneBridge(this.metalVxOpaque0, mrb, fbw, fbh);
            this.metalVxOpaque1 = ensurePlaneBridge(this.metalVxOpaque1, mrb, fbw, fbh);
            this.metalVxOpaque2 = ensurePlaneBridge(this.metalVxOpaque2, mrb, fbw, fbh);
        }
        if (vxMaterial) {
            this.metalVxTrans0 = ensurePlaneBridge(this.metalVxTrans0, mrb, fbw, fbh);
            this.metalVxTrans1 = ensurePlaneBridge(this.metalVxTrans1, mrb, fbw, fbh);
            this.metalVxTrans2 = ensurePlaneBridge(this.metalVxTrans2, mrb, fbw, fbh);
        }
        var passBuilder = me.cortex.voxy.client.core.gpu.RenderPassDesc.builder(fbw, fbh);
        if (vxOpaqueMat) {
            passBuilder.clearColor(this.metalVxOpaque0.asGpuTexture(), 0f, 0f, 0f, 0f)
                       .clearColor(this.metalVxOpaque1.asGpuTexture(), 0f, 0f, 0f, 0f)
                       .clearColor(this.metalVxOpaque2.asGpuTexture(), 0f, 0f, 0f, 0f);
        } else {
            passBuilder.clearColor(this.metalBridge.asGpuTexture(), clearR, clearG, clearB, clearA);
        }
        var pass = passBuilder.clearDepth(this.metalDepthTex, 1.0f).build();
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
                        && !me.cortex.voxy.client.core.util.IrisUtil.vxContractActive()) {
                    mdic.renderTranslucentMetal(enc, mv);
                }
            }
        }
        // Iris gbuffer injection: export the LOD pass's depth into the R32F
        // depth bridge so the GL-side injector can unproject it back into
        // MC clip space and write pack-visible gl_FragDepth. Encoded into the
        // SAME command buffer as the LOD pass (encoder order = the barrier),
        // so the submit() below covers it — no extra waits. Bridge alloc
        // mirrors metalBridge's resize discipline above.
        if (irisGbufferInject) {
            if (this.metalDepthBridge == null || this.metalDepthBridgeWidth != fbw || this.metalDepthBridgeHeight != fbh) {
                if (this.metalDepthBridge != null) this.metalDepthBridge.close();
                this.metalDepthBridge = me.cortex.voxy.client.core.interop.IOSurfaceBridge.create(
                        mrb.device(), fbw, fbh,
                        // BGRA8, not R32F: Apple GL's CGLTexImageIOSurface2D
                        // binds 'L00f' R32F surfaces without error but SAMPLES
                        // ZEROS (debug-mode verified: full-screen red = d<=0).
                        // The depth is 24-bit-packed into RGB instead — the
                        // BGRA8 path is the proven one (the color bridge).
                        me.cortex.voxy.client.core.interop.IOSurfaceBridge.IOSurfaceFormat.BGRA8);
                this.metalDepthBridgeWidth  = fbw;
                this.metalDepthBridgeHeight = fbh;
            }
            if (this.metalDepthExport == null) {
                this.metalDepthExport = new me.cortex.voxy.client.core.interop.MetalDepthExport(backend);
            }
            // Depth reaches the export pass via a plain buffer, not by
            // sampling metalDepthTex: depth-format textures bound to the
            // texture2d<float> slot SPIRV-Cross emits for sampler2D silently
            // read ZEROS on Metal. The blit encodes after the LOD pass and
            // the next beginRenderPass (inside render()) closes the blit
            // encoder, so encoder order gives LOD-pass → blit → export.
            long depthBufSize = (long) fbw * fbh * 4;
            if (this.metalDepthReadBuffer == null || this.metalDepthReadBuffer.size() != depthBufSize) {
                if (this.metalDepthReadBuffer != null) this.metalDepthReadBuffer.free();
                this.metalDepthReadBuffer = backend.createBuffer(depthBufSize);
            }
            mrb.copyTextureToBuffer(this.metalDepthTex, this.metalDepthReadBuffer, fbw, fbh);
            this.metalDepthExport.render(backend, this.metalDepthReadBuffer,
                    this.metalDepthBridge.asGpuTexture(), fbw, fbh);

            // Phase D translucent split (vx contract only): seed a second
            // depth target with the opaque depth (restore pass — the blit
            // buffer already holds it), render translucent LOD into its own
            // premultiplied colour bridge with depth WRITE so the water
            // SURFACE depth lands in vxDepthTexTrans, then blit+export that
            // depth through a second packed bridge. Encoder order keeps it
            // all in this frame's single submit.
            if ((vxMaterial || me.cortex.voxy.client.core.util.IrisUtil.vxContractActive())
                    && !bridgeSolidTest && (TRANS_SUBMERSION_CLEAR || !submersionSkip)
                    && this.pipeline.sectionRenderer instanceof me.cortex.voxy.client.core.rendering.section.backend.mdic.MDICSectionRenderer mdicT
                    && viewport instanceof me.cortex.voxy.client.core.rendering.section.backend.mdic.MDICViewport mvT
                    && !this.pipeline.deferTranslucency) {
                // Material mode uses the 3 translucent planes (allocated above);
                // the single trans colour bridge is only for the Phase D-lite path.
                if (!vxMaterial && (this.metalTransBridge == null || this.metalTransBridgeWidth != fbw || this.metalTransBridgeHeight != fbh)) {
                    if (this.metalTransBridge != null) this.metalTransBridge.close();
                    this.metalTransBridge = me.cortex.voxy.client.core.interop.IOSurfaceBridge.create(
                            mrb.device(), fbw, fbh,
                            me.cortex.voxy.client.core.interop.IOSurfaceBridge.IOSurfaceFormat.BGRA8);
                    this.metalTransBridgeWidth = fbw;
                    this.metalTransBridgeHeight = fbh;
                }
                if (this.metalDepthTransBridge == null || this.metalDepthTransBridgeWidth != fbw || this.metalDepthTransBridgeHeight != fbh) {
                    if (this.metalDepthTransBridge != null) this.metalDepthTransBridge.close();
                    this.metalDepthTransBridge = me.cortex.voxy.client.core.interop.IOSurfaceBridge.create(
                            mrb.device(), fbw, fbh,
                            me.cortex.voxy.client.core.interop.IOSurfaceBridge.IOSurfaceFormat.BGRA8);
                    this.metalDepthTransBridgeWidth = fbw;
                    this.metalDepthTransBridgeHeight = fbh;
                }
                if (this.metalDepthTransTex == null || this.metalDepthTransTex.getWidth() != fbw || this.metalDepthTransTex.getHeight() != fbh) {
                    if (this.metalDepthTransTex != null) this.metalDepthTransTex.free();
                    this.metalDepthTransTex = backend.createTexture()
                            .store(org.lwjgl.opengl.GL30C.GL_DEPTH_COMPONENT32F, 1, fbw, fbh);
                }
                if (this.metalTransReadBuffer == null || this.metalTransReadBuffer.size() != depthBufSize) {
                    if (this.metalTransReadBuffer != null) this.metalTransReadBuffer.free();
                    this.metalTransReadBuffer = backend.createBuffer(depthBufSize);
                }
                if (this.metalDepthRestore == null) {
                    this.metalDepthRestore = new me.cortex.voxy.client.core.interop.MetalDepthRestore(backend);
                }

                this.metalDepthRestore.render(backend, this.metalDepthReadBuffer, this.metalDepthTransTex, fbw, fbh);

                var transPassBuilder = me.cortex.voxy.client.core.gpu.RenderPassDesc.builder(fbw, fbh);
                if (vxMaterial) {
                    transPassBuilder.clearColor(this.metalVxTrans0.asGpuTexture(), 0f, 0f, 0f, 0f)
                                    .clearColor(this.metalVxTrans1.asGpuTexture(), 0f, 0f, 0f, 0f)
                                    .clearColor(this.metalVxTrans2.asGpuTexture(), 0f, 0f, 0f, 0f);
                } else {
                    transPassBuilder.clearColor(this.metalTransBridge.asGpuTexture(), 0.0f, 0.0f, 0.0f, 0.0f);
                }
                var transPass = transPassBuilder
                        .depthAttachment(this.metalDepthTransTex, 0,
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

                mrb.copyTextureToBuffer(this.metalDepthTransTex, this.metalTransReadBuffer, fbw, fbh);
                this.metalDepthExport.render(backend, this.metalTransReadBuffer,
                        this.metalDepthTransBridge.asGpuTexture(), fbw, fbh);
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
        this.diagnostics.sample(mrb, viewport, vxOpaqueMat ? this.metalVxOpaque0 : this.metalBridge,
                this.metalDepthTex, vxMaterial ? this.metalVxTrans0 : null,
                vxMaterial ? this.metalDepthTransTex : null,
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
        return this.metalBridge;
    }

    /**
     * R32F depth bridge for the Iris gbuffer injection. Null until the first
     * Metal frame rendered with {@code IrisUtil.irisGbufferInjectMode()} on.
     */
    public me.cortex.voxy.client.core.interop.IOSurfaceBridge metalTransBridge() {
        return this.metalTransBridge;
    }

    public me.cortex.voxy.client.core.interop.IOSurfaceBridge metalDepthTransBridge() {
        return this.metalDepthTransBridge;
    }

    public me.cortex.voxy.client.core.interop.IOSurfaceBridge metalDepthBridge() {
        return this.metalDepthBridge;
    }

    // Phase C material g-buffer planes (null unless vxMaterialMode rendered a frame).
    public me.cortex.voxy.client.core.interop.IOSurfaceBridge metalVxOpaque0() { return this.metalVxOpaque0; }
    public me.cortex.voxy.client.core.interop.IOSurfaceBridge metalVxOpaque1() { return this.metalVxOpaque1; }
    public me.cortex.voxy.client.core.interop.IOSurfaceBridge metalVxOpaque2() { return this.metalVxOpaque2; }
    public me.cortex.voxy.client.core.interop.IOSurfaceBridge metalVxTrans0() { return this.metalVxTrans0; }
    public me.cortex.voxy.client.core.interop.IOSurfaceBridge metalVxTrans1() { return this.metalVxTrans1; }
    public me.cortex.voxy.client.core.interop.IOSurfaceBridge metalVxTrans2() { return this.metalVxTrans2; }

    @Override public void close() {
        this.diagnostics.close();
        if (this.metalBridge != null) {
            this.metalBridge.close();
            this.metalBridge = null;
        }
        if (this.metalDepthBridge != null) {
            this.metalDepthBridge.close();
            this.metalDepthBridge = null;
        }
        if (this.metalDepthExport != null) {
            this.metalDepthExport.close();
            this.metalDepthExport = null;
        }
        if (this.metalDepthReadBuffer != null) {
            this.metalDepthReadBuffer.free();
            this.metalDepthReadBuffer = null;
        }
        if (this.metalTransBridge != null) { this.metalTransBridge.close(); this.metalTransBridge = null; }
        if (this.metalDepthTransBridge != null) { this.metalDepthTransBridge.close(); this.metalDepthTransBridge = null; }
        if (this.metalDepthTransTex != null) { this.metalDepthTransTex.free(); this.metalDepthTransTex = null; }
        if (this.metalTransReadBuffer != null) { this.metalTransReadBuffer.free(); this.metalTransReadBuffer = null; }
        if (this.metalDepthRestore != null) { this.metalDepthRestore.close(); this.metalDepthRestore = null; }
        if (this.metalVxOpaque0 != null) { this.metalVxOpaque0.close(); this.metalVxOpaque0 = null; }
        if (this.metalVxOpaque1 != null) { this.metalVxOpaque1.close(); this.metalVxOpaque1 = null; }
        if (this.metalVxOpaque2 != null) { this.metalVxOpaque2.close(); this.metalVxOpaque2 = null; }
        if (this.metalVxTrans0 != null) { this.metalVxTrans0.close(); this.metalVxTrans0 = null; }
        if (this.metalVxTrans1 != null) { this.metalVxTrans1.close(); this.metalVxTrans1 = null; }
        if (this.metalVxTrans2 != null) { this.metalVxTrans2.close(); this.metalVxTrans2 = null; }
        if (this.metalDepthTex != null) {
            this.metalDepthTex.free();
            this.metalDepthTex = null;
        }
        if (this.metalDepthMirror != null) {
            this.metalDepthMirror.free();
            this.metalDepthMirror = null;
        }
    }
}
