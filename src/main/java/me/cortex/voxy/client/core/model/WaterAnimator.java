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

/** Render-thread water atlas animation, following Minecraft's actual sprite state.
 * Captured sprite frames retain the bakery's fluid alpha multiplier and transparent texels.
 * Source-water UP/DOWN faces are animated; flowing-water UV adaptation remains separate.
 */
public final class WaterAnimator {
    /** Source-water faces that sample water_still: DOWN (0) and UP (1). */
    public static final int STILL_WATER_FACES = 0b11;

    /** Pixel ints for one frame's full mip chain (16² + 8² + 4² + 2²). */
    private static final int FRAME_CHAIN_PIXELS = computeChainPixels();
    /** Safety cap on captured frames for pathological resource packs. */
    private static final int MAX_ANIMATION_FRAMES = 1 << 20;

    private record Target(int modelId, int faceMask, float opacity) {}

    private final ModelStore storage;
    // Render-thread only — registration and ticking both happen inside
    // ModelFactory.tickAndProcessUploads.
    private final List<Target> targets = new ArrayList<>();

    private boolean spriteCaptureAttempted;
    private MemoryBuffer frameData;
    private int frameCount;
    private SpriteContents.AnimationState animationState;
    private boolean interpolate;
    private MemoryBuffer interpolated;
    private int lastUploadedSubFrame = -1;
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
    void register(int modelId, int faceMask, MemoryBuffer baked) {
        if (!this.spriteCaptureAttempted) {
            this.captureSprite();
        }
        float opacity = 1;
        if (this.frameData != null) {
            int face = Integer.numberOfTrailingZeros(faceMask);
            int x = (face >> 1) * MODEL_TEXTURE_SIZE, y = (face & 1) * MODEL_TEXTURE_SIZE;
            long bakedAlpha = 0, sourceAlpha = 0;
            int bakedCount = 0, sourceCount = 0;
            for (int i = 0; i < MODEL_TEXTURE_SIZE * MODEL_TEXTURE_SIZE; i++) {
                long offset = ((long)(y + i / MODEL_TEXTURE_SIZE) * MODEL_TEXTURE_SIZE * 3 + x + i % MODEL_TEXTURE_SIZE) * 4;
                int a = MemoryUtil.memGetInt(baked.address + offset) >>> 24;
                int b = MemoryUtil.memGetInt(this.frameData.address + i * 4L) >>> 24;
                if (a > 0) { bakedAlpha += a; bakedCount++; }
                if (b > 0) { sourceAlpha += b; sourceCount++; }
            }
            if (bakedCount > 0 && sourceCount > 0) {
                opacity = Math.min(1, (float)bakedAlpha * sourceCount / (bakedCount * sourceAlpha));
            }
        }
        this.targets.add(new Target(modelId, faceMask, opacity));
        // Force a re-upload next tick so the new target gets the current
        // frame immediately (re-writing existing targets is ~3 KB, trivial).
        this.lastUploadedFrame = -1;
    }

    /** Upload only when Minecraft's sprite frame or interpolation subframe advances. */
    void tick() {
        if (this.frameData == null || this.targets.isEmpty()) {
            return;
        }
        if (this.animationState == null) return;
        var position = WaterAnimationFrames.position(this.animationState);
        int frame = position.frame(), subFrame = this.interpolate ? this.animationState.subFrame : 0;
        if (frame == this.lastUploadedFrame && subFrame == this.lastUploadedSubFrame) return;
        this.lastUploadedFrame = frame;
        this.lastUploadedSubFrame = subFrame;
        long current = this.frameData.address + (long)frame * FRAME_CHAIN_PIXELS * 4;
        long next = this.frameData.address + (long)position.next() * FRAME_CHAIN_PIXELS * 4;
        for (var target : this.targets) {
            for (int i = 0; i < FRAME_CHAIN_PIXELS; i++) {
                int pixel = WaterAnimationFrames.blend(MemoryUtil.memGetInt(current + i * 4L), MemoryUtil.memGetInt(next + i * 4L),
                        this.interpolate ? position.fraction() : 0, target.opacity);
                MemoryUtil.memPutInt(this.interpolated.address + i * 4L, pixel);
            }
            long frameBase = this.interpolated.address;
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
        if (frames.isEmpty() || frames.size() > MAX_ANIMATION_FRAMES) {
            Logger.warn("[Metal-WATERANIM] invalid sprite frame count — animation disabled"); return;
        }
        for (var state : atlas.animatedTexturesStates) {
            if (state.animationInfo == anim) { this.animationState = state; break; }
        }
        if(this.animationState==null) {
            Logger.warn("[Metal-WATERANIM] no live Minecraft sprite animation state — retaining the baked water"); return;
        }
        this.interpolate = anim.interpolateFrames;
        this.interpolated = new MemoryBuffer(FRAME_CHAIN_PIXELS * 4L);
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
            dilateTransparentRgb(level0);
            this.writeFrameChain(level0,
                    this.frameData.address + (long) k * FRAME_CHAIN_PIXELS * 4L);
        }

        Logger.info("[Metal-WATERANIM] registered sprite minecraft:block/water_still: frames="
                +this.frameCount+" clock=Minecraft animation state interpolation="+this.interpolate+" faces=DOWN,UP; baked opacity retained");
    }

    /** Transparent sprite pixels receive nearest-edge RGB while keeping alpha zero. */
    private static void dilateTransparentRgb(int[] cell) {
        MipGen.dilateRgb(cell);
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
        if (this.interpolated != null) { this.interpolated.free(); this.interpolated = null; }
        this.animationState = null;
        this.targets.clear();
    }
}
