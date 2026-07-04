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
 * Metal-side depth export for the Iris gbuffer injection: a tiny fullscreen
 * pass that packs the LOD render pass's depth into the BGRA8 depth
 * {@link IOSurfaceBridge} (24-bit RGB EncodeFloatRGB), which the GL-side
 * compositor decodes and unprojects back into MC clip space.
 *
 * The pass reads depth from a plain MTLBuffer, NOT from the depth texture:
 * SPIRV-Cross declares plain sampler2D as texture2d&lt;float&gt;, and Metal
 * silently reads ZEROS when a depth-format texture is bound there (depth
 * needs a depth2d declaration, which SPIRV-Cross only emits for shadow
 * samplers). The caller blits the D32F attachment into the buffer first
 * ({@link me.cortex.voxy.client.core.metal.MetalRenderBackend#copyTextureToBuffer});
 * buffer reads are format-blind.
 *
 * Pipeline shape follows {@link me.cortex.voxy.client.core.rendering.util.HiZBuffer}'s
 * blit: GLSL via {@link ShaderLoader} → runtime MSL transpile, gl_VertexID
 * driven fullscreen geometry ({@code post/fullscreen2.vert}'s 4-vertex
 * strip), {@link VertexLayout#EMPTY}. WIDTH is baked in as a compile-time
 * define (the shader indexes the buffer by gl_FragCoord), so pipelines are
 * cached per width — resize is rare and the transpile is disk-cached. The
 * pass is color-only (CLEAR 0 then draw — no depth attachment, the fragment
 * writes no depth) and is encoded into the frame's already-open command
 * buffer, so it rides the existing end-of-frame {@code submit()} without
 * extra waits.
 */
public final class MetalDepthExport {

    /** GL_RGBA8 — depth crosses the bridge 24-bit-packed in the proven BGRA8 surface format. */
    private static final int GL_RGBA8 = 0x8058;

    private final Map<Integer, IGpuPipeline> pipelineByWidth = new HashMap<>();

    public MetalDepthExport(RenderBackend backend) {
        // Pipelines are built lazily per width in render(); nothing to set up.
    }

    private IGpuPipeline pipeline(RenderBackend backend, int width) {
        return this.pipelineByWidth.computeIfAbsent(width, w ->
                backend.createGraphicsPipeline(new GraphicsPipelineDesc(
                        ShaderLoader.parse("voxy:post/fullscreen2.vert"),
                        ShaderLoader.parse("voxy:post/metal_depth_export.frag"),
                        Map.of("WIDTH", Integer.toString(w)),
                        null, null,                  // no MSL — runtime compiler produces it
                        null, null,                  // no SPIRV
                        GL_RGBA8,
                        VertexLayout.EMPTY,
                        // No depth attachment on the export pass, so depth
                        // test/write must be fully off (Metal rejects encoder
                        // depth-write without an attachment); DEFAULT is
                        // exactly that (DISABLED depth, OPAQUE blend, NO_CULL).
                        PipelineState.DEFAULT,
                        "MetalDepthExport@" + w)));
    }

    /**
     * Encode the export pass: read raw depth floats from {@code depthBuf}
     * (the blit destination of the LOD pass's depth attachment, row-major,
     * {@code width} floats per row) and write them RGB-packed into
     * {@code target} (the BGRA8 depth bridge's texture). Caller must invoke
     * AFTER the LOD render pass closed and AFTER the depth blit was enqueued,
     * and BEFORE {@code backend.submit()} so the pass lands in the same
     * command buffer (encoder order on one buffer is the cross-encoder
     * barrier Metal gives us for free).
     */
    public void render(RenderBackend backend, IGpuBuffer depthBuf, IGpuTexture target, int width, int height) {
        var pass = RenderPassDesc.builder(width, height)
                .clearColor(target, 0.0f, 0.0f, 0.0f, 0.0f)
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
