package me.cortex.voxy.client.mixin.sodium;

import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.buffers.GpuBufferSlice;
import com.mojang.renderpearl.api.commands.RenderPass;
import com.mojang.renderpearl.api.textures.GpuSampler;
import com.mojang.renderpearl.backend.opengl.GlTextureView;
import me.cortex.voxy.client.VoxyClient;
import me.cortex.voxy.client.core.IVoxyRenderSystemHolder;
import me.cortex.voxy.client.core.VoxyRenderSystem;
import me.cortex.voxy.client.core.gpu.BackendType;
import me.cortex.voxy.client.core.gpu.RenderBackendFactory;
import me.cortex.voxy.client.core.rendering.Viewport;
import me.cortex.voxy.client.core.util.IrisGbufferInjector;
import me.cortex.voxy.client.core.util.IrisUtil;
import net.caffeinemc.mods.sodium.client.render.chunk.ChunkRenderMatrices;
import net.caffeinemc.mods.sodium.client.render.chunk.DefaultChunkRenderer;
import net.caffeinemc.mods.sodium.client.render.chunk.ShaderChunkRenderer;
import net.caffeinemc.mods.sodium.client.render.chunk.lists.ChunkRenderListIterable;
import net.caffeinemc.mods.sodium.client.render.chunk.terrain.DefaultTerrainRenderPasses;
import net.caffeinemc.mods.sodium.client.render.chunk.terrain.TerrainRenderPass;
import net.caffeinemc.mods.sodium.client.render.chunk.vertex.format.ChunkVertexType;
import net.caffeinemc.mods.sodium.client.render.viewport.CameraTransform;
import net.caffeinemc.mods.sodium.client.util.FogParameters;
import net.minecraft.client.renderer.oit.OitStage;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import static me.cortex.voxy.client.core.interop.IOSurfaceBridgeCompositor.acquireAuxRectTex;

@Mixin(value = DefaultChunkRenderer.class, remap = false)
public abstract class MixinDefaultChunkRenderer extends ShaderChunkRenderer {

    @Shadow
    @Final
    private boolean[] shouldDraw;

    public MixinDefaultChunkRenderer(ChunkVertexType vertexType) {
        super(vertexType);
    }

    @Unique
    private static boolean voxy$isMetal() {
        return RenderBackendFactory.get().getType() != BackendType.OPENGL;
    }

    @Inject(method = "render", at = @At(value = "HEAD"), cancellable = true)
    private void voxy$cancelThingie(ChunkRenderMatrices matrices, ChunkRenderListIterable renderLists, TerrainRenderPass renderPass, CameraTransform camera, FogParameters parameters, boolean indexedRenderingEnabled, RenderPass pass, GpuSampler terrainSampler, GpuBufferSlice uniformData, GpuBuffer sectionTimeInfo, OitStage stage, CallbackInfo ci) {
        if (VoxyClient.disableSodiumChunkRender()) {
            super.begin(renderPass, parameters, terrainSampler, stage);
            this.doRender(matrices, renderPass, camera, parameters);
            super.end(renderPass);
            ci.cancel();
        }
    }

    // Metal: composite the LOD bridge at the HEAD of the SOLID pass, so Sodium's
    // near terrain draws over the distant LODs with a normal depth test.
    @Inject(method = "render", at = @At(value = "HEAD"))
    private void voxy$injectRenderMetal(ChunkRenderMatrices matrices, ChunkRenderListIterable renderLists, TerrainRenderPass renderPass, CameraTransform camera, FogParameters parameters, boolean indexedRenderingEnabled, RenderPass pass, GpuSampler terrainSampler, GpuBufferSlice uniformData, GpuBuffer sectionTimeInfo, OitStage stage, CallbackInfo ci) {
        if (voxy$isMetal() && !VoxyClient.disableSodiumChunkRender()) {
            this.doRender(matrices, renderPass, camera, parameters);
        }
    }

    @Inject(method = "render", at = @At(value = "INVOKE", target = "Lnet/caffeinemc/mods/sodium/client/render/chunk/ShaderChunkRenderer;end(Lnet/caffeinemc/mods/sodium/client/render/chunk/terrain/TerrainRenderPass;)V", shift = At.Shift.BEFORE))
    private void voxy$injectRender(ChunkRenderMatrices matrices, ChunkRenderListIterable renderLists, TerrainRenderPass renderPass, CameraTransform camera, FogParameters parameters, boolean indexedRenderingEnabled, RenderPass pass, GpuSampler terrainSampler, GpuBufferSlice uniformData, GpuBuffer sectionTimeInfo, OitStage stage, CallbackInfo ci) {
        if (!voxy$isMetal()) {
            this.doRender(matrices, renderPass, camera, parameters);
        }
    }

    @Inject(method = "prepare", at = @At(value = "TAIL"))
    private void voxy$forceOpaque(ChunkRenderListIterable renderLists, CameraTransform cameraTransform, boolean indexedRenderingEnabled, CallbackInfo ci) {
        var renderer = IVoxyRenderSystemHolder.getNullable();
        if (renderer != null) {
            this.shouldDraw[DefaultTerrainRenderPasses.getPassIndex(DefaultTerrainRenderPasses.CUTOUT)] = true;
            this.shouldDraw[DefaultTerrainRenderPasses.getPassIndex(DefaultTerrainRenderPasses.SOLID)] = true;
        }
    }

    @Unique
    private void doRender(ChunkRenderMatrices matrices, TerrainRenderPass renderPass, CameraTransform camera, FogParameters fogParameters) {
        boolean metal = voxy$isMetal();
        if (renderPass != (metal ? DefaultTerrainRenderPasses.SOLID : DefaultTerrainRenderPasses.CUTOUT)) {
            return;
        }
        VoxyRenderSystem renderer = IVoxyRenderSystemHolder.getNullable();
        if (renderer == null) {
            return;
        }
        boolean gbufferInject = metal && IrisUtil.irisGbufferInjectMode();
        if (gbufferInject && IrisUtil.shadowsBeingRendered()) {
            return;
        }

        var target = renderPass.getTarget();
        Viewport<?> viewport;
        // The stale-viewport reuse is for the GL Iris pipeline only; on Metal the
        // NormalRenderPipeline runs regardless of packs, so always set up per frame.
        if (IrisUtil.USED_IRIS_VIEWPORT && !metal) {
            viewport = renderer.getViewport();
            IrisUtil.USED_IRIS_VIEWPORT = false;
        } else {
            viewport = renderer.setupViewport(matrices.projection(), matrices.modelView(), fogParameters, target.width, target.height, camera.x, camera.y, camera.z);
        }
        if (viewport == null) {
            return;
        }
        renderer.renderOpaque(viewport, ((GlTextureView) target.getDepthTextureView()).glId(), ((GlTextureView) target.getColorTextureView()).glId());

        if (!metal) {
            return;
        }
        var pipeline = renderer.getPipeline();
        if (pipeline == null || pipeline.metalBridge() == null) {
            return;
        }
        if (pipeline.vxOpaqueMaterialMode()
                && pipeline instanceof me.cortex.voxy.client.core.MetalVxRenderPipeline mvp
                && pipeline.metalVxOpaque0() != null
                && pipeline.metalDepthBridge() != null) {
            var irisPipe = net.irisshaders.iris.Iris.getPipelineManager().getPipelineNullable();
            if (irisPipe instanceof net.irisshaders.iris.pipeline.IrisRenderingPipeline irp) {
                int tP0 = pipeline.metalVxTrans0() != null ? acquireAuxRectTex(pipeline.metalVxTrans0()) : 0;
                int tP1 = pipeline.metalVxTrans1() != null ? acquireAuxRectTex(pipeline.metalVxTrans1()) : 0;
                int tP2 = pipeline.metalVxTrans2() != null ? acquireAuxRectTex(pipeline.metalVxTrans2()) : 0;
                int tD  = pipeline.metalDepthTransBridge() != null ? acquireAuxRectTex(pipeline.metalDepthTransBridge()) : 0;
                me.cortex.voxy.client.core.util.MetalVxResolvePass.resolve(
                        mvp.getPipelineData(), irp,
                        acquireAuxRectTex(pipeline.metalVxOpaque0()),
                        acquireAuxRectTex(pipeline.metalVxOpaque1()),
                        acquireAuxRectTex(pipeline.metalVxOpaque2()),
                        acquireAuxRectTex(pipeline.metalDepthBridge()),
                        tP0, tP1, tP2, tD,
                        viewport.width, viewport.height);
            }
        } else if (pipeline.vxMaterialMode()
                && pipeline instanceof me.cortex.voxy.client.core.MetalVxRenderPipeline mvp2
                && pipeline.metalVxTrans0() != null
                && pipeline.metalDepthTransBridge() != null) {
            me.cortex.voxy.client.core.util.VxContractInjector.inject(viewport,
                    pipeline.metalBridge(), pipeline.metalDepthBridge(), null,
                    pipeline.metalDepthTransBridge());
            var irisPipe = net.irisshaders.iris.Iris.getPipelineManager().getPipelineNullable();
            if (irisPipe instanceof net.irisshaders.iris.pipeline.IrisRenderingPipeline irp) {
                me.cortex.voxy.client.core.util.MetalVxResolvePass.resolveTranslucentOnly(
                        mvp2.getPipelineData(), irp,
                        acquireAuxRectTex(pipeline.metalVxTrans0()),
                        acquireAuxRectTex(pipeline.metalVxTrans1()),
                        acquireAuxRectTex(pipeline.metalVxTrans2()),
                        acquireAuxRectTex(pipeline.metalDepthTransBridge()),
                        viewport.width, viewport.height);
            }
        } else if (IrisUtil.vxContractActive()) {
            me.cortex.voxy.client.core.util.VxContractInjector.inject(viewport,
                    pipeline.metalBridge(), pipeline.metalDepthBridge(),
                    pipeline.metalTransBridge(), pipeline.metalDepthTransBridge());
        } else if (gbufferInject) {
            IrisGbufferInjector.inject(viewport,
                    pipeline.metalBridge(), pipeline.metalDepthBridge());
        } else {
            me.cortex.voxy.client.core.interop.IOSurfaceBridgeCompositor
                    .composite(pipeline.metalBridge());
        }
    }
}
