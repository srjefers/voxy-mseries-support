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

    @Unique
    private void doRender(ChunkRenderMatrices matrices, CommandList commandList, ChunkRenderListIterable renderLists, TerrainRenderPass renderPass, CameraTransform camera, FogParameters fogParameters, boolean indexedRenderingEnabled) {
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
                if (pipeline != null && pipeline.metalBridge() != null) {
                    if (pipeline.vxOpaqueMaterialMode()
                            && pipeline instanceof me.cortex.voxy.client.core.MetalVxRenderPipeline mvp
                            && pipeline.metalVxOpaque0() != null
                            && pipeline.metalDepthBridge() != null) {
                        // FULL material path (A/B only, VOXY_VX_MATERIAL_OPAQUE=1): run the
                        // pack's voxy_opaque AND voxy_translucent over the material g-buffer.
                        // Darkens far opaque (BSL deferred shading of grazing LOD) — not the
                        // mergeable shape; kept for comparison.
                        var irisPipe = net.irisshaders.iris.Iris.getPipelineManager().getPipelineNullable();
                        if (irisPipe instanceof net.irisshaders.iris.pipeline.IrisRenderingPipeline irp) {
                            int oP0 = me.cortex.voxy.client.core.interop.IOSurfaceBridgeCompositor.acquireAuxRectTex(pipeline.metalVxOpaque0());
                            int oP1 = me.cortex.voxy.client.core.interop.IOSurfaceBridgeCompositor.acquireAuxRectTex(pipeline.metalVxOpaque1());
                            int oP2 = me.cortex.voxy.client.core.interop.IOSurfaceBridgeCompositor.acquireAuxRectTex(pipeline.metalVxOpaque2());
                            int oD  = me.cortex.voxy.client.core.interop.IOSurfaceBridgeCompositor.acquireAuxRectTex(pipeline.metalDepthBridge());
                            int tP0 = pipeline.metalVxTrans0() != null ? me.cortex.voxy.client.core.interop.IOSurfaceBridgeCompositor.acquireAuxRectTex(pipeline.metalVxTrans0()) : 0;
                            int tP1 = pipeline.metalVxTrans1() != null ? me.cortex.voxy.client.core.interop.IOSurfaceBridgeCompositor.acquireAuxRectTex(pipeline.metalVxTrans1()) : 0;
                            int tP2 = pipeline.metalVxTrans2() != null ? me.cortex.voxy.client.core.interop.IOSurfaceBridgeCompositor.acquireAuxRectTex(pipeline.metalVxTrans2()) : 0;
                            int tD  = pipeline.metalDepthTransBridge() != null ? me.cortex.voxy.client.core.interop.IOSurfaceBridgeCompositor.acquireAuxRectTex(pipeline.metalDepthTransBridge()) : 0;
                            me.cortex.voxy.client.core.util.MetalVxResolvePass.resolve(
                                    mvp.getPipelineData(), irp,
                                    oP0, oP1, oP2, oD, tP0, tP1, tP2, tD,
                                    viewport.width, viewport.height);
                        }
                    } else if (pipeline.vxMaterialMode()
                            && pipeline instanceof me.cortex.voxy.client.core.MetalVxRenderPipeline mvp2
                            && pipeline.metalVxTrans0() != null
                            && pipeline.metalDepthTransBridge() != null) {
                        // TRANS-ONLY (issue #11, the mergeable shape): opaque LODs go through
                        // the proven Phase-B inject (bridge colour + vxDepthTexOpaque, untouched
                        // vs dev); ONLY the translucent (water) layer runs voxy_translucent over
                        // its material g-buffer → colortex16, so water gets real BSL shading.
                        // 4th arg (transDepthBridge, non-null by the branch guard): the
                        // inject's seafloor water-column dim needs the LOD trans depth to
                        // reconstruct the water column above each opaque pixel. transBridge
                        // stays null, so the Phase-D passthrough (which needs BOTH) stays
                        // off and colortex16 is still written only by resolveTranslucentOnly.
                        me.cortex.voxy.client.core.util.VxContractInjector.inject(viewport,
                                pipeline.metalBridge(), pipeline.metalDepthBridge(), null,
                                pipeline.metalDepthTransBridge());
                        var irisPipe = net.irisshaders.iris.Iris.getPipelineManager().getPipelineNullable();
                        if (irisPipe instanceof net.irisshaders.iris.pipeline.IrisRenderingPipeline irp) {
                            int tP0 = me.cortex.voxy.client.core.interop.IOSurfaceBridgeCompositor.acquireAuxRectTex(pipeline.metalVxTrans0());
                            int tP1 = me.cortex.voxy.client.core.interop.IOSurfaceBridgeCompositor.acquireAuxRectTex(pipeline.metalVxTrans1());
                            int tP2 = me.cortex.voxy.client.core.interop.IOSurfaceBridgeCompositor.acquireAuxRectTex(pipeline.metalVxTrans2());
                            int tD  = me.cortex.voxy.client.core.interop.IOSurfaceBridgeCompositor.acquireAuxRectTex(pipeline.metalDepthTransBridge());
                            me.cortex.voxy.client.core.util.MetalVxResolvePass.resolveTranslucentOnly(
                                    mvp2.getPipelineData(), irp, tP0, tP1, tP2, tD,
                                    viewport.width, viewport.height);
                        }
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
