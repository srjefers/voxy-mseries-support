package me.cortex.voxy.client.core.model.bakery;

import me.cortex.voxy.client.core.gpu.BackendType;
import me.cortex.voxy.client.core.gpu.IGpuTexture;
import me.cortex.voxy.client.core.gpu.RenderBackend;
import me.cortex.voxy.client.core.gpu.RenderBackendFactory;
import me.cortex.voxy.client.core.metal.MetalTexture;
import me.cortex.voxy.client.core.rendering.util.AtlasMirror;
import org.joml.Matrix4f;
import org.lwjgl.system.MemoryUtil;

import static org.lwjgl.opengl.GL11C.GL_RGBA8;
import static org.lwjgl.opengl.GL11C.GL_TEXTURE_2D;

/** Metal bakery owner. Color and drawn/tint metadata share identical face viewports.
 * Depth remains the fork's existing synthetic convention; coverage is never synthesized from color.
 */
public final class MetalViewCapture {
    private final int width;        // per-face cell width
    private final int height;       // per-face cell height
    private final int totalW;       // 3 * width
    private final int totalH;       // 2 * height

    private final RenderBackend backend;
    private final MetalTexture bakeTarget;
    private final MetalTexture metadataTarget;
    private final AtlasMirror atlasMirror;
    private final MetalBudgetBufferRenderer renderer;
    private final long readbackBuffer;
    private final long metadataReadback;
    private final long readbackBytes;
    private boolean activeBake;

    public MetalViewCapture(int width, int height) {
        this.width = width;
        this.height = height;
        this.totalW = width * 3;
        this.totalH = height * 2;
        this.backend = RenderBackendFactory.get();
        if (this.backend.getType() == BackendType.OPENGL) {
            throw new IllegalStateException(
                    "MetalViewCapture is Metal-only — GL goes through GlViewCapture.");
        }

        MetalTexture color = null, metadata = null;
        AtlasMirror mirror = null;
        MetalBudgetBufferRenderer draws = null;
        long colors = 0, flags = 0;
        this.readbackBytes = (long) this.totalW * this.totalH * 4;
        try {
            color = target("Voxy.MetalBakeColor");
            metadata = target("Voxy.MetalBakeMetadata");
            mirror = new AtlasMirror();
            draws = new MetalBudgetBufferRenderer();
            colors = MemoryUtil.nmemAllocChecked(this.readbackBytes);
            flags = MemoryUtil.nmemAllocChecked(this.readbackBytes);
        } catch (Throwable error) {
            if (draws != null) draws.shutdown();
            if (mirror != null) mirror.free();
            if (metadata != null) metadata.free();
            if (color != null) color.free();
            if (colors != 0) MemoryUtil.nmemFree(colors);
            if (flags != 0) MemoryUtil.nmemFree(flags);
            throw error;
        }
        this.bakeTarget = color;
        this.metadataTarget = metadata;
        this.atlasMirror = mirror;
        this.renderer = draws;
        this.readbackBuffer = colors;
        this.metadataReadback = flags;
    }

    private MetalTexture target(String name) {
        MetalTexture texture = (MetalTexture) this.backend.createTexture(GL_TEXTURE_2D);
        try {
            texture.storeRenderTargetUploadable(GL_RGBA8, 1, this.totalW, this.totalH);
            texture.name(name);
            return texture;
        } catch (Throwable error) { texture.free(); throw error; }
    }

    public void clear() {
        if (this.activeBake) {
            throw new IllegalStateException("clear() while a bake pass is active");
        }
        // Open + immediately close a CLEAR pass. The Metal driver collapses
        // this into a single clearColor command on the bake target.
        try (var enc = this.backend.beginRenderPass(
                me.cortex.voxy.client.core.gpu.RenderPassDesc.builder(this.totalW, this.totalH)
                        .clearColor(this.bakeTarget, 0f, 0f, 0f, 0f)
                        .clearColor(this.metadataTarget, 0f, 0f, 0f, 0f)
                        .build())) {
            enc.setViewport(0, 0, this.totalW, this.totalH, 0, 1);
        }
        this.backend.submit();
    }

    public void beginBake(int mcAtlasGlId, long meshAddr, int quadCount, boolean clear) {
        if (this.activeBake) {
            throw new IllegalStateException("beginBake while a previous bake is active");
        }
        IGpuTexture atlas = this.atlasMirror.syncMetal(mcAtlasGlId);
        if (atlas == null) {
            this.clear(); // Never publish the previous block's bake.
            return;
        }
        this.renderer.beginPass(this.bakeTarget, this.metadataTarget, this.totalW, this.totalH, clear);
        this.renderer.setup(meshAddr, quadCount, atlas, this.atlasMirror.sampler());
        this.activeBake = true;
    }

    /**
     * Render the per-block mesh once into the 16×16 cell at
     * {@code (faceX, faceY)} of the 3×2 grid, using {@code matrix} as the
     * cube-projection transform. Mirrors the per-face draw the GL bakery
     * does at {@link ModelTextureBakery#renderToStream}.
     */
    public void renderFace(int faceX, int faceY, Matrix4f matrix) {
        if (!this.activeBake) return; // beginBake bailed (atlas not ready)
        this.renderer.setViewport(faceX * this.width, faceY * this.height,
                this.width, this.height);
        this.renderer.render(matrix);
    }

    /**
     * Close the render pass and submit. On Metal,
     * {@code RenderBackend.submit()} waits for the command buffer to
     * complete (verified at {@code MetalRenderBackend.submit():691}), so the
     * Shared bake target is CPU-readable as soon as this returns.
     */
    public void endBake() {
        if (!this.activeBake) return;
        this.renderer.endPass();
        this.activeBake = false;
    }

    /** Pack the 3x2 raster into legacy face-major RGBA + metadata words. */
    public void emitToStream(long destAddr) {
        this.bakeTarget.getBytes(0, 0, 0, this.totalW, this.totalH, this.readbackBuffer);
        this.metadataTarget.getBytes(0, 0, 0, this.totalW, this.totalH, this.metadataReadback);
        for (int face = 0; face < 6; face++) {
            for (int y = 0; y < this.height; y++) {
                for (int x = 0; x < this.width; x++) {
                    long src = ((long)(face / 3 * this.height + y) * this.totalW + face % 3 * this.width + x) * 4;
                    long dst = destAddr + ((long)face * this.width * this.height + y * this.width + x) * 8;
                    int rgba = MemoryUtil.memGetInt(this.readbackBuffer + src);
                    int flags = MemoryUtil.memGetInt(this.metadataReadback + src);
                    boolean drawn = (flags >>> 24) != 0;
                    MemoryUtil.memPutInt(dst, rgba);
                    MemoryUtil.memPutInt(dst + 4, drawn ? 1 | ((flags & 255) != 0 ? 128 : 0) : 0);
                }
            }
        }
        // MipGen already dilates RGB within each face while retaining alpha and coverage.
    }

    public void free() {
        this.renderer.shutdown();
        this.atlasMirror.free();
        this.bakeTarget.free();
        this.metadataTarget.free();
        MemoryUtil.nmemFree(this.readbackBuffer);
        MemoryUtil.nmemFree(this.metadataReadback);
    }
}
