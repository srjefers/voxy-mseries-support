package me.cortex.voxy.client.core.interop;

import me.cortex.voxy.client.core.gpu.*;
import me.cortex.voxy.client.core.metal.MetalRenderBackend;
import me.cortex.voxy.client.core.rendering.section.backend.mdic.TerrainShaderConfiguration;
import me.cortex.voxy.common.util.ResourceScope;
import java.util.function.BiConsumer;
import static org.lwjgl.opengl.GL30C.*;

/** Nearest fluid and non-fluid surfaces, sharing opaque occlusion and resolved in pixel depth order. */
public final class MetalPrelitLayers implements AutoCloseable {
    public record Inputs(IGpuTexture opaque, IGpuTexture surface, IGpuTexture surfaceDepth,
                         IGpuTexture fluid, IGpuTexture fluidDepth) { }
    private record Layer(IGpuTexture color, IGpuTexture depth, IGpuTexture rasterDepth, IGpuPipeline pipeline) { }
    private final MetalRenderBackend backend;
    private final int width, height;
    private final Layer surface, fluid;
    private final IGpuBuffer opaqueDepth;
    private final MetalDepthRestore restore;
    private final MetalPrelitResolve resolve;
    private final ResourceScope resources = new ResourceScope();

    public MetalPrelitLayers(MetalRenderBackend backend, int width, int height, TerrainShaderConfiguration configuration) {
        this.backend=backend;this.width=width;this.height=height;
        try {
            surface=layer(configuration.prelitLayer(false));
            fluid=layer(configuration.prelitLayer(true));
            opaqueDepth=resources.own(backend.createBuffer((long)width*height*4),IGpuBuffer::free);
            restore=resources.own(new MetalDepthRestore(backend),MetalDepthRestore::close);
            resolve=resources.own(new MetalPrelitResolve(backend,true),MetalPrelitResolve::close);
        } catch(RuntimeException | Error failure) {
            resources.rollback(failure);throw failure;
        }
    }
    private IGpuTexture texture(int format) {
        return resources.own(backend.createTexture(),IGpuTexture::free).store(format,1,width,height);
    }
    private Layer layer(GraphicsPipelineDesc descriptor) {
        return new Layer(texture(GL_RGBA8),texture(GL_R32F),texture(GL_DEPTH_COMPONENT32F),
                resources.own(backend.createGraphicsPipeline(descriptor),IGpuPipeline::close));
    }

    /** Normal captures stay on the GPU queue, without CPU readback or a visibility wait. */
    public void render(IGpuTexture destination, IGpuTexture sceneDepth, BiConsumer<RenderEncoder,IGpuPipeline> draw) {
        render(destination,sceneDepth,draw,ignored -> { });
    }

    public void render(IGpuTexture destination, IGpuTexture sceneDepth, BiConsumer<RenderEncoder,IGpuPipeline> draw,
                       java.util.function.Consumer<Inputs> beforeResolve) {
        backend.copyTextureToBuffer(sceneDepth,opaqueDepth,width,height);
        capture(surface,draw);
        capture(fluid,draw);
        beforeResolve.accept(new Inputs(destination,surface.color,surface.depth,fluid.color,fluid.depth));
        resolve.render(destination,surface.color,surface.depth,fluid.color,fluid.depth,sceneDepth,width,height);
    }
    private void capture(Layer layer, BiConsumer<RenderEncoder,IGpuPipeline> draw) {
        restore.render(backend,opaqueDepth,layer.rasterDepth,width,height);
        var pass=RenderPassDesc.builder(width,height).clearColor(layer.color,0,0,0,0)
                .clearColor(layer.depth,1,0,0,0)
                .depthAttachment(layer.rasterDepth,0,RenderPassDesc.LoadAction.LOAD,
                        RenderPassDesc.StoreAction.STORE,1).build();
        try(var encoder=backend.beginRenderPass(pass)) {
            encoder.setViewport(0,0,width,height,0,1);
            draw.accept(encoder,layer.pipeline);
        }
    }
    @Override public void close() { resources.close(); }
}
