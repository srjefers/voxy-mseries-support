package me.cortex.voxy.client.core.rendering.clouds;

import me.cortex.voxy.common.Logger;
import net.minecraft.util.Mth;
import org.joml.Vector4f;
import org.lwjgl.system.MemoryUtil;

import java.nio.ByteBuffer;

import static org.lwjgl.opengl.GL11C.GL_LINEAR;
import static org.lwjgl.opengl.GL11C.GL_RED;
import static org.lwjgl.opengl.GL11C.GL_REPEAT;
import static org.lwjgl.opengl.GL11C.GL_TEXTURE_2D;
import static org.lwjgl.opengl.GL11C.GL_TEXTURE_BINDING_2D;
import static org.lwjgl.opengl.GL11C.GL_TEXTURE_MAG_FILTER;
import static org.lwjgl.opengl.GL11C.GL_TEXTURE_MIN_FILTER;
import static org.lwjgl.opengl.GL11C.GL_TEXTURE_WRAP_S;
import static org.lwjgl.opengl.GL11C.GL_TEXTURE_WRAP_T;
import static org.lwjgl.opengl.GL11C.GL_UNPACK_ALIGNMENT;
import static org.lwjgl.opengl.GL11C.GL_UNPACK_ROW_LENGTH;
import static org.lwjgl.opengl.GL11C.GL_UNPACK_SKIP_PIXELS;
import static org.lwjgl.opengl.GL11C.GL_UNPACK_SKIP_ROWS;
import static org.lwjgl.opengl.GL11C.GL_UNSIGNED_BYTE;
import static org.lwjgl.opengl.GL11C.glBindTexture;
import static org.lwjgl.opengl.GL11C.glGenTextures;
import static org.lwjgl.opengl.GL11C.glGetInteger;
import static org.lwjgl.opengl.GL11C.glPixelStorei;
import static org.lwjgl.opengl.GL11C.glTexImage2D;
import static org.lwjgl.opengl.GL11C.glTexParameteri;
import static org.lwjgl.opengl.GL11C.glTexSubImage2D;
import static org.lwjgl.opengl.GL12C.GL_TEXTURE_MAX_LEVEL;
import static org.lwjgl.opengl.GL15C.glBindBuffer;
import static org.lwjgl.opengl.GL21C.GL_PIXEL_UNPACK_BUFFER;
import static org.lwjgl.opengl.GL21C.GL_PIXEL_UNPACK_BUFFER_BINDING;
import static org.lwjgl.opengl.GL30C.GL_R8;

/**
 * {@link LodTerrainCoverage} as a GL texture, for shader packs: the pack-text patch
 * ({@code VxLodCloudDistance}) samples it as {@code vxLodCoverage} so the pack's
 * clouds, like the no-pack layer, only form over LOD terrain. Uniform
 * {@code vxLodCoverageInfo}: see {@link #info}.
 *
 * <p>R8, one texel per 32 blocks (16 per 512-block LOD column), linear filtering,
 * REPEAT: toroidal (see {@link LodTerrainCoverage#fillTexels}), so the texture has no
 * origin to keep in step with the uniform. That matters because Iris updates per-frame
 * uniforms before this texture is uploaded at Sodium's SOLID pass: a camera-centred
 * origin would lag by a frame each time the camera crosses a LOD column. Rebuilt on
 * the render thread when the terrain or the camera's LOD
 * column changed, at most 4 times a second (~4 MB at LOD distance 63). Upload only
 * (no readback); every piece of GL state it touches is restored, so MC's cached
 * texture binding stays right.
 */
public final class LodCoverageGlTexture {
    private static final int TEXELS_PER_COLUMN = LodTerrainCoverage.CELLS / 2;
    private static final int COLUMN_BLOCKS = 512;
    private static final int MAX_COLUMNS = 257;
    private static final long UPLOAD_INTERVAL_NS = 250_000_000L;

    private static int textureId;
    private static int side;
    private static ByteBuffer staging;
    private static LodTerrainCoverage source;
    private static int version = Integer.MIN_VALUE;
    private static int centreX;
    private static int centreZ;
    private static long uploadNanos;
    private static boolean valid;
    private static float invSpan;
    private static boolean logged;

    private LodCoverageGlTexture() {}

    /** Render thread, GL context current: bring the texture up to date. Null coverage = no texture. */
    public static void update(LodTerrainCoverage coverage, double cameraX, double cameraZ) {
        if (coverage == null) {
            valid = false;
            return;
        }
        int columns = Math.min(2 * (int) Math.ceil(LodClouds.lodRadiusBlocks() / COLUMN_BLOCKS) + 3, MAX_COLUMNS);
        int texels = columns * TEXELS_PER_COLUMN;
        int cx = Mth.floor(cameraX) >> 9;
        int cz = Mth.floor(cameraZ) >> 9;
        int v = coverage.version();
        long now = System.nanoTime();
        boolean sameSource = coverage == source && texels == side && textureId != 0;
        if (sameSource && v == version && cx == centreX && cz == centreZ) return;
        if (sameSource && now - uploadNanos < UPLOAD_INTERVAL_NS) return;

        int bytes = texels * texels;
        if (staging == null || staging.capacity() != bytes) {
            if (staging != null) MemoryUtil.memFree(staging);
            staging = MemoryUtil.memAlloc(bytes);
        }
        coverage.fillTexels(staging, cx, cz, columns);
        staging.clear();

        int prevTexture = glGetInteger(GL_TEXTURE_BINDING_2D);
        int prevUnpackBuffer = glGetInteger(GL_PIXEL_UNPACK_BUFFER_BINDING);
        int prevAlignment = glGetInteger(GL_UNPACK_ALIGNMENT);
        int prevRowLength = glGetInteger(GL_UNPACK_ROW_LENGTH);
        int prevSkipPixels = glGetInteger(GL_UNPACK_SKIP_PIXELS);
        int prevSkipRows = glGetInteger(GL_UNPACK_SKIP_ROWS);
        try {
            glBindBuffer(GL_PIXEL_UNPACK_BUFFER, 0);
            glPixelStorei(GL_UNPACK_ALIGNMENT, 1);
            glPixelStorei(GL_UNPACK_ROW_LENGTH, 0);
            glPixelStorei(GL_UNPACK_SKIP_PIXELS, 0);
            glPixelStorei(GL_UNPACK_SKIP_ROWS, 0);
            if (textureId == 0) textureId = glGenTextures();
            glBindTexture(GL_TEXTURE_2D, textureId);
            if (side != texels) {
                glTexImage2D(GL_TEXTURE_2D, 0, GL_R8, texels, texels, 0, GL_RED, GL_UNSIGNED_BYTE, staging);
                glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
                glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
                glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_REPEAT);
                glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_REPEAT);
                glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAX_LEVEL, 0);
                side = texels;
            } else {
                glTexSubImage2D(GL_TEXTURE_2D, 0, 0, 0, texels, texels, GL_RED, GL_UNSIGNED_BYTE, staging);
            }
        } finally {
            glBindTexture(GL_TEXTURE_2D, prevTexture);
            glBindBuffer(GL_PIXEL_UNPACK_BUFFER, prevUnpackBuffer);
            glPixelStorei(GL_UNPACK_ALIGNMENT, prevAlignment);
            glPixelStorei(GL_UNPACK_ROW_LENGTH, prevRowLength);
            glPixelStorei(GL_UNPACK_SKIP_PIXELS, prevSkipPixels);
            glPixelStorei(GL_UNPACK_SKIP_ROWS, prevSkipRows);
        }

        source = coverage;
        version = v;
        centreX = cx;
        centreZ = cz;
        uploadNanos = now;
        invSpan = 1.0f / (columns * (float) COLUMN_BLOCKS);
        valid = true;
        if (!logged) {
            logged = true;
            Logger.info("[Metal-CLOUDS] pack clouds only over LOD terrain: coverage texture " + texels + "x" + texels
                    + " (32 blocks per texel), " + coverage.sectionCount() + " top-level sections with terrain so far");
        }
    }

    /** GL name of the texture for the {@code vxLodCoverage} sampler; 0 until the first upload. */
    public static int textureId() {
        return valid ? textureId : 0;
    }

    /**
     * Value of the {@code vxLodCoverageInfo} uniform: (camera x, camera z modulo the
     * texture's span in blocks, 1 / span, 1 when valid). The pack samples
     * {@code (samplePos.xz - cameraPos.xz + info.xy) * info.z}: the camera-relative
     * offset, because Iris shifts {@code cameraPosition} by multiples of 30 000 blocks
     * far from the origin (CameraUniforms.CameraPositionTracker), plus the real camera
     * position, kept small (modulo) for float precision.
     */
    public static Vector4f info() {
        if (!valid) return new Vector4f();
        var camera = net.minecraft.client.Minecraft.getInstance().gameRenderer.getMainCamera().position();
        double span = 1.0 / invSpan;
        float x = (float) (camera.x - span * Math.floor(camera.x / span));
        float z = (float) (camera.z - span * Math.floor(camera.z / span));
        return new Vector4f(x, z, invSpan, 1.0f);
    }
}
