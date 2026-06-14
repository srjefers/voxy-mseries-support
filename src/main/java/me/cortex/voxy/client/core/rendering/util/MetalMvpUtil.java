package me.cortex.voxy.client.core.rendering.util;

import me.cortex.voxy.common.Logger;
import org.joml.Matrix4f;

/**
 * Shared GL→Metal clip-space depth remap for raster passes that consume a
 * GL-convention MVP (built from {@code setPerspective} without
 * {@code zZeroToOne} in VoxyRenderSystem.makeProjectionMatrix).
 *
 * Extracted from MDICSectionRenderer's 2026-05-26 experiment
 * ({@code VOXY_LOD_METAL_NDC=1}) so every Metal pass shares ONE depth
 * convention — the LOD terrain pass (quads.frag's {@code gl_FragCoord.z})
 * and the chunk-bound depth mask (ChunkBoundRenderer/outline.vsh) compare
 * depths against each other, so they must remap identically (both on, or
 * both off). On Metal — whose visible clip volume is z ∈ [0, w] — the near
 * half of the GL clip range lands at NDC z &lt; 0 and is clipped by the
 * rasterizer; the remap maps the whole GL range into Metal's [0,1] so
 * nothing in the GL frustum is clipped, and depth ordering is preserved
 * (the remap is monotonic). OFF by default because it shifts every depth
 * value and needs visual confirmation — same class as the bakery m22 fix.
 */
public final class MetalMvpUtil {
    /** Opt-in flag for the remap ({@code VOXY_LOD_METAL_NDC=1}). Callers gate on this AND a non-GL backend. */
    public static final boolean METAL_NDC_REMAP = "1".equals(System.getenv("VOXY_LOD_METAL_NDC"));
    private static boolean logged = false;

    private MetalMvpUtil() {}

    /**
     * metalMVP = Zremap · mat, where Zremap maps NDC z [-1,1] → [0,1].
     * Column-major args (mColRow): m22 = 0.5, m32 = 0.5 give the row
     * form  z' = 0.5·z + 0.5·w,  w' = w. X/Y are untouched so on-screen
     * position is identical; only the clipped/written depth changes.
     * Mutates {@code mat} in place.
     */
    public static void applyNdcRemap(Matrix4f mat) {
        new Matrix4f(
                1, 0, 0,    0,
                0, 1, 0,    0,
                0, 0, 0.5f, 0,
                0, 0, 0.5f, 1).mul(mat, mat);
        if (!logged) {
            logged = true;
            Logger.info("[Metal] VOXY_LOD_METAL_NDC active: render MVP remapped to [0,1] NDC-z");
        }
    }
}
