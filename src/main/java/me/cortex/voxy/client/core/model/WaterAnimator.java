package me.cortex.voxy.client.core.model;

import me.cortex.voxy.client.core.gpu.BackendType;
import me.cortex.voxy.client.core.gpu.RenderBackendFactory;
import me.cortex.voxy.common.Logger;
import me.cortex.voxy.common.util.MemoryBuffer;
import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.SpriteContents;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.resources.Identifier;
import org.lwjgl.system.MemoryUtil;

import java.util.ArrayList;
import java.util.List;

import static me.cortex.voxy.client.core.model.ModelFactory.LAYERS;
import static me.cortex.voxy.client.core.model.ModelFactory.MODEL_TEXTURE_SIZE;
import static org.lwjgl.opengl.GL11.GL_RGBA;
import static org.lwjgl.opengl.GL11.GL_UNSIGNED_BYTE;

/**
 * Animated LOD water on Metal (2026-06-10). The last visible LOD&lt;-&gt;MC
 * water difference: MC's {@code block/water_still} sprite animates (32
 * frames of 16x16, frametime 2 ticks in vanilla) while the model atlas
 * holds the ONE frame the bakery captured at bake time. This class
 * re-uploads the source-water model's atlas cells with the current
 * animation frame as it advances, so LOD water ripples at the same cadence
 * as MC's near-field water.
 *
 * <p><strong>Cell layout / orientation facts</strong> (verified against the
 * bake chain, see the session notes):
 * <ul>
 *   <li>Model {@code modelId} occupies a 48x32 region of the model atlas at
 *       {@code X = (modelId&0xFF)*48, Y = ((modelId>>8)&0xFF)*32}
 *       ({@link ModelFactory.ModelBakeResultUpload#upload}); face {@code f}
 *       is the 16x16 cell at {@code (f>>1, f&1)} inside that region
 *       ({@link MipGen#putTextures}, matching quads.frag's getBaseUV).</li>
 *   <li>The fluid bake samples MC's atlas with no vertex colour, no shade
 *       and no blending (ReuseVertexConsumer drops setColor; the bakery
 *       pipeline is BlendState.OPAQUE and the LOD bias is 0 for
 *       TRANSLUCENT), so the stored cell texels ARE the sprite texels.</li>
 *   <li>Orientation is IDENTITY: LiquidBlockRenderer maps u&prop;x, v&prop;z
 *       on both the UP and DOWN still-water quads (verified in the 1.21.11
 *       bytecode), and the Metal bake projection (ndcX=2x-1, ndcY=-2z+1,
 *       top-row-first storage) lands sprite row {@code r} in cell row
 *       {@code r}. No flip is required.</li>
 *   <li>The 0x80 tint bit lives in the bake's DEPTH metadata stream
 *       (MetalViewCapture.emitToStream's second uvec2 word), which only
 *       feeds CPU-side metadata at bake time — the colour cell carries pure
 *       RGBA, so re-uploading colour never disturbs tint/face metadata.</li>
 * </ul>
 *
 * <p>Frames are CPU-resident ({@code SpriteContents.originalImage}
 * NativeImage — RGBA bytes, same memory layout as the atlas upload), copied
 * ONCE into a long-lived native buffer (incl. the 8/4/2 mip chain computed
 * with the same {@link TextureUtils#mipColours} MipGen uses; cell-local
 * mipping is byte-identical because cell origins stay 2^lvl-aligned).
 * Absolutely no GL readbacks (the glGetTexImage class SIGBUSes Apple GL).
 *
 * <p>Threading: bake completion runs on the model-factory thread, but the
 * animation target only becomes valid once the bake's atlas upload lands —
 * so registration happens on the render thread inside
 * {@link ModelFactory#tickAndProcessUploads} (the marked upload object is
 * safely published through the uploadResults deque), and {@link #tick}
 * uploads strictly on the render thread, mirroring LightMapHelper's
 * per-frame {@code uploadSubImage2D} pattern.
 *
 * <p>This pass animates the still-water faces only (source water UP+DOWN,
 * which sample {@code water_still}); the side faces sample
 * {@code water_flow} and flowing-water states rotate it per flow direction
 * — listed as a follow-up. Metal-only; kill switch
 * {@code VOXY_WATER_ANIMATE=0}.
 */
public final class WaterAnimator {
    /** Source-water faces that sample water_still: DOWN (0) and UP (1). */
    public static final int STILL_WATER_FACES = 0b11;

    /** Pixel ints for one frame's full mip chain (16² + 8² + 4² + 2²). */
    private static final int FRAME_CHAIN_PIXELS = computeChainPixels();
    /** Safety cap on the tick→frame LUT for pathological resource packs. */
    private static final int MAX_ANIMATION_TICKS = 1 << 20;

    private record Target(int modelId, int faceMask) {}

    private final ModelStore storage;
    // Render-thread only — registration and ticking both happen inside
    // ModelFactory.tickAndProcessUploads.
    private final List<Target> targets = new ArrayList<>();

    private boolean spriteCaptureAttempted;
    private MemoryBuffer frameData;
    private int frameCount;
    private int[] tickToFrame;
    private int lastUploadedFrame = -1;

    private WaterAnimator(ModelStore storage) {
        this.storage = storage;
    }

    /**
     * Metal/non-GL only; {@code VOXY_WATER_ANIMATE=0} disables (default ON).
     * Also off under {@code VOXY_BAKERY_OFF} — that mode pairs with
     * VOXY_NO_ATLAS hash colours, so the atlas content is never sampled.
     */
    static WaterAnimator createIfEnabled(ModelStore storage) {
        if (RenderBackendFactory.get().getType() == BackendType.OPENGL) {
            return null;
        }
        if ("0".equals(System.getenv("VOXY_WATER_ANIMATE"))) {
            return null;
        }
        if ("1".equals(System.getenv("VOXY_BAKERY_OFF"))) {
            return null;
        }
        return new WaterAnimator(storage);
    }

    /**
     * Register a model's water_still cells. Called on the render thread
     * right before the bake's own atlas upload lands (same tick), so the
     * first animated upload — next tick at the earliest — always overwrites
     * the frozen bake frame, never the other way round.
     */
    void register(int modelId, int faceMask) {
        if (!this.spriteCaptureAttempted) {
            this.captureSprite();
        }
        this.targets.add(new Target(modelId, faceMask));
        // Force a re-upload next tick so the new target gets the current
        // frame immediately (re-writing existing targets is ~3 KB, trivial).
        this.lastUploadedFrame = -1;
    }

    /**
     * Render-thread per-frame tick. Replicates MC's sprite ticker —
     * frameIndex advances every frametime client ticks, generalized to
     * per-frame times via a tick→frame LUT — and uploads only when the
     * frame index CHANGED (every 2 ticks ≈ 10 Hz for vanilla water).
     */
    void tick() {
        if (this.frameData == null || this.targets.isEmpty()) {
            return;
        }
        var level = Minecraft.getInstance().level;
        if (level == null) {
            return;
        }
        int frame = this.tickToFrame[(int) Math.floorMod(level.getGameTime(), this.tickToFrame.length)];
        if (frame == this.lastUploadedFrame) {
            return;
        }
        this.lastUploadedFrame = frame;

        long frameBase = this.frameData.address + (long) frame * FRAME_CHAIN_PIXELS * 4L;
        for (var target : this.targets) {
            int X = (target.modelId & 0xFF) * MODEL_TEXTURE_SIZE * 3;
            int Y = ((target.modelId >> 8) & 0xFF) * MODEL_TEXTURE_SIZE * 2;
            for (int face = 0; face < 6; face++) {
                if ((target.faceMask & (1 << face)) == 0) continue;
                int cx = X + (face >> 1) * MODEL_TEXTURE_SIZE;
                int cy = Y + (face & 1) * MODEL_TEXTURE_SIZE;
                long lvlAddr = frameBase;
                for (int lvl = 0; lvl < LAYERS; lvl++) {
                    int size = MODEL_TEXTURE_SIZE >> lvl;
                    // Cell origins are multiples of 16, so the >>lvl stays
                    // exact for every mip level the atlas allocates.
                    this.storage.textures.uploadSubImage2D(lvl, cx >> lvl, cy >> lvl,
                            size, size, GL_RGBA, GL_UNSIGNED_BYTE, lvlAddr);
                    lvlAddr += (long) size * size * 4L;
                }
            }
        }
    }

    /**
     * One-shot copy of every animation frame (plus its mip chain) out of the
     * CPU-resident sprite into a long-lived native buffer. On failure the
     * animator stays inert (tick() no-ops) — the baked frozen frame remains.
     */
    private void captureSprite() {
        this.spriteCaptureAttempted = true;

        var tex = Minecraft.getInstance().getTextureManager()
                .getTexture(Identifier.fromNamespaceAndPath("minecraft", "textures/atlas/blocks.png"));
        if (!(tex instanceof TextureAtlas atlas)) {
            Logger.warn("[Metal-WATERANIM] block atlas not ready — water animation disabled");
            return;
        }
        var contents = atlas.getSprite(Identifier.fromNamespaceAndPath("minecraft", "block/water_still")).contents();
        var anim = contents.animatedTexture;
        if (anim == null) {
            // Also the missing-sprite case — getSprite falls back to
            // minecraft:missingno, which is never animated.
            Logger.info("[Metal-WATERANIM] sprite " + contents.name()
                    + " is not animated — water animation disabled");
            return;
        }
        NativeImage image = contents.originalImage;
        if (image.format() != NativeImage.Format.RGBA) {
            Logger.warn("[Metal-WATERANIM] water_still image format " + image.format()
                    + " != RGBA — water animation disabled");
            return;
        }
        int frameW = contents.width();
        int frameH = contents.height();
        if (frameW != MODEL_TEXTURE_SIZE || frameH != MODEL_TEXTURE_SIZE) {
            // The bake cells are fixed 16x16; resampling HD packs is out of
            // scope for this pass.
            Logger.warn("[Metal-WATERANIM] water_still is " + frameW + "x" + frameH
                    + " (expected " + MODEL_TEXTURE_SIZE + "x" + MODEL_TEXTURE_SIZE
                    + ") — water animation disabled");
            return;
        }

        List<SpriteContents.FrameInfo> frames = anim.frames;
        int totalTicks = 0;
        int uniformTime = frames.isEmpty() ? 0 : Math.max(1, frames.get(0).time());
        boolean uniform = true;
        for (var frame : frames) {
            int time = Math.max(1, frame.time());
            uniform &= time == uniformTime;
            totalTicks += time;
        }
        if (totalTicks <= 0 || totalTicks > MAX_ANIMATION_TICKS) {
            Logger.warn("[Metal-WATERANIM] degenerate animation (" + frames.size()
                    + " frames, " + totalTicks + " ticks) — water animation disabled");
            return;
        }

        // tick→frame LUT: frameIndex = lut[clientTicks % totalTicks]. For the
        // vanilla uniform case this is exactly (clientTicks / frametime) %
        // frameCount, and it stays exact for per-frame times.
        this.tickToFrame = new int[totalTicks];
        int cursor = 0;
        for (int k = 0; k < frames.size(); k++) {
            int time = Math.max(1, frames.get(k).time());
            for (int t = 0; t < time; t++) {
                this.tickToFrame[cursor++] = k;
            }
        }

        this.frameCount = frames.size();
        this.frameData = new MemoryBuffer((long) this.frameCount * FRAME_CHAIN_PIXELS * 4L);
        long imgPtr = image.getPointer();
        int imgW = image.getWidth();
        int[] level0 = new int[MODEL_TEXTURE_SIZE * MODEL_TEXTURE_SIZE];
        for (int k = 0; k < this.frameCount; k++) {
            int index = frames.get(k).index();
            int fx = (index % anim.frameRowSize) * frameW;
            int fy = (index / anim.frameRowSize) * frameH;
            for (int y = 0; y < frameH; y++) {
                long srcRow = imgPtr + ((long) (fy + y) * imgW + fx) * 4L;
                for (int x = 0; x < frameW; x++) {
                    // NativeImage RGBA bytes == the atlas cell layout (R at
                    // the lowest byte of the little-endian int).
                    level0[y * frameW + x] = MemoryUtil.memGetInt(srcRow + x * 4L);
                }
            }
            fillTransparentWithAverage(level0);
            this.writeFrameChain(level0,
                    this.frameData.address + (long) k * FRAME_CHAIN_PIXELS * 4L);
        }

        Logger.info("[Metal-WATERANIM] registered sprite minecraft:block/water_still: frames="
                + this.frameCount + " frametime=" + (uniform ? Integer.toString(uniformTime) : "variable")
                + (anim.interpolateFrames ? " (interpolation not supported — snapping)" : "")
                + " faces=DOWN,UP");
    }

    /**
     * Parity with MetalViewCapture.dilateOpaqueIntoGaps: any alpha==0 texel
     * becomes the cell's average written RGBA, so the animated cells keep the
     * exact alpha conventions the bake produced (a no-op for vanilla
     * water_still, whose texels are all written).
     */
    private static void fillTransparentWithAverage(int[] cell) {
        long rSum = 0, gSum = 0, bSum = 0, aSum = 0;
        int written = 0;
        for (int p : cell) {
            if ((p & 0xFF000000) == 0) continue;
            rSum += (p      ) & 0xFF;
            gSum += (p >>  8) & 0xFF;
            bSum += (p >> 16) & 0xFF;
            aSum += (p >>> 24);
            written++;
        }
        if (written == 0 || written == cell.length) {
            return;
        }
        int avgA = Math.max(1, (int) (aSum / written));
        int fill = (avgA << 24) | ((int) (bSum / written) << 16)
                | ((int) (gSum / written) << 8) | (int) (rSum / written);
        for (int i = 0; i < cell.length; i++) {
            if ((cell[i] & 0xFF000000) == 0) {
                cell[i] = fill;
            }
        }
    }

    /**
     * Write level 0 plus the 8/4/2 mips at {@code destAddr}, using the same
     * {@link TextureUtils#mipColours} (darkened=false — fluid bakes never set
     * hasDarkenedTextures) and the same 2x2 source addressing MipGen uses, so
     * the animated mips are byte-identical to what a fresh bake would upload.
     */
    private void writeFrameChain(int[] level0, long destAddr) {
        int[] src = level0;
        int srcSize = MODEL_TEXTURE_SIZE;
        for (int i = 0; i < src.length; i++) {
            MemoryUtil.memPutInt(destAddr, src[i]);
            destAddr += 4;
        }
        for (int lvl = 1; lvl < LAYERS; lvl++) {
            int size = MODEL_TEXTURE_SIZE >> lvl;
            int[] dst = new int[size * size];
            for (int y = 0; y < size; y++) {
                for (int x = 0; x < size; x++) {
                    int base = (y * 2) * srcSize + x * 2;
                    dst[y * size + x] = TextureUtils.mipColours(false,
                            src[base], src[base + srcSize], src[base + 1], src[base + srcSize + 1]);
                }
            }
            for (int i = 0; i < dst.length; i++) {
                MemoryUtil.memPutInt(destAddr, dst[i]);
                destAddr += 4;
            }
            src = dst;
            srcSize = size;
        }
    }

    private static int computeChainPixels() {
        int total = 0;
        for (int lvl = 0; lvl < LAYERS; lvl++) {
            int size = MODEL_TEXTURE_SIZE >> lvl;
            total += size * size;
        }
        return total;
    }

    void free() {
        if (this.frameData != null) {
            this.frameData.free();
            this.frameData = null;
        }
        this.targets.clear();
    }
}
