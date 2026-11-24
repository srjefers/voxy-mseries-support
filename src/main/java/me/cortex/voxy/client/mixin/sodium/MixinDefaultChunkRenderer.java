package me.cortex.voxy.client.mixin.sodium;

import com.mojang.blaze3d.textures.GpuSampler;
import me.cortex.voxy.client.VoxyClient;
import me.cortex.voxy.client.core.IGetVoxyRenderSystem;
import me.cortex.voxy.client.core.gpu.BackendType;
import me.cortex.voxy.client.core.gpu.RenderBackendFactory;
import me.cortex.voxy.client.core.rendering.Viewport;
import me.cortex.voxy.client.core.util.IrisGbufferInjector;
import me.cortex.voxy.client.core.util.IrisUtil;
import me.cortex.voxy.common.Logger;
import me.cortex.voxy.commonImpl.VoxyCommon;
import net.caffeinemc.mods.sodium.client.SodiumClientMod;
import net.caffeinemc.mods.sodium.client.gl.buffer.GlBufferUsage;
import net.caffeinemc.mods.sodium.client.gl.device.MultiDrawBatch;
import net.caffeinemc.mods.sodium.client.gl.device.CommandList;
import net.caffeinemc.mods.sodium.client.gl.device.RenderDevice;
import net.caffeinemc.mods.sodium.client.render.chunk.ChunkRenderMatrices;
import net.caffeinemc.mods.sodium.client.render.chunk.DefaultChunkRenderer;
import net.caffeinemc.mods.sodium.client.render.chunk.ShaderChunkRenderer;
import net.caffeinemc.mods.sodium.client.render.chunk.SharedQuadIndexBuffer;
import net.caffeinemc.mods.sodium.client.render.chunk.data.SectionRenderDataStorage;
import net.caffeinemc.mods.sodium.client.render.chunk.lists.ChunkRenderList;
import net.caffeinemc.mods.sodium.client.render.chunk.lists.ChunkRenderListIterable;
import net.caffeinemc.mods.sodium.client.render.chunk.region.RenderRegion;
import net.caffeinemc.mods.sodium.client.render.chunk.terrain.DefaultTerrainRenderPasses;
import net.caffeinemc.mods.sodium.client.render.chunk.terrain.TerrainRenderPass;
import net.caffeinemc.mods.sodium.client.render.chunk.vertex.format.ChunkVertexType;
import net.caffeinemc.mods.sodium.client.render.viewport.CameraTransform;
import net.caffeinemc.mods.sodium.client.util.FogParameters;
import net.caffeinemc.mods.sodium.client.util.NativeBuffer;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.gen.Invoker;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value = DefaultChunkRenderer.class, remap = false)
public abstract class MixinDefaultChunkRenderer extends ShaderChunkRenderer {
    @Unique
    private static final int VOXY_MAX_REASONABLE_SHARED_INDEX_ELEMENTS = 16_000_000;

    @Shadow @Final private SharedQuadIndexBuffer sharedIndexBuffer;

    public MixinDefaultChunkRenderer(RenderDevice device, ChunkVertexType vertexType) {
        super(device, vertexType);
    }

    @Invoker("fillCommandBuffer")
    private static void voxy$fillCommandBuffer(MultiDrawBatch batch, RenderRegion region,
                                               SectionRenderDataStorage storage, ChunkRenderList renderList,
                                               CameraTransform camera, TerrainRenderPass renderPass,
                                               boolean useBlockFaceCulling, boolean indexedRendering) {
        throw new AssertionError();
    }

    @Inject(method = "render", at = @At(value = "HEAD"), cancellable = true)
    private void cancelThingie(ChunkRenderMatrices matrices, CommandList commandList, ChunkRenderListIterable renderLists, TerrainRenderPass renderPass, CameraTransform camera, FogParameters fogParameters, boolean indexedRenderingEnabled, GpuSampler terrainSampler, CallbackInfo ci) {
        if (VoxyClient.disableSodiumChunkRender()) {
            super.begin(renderPass, fogParameters, terrainSampler);
            this.doRender(matrices, commandList, renderLists, renderPass, camera, fogParameters, indexedRenderingEnabled);
            super.end(renderPass);
            ci.cancel();
        }
    }

    // M12 close: moved the Voxy hook from BEFORE-Sodium-end on the CUTOUT
    // pass to HEAD of the SOLID pass. Compositing before Sodium SOLID lets
    // MC's near terrain overdraw Voxy's distant LOD naturally.
    @Inject(method = "render", at = @At(value = "HEAD"))
    private void injectRender(ChunkRenderMatrices matrices, CommandList commandList, ChunkRenderListIterable renderLists, TerrainRenderPass renderPass, CameraTransform camera, FogParameters fogParameters, boolean indexedRenderingEnabled, GpuSampler terrainSampler, CallbackInfo ci) {
        if (!VoxyClient.disableSodiumChunkRender()) {
            this.doRender(matrices, commandList, renderLists, renderPass, camera, fogParameters, indexedRenderingEnabled);
        }
    }

    /** [Metal-CULL] probe: raw GL_CULL_FACE at the head of Sodium's TRANSLUCENT pass
     *  (before Sodium's tracked applyPipelineState, which is a no-op when
     *  GlStateManager's shadow copy already says "enabled"). Logs transitions
     *  only, with the held-item / hideGui state, so the triad correlation is
     *  readable straight from latest.log. VOXY_CULL_PROBE=0 disables. */
    @Unique private static final boolean VOXY_CULL_PROBE = !"0".equals(System.getenv("VOXY_CULL_PROBE"));
    @Unique private static int voxy$cullProbeLast = -1;
    @Unique private static long voxy$cullProbePasses;
    @Unique private static long voxy$cullProbeLastLogNs;
    @Unique private static int voxy$cullProbeSuppressed;

    @Unique
    private static void voxy$cullProbe(TerrainRenderPass renderPass) {
        if (!VOXY_CULL_PROBE || !renderPass.isTranslucent()) return;
        if (RenderBackendFactory.get().getType() != BackendType.METAL) return;
        // Iris's shadow pass re-enters render(TRANSLUCENT) with cull legitimately
        // disabled (SodiumShader.setupState -> _disableCull); not the frame we probe.
        if (IrisUtil.shadowsBeingRendered()) return;
        boolean cull = org.lwjgl.opengl.GL11C.glIsEnabled(org.lwjgl.opengl.GL11C.GL_CULL_FACE);
        voxy$cullProbePasses++;
        int now = cull ? 1 : 0;
        if (now == voxy$cullProbeLast) return;
        voxy$cullProbeLast = now;
        // With the leak live (VOXY_GL_CULL_RESTORE=0) a mob at the visibility
        // edge flips this every frame: throttle to one line per second and
        // count what was suppressed so no transition is silently lost.
        long t = System.nanoTime();
        if (t - voxy$cullProbeLastLogNs < 1_000_000_000L) { voxy$cullProbeSuppressed++; return; }
        voxy$cullProbeLastLogNs = t;
        String hand = "?";
        boolean hideGui = false;
        try {
            var mc = Minecraft.getInstance();
            hideGui = mc.options.hideGui;
            hand = mc.player == null ? "no-player"
                    : (mc.player.getMainHandItem().isEmpty() ? "EMPTY" : mc.player.getMainHandItem().getItem().toString());
        } catch (Throwable ignored) {}
        Logger.info("[Metal-CULL] translucent-pass head: GL cull=" + (cull ? "ON" : "OFF")
                + " mainHand=" + hand + " hideGui=" + hideGui + " passes=" + voxy$cullProbePasses
                + (voxy$cullProbeSuppressed > 0 ? " suppressedFlips=" + voxy$cullProbeSuppressed : "")
                + (cull ? "" : "  <- back faces of water WILL rasterise this frame"));
        voxy$cullProbeSuppressed = 0;
    }

    @Unique
    private void doRender(ChunkRenderMatrices matrices, CommandList commandList, ChunkRenderListIterable renderLists, TerrainRenderPass renderPass, CameraTransform camera, FogParameters fogParameters, boolean indexedRenderingEnabled) {
        voxy$cullProbe(renderPass);
        this.voxy$preflightSodiumSharedIndexBuffer(commandList, renderLists, renderPass, camera, indexedRenderingEnabled);

        if (renderPass == DefaultTerrainRenderPasses.SOLID) {
            var renderer = ((IGetVoxyRenderSystem) Minecraft.getInstance().levelRenderer).getVoxyRenderSystem();
            if (renderer != null) {
                boolean metal = me.cortex.voxy.client.core.gpu.RenderBackendFactory.get().getType()
                        != me.cortex.voxy.client.core.gpu.BackendType.OPENGL;
                boolean gbufferInject = metal && IrisUtil.irisGbufferInjectMode();
                // render(SOLID) RE-ENTERS during Iris's shadow-map pass. In
                // gbuffer-inject mode skip BOTH the Voxy render and the
                // inject outright: the Metal pipeline must run exactly once
                // per frame, and nothing may draw into the shadow FB. (The
                // GL-path equivalent guard is getViewport() returning null
                // while irisShadowActive.)
                if (gbufferInject && IrisUtil.shadowsBeingRendered()) {
                    return;
                }
                Viewport<?> viewport = null;
                // The stale-viewport reuse is for the GL Iris pipeline only
                // (Iris captures matrices through its own hooks there). On
                // Metal the NormalRenderPipeline runs regardless of packs, so
                // the full per-frame setupViewport must always happen.
                if (IrisUtil.irisShaderPackEnabled() && !metal) {
                    viewport = renderer.getViewport();
                } else {
                    viewport = renderer.setupViewport(matrices, fogParameters, camera.x, camera.y, camera.z);
                }
                renderer.renderOpaque(viewport);

                var pipeline = renderer.getPipeline();
                // viewport == null: setupViewport skipped a 0x0 frame (minimised window, Iris resize).
                // The resolve passes below dereference it, and the bridges hold the previous frame.
                if (viewport != null && pipeline != null && pipeline.metalBridge() != null) {
                    // H12: the pack's clouds only over LOD terrain — upload where the LOD
                    // terrain is for the vxLodCoverage sampler (VxLodCloudDistance). A
                    // no-op unless the terrain or the camera's LOD column changed.
                    if (IrisUtil.irisShaderPackEnabled()) {
                        me.cortex.voxy.client.core.rendering.clouds.LodCoverageGlTexture.update(
                                pipeline.lodCoverage(), viewport.cameraX, viewport.cameraZ);
                    }
                    // The two vx material branches name Iris types, so their bodies live in
                    // MetalVxResolveHooks: a mixin body that references an optional mod's class
                    // fails to apply when that mod is missing (H5). Keep Iris types out of here.
                    if (pipeline.vxOpaqueMaterialMode()
                            && pipeline instanceof me.cortex.voxy.client.core.MetalVxRenderPipeline mvp
                            && pipeline.metalVxOpaque0() != null
                            && pipeline.metalDepthBridge() != null) {
                        // FULL material path (default; VOXY_VX_MATERIAL_OPAQUE=0 selects TRANS-ONLY).
                        me.cortex.voxy.client.core.util.MetalVxResolveHooks.resolveFullMaterial(mvp, viewport);
                    } else if (pipeline.vxMaterialMode()
                            && pipeline instanceof me.cortex.voxy.client.core.MetalVxRenderPipeline mvp2
                            && pipeline.metalVxTrans0() != null
                            && pipeline.metalDepthTransBridge() != null) {
                        // TRANS-ONLY (issue #11, the mergeable shape): Phase-B inject for opaque,
                        // voxy_translucent over the water material g-buffer.
                        me.cortex.voxy.client.core.util.MetalVxResolveHooks.resolveTranslucentOnly(mvp2, viewport);
                    } else if (IrisUtil.vxContractActive()) {
                        // Native vx contract (milestone issue #9): hand the
                        // LOD depth to the pack's vxDepthTexOpaque/Trans
                        // side-channel and the pre-lit colour to the pack's
                        // voxy.json draw targets — the pack's own #ifdef
                        // VOXY branches do fog/shadows/AO/clouds. LOD depth
                        // never enters depthtex0 (excludeLodsFromVanillaDepth)
                        // so terrain stomping is impossible by construction.
                        me.cortex.voxy.client.core.util.VxContractInjector.inject(viewport,
                                pipeline.metalBridge(), pipeline.metalDepthBridge(),
                                pipeline.metalTransBridge(), pipeline.metalDepthTransBridge());
                    } else if (gbufferInject) {
                        // Fallback for packs WITHOUT voxy.json: single-phase
                        // inject at SOLID-head, pre-deferred (round 23).
                        IrisGbufferInjector.inject(viewport,
                                pipeline.metalBridge(), pipeline.metalDepthBridge());
                    } else {
                        me.cortex.voxy.client.core.interop.IOSurfaceBridgeCompositor
                                .composite(pipeline.metalBridge());
                    }
                }
            }
        }
    }

    @Unique
    private void voxy$preflightSodiumSharedIndexBuffer(CommandList commandList, ChunkRenderListIterable renderLists,
                                                       TerrainRenderPass renderPass, CameraTransform camera,
                                                       boolean indexedRenderingEnabled) {
        if (VoxyClient.disableSodiumChunkRender()) return;
        if (RenderBackendFactory.get().getType() != BackendType.METAL) return;

        boolean useBlockFaceCulling = SodiumClientMod.options().performance.useBlockFaceCulling;
        boolean indexedRendering = renderPass.isTranslucent() && indexedRenderingEnabled;
        if (indexedRendering) return;

        var iterator = renderLists.iterator(renderPass.isTranslucent());
        while (iterator.hasNext()) {
            ChunkRenderList renderList = iterator.next();
            RenderRegion region = renderList.getRegion();
            SectionRenderDataStorage storage = region.getStorage(renderPass);
            if (storage == null) continue;

            MultiDrawBatch batch = region.getCachedBatch(renderPass);
            if (!batch.isFilled) {
                voxy$fillCommandBuffer(batch, region, storage, renderList, camera, renderPass,
                        useBlockFaceCulling, false);
            }
            if (!batch.isEmpty()) {
                if (!this.voxy$ensureSharedIndexBufferCapacityWithoutMapping(commandList, batch.getIndexBufferSize())) {
                    batch.size = 0;
                    batch.isFilled = true;
                }
            }
        }
    }

    @Unique
    private boolean voxy$ensureSharedIndexBufferCapacityWithoutMapping(CommandList commandList, int elementCount) {
        if (elementCount <= 0 || elementCount > VOXY_MAX_REASONABLE_SHARED_INDEX_ELEMENTS) {
            Logger.warn("Voxy Metal: skipping suspicious Sodium shared index batch with", elementCount, "elements");
            return false;
        }

        AccessorSharedQuadIndexBuffer accessor = (AccessorSharedQuadIndexBuffer) this.sharedIndexBuffer;
        SharedQuadIndexBuffer.IndexType indexType = accessor.voxy$getIndexType();
        if (elementCount > indexType.getMaxElementCount()) {
            throw new IllegalArgumentException("Tried to reserve storage for more vertices in this buffer than it can hold");
        }

        int primitiveCount = elementCount / 6;
        if (primitiveCount <= accessor.voxy$getMaxPrimitives()) {
            return true;
        }

        int nextSize = Math.min(
                Math.max(accessor.voxy$getMaxPrimitives() * 2, primitiveCount + 16_384),
                indexType.getMaxPrimitiveCount());

        NativeBuffer indexBuffer = null;
        try {
            indexBuffer = SharedQuadIndexBuffer.createIndexBuffer(indexType, nextSize);
            commandList.uploadData(accessor.voxy$getBuffer(), indexBuffer.getDirectBuffer(), GlBufferUsage.STATIC_DRAW);
            accessor.voxy$setMaxPrimitives(nextSize);
            Logger.info("Voxy Metal: grew Sodium shared quad index buffer via uploadData to " + nextSize + " primitives");
            return true;
        } catch (RuntimeException | OutOfMemoryError e) {
            Logger.warn("Voxy Metal: failed to grow Sodium shared quad index buffer without mapping; skipping batch", e);
            return false;
        } finally {
            if (indexBuffer != null) {
                indexBuffer.free();
            }
        }
    }
}
