package me.cortex.voxy.client.core.model.bakery;

import me.cortex.voxy.client.core.gl.shader.ShaderLoader;
import me.cortex.voxy.client.core.gpu.BackendType;
import me.cortex.voxy.client.core.gpu.GraphicsPipelineDesc;
import me.cortex.voxy.client.core.gpu.IGpuBuffer;
import me.cortex.voxy.client.core.gpu.IGpuPipeline;
import me.cortex.voxy.client.core.gpu.IGpuTexture;
import me.cortex.voxy.client.core.gpu.PipelineState;
import me.cortex.voxy.client.core.gpu.RenderBackend;
import me.cortex.voxy.client.core.gpu.RenderBackendFactory;
import me.cortex.voxy.client.core.gpu.RenderEncoder;
import me.cortex.voxy.client.core.gpu.RenderPassDesc;
import me.cortex.voxy.client.core.gpu.VertexLayout;
import me.cortex.voxy.client.core.metal.MetalTexture;
import me.cortex.voxy.client.core.rendering.util.AtlasMirror;
import me.cortex.voxy.client.core.rendering.util.NativeUniformWriter;
import me.cortex.voxy.client.core.rendering.util.UploadStream;
import org.joml.Matrix4f;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;

import static org.lwjgl.opengl.GL11C.GL_RGBA8;
import static org.lwjgl.opengl.GL11C.GL_TEXTURE_2D;

/** Uploads block meshes and renders six views into paired color/coverage targets. */
public final class MetalBudgetBufferRenderer {

    /** UBO binding for {@code position_tex.vsh}'s {@code Push { mat4 transform; }}. */
    private static final int PUSH_BINDING = 14;
    /** Sampler unit for {@code position_tex.fsh}'s {@code tex}. */
    private static final int TEX_BINDING = 0;

    /** Matches {@link BudgetBufferRenderer#VERTEX_FORMAT_SIZE}: vec4 pos + vec2 uv. */
    private static final int STRIDE = 24;
    /** Matches the GL path: 4096 quads × 6 indices × 2 bytes = 48 KB. */
    private static final int INDEX_BUFFER_BYTES = 3 * 2 * 2 * 4096;

    private final RenderBackend backend;
    private IGpuPipeline pipeline;
    private IGpuBuffer vertexBuffer;
    private long vertexBufferCapacity;
    private IGpuBuffer indexBuffer;
    private RenderEncoder activeEncoder;
    private int activeQuadCount;
    private IGpuTexture activeSourceTex;
    private IGpuTexture activeBakeTarget;
    private int activeWidth;
    private int activeHeight;

    public MetalBudgetBufferRenderer() {
        this.backend = RenderBackendFactory.get();
        if (this.backend.getType() == BackendType.OPENGL) {
            throw new IllegalStateException(
                    "MetalBudgetBufferRenderer is Metal-only — GL goes through BudgetBufferRenderer.");
        }
    }

    /**
     * Build the graphics pipeline + index buffer + push UBO. Idempotent;
     * the first {@link #beginPass} call invokes this lazily so callers
     * don't have to remember an init step.
     */
    private void ensureInit() {
        if (this.pipeline != null) return;

        String vsh = ShaderLoader.parse("voxy:bakery/position_tex.vsh");
        String fsh = ShaderLoader.parse("voxy:bakery/position_tex.fsh");

        // Vertex layout mirrors the GL VAO: attribute 0 = vec4 (pos + meta),
        // attribute 1 = vec2 (uv). Both interleaved at STRIDE bytes in
        // buffer slot 0.
        VertexLayout layout = VertexLayout.builder()
                .buffer(0, STRIDE, VertexLayout.StepRate.PER_VERTEX)
                .attribute(0, VertexLayout.VertexFormat.FLOAT4, 0, 0)
                .attribute(1, VertexLayout.VertexFormat.FLOAT2, 4 * 4, 0)
                .build();

        // NO_CULL because we render every face from a single pipeline and
        // rely on the per-face projection matrices to orient the geometry
        // correctly — same simplification as MDIC's terrain pipeline on
        // Metal (see MDICSectionRenderer constructor M12 chunk 6 polish).
        PipelineState state = new PipelineState(
                PipelineState.DepthState.DISABLED,
                PipelineState.BlendState.OPAQUE,
                PipelineState.RasterState.NO_CULL);

        java.util.Map<String, String> defines = java.util.Map.of("BAKERY_METAL_METADATA", "");
        this.pipeline = this.backend.createGraphicsPipeline(new GraphicsPipelineDesc(
                vsh, fsh, defines,
                null, null, null, null,
                new int[]{GL_RGBA8, GL_RGBA8},
                layout, state, "MetalBudgetBufferRenderer"));

        // Sequential 0,1,2 / 2,3,0 quad indices, same shape as the GL path's
        // copy from MC's sequential-buffer. We build them once on CPU and
        // upload via UploadStream; for the bakery's tiny mesh this is
        // cheaper than chasing MC's blaze3d helper from the Metal side.
        this.indexBuffer = this.backend.createBuffer(INDEX_BUFFER_BYTES);
        long ixDst = UploadStream.INSTANCE.upload(this.indexBuffer, 0, INDEX_BUFFER_BYTES);
        for (int q = 0; q < 4096; q++) {
            int base = q * 4;
            long off = ixDst + (long) q * 6L * 2L;
            MemoryUtil.memPutShort(off +  0, (short) (base + 0));
            MemoryUtil.memPutShort(off +  2, (short) (base + 1));
            MemoryUtil.memPutShort(off +  4, (short) (base + 2));
            MemoryUtil.memPutShort(off +  6, (short) (base + 2));
            MemoryUtil.memPutShort(off +  8, (short) (base + 3));
            MemoryUtil.memPutShort(off + 10, (short) (base + 0));
        }
        UploadStream.INSTANCE.commit();


    }

    /**
     * Open a render pass that targets {@code bakeTarget}. With {@code clear=true}
     * the colour attachment loads with CLEAR (transparent black); with
     * {@code clear=false} it loads with LOAD, preserving prior content — used
     * by the fluid bakery path which rebuilds the mesh between faces so it
     * needs each pass to keep the previous face's pixels in the target.
     *
     * <p>The bake target must be a Shared+RenderTarget Metal texture (allocate
     * via {@link MetalTexture#storeRenderTargetUploadable}) so the readback
     * after the final pass is a CPU memcpy.
     */
    public void beginPass(IGpuTexture bakeTarget, IGpuTexture metadataTarget, int width, int height, boolean clear) {
        ensureInit();
        if (this.activeEncoder != null) {
            throw new IllegalStateException("MetalBudgetBufferRenderer: nested beginPass");
        }
        this.activeBakeTarget = bakeTarget;
        this.activeWidth = width;
        this.activeHeight = height;
        RenderPassDesc.Builder b = RenderPassDesc.builder(width, height);
        if (clear) {
            b.clearColor(bakeTarget, 0f, 0f, 0f, 0f);
            b.clearColor(metadataTarget, 0f, 0f, 0f, 0f);
        } else {
            b.addColorAttachment(bakeTarget, 0,
                    RenderPassDesc.LoadAction.LOAD, RenderPassDesc.StoreAction.STORE,
                    0f, 0f, 0f, 0f);
            b.addColorAttachment(metadataTarget, 0, RenderPassDesc.LoadAction.LOAD,
                    RenderPassDesc.StoreAction.STORE, 0f, 0f, 0f, 0f);
        }
        this.activeEncoder = this.backend.beginRenderPass(b.build());
        this.activeEncoder.setPipeline(this.pipeline);
        this.activeEncoder.setViewport(0, 0, width, height, 0, 1);
        this.activeEncoder.bindIndexBuffer(this.indexBuffer,
                RenderEncoder.INDEX_TYPE_UINT16, 0L);
    }

    /**
     * Upload {@code quads} quads of vertex data starting at {@code dataPtr},
     * bind the source texture, and remember the count for the next
     * {@link #render} call. Mirrors {@link BudgetBufferRenderer#setup}.
     */
    public void setup(long dataPtr, int quads, IGpuTexture sourceTex,
                      me.cortex.voxy.client.core.gpu.IGpuSampler sourceSampler) {
        if (this.activeEncoder == null) {
            throw new IllegalStateException("setup() outside an active beginPass");
        }
        if (quads <= 0 || quads > 4096) {
            throw new IllegalStateException("Invalid quad count: " + quads);
        }
        this.activeQuadCount = quads;
        long bytes = (long) quads * 4L * STRIDE;
        if (this.vertexBuffer == null || this.vertexBufferCapacity < bytes) {
            if (this.vertexBuffer != null) this.vertexBuffer.free();
            this.vertexBufferCapacity = Math.max(bytes * 2L, 64L * 1024L);
            this.vertexBuffer = this.backend.createBuffer(this.vertexBufferCapacity);
        }
        long vDst = UploadStream.INSTANCE.upload(this.vertexBuffer, 0, bytes);
        MemoryUtil.memCopy(dataPtr, vDst, bytes);
        UploadStream.INSTANCE.commit();
        this.activeEncoder.bindVertexBuffer(0, this.vertexBuffer, 0L);
        this.activeEncoder.setTexture(TEX_BINDING, sourceTex);
        this.activeEncoder.setSampler(TEX_BINDING, sourceSampler);
        this.activeSourceTex = sourceTex;
    }

    /**
     * Change the viewport inside the active pass. The bakery places six cube
     * faces in a 3×2 grid inside the bake target, calling this between
     * {@link #render} invocations to target each face's 16×16 cell.
     */
    public void setViewport(int x, int y, int w, int h) {
        if (this.activeEncoder == null) {
            throw new IllegalStateException("setViewport() outside an active beginPass");
        }
        this.activeEncoder.setViewport(x, y, w, h, 0, 1);
    }

    /**
     * Issue the indexed draw with {@code matrix} pushed at {@link #PUSH_BINDING}.
     * Mirrors {@link BudgetBufferRenderer#render}.
     */
    public void render(Matrix4f matrix) {
        if (this.activeEncoder == null) {
            throw new IllegalStateException("render() outside an active beginPass");
        }
        try (MemoryStack stack = MemoryStack.stackPush()) {
            long addr = stack.nmalloc(64);
            NativeUniformWriter.putMatrix4f(addr, matrix);
            this.activeEncoder.setBytes(PUSH_BINDING, addr, 64);
        }
        this.activeEncoder.drawIndexed(
                RenderEncoder.PRIMITIVE_TRIANGLES,
                this.activeQuadCount * 6,
                /*instanceCount*/ 1,
                /*firstIndex*/ 0,
                /*vertexOffset*/ 0,
                /*firstInstance*/ 0);
    }

    /**
     * Close the render pass and submit. After this returns, the bake target
     * is readable on the CPU side (Shared storage) — callers read it via
     * a future {@code MetalViewCapture.emitToStream} equivalent.
     */
    public void endPass() {
        if (this.activeEncoder == null) return;
        this.activeEncoder.close();
        this.activeEncoder = null;
        this.backend.submit();
        this.activeQuadCount = 0;
        this.activeSourceTex = null;
        this.activeBakeTarget = null;
    }

    public void shutdown() {
        if (this.activeEncoder != null) {
            this.activeEncoder.close();
            this.activeEncoder = null;
        }
        if (this.pipeline != null) { this.pipeline.close(); this.pipeline = null; }
        if (this.vertexBuffer != null) { this.vertexBuffer.free(); this.vertexBuffer = null; }
        if (this.indexBuffer != null) { this.indexBuffer.free(); this.indexBuffer = null; }
    }
}
