package me.cortex.voxy.client.core.interop;

import me.cortex.voxy.client.core.gl.shader.ShaderLoader;
import me.cortex.voxy.client.core.gpu.GraphicsPipelineDesc;
import me.cortex.voxy.client.core.gpu.IGpuBuffer;
import me.cortex.voxy.client.core.gpu.IGpuPipeline;
import me.cortex.voxy.client.core.gpu.IGpuTexture;
import me.cortex.voxy.client.core.gpu.PipelineState;
import me.cortex.voxy.client.core.gpu.RenderBackend;
import me.cortex.voxy.client.core.gpu.RenderEncoder;
import me.cortex.voxy.client.core.gpu.RenderPassDesc;
import me.cortex.voxy.client.core.gpu.VertexLayout;

import java.util.HashMap;
import java.util.Map;

/**
 * Phase D (issue #11): fullscreen depth-only pass seeding the translucent
 * LOD pass's depth attachment from the opaque depth blit buffer (the same
 * raw-float buffer MetalDepthExport reads — Metal can't sample depth
 * textures, and there's no texture→texture depth blit binding; the buffer
 * already exists post-opaque, so this costs one cheap fullscreen pass).
 * Depth func ALWAYS + write on; no colour attachments. Pipelines cached
 * per width (WIDTH is a compile-time define; resize is rare).
 */
public final class MetalDepthRestore {

    private static final PipelineState DEPTH_ALWAYS_WRITE = new PipelineState(
            new PipelineState.DepthState(true, true, PipelineState.CompareOp.ALWAYS),
            PipelineState.BlendState.OPAQUE,
            PipelineState.RasterState.NO_CULL);

    private final Map<Integer, IGpuPipeline> pipelineByWidth = new HashMap<>();

    public MetalDepthRestore(RenderBackend backend) {
        // Pipelines built lazily per width in render().
    }

    private IGpuPipeline pipeline(RenderBackend backend, int width) {
        return this.pipelineByWidth.computeIfAbsent(width, w ->
                backend.createGraphicsPipeline(new GraphicsPipelineDesc(
                        ShaderLoader.parse("voxy:post/fullscreen2.vert"),
                        ShaderLoader.parse("voxy:post/metal_depth_restore.frag"),
                        Map.of("WIDTH", Integer.toString(w)),
                        null, null,
                        null, null,
                        /*no colour attachment*/ 0,
                        VertexLayout.EMPTY,
                        DEPTH_ALWAYS_WRITE,
                        "MetalDepthRestore@" + w)));
    }

    /** Write the buffer's depths into {@code depthTarget} (full screen). */
    public void render(RenderBackend backend, IGpuBuffer depthBuf, IGpuTexture depthTarget, int width, int height) {
        var pass = RenderPassDesc.builder(width, height)
                .depthAttachment(depthTarget, 0,
                        RenderPassDesc.LoadAction.DONT_CARE,
                        RenderPassDesc.StoreAction.STORE, 1.0f)
                .build();
        try (RenderEncoder enc = backend.beginRenderPass(pass)) {
            enc.setPipeline(this.pipeline(backend, width));
            enc.setBuffer(0, depthBuf, 0);
            enc.setViewport(0, 0, width, height, 0.0f, 1.0f);
            enc.draw(RenderEncoder.PRIMITIVE_TRIANGLE_STRIP, 0, 4, 1, 0);
        }
    }

    public void close() {
        this.pipelineByWidth.values().forEach(IGpuPipeline::close);
        this.pipelineByWidth.clear();
    }
}
