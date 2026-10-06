package me.cortex.voxy.client.core.interop;

import me.cortex.voxy.client.core.gl.shader.ShaderLoader;
import me.cortex.voxy.client.core.gpu.*;
import me.cortex.voxy.common.util.ResourceScope;
import static org.lwjgl.opengl.GL11C.GL_RGBA8;

/** Blends captured prelit surfaces once over the opaque bridge. */
public final class MetalPrelitResolve implements AutoCloseable {
    private final RenderBackend backend;
    private final IGpuPipeline pipeline;
    private final boolean twoLayers;
    private final ResourceScope resources = new ResourceScope();

    public MetalPrelitResolve(RenderBackend backend) {
        this(backend, false);
    }

    public MetalPrelitResolve(RenderBackend backend, boolean twoLayers) {
        this.backend = backend;
        this.twoLayers = twoLayers;
        try {
            this.pipeline = resources.own(backend.createGraphicsPipeline(new GraphicsPipelineDesc(
                    ShaderLoader.parse("voxy:post/fullscreen2.vert"),
                    ShaderLoader.parse("voxy:post/prelit_resolve.frag"), twoLayers ? java.util.Map.of("TWO_LAYERS", "") : java.util.Map.of(),
                    null, null, null, null, GL_RGBA8, VertexLayout.EMPTY,
                    new PipelineState(twoLayers
                            ? new PipelineState.DepthState(true, true, PipelineState.CompareOp.ALWAYS)
                            : PipelineState.DepthState.DISABLED, PipelineState.BlendState.ALPHA,
                            PipelineState.RasterState.NO_CULL), "MetalPrelitResolve")), IGpuPipeline::close);
        } catch (RuntimeException | Error error) {
            resources.rollback(error);
            throw error;
        }
    }

    public void render(IGpuTexture destination, IGpuTexture layer, int width, int height) {
        render(destination, layer, null, null, null, null, width, height);
    }

    public void render(IGpuTexture destination, IGpuTexture surface, IGpuTexture surfaceDepth,
                       IGpuTexture fluid, IGpuTexture fluidDepth, IGpuTexture sceneDepth, int width, int height) {
        if (twoLayers != (fluid != null) || twoLayers != (sceneDepth != null)
                || twoLayers != (surfaceDepth != null) || twoLayers != (fluidDepth != null)) {
            throw new IllegalArgumentException("Prelit inputs must match the single-layer or depth-sorted resolve pipeline");
        }
        var pass = RenderPassDesc.builder(width, height)
                .addColorAttachment(destination, 0, RenderPassDesc.LoadAction.LOAD,
                        RenderPassDesc.StoreAction.STORE, 0, 0, 0, 0);
        if (sceneDepth != null) pass.depthAttachment(sceneDepth, 0,
                RenderPassDesc.LoadAction.LOAD, RenderPassDesc.StoreAction.STORE, 1);
        try (var encoder = backend.beginRenderPass(pass.build())) {
            encoder.setPipeline(pipeline);
            encoder.setTexture(0, surface);
            if (fluid != null) {
                encoder.setTexture(1, surfaceDepth);
                encoder.setTexture(2, fluid);
                encoder.setTexture(3, fluidDepth);
            }
            encoder.setViewport(0, 0, width, height, 0, 1);
            encoder.draw(RenderEncoder.PRIMITIVE_TRIANGLE_STRIP, 0, 4, 1, 0);
        }
    }

    @Override public void close() { resources.close(); }
}
