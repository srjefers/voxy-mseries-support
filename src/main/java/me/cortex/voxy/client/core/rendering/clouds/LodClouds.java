package me.cortex.voxy.client.core.rendering.clouds;

import me.cortex.voxy.client.config.VoxyConfig;
import me.cortex.voxy.client.core.IGetVoxyRenderSystem;
import me.cortex.voxy.client.core.gpu.BackendType;
import me.cortex.voxy.client.core.gpu.RenderBackendFactory;
import me.cortex.voxy.client.core.interop.IOSurfaceBridgeCompositor;
import me.cortex.voxy.client.core.util.IrisUtil;
import me.cortex.voxy.common.Logger;
import net.minecraft.client.CloudStatus;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.CloudRenderer;
import net.minecraft.client.renderer.fog.FogData;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;

/**
 * Clouds over the LOD terrain (Metal path, no shader pack).
 *
 * <p>Vanilla draws its clouds as a mesh of 12x4x12 cells around the camera, out to
 * the "Cloud Distance" option (2-128 chunks), and fades them linearly from the
 * camera to that distance ({@code rendertype_clouds.fsh}:
 * {@code alpha *= 1 - dist / FogCloudsEnd}, {@code FogCloudsEnd = cloudRange * 16}).
 * The mesh cannot reach much further: {@code CloudRenderer.encodeFace} stores a
 * cell offset in 9 signed bits, so ±255 cells (3060 blocks) at most. The LOD
 * terrain reaches {@code sectionRenderDistance * 512} blocks (8 km by default),
 * so above the clouds the LODs had no clouds at all, and the near ones faded out
 * with distance.
 *
 * <p>The fix keeps the vanilla mesh near the camera and continues it with
 * {@link LodCloudLayer}: a cloud slab drawn into the LOD frame on Metal, with the
 * same cells, drift, height, colour and face shades, so the hand-off is seamless.
 * While the layer is on, three things change, all on the Metal backend only:
 * <ul>
 *   <li>{@code FogCloudsEnd} is pushed out so the vanilla mesh no longer fades
 *       ({@link #onFogSetup});</li>
 *   <li>the vanilla mesh radius grows to cover the vanilla terrain, because that
 *       terrain is drawn after (over) the LOD frame, so the layer cannot show
 *       clouds in front of it ({@link #meshRangeChunks});</li>
 *   <li>the layer draws every cloud cell outside that radius, fading out over the
 *       last part of the LOD distance.</li>
 * </ul>
 *
 * <p>All calls happen on the render thread. Order within a frame:
 * {@code FogRenderer.setupFog} ({@link #onFogSetup}, decides the frame), then
 * {@code LevelRenderer.addCloudsPass} while the frame graph is built
 * ({@link #captureFrame}), then the frame graph runs: Sodium's SOLID pass runs the
 * Metal LOD frame ({@link #consumeFrame}), and the clouds pass draws the vanilla
 * mesh ({@link #meshRangeChunks}).
 *
 * <p>{@code VOXY_LOD_CLOUDS=0} turns all of it off (vanilla clouds, untouched).
 * {@code VOXY_LOD_CLOUDS_DEBUG=1} paints the layer's clouds magenta, to see where
 * the vanilla mesh hands over.
 */
public final class LodClouds {
    /** Kill switch: {@code VOXY_LOD_CLOUDS=0} leaves the vanilla clouds exactly as they were. */
    public static final boolean ENABLED = !"0".equals(System.getenv("VOXY_LOD_CLOUDS"));
    /** {@code VOXY_LOD_CLOUDS_DEBUG=1}: the layer draws its clouds magenta. */
    public static final boolean DEBUG_TINT = "1".equals(System.getenv("VOXY_LOD_CLOUDS_DEBUG"));

    /** CloudRenderer constants: cell size in blocks, height of a fancy cell, the drift. */
    static final float CELL_SIZE = 12.0f;
    static final float CELL_HEIGHT = 4.0f;
    private static final long TICKS_PER_CELL = 400L;
    private static final float BLOCKS_PER_TICK = 0.030000001f;
    private static final double Z_OFFSET = 3.9600000381469727;

    /** Vanilla's own maximum Cloud Distance; also keeps the mesh inside its 9-bit cell offsets. */
    private static final int MAX_MESH_CHUNKS = 128;
    /** {@code FogCloudsEnd} while the layer is on: the vanilla mesh keeps its full alpha. */
    private static final float NO_FADE_CLOUD_END = 1.0e7f;
    /**
     * The layer starts fading at this fraction of the LOD distance and is gone at the LOD
     * edge. Also used by the shader-pack patch ({@code VxLodCloudDistance}).
     */
    public static final float FADE_START_FRACTION = 0.8f;
    /** The LOD projection's far plane is 48 000 blocks (VoxyRenderSystem.computeProjectionMat). */
    private static final float MAX_FADE_END = 40_000.0f;

    static final int MODE_NONE = 0;
    /** FAST clouds: one flat layer of cells at the cloud height, top colour. */
    static final int MODE_FLAT = 1;
    /** FANCY, camera above the cells: top faces first. */
    static final int MODE_ABOVE = 2;
    /** FANCY, camera below the cells: bottom faces first. */
    static final int MODE_BELOW = 3;

    // ---- cloud texture, from CloudRenderer.apply (resource reload) ----
    /** Vanilla's own TextureData (CloudRenderer keeps it too); bits built on first use. */
    private static CloudRenderer.TextureData texture;
    private static int texGeneration;
    private static int[] occupancy;
    private static int occupancyGeneration = -1;
    private static int texWidth;
    private static int texHeight;
    private static int texFilledCells;

    // ---- this frame's clouds pass arguments, from LevelRenderer.addCloudsPass ----
    private static boolean snapValid;
    private static CloudStatus snapStatus;
    private static double snapCamX;
    private static double snapCamY;
    private static double snapCamZ;
    private static long snapGameTime;
    private static float snapPartialTick;
    private static int snapColor;
    private static float snapHeight;
    /** Last cloud height and status seen; the fog decision runs before this frame's pass is added. */
    private static boolean seenPass;
    private static float lastHeight;
    private static CloudStatus lastStatus;

    // ---- this frame's decision, from FogRenderer.setupFog ----
    private static boolean frameActive;
    private static int frameMeshChunks;
    private static String lastReason = "";
    private static boolean broken;
    private static boolean loggedFirstDraw;
    private static int missedSnapshots;

    private LodClouds() {}

    /** Frame parameters for {@link LodCloudLayer}; one instance, rewritten each frame. */
    static final class Frame {
        int mode;
        /** Camera-relative heights of the face the rays enter through and the face they leave by. */
        float entryY;
        float exitY;
        /** Shade of the entry face: vanilla's top (1.0) or bottom (0.7) face colour. */
        float entryShade;
        /** Camera position inside its cloud cell, in blocks ([0, 12)). */
        float offX;
        float offZ;
        /** The camera's cloud cell, already wrapped into the texture. */
        int cellX;
        int cellZ;
        int texWidth;
        int texHeight;
        /** Radius of the vanilla mesh in cells; the layer skips every cell inside it. */
        int meshRadiusCells;
        float fadeStart;
        float fadeEnd;
        float red;
        float green;
        float blue;
        float alpha;
    }

    private static final Frame FRAME = new Frame();

    /**
     * Voxy renders this frame on Metal. Checks the render system first:
     * {@link RenderBackendFactory#get} creates the backend when there is none yet,
     * and these hooks run every frame even with Voxy off.
     */
    private static boolean voxyOnMetal() {
        return IGetVoxyRenderSystem.getNullable() != null
                && RenderBackendFactory.get().getType() != BackendType.OPENGL;
    }

    // ------------------------------------------------------------------
    // Hooks
    // ------------------------------------------------------------------

    /**
     * {@code CloudRenderer.apply}: the cloud texture was (re)loaded ({@code null}: no
     * texture). Only keeps the reference: this runs at startup, before any backend
     * exists, and on GL too; the bits are built by {@link #occupancy} on first use.
     */
    public static void onTextureReloaded(CloudRenderer.TextureData data) {
        boolean valid = data != null && data.cells() != null && data.width() > 0 && data.height() > 0;
        texture = valid ? data : null;
        texGeneration++;
    }

    /** One bit per texture cell, row-major ({@code x + z * width}): 1 = cloud. Null without a texture. */
    static int[] occupancy() {
        if (occupancyGeneration == texGeneration) return occupancy;
        occupancyGeneration = texGeneration;
        CloudRenderer.TextureData data = texture;
        if (data == null) {
            occupancy = null;
            return null;
        }
        long[] cells = data.cells();
        int w = data.width();
        int h = data.height();
        int count = w * h;
        int[] bits = new int[(count + 31) >>> 5];
        int filled = 0;
        for (int i = 0; i < count && i < cells.length; i++) {
            // CloudRenderer.tryBuildCell skips exactly the cells stored as 0 (EMPTY_CELL).
            if (cells[i] != 0L) {
                bits[i >>> 5] |= 1 << (i & 31);
                filled++;
            }
        }
        occupancy = bits;
        texWidth = w;
        texHeight = h;
        texFilledCells = filled;
        return bits;
    }

    /** {@code LevelRenderer.addCloudsPass} HEAD: the arguments vanilla will draw this frame's clouds with. */
    public static void captureFrame(CloudStatus status, Vec3 cameraPos, long gameTime, float partialTick,
                                    int cloudColor, float cloudHeight) {
        if (!ENABLED || !voxyOnMetal()) return;
        snapValid = true;
        snapStatus = status;
        snapCamX = cameraPos.x;
        snapCamY = cameraPos.y;
        snapCamZ = cameraPos.z;
        snapGameTime = gameTime;
        snapPartialTick = partialTick;
        snapColor = cloudColor;
        snapHeight = cloudHeight;
        seenPass = true;
        lastHeight = cloudHeight;
        lastStatus = status;
    }

    /**
     * {@code FogRenderer.setupFog}, once per frame before the level renders: decide
     * whether the layer runs this frame and, if so, stop the vanilla mesh from fading.
     * Leaves {@code data} untouched whenever the layer is off (always, on GL).
     */
    public static void onFogSetup(FogData data) {
        frameActive = false;
        if (!ENABLED || !voxyOnMetal()) {
            lastReason = ""; // log the state again when Voxy comes back (world rejoin)
            return;
        }
        String reason = decide(data);
        if (reason == null) {
            frameActive = true;
            data.cloudEnd = NO_FADE_CLOUD_END;
        }
        String shown = reason == null ? "on" : reason;
        if (!shown.equals(lastReason)) {
            lastReason = shown;
            Logger.info("[Metal-CLOUDS] LOD cloud layer " + (reason == null
                    ? "on (mesh " + frameMeshChunks + " chunks, LOD radius " + (int) lodRadiusBlocks() + " blocks)"
                    : "off: " + reason));
        }
    }

    /** Null when the layer should run this frame, else why not. */
    private static String decide(FogData data) {
        if (broken) return "disabled after an error (see above)";
        if (texture == null) return "no cloud texture";
        if (!VoxyConfig.CONFIG.isRenderingEnabled()) return "Voxy is not rendering";
        // The layer is drawn into the LOD colour bridge, which only the blit composite
        // shows as is. With a shader pack the bridge is pack input (the pack draws its own
        // clouds), so packs keep vanilla behaviour.
        if (!IOSurfaceBridgeCompositor.USE_BLIT || IrisUtil.vxContractActive() || IrisUtil.irisGbufferInjectMode()) {
            return "shader pack or non-blit composite";
        }
        var mc = Minecraft.getInstance();
        CloudStatus status = mc.options.getCloudsType();
        if (status == CloudStatus.OFF) return "clouds are off";
        int optionChunks = mc.options.cloudRange().get();
        // Vanilla sets cloudEnd = min(Cloud Distance, CLOUD_FOG_END_DISTANCE) in the open air
        // and something shorter under water, in lava, with blindness... Anything shorter
        // than the option is the game hiding clouds on purpose: keep vanilla.
        if (data.cloudEnd < optionChunks * 16.0f - 0.5f) return "fog shortens the clouds (water, weather, effect)";
        if (!seenPass || lastStatus == null) return "waiting for the first clouds pass";
        double camY = mc.gameRenderer.getMainCamera().position().y;
        if (mode(status, lastHeight, camY) == MODE_NONE) return "camera inside the cloud layer";
        int meshChunks = meshChunksFor(optionChunks, mc.options.getEffectiveRenderDistance());
        if (lodRadiusBlocks() <= meshChunks * 16.0f + 256.0f) return "LOD distance does not reach past the vanilla clouds";
        frameMeshChunks = meshChunks;
        return null;
    }

    /**
     * {@code CloudRenderer.render}: the Cloud Distance (chunks) the vanilla mesh is built
     * with. Unchanged unless the layer runs this frame.
     */
    public static int meshRangeChunks(int optionChunks) {
        return frameActive ? frameMeshChunks : optionChunks;
    }

    /**
     * The vanilla terrain is drawn over the LOD frame, so a cloud in front of vanilla
     * terrain must come from the vanilla mesh. Vanilla terrain reaches the render
     * distance in a square: its farthest corner is RD * sqrt(2) away horizontally.
     */
    static int meshChunksFor(int optionChunks, int renderDistanceChunks) {
        double coverBlocks = renderDistanceChunks * 16.0 * Math.sqrt(2.0) + 48.0;
        int coverChunks = (int) Math.ceil(coverBlocks / 16.0);
        return Math.min(Math.max(optionChunks, coverChunks), MAX_MESH_CHUNKS);
    }

    static float lodRadiusBlocks() {
        // RenderDistanceTracker rings top-level nodes of 512 blocks.
        return VoxyConfig.CONFIG.sectionRenderDistance * 512.0f;
    }

    /** CloudRenderer.render's RelativeCameraPos, mapped to the layer's modes. */
    static int mode(CloudStatus status, float cloudHeight, double cameraY) {
        float rel = (float) (cloudHeight - cameraY);
        if (status == CloudStatus.FAST) {
            // A flat layer has no inside; only skip the frame the camera sits on it.
            return Math.abs(rel) < 1.0e-3f ? MODE_NONE : MODE_FLAT;
        }
        if (rel + CELL_HEIGHT < 0.0f) return MODE_ABOVE;
        if (rel > 0.0f) return MODE_BELOW;
        return MODE_NONE;
    }

    /**
     * Metal LOD frame: this frame's layer parameters, or null when the layer does not
     * run. Consumes the clouds-pass snapshot, so a frame whose clouds pass was not
     * added (no clouds in this dimension, clouds off) draws nothing.
     */
    static Frame consumeFrame() {
        boolean valid = snapValid;
        snapValid = false;
        if (!frameActive) return null;
        if (!valid) {
            // The clouds pass is added while the frame graph is built, before the graph
            // (and Sodium's SOLID pass, and this frame) runs. Missing it means no clouds
            // this frame — or that ordering assumption broke.
            if (missedSnapshots++ == 0) {
                Logger.info("[Metal-CLOUDS] no clouds pass before the LOD frame; layer skipped for that frame");
            }
            return null;
        }
        int mode = mode(snapStatus, snapHeight, snapCamY);
        if (mode == MODE_NONE || occupancy() == null) return null;
        if (((snapColor >>> 24) & 0xFF) == 0) return null;

        Frame f = FRAME;
        f.mode = mode;
        float rel = (float) (snapHeight - snapCamY);
        switch (mode) {
            case MODE_ABOVE -> { f.entryY = rel + CELL_HEIGHT; f.exitY = rel; f.entryShade = 1.0f; }
            case MODE_BELOW -> { f.entryY = rel; f.exitY = rel + CELL_HEIGHT; f.entryShade = 0.7f; }
            default -> { f.entryY = rel; f.exitY = rel; f.entryShade = 1.0f; }
        }

        // Exactly CloudRenderer.render's cell and offset arithmetic (same float/double steps).
        int w = texWidth;
        int h = texHeight;
        float drift = (float) (snapGameTime % ((long) w * TICKS_PER_CELL)) + snapPartialTick;
        double x = snapCamX + (double) (drift * BLOCKS_PER_TICK);
        double z = snapCamZ + Z_OFFSET;
        double spanX = w * 12.0;
        double spanZ = h * 12.0;
        x -= Mth.floor(x / spanX) * spanX;
        z -= Mth.floor(z / spanZ) * spanZ;
        int cellX = Mth.floor(x / 12.0);
        int cellZ = Mth.floor(z / 12.0);
        f.offX = (float) (x - (double) ((float) cellX * CELL_SIZE));
        f.offZ = (float) (z - (double) ((float) cellZ * CELL_SIZE));
        f.cellX = cellX;
        f.cellZ = cellZ;
        f.texWidth = w;
        f.texHeight = h;
        f.meshRadiusCells = Mth.ceil(frameMeshChunks * 16 / CELL_SIZE);

        float fadeEnd = Math.min(lodRadiusBlocks(), MAX_FADE_END);
        float meshEdge = (f.meshRadiusCells + 2) * CELL_SIZE;
        f.fadeEnd = fadeEnd;
        f.fadeStart = Math.min(Math.max(fadeEnd * FADE_START_FRACTION, meshEdge), fadeEnd - 1.0f);

        // ARGB.vector4fFromARGB32, as CloudRenderer uploads it.
        f.alpha = ((snapColor >>> 24) & 0xFF) / 255.0f;
        f.red = ((snapColor >>> 16) & 0xFF) / 255.0f;
        f.green = ((snapColor >>> 8) & 0xFF) / 255.0f;
        f.blue = (snapColor & 0xFF) / 255.0f;
        return f;
    }

    static int textureGeneration() {
        return texGeneration;
    }

    /** One log line the first time the layer draws: what it drew with. */
    static void onDrawn(Frame f) {
        if (loggedFirstDraw) return;
        loggedFirstDraw = true;
        String mode = switch (f.mode) {
            case MODE_ABOVE -> "fancy, camera above";
            case MODE_BELOW -> "fancy, camera below";
            default -> "fast (flat)";
        };
        int total = f.texWidth * f.texHeight;
        Logger.info(String.format(
                "[Metal-CLOUDS] layer drawn: %s, vanilla mesh radius %d cells, fade %.0f..%.0f blocks, texture %dx%d (%d%% cells filled)%s",
                mode, f.meshRadiusCells, f.fadeStart, f.fadeEnd, f.texWidth, f.texHeight,
                total > 0 ? texFilledCells * 100 / total : 0,
                DEBUG_TINT ? ", DEBUG tint on" : ""));
    }

    /** The layer failed to build its GPU objects: stay off for the rest of the session (vanilla clouds). */
    static void markBroken(Throwable t) {
        broken = true;
        frameActive = false;
        Logger.error("[Metal-CLOUDS] LOD cloud layer disabled, vanilla clouds from now on:", t);
    }
}
