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

/**
 * M13 chunk 1 (2026-05-13): Metal-side counterpart to {@link GlViewCapture}.
 * Owns the 48×32 Shared-storage Metal bake target, the
 * {@link MetalBudgetBufferRenderer}, and the {@link AtlasMirror} that lifts
 * MC's GL atlas onto Metal. Exposes the same API shape
 * ({@link #emitToStream}) so {@link ModelTextureBakery} can dispatch to
 * either GL or Metal at run-time without per-pixel format conversion in
 * the caller.
 *
 * <p>Per-bake flow used by {@code renderToStreamMetal}:
 * <pre>
 *   capture.beginBake(mcAtlasGlId, meshAddr, quadCount);
 *   for (int face = 0; face &lt; 6; face++) {
 *       capture.renderFace(face%3, face/3, matrix);
 *   }
 *   capture.endBake();           // ends pass + submit + GPU wait
 *   capture.emitToStream(dest);  // CPU read + pack into uvec2-per-pixel
 * </pre>
 *
 * <p>Output format matches {@link GlViewCapture#emitToStream} pixel-for-pixel
 * so {@code ModelStore} consumers don't need to branch on backend:
 * <pre>
 *   outA[0..3] = packed RGBA (little-endian: R at lowest byte)
 *   outA[4..7] = (depthBits &lt;&lt; 8) | (tintBit &lt;&lt; 7)
 * </pre>
 * The MVP doesn't write a metadata attachment (single RGBA8 colour target
 * only), so the second uvec2 component is zero — depth-test-driven downstream
 * features (e.g. alpha-discard variant flags) are lost in this round. Real
 * RGBA colour pixels DO flow through, which is the big visual win versus the
 * VOXY_NO_ATLAS hash-colour placeholder.
 *
 * <p>The single-attachment shape sidesteps a Metal-side
 * {@code RenderPassDesc.colorAttachment(N, ...)} multi-binding investigation
 * — the bakery's MVP can ship without it, and a follow-up can swap to a
 * two-attachment variant once we verify the descriptor supports it on Metal.
 */
public final class MetalViewCapture {
    private final int width;        // per-face cell width
    private final int height;       // per-face cell height
    private final int totalW;       // 3 * width
    private final int totalH;       // 2 * height

    private final RenderBackend backend;
    private final MetalTexture bakeTarget;
    private final AtlasMirror atlasMirror;
    private final MetalBudgetBufferRenderer renderer;
    private final long readbackBuffer;
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

        MetalTexture t = (MetalTexture) this.backend.createTexture(GL_TEXTURE_2D);
        t.storeRenderTargetUploadable(GL_RGBA8, 1, this.totalW, this.totalH);
        t.name("Voxy.MetalBakeTarget");
        this.bakeTarget = t;

        this.atlasMirror = new AtlasMirror();
        this.renderer = new MetalBudgetBufferRenderer();

        this.readbackBytes = (long) this.totalW * this.totalH * 4L;
        this.readbackBuffer = MemoryUtil.nmemAllocChecked(this.readbackBytes);
    }

    /**
     * Open the render pass with a clear-to-zero load and stage the mesh +
     * atlas + sampler bindings. {@code meshAddr} must point at packed
     * quad vertex data in the same {@link BudgetBufferRenderer#VERTEX_FORMAT_SIZE}-stride
     * layout the GL path uses; {@code mcAtlasGlId} is MC's
     * {@code textures/atlas/blocks.png} GL texture id (the
     * {@link AtlasMirror} CPU-reads it via {@code nglGetTexImage} and uploads
     * to the Metal mirror only when the id changes).
     */
    /**
     * Clear the bake target to transparent black. Opens and closes a tiny
     * render pass that only does a CLEAR load action — no draws. Lets the
     * caller separate "clear once at start of bake" from "draw each face",
     * which is essential for the fluid path where the face draws happen in
     * per-face passes (each with LOAD) to allow per-face mesh re-uploads
     * outside the active render encoder.
     */
    public void clear() {
        if (this.activeBake) {
            throw new IllegalStateException("clear() while a bake pass is active");
        }
        // Open + immediately close a CLEAR pass. The Metal driver collapses
        // this into a single clearColor command on the bake target.
        try (var enc = this.backend.beginRenderPass(
                me.cortex.voxy.client.core.gpu.RenderPassDesc.builder(this.totalW, this.totalH)
                        .clearColor(this.bakeTarget, 0f, 0f, 0f, 0f)
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
            // MC's atlas not yet ready — leave the bake target zeroed. The
            // pack loop below will emit transparent black pixels which the
            // shader's alpha-discard will skip naturally on the next draw.
            return;
        }
        this.renderer.beginPass(this.bakeTarget, this.totalW, this.totalH, clear);
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

    /**
     * Read the bake target via {@link MetalTexture#getBytes} and pack into
     * the legacy uvec2-per-pixel layout {@link GlViewCapture#emitToStream}
     * produces. The metadata (second uvec2 component) is zero — see class
     * Javadoc for the MVP trade-off.
     */
    public void emitToStream(long destAddr) {
        emitToStream(destAddr, false);
    }

    /**
     * @param skipDilate true for plant cross-model bakes (2026-09-25, S1 of the
     *   far-LOD plant work): the bake-fill dilation turns a sparse blade cell
     *   into a 100%-written flat green tile, which ModelFactory then marks as
     *   full-cover + occluding and quads.frag has no alpha left to discard —
     *   that, plus the zero depth metadata below, is what made every plant a
     *   hollow green box at LOD 0. Plants keep their blade alpha instead;
     *   ModelFactory centres their side faces (VOXY_LOD_PLANT_CROSS=0 reverts).
     */
    public void emitToStream(long destAddr, boolean skipDilate) {
        // CPU read from Shared storage. Backend.submit() in endBake() already
        // waited for the GPU; getBytes is then just a memcpy.
        this.bakeTarget.getBytes(0, 0, 0, this.totalW, this.totalH, this.readbackBuffer);

        // Diagnostic: alpha distribution BEFORE dilation. The [Metal-BAKE]
        // log reads these counters and steady-state shows ~1.5% fullAlpha,
        // ~50% nonzero, ~50% zero — the bakery itself is sparse, which is why
        // dilation matters for the LOD horizon visual.
        long pixels = (long) this.totalW * this.totalH;
        int nonzeroAlphaPixels = 0;
        for (long i = 0; i < pixels; i++) {
            int rgba = MemoryUtil.memGetInt(this.readbackBuffer + i * 4L);
            if ((rgba & 0xFF000000) != 0) nonzeroAlphaPixels++;
        }
        if (nonzeroAlphaPixels > 0) {
            GlViewCapture.DIAG_BAKE_NONZERO_PIXEL_INVOCATIONS.incrementAndGet();
            if (nonzeroAlphaPixels * 2L > pixels) {
                GlViewCapture.DIAG_BAKE_FULL_ALPHA_INVOCATIONS.incrementAndGet();
            }
        } else {
            GlViewCapture.DIAG_BAKE_ZERO_ALPHA_INVOCATIONS.incrementAndGet();
        }

        // M13 chunk 1 polish (2026-05-16): bake-fill via per-cell average.
        // The bakery's per-face model rendering only fills the cell where the
        // model has geometry — for non-cube blocks (leaves, fences, slabs,
        // ~98% of baked states) that's 5-30% of pixels, leaving the rest at
        // the RGBA(0,0,0,0) clear value. Without dilation: distant LOD chunks
        // mip-average to alpha≈0 and the shader's discard kicks in, leaving
        // see-through patches on the LOD horizon.
        //
        // Fix D (2026-05-17): run dilation on any bake that produced opaque
        // pixels. The original gate skipped bakes where >50% of pixels were
        // already opaque ("fullAlpha"), but that bucket includes blocks with
        // 5-of-6 fully-rendered faces and 1 cell with internal gaps — those
        // gaps survived and discarded in the LOD shader at any FOV (not just
        // through the spyglass). Dilation is a no-op for pixels already opaque
        // (the second pass checks `(p & 0xFF000000) != 0`), so running it on
        // fullAlpha bakes only costs the per-cell average scan and only fills
        // genuinely empty texels.
        if (nonzeroAlphaPixels > 0 && !skipDilate) {
            GlViewCapture.DIAG_BAKE_DILATE_RUNS.incrementAndGet();
            int filled = dilateOpaqueIntoGaps();
            GlViewCapture.DIAG_BAKE_DILATE_PIXELS_FILLED.addAndGet(filled);
        }

        // CRITICAL: the bakery's bake target is a 48×32 raster image where the
        // 6 face cells are laid out in a 3×2 grid (face N at column=N%3,
        // row=N/3 — see the renderFace call sites in renderToStreamMetal /
        // ModelTextureBakery.renderToStream). But the consumer of destAddr
        // (ModelFactory.processModelResult) reads bytes [2048*N, 2048*(N+1))
        // as face N's 256 pixels in row-major within-cell order. Walking the
        // raster image straight into destAddr lands rows-0..5 of the whole
        // bake target into "face 0", which is geometrically wrong — face 0
        // actually lives at pixels (0..15, 0..15).
        //
        // The legacy GL bakery had a `bufferreorder.comp` compute shader that
        // did this raster→face-major rearrangement on the GPU; when the
        // bakery switched to CPU readback it dropped that step. The result is
        // every consumer downstream (ColourDepthTextureData[], MipGen, atlas
        // upload, LOD shader sampling) sees scrambled face data, which is
        // why the user reports "LOD chunks parpadean entre gris, magenta y
        // transparente" — each section's atlas slot has the wrong sub-image
        // for each face, so mip averaging produces unpredictable noise.
        //
        // Pack face-major: for each face N, copy that face's 16×16 cell from
        // the bake target into a contiguous 256-pixel run at destAddr +
        // 2048*N. Within each face, pixels are row-major (y*16 + x).
        final int cellW = this.width;       // 16
        final int cellH = this.height;      // 16
        final int rowStrideBytes = this.totalW * 4;  // 48 * 4 = 192
        for (int face = 0; face < 6; face++) {
            int faceX = face % 3;
            int faceY = face / 3;
            long faceDst = destAddr + (long) face * cellW * cellH * 8L;
            long faceSrcBase = this.readbackBuffer
                    + (long) faceY * cellH * rowStrideBytes
                    + (long) faceX * cellW * 4L;
            for (int ly = 0; ly < cellH; ly++) {
                long srcRow = faceSrcBase + (long) ly * rowStrideBytes;
                long dstRow = faceDst + (long) ly * cellW * 8L;
                for (int lx = 0; lx < cellW; lx++) {
                    int rgba = MemoryUtil.memGetInt(srcRow + lx * 4L);
                    long dstPx = dstRow + lx * 8L;
                    MemoryUtil.memPutInt(dstPx,     rgba);
                    // The second uvec2 component normally carries depth and
                    // stencil/tint metadata. Metal's single-attachment MVP
                    // does not have those buffers yet, but SOLID model
                    // analysis still treats the low byte as "pixel was
                    // written". Mark opaque pixels with bit 7 so solid faces
                    // survive TextureUtils.WRITE_CHECK_STENCIL while empty
                    // cells remain empty.
                    MemoryUtil.memPutInt(dstPx + 4, (rgba & 0xFF000000) != 0 ? 0x80 : 0);
                }
            }
        }
    }

    /**
     * Fill every transparent pixel in each cell with that cell's average
     * written RGBA so each face cell becomes a uniform-colour tile.
     * Water fix (2026-06-09): the fill alpha is the cell's average written
     * alpha, not a hard 0xFF — for SOLID/CUTOUT bakes every written pixel is
     * alpha=255 so the fill is bit-identical to before, but TRANSLUCENT
     * bakes (water ≈ 0.7-0.8 alpha) keep their translucency instead of the
     * gaps turning opaque.
     *
     * <p>Original implementation was 4-neighbour dilation. It worked for
     * gap-densities up to {@link #DILATE_PASSES}-pixels but left larger
     * gaps in sparse models (fences, thin gates) at alpha=0 — at higher mip
     * levels the LOD shader's {@code textureGrad} averaged those gaps back
     * to alpha≈0 and the magenta-missing debug fired (visible to the user
     * as "grey flickering chunks that turn magenta in the spyglass zoom").
     *
     * <p>The cell-average approach guarantees full coverage in O(cellArea)
     * with no neighbour iterations or pass count. The trade-off is no
     * within-cell variation — every gap pixel in the cell gets the same
     * colour. For LOD chunks that's the right call: at viewing distance
     * each face cell is sub-pixel in screen space, so the dominant colour
     * is what matters, not the spatial pattern.
     *
     * <p>Per-cell boundary still matters: each of the 6 face cells renders
     * a different cube face. Averaging across cell boundaries would smear
     * top-face green into side-face dirt-brown, etc.
     *
     * @return total pixels filled (for diagnostic only)
     */
    private int dilateOpaqueIntoGaps() {
        final int w = this.totalW;
        final int cellW = this.width;
        final int cellH = this.height;
        int totalFilled = 0;
        // 6 cells in a 3×2 grid. Process each cell independently.
        for (int cellRow = 0; cellRow < 2; cellRow++) {
            for (int cellCol = 0; cellCol < 3; cellCol++) {
                final int x0 = cellCol * cellW;
                final int y0 = cellRow * cellH;
                // Pass 1: compute the average written RGBA in this cell.
                long rSum = 0, gSum = 0, bSum = 0, aSum = 0;
                int opaqueCount = 0;
                for (int y = y0; y < y0 + cellH; y++) {
                    for (int x = x0; x < x0 + cellW; x++) {
                        long off = ((long) y * w + x) * 4L;
                        int p = MemoryUtil.memGetInt(this.readbackBuffer + off);
                        if ((p & 0xFF000000) == 0) continue;
                        rSum += (p      ) & 0xFF;
                        gSum += (p >>  8) & 0xFF;
                        bSum += (p >> 16) & 0xFF;
                        aSum += (p >>> 24);
                        opaqueCount++;
                    }
                }
                if (opaqueCount == 0) continue; // entire cell empty — leave it
                int avgR = (int) (rSum / opaqueCount);
                int avgG = (int) (gSum / opaqueCount);
                int avgB = (int) (bSum / opaqueCount);
                // Written pixels have alpha >= 1, so the fill always survives
                // the (p & 0xFF000000) != 0 "was written" checks downstream.
                int avgA = Math.max(1, (int) (aSum / opaqueCount));
                int fill = (avgA << 24) | (avgB << 16) | (avgG << 8) | avgR;
                // Pass 2: write `fill` into every transparent pixel in cell.
                for (int y = y0; y < y0 + cellH; y++) {
                    for (int x = x0; x < x0 + cellW; x++) {
                        long off = ((long) y * w + x) * 4L;
                        int p = MemoryUtil.memGetInt(this.readbackBuffer + off);
                        if ((p & 0xFF000000) != 0) continue;
                        MemoryUtil.memPutInt(this.readbackBuffer + off, fill);
                        totalFilled++;
                    }
                }
            }
        }
        return totalFilled;
    }

    public void free() {
        this.renderer.shutdown();
        this.atlasMirror.free();
        if (this.bakeTarget != null) this.bakeTarget.free();
        if (this.readbackBuffer != 0L) MemoryUtil.nmemFree(this.readbackBuffer);
    }
}
