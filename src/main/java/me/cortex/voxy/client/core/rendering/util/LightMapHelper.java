package me.cortex.voxy.client.core.rendering.util;

import static org.lwjgl.opengl.GL11C.GL_RGBA;
import static org.lwjgl.opengl.GL11C.GL_RGBA8;
import static org.lwjgl.opengl.GL11C.GL_TEXTURE_2D;
import static org.lwjgl.opengl.GL11C.GL_UNSIGNED_BYTE;
import static org.lwjgl.opengl.GL13C.GL_TEXTURE0;
import static org.lwjgl.opengl.GL13C.glActiveTexture;
import static org.lwjgl.opengl.GL11C.glBindTexture;
import static org.lwjgl.opengl.GL11C.glGetInteger;
import static org.lwjgl.opengl.GL11C.GL_TEXTURE_BINDING_2D;
import static org.lwjgl.opengl.GL13C.GL_ACTIVE_TEXTURE;
import static org.lwjgl.opengl.GL33.glBindSampler;
import static org.lwjgl.opengl.GL11.GL_PACK_ALIGNMENT;
import static org.lwjgl.opengl.GL11.GL_PACK_ROW_LENGTH;
import static org.lwjgl.opengl.GL11.GL_PACK_SKIP_PIXELS;
import static org.lwjgl.opengl.GL11.GL_PACK_SKIP_ROWS;
import static org.lwjgl.opengl.GL11.GL_TEXTURE_HEIGHT;
import static org.lwjgl.opengl.GL11.GL_TEXTURE_WIDTH;
import static org.lwjgl.opengl.GL11.glGetTexLevelParameteri;
import static org.lwjgl.opengl.GL11.glPixelStorei;
import static org.lwjgl.opengl.GL12.GL_PACK_IMAGE_HEIGHT;
import static org.lwjgl.opengl.GL12.GL_PACK_SKIP_IMAGES;
import static me.cortex.voxy.client.core.gl.GLCompat.bindTextureUnit;

import me.cortex.voxy.client.core.gpu.BackendType;
import me.cortex.voxy.client.core.gpu.IGpuSampler;
import me.cortex.voxy.client.core.gpu.IGpuTexture;
import me.cortex.voxy.client.core.gpu.RenderBackend;
import me.cortex.voxy.client.core.gpu.RenderBackendFactory;
import me.cortex.voxy.client.core.gpu.RenderEncoder;
import me.cortex.voxy.client.core.gpu.SamplerDesc;
import me.cortex.voxy.client.core.metal.MetalTexture;
import net.minecraft.client.Minecraft;
import org.lwjgl.system.MemoryUtil;

/**
 * Routes the MC lightmap to Voxy's terrain shaders.
 *
 * GL path: binds MC's GlTexture directly to the configured texture unit —
 * MC keeps its own lightmap upload current so we just point at it.
 *
 * Metal path (M13 chunk 2): MC's GlTexture handle is unusable from Metal,
 * so Voxy keeps a Shared-storage mirror texture and copies MC's 16×16
 * RGBA8 lightmap into it once per frame via {@code glGetTexImage} →
 * {@link IGpuTexture#uploadSubImage2D}. The mirror is then bound on the
 * active {@link RenderEncoder} alongside a LINEAR / CLAMP_TO_EDGE sampler
 * that matches MC's lightmap sampling. Lazy-allocated; gated by frame id
 * so the three terrain passes (opaque + temporal + translucent) share a
 * single readback.
 */
public class LightMapHelper {

    private static final int LIGHTMAP_WIDTH = 16;
    private static final int LIGHTMAP_HEIGHT = 16;
    private static final int LIGHTMAP_BYTES = LIGHTMAP_WIDTH * LIGHTMAP_HEIGHT * 4;

    private static IGpuTexture metalLightmap;
    private static IGpuSampler metalSampler;
    private static long stagingAddr;
    private static int lastSyncedFrame = -1;
    private static boolean lightmapSizeWarned = false;

    public static void bind(int lightingIndex) {
        glBindSampler(lightingIndex, 0);
        bindTextureUnit(lightingIndex, ((com.mojang.blaze3d.opengl.GlTexture)(Minecraft.getInstance().gameRenderer.lightTexture().getTextureView().texture())).glId());
    }

    /**
     * Metal-only path. Syncs MC's GL lightmap into the Voxy-side Shared
     * texture (once per {@code frameId}) and binds it + a matching sampler
     * at {@code slot} on the encoder.
     *
     * Safe to call from any of the three terrain passes in a frame — the
     * frame-id gate keeps the readback + upload to one round per frame
     * even though {@code renderTerrainMetal} dispatches three times.
     */
    public static void bindMetal(RenderEncoder encoder, int slot, int frameId) {
        ensureMetalResources();
        syncFromMc(frameId);
        encoder.setTexture(slot, metalLightmap);
        encoder.setSampler(slot, metalSampler);
    }

    private static void ensureMetalResources() {
        if (metalLightmap == null) {
            RenderBackend backend = RenderBackendFactory.get();
            if (backend.getType() != BackendType.METAL) {
                throw new IllegalStateException(
                        "LightMapHelper.bindMetal called on non-Metal backend: " + backend.getType());
            }
            MetalTexture tex = (MetalTexture) backend.createTexture(GL_TEXTURE_2D);
            tex.storeUploadable(GL_RGBA8, 1, LIGHTMAP_WIDTH, LIGHTMAP_HEIGHT);
            tex.name("Voxy.MCLightmapMirror");
            metalLightmap = tex;
        }
        if (metalSampler == null) {
            metalSampler = RenderBackendFactory.get().createSampler(SamplerDesc.builder()
                    .filter(SamplerDesc.Filter.LINEAR, SamplerDesc.Filter.LINEAR)
                    .mipFilter(SamplerDesc.MipFilter.NOT_MIPMAPPED)
                    .wrap(SamplerDesc.Wrap.CLAMP_TO_EDGE, SamplerDesc.Wrap.CLAMP_TO_EDGE)
                    .label("Voxy.MCLightmapSampler")
                    .build());
        }
        if (stagingAddr == 0L) {
            stagingAddr = MemoryUtil.nmemAllocChecked(LIGHTMAP_BYTES);
        }
    }

    /**
     * Read MC's 16×16 GL lightmap into the staging buffer and push it into
     * the Metal mirror. Apple's GL caps at 4.1 so we use the bind-then-read
     * legacy path rather than DSA's {@code glGetTextureImage}. The
     * 1 KB-per-call cost is negligible; the frame-id gate makes this happen
     * at most once per frame even with three terrain passes.
     */
    private static void syncFromMc(int frameId) {
        if (frameId == lastSyncedFrame) return;
        lastSyncedFrame = frameId;
        // VOXY_FRAME_TIMING=1 GL-side span (the one sanctioned per-frame readback).
        me.cortex.voxy.client.core.util.VxTiming.begin(me.cortex.voxy.client.core.util.VxTiming.LIGHTMAP);
        try {
            syncFromMc0();
        } finally {
            me.cortex.voxy.client.core.util.VxTiming.end(me.cortex.voxy.client.core.util.VxTiming.LIGHTMAP);
        }
    }

    private static void syncFromMc0() {
        var lightTex = Minecraft.getInstance().gameRenderer.lightTexture().getTextureView().texture();
        int glId = ((com.mojang.blaze3d.opengl.GlTexture) lightTex).glId();

        int prevActive = glGetInteger(GL_ACTIVE_TEXTURE);
        glActiveTexture(GL_TEXTURE0);
        int prevBinding = glGetInteger(GL_TEXTURE_BINDING_2D);

        // SIGBUS fix (2026-05-26): glGetTexImage honours the GL_PACK_* pixel-store
        // state, and MC/Sodium — notably MC's screenshot glReadPixels — leave
        // GL_PACK_ROW_LENGTH / skips set to non-default values. With a polluted
        // ROW_LENGTH the readback strides past the end of the 1 KB staging buffer
        // and _platform_memmove SIGBUSes inside glGetTexImage (the crash seen in
        // renderTerrainMetal; reproduced by "taking a screenshot crashes the
        // game"). Force the pack params to defaults so the readback is exactly
        // W*H*4 bytes, then restore them so MC's state is undisturbed. Mirrors
        // the existing GL_UNPACK_* reset in HierarchicalOcclusionTraverser.
        int prevRowLen  = glGetInteger(GL_PACK_ROW_LENGTH);
        int prevSkipPix = glGetInteger(GL_PACK_SKIP_PIXELS);
        int prevSkipRow = glGetInteger(GL_PACK_SKIP_ROWS);
        int prevSkipImg = glGetInteger(GL_PACK_SKIP_IMAGES);
        int prevImgH    = glGetInteger(GL_PACK_IMAGE_HEIGHT);
        int prevAlign   = glGetInteger(GL_PACK_ALIGNMENT);
        try {
            glBindTexture(GL_TEXTURE_2D, glId);

            glPixelStorei(GL_PACK_ROW_LENGTH, 0);
            glPixelStorei(GL_PACK_SKIP_PIXELS, 0);
            glPixelStorei(GL_PACK_SKIP_ROWS, 0);
            glPixelStorei(GL_PACK_SKIP_IMAGES, 0);
            glPixelStorei(GL_PACK_IMAGE_HEIGHT, 0);
            glPixelStorei(GL_PACK_ALIGNMENT, 4);

            // Defensive backstop: never let glGetTexImage write past the 1 KB
            // staging buffer. If MC's lightmap is ever not 16×16 (resized, or a
            // stale/wrong GL handle), skip the readback instead of overflowing —
            // keep the last good mirror.
            int w = glGetTexLevelParameteri(GL_TEXTURE_2D, 0, GL_TEXTURE_WIDTH);
            int h = glGetTexLevelParameteri(GL_TEXTURE_2D, 0, GL_TEXTURE_HEIGHT);
            if (w == LIGHTMAP_WIDTH && h == LIGHTMAP_HEIGHT) {
                org.lwjgl.opengl.GL11C.nglGetTexImage(
                        GL_TEXTURE_2D, 0, GL_RGBA, GL_UNSIGNED_BYTE, stagingAddr);
                metalLightmap.uploadSubImage2D(0, 0, 0,
                        LIGHTMAP_WIDTH, LIGHTMAP_HEIGHT,
                        GL_RGBA, GL_UNSIGNED_BYTE, stagingAddr);
            } else if (!lightmapSizeWarned) {
                lightmapSizeWarned = true;
                me.cortex.voxy.common.Logger.warn(
                        "[Metal] MC lightmap is " + w + "x" + h + ", expected "
                        + LIGHTMAP_WIDTH + "x" + LIGHTMAP_HEIGHT
                        + " — skipping lightmap mirror readback to avoid buffer overflow");
            }
        } finally {
            glPixelStorei(GL_PACK_ROW_LENGTH, prevRowLen);
            glPixelStorei(GL_PACK_SKIP_PIXELS, prevSkipPix);
            glPixelStorei(GL_PACK_SKIP_ROWS, prevSkipRow);
            glPixelStorei(GL_PACK_SKIP_IMAGES, prevSkipImg);
            glPixelStorei(GL_PACK_IMAGE_HEIGHT, prevImgH);
            glPixelStorei(GL_PACK_ALIGNMENT, prevAlign);
            glBindTexture(GL_TEXTURE_2D, prevBinding);
            glActiveTexture(prevActive);
        }
    }
}
