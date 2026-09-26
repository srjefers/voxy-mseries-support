package me.cortex.voxy.client.core.rendering.util;

/**
 * Monotonic version of MC's lightmap texture contents.
 *
 * {@code MixinLightTexture} bumps it at the tail of
 * {@code LightTexture.updateLightTexture(float)} — the one place MC
 * re-renders its 16×16 lightmap — which vanilla only reaches when
 * {@code LightTexture.tick()} armed the {@code updateLightTexture} flag
 * (client tick, ~20 Hz) and a level is loaded. {@link LightMapHelper}
 * compares it against the version it last mirrored so the Metal path's
 * {@code glGetTexImage} readback runs only when the texture actually
 * changed, instead of every frame.
 *
 * Render-thread only (both the bump in GameRenderer.render and the read in
 * the SOLID-pass LOD draw run there), so a plain int is enough. The counter
 * is a no-op on the GL backend: nothing reads it there.
 */
public final class McLightmapVersion {
    private static int version;

    private McLightmapVersion() {}

    /** Called by the mixin after MC rewrote the lightmap texture. */
    public static void bump() {
        version++;
    }

    public static int get() {
        return version;
    }
}
