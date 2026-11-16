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
import me.cortex.voxy.client.core.rendering.util.UploadStream;
import org.joml.Matrix4f;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;

import static org.lwjgl.opengl.GL11C.GL_RGBA8;
import static org.lwjgl.opengl.GL11C.GL_TEXTURE_2D;

/**
 * M13 chunk 1 foundation (2026-05-13): Metal-native counterpart to
 * {@link BudgetBufferRenderer}. Same shape — upload a quad mesh, bind a
 * source texture, draw into the bake target — but every GPU resource lives
 * on the Voxy {@link RenderBackend} abstraction (which on Metal lowers to
 * MTLBuffer / MTLTexture / MTLRenderCommandEncoder), so it bypasses Apple's
 * GL pixel-processor entirely and sidesteps the {@code glReadPixels}
 * SIGBUS chain that gated the original bakery off.
 *
 * <p><strong>This class is dormant.</strong> It compiles, allocates resources
 * on first use, and is ready to be invoked by a future Metal-aware variant
 * of {@link ModelTextureBakery#renderToStream}; but the bakery's auto-gate
 * is still active, so no call site exists yet. Wiring it in requires:
 * <ol>
 *   <li>Allocating a Shared-storage bake-target {@link MetalTexture} via
 *       {@link MetalTexture#storeRenderTargetUploadable} sized to the same
 *       48×32 RGBA8 layout {@code GlViewCapture} uses.</li>
 *   <li>Per-block: {@link AtlasMirror#syncMetal} on MC's atlas, then
 *       {@link #setup} / {@link #render} for each of the 6 cube faces.</li>
 *   <li>A Metal equivalent of {@link GlViewCapture#emitToStream} that
 *       memcpys the bake target's Shared storage into the persistent-mapped
 *       {@code destAddr} the caller provides — the simplest shape since the
 *       texture is already CPU-readable on Apple Silicon.</li>
 *   <li>Dropping the {@code isMetal} gate at
 *       {@link ModelTextureBakery#renderToStream}.</li>
 * </ol>
 *
 * <p>Caveats versus the GL path:
 * <ul>
 *   <li>Single colour attachment (no R32UI metadata buffer). The
 *       depth/stencil/tint bits {@code GlViewCapture} packs into the second
 *       uvec2 component default to 0 — sufficient for "real texture pixels
 *       on LOD chunks", insufficient for per-pixel tint state. A second
 *       attachment can be added once {@link RenderPassDesc} is confirmed to
 *       support multi-colour attachments on Metal (verified via a new smoke
 *       test before any per-pixel tint correctness work).</li>
 *   <li>No depth-stencil testing during the bake. The bakery's stencil
 *       dance counted overdraw between block faces; without it the simple
 *       case (single-cube models) works correctly via the cube projection
 *       matrices, but partially-transparent geometry (glass, slabs, etc.)
 *       may over-draw incorrectly. Acceptable for the MVP.</li>
 *   <li>{@link PipelineState#OPAQUE_MESH} with NO_CULL — the bakery's GL
 *       path toggles {@code glCullFace} between faces; Metal bakes cull
 *       mode into pipeline state so a single NO_CULL pipeline keeps the
 *       implementation small and matches the GL behaviour at the cost of
 *       drawing one extra triangle's worth of fragments per face.</li>
 * </ul>
 */
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
    private IGpuBuffer pushUbo;
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

        // M13 chunk 1: BAKERY_SINGLE_ATTACHMENT gates out the fragment shader's
        // metaOut declaration so position_tex.fsh transpiles to MSL with a
        // single colour output — matches the single-attachment Metal bake
        // target. The GL path keeps both outputs (it has the multi-attachment
        // FBO from GlViewCapture).
        java.util.Map<String, String> defines = java.util.Map.of("BAKERY_SINGLE_ATTACHMENT", "");
        this.pipeline = this.backend.createGraphicsPipeline(new GraphicsPipelineDesc(
                vsh, fsh, defines,
                null, null, null, null,
                GL_RGBA8,
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

        // One mat4 per draw — 64 bytes rounded up to 256 for std140-friendly
        // alignment. Re-used across all bakes (one block at a time, single
        // thread). NOTE: actually unused for now — render() pushes the
        // matrix via setBytes() inline; the UBO field is reserved for if a
        // future variant needs a persistent UBO (e.g. for instanced bakes).
        this.pushUbo = this.backend.createBuffer(256);
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
    public void beginPass(IGpuTexture bakeTarget, int width, int height, boolean clear) {
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
        } else {
            b.addColorAttachment(bakeTarget, 0,
                    RenderPassDesc.LoadAction.LOAD, RenderPassDesc.StoreAction.STORE,
                    0f, 0f, 0f, 0f);
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
            me.cortex.voxy.common.util.JomlMem.put(matrix, addr);
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
        if (this.pushUbo != null) { this.pushUbo.free(); this.pushUbo = null; }
    }
}
