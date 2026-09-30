package me.cortex.voxy.client.core;

/** One material contract decision for the complete renderer generation. */
public enum MetalMaterialPolicy {
    SHADERS_OFF(false, false), CONTRACT(true, false), BSL_FULL(true, true), BSL_TRANSLUCENT(false, true);

    private final boolean opaque, legacyWater;

    MetalMaterialPolicy(boolean opaque, boolean legacyWater) {
        this.opaque = opaque;
        this.legacyWater = legacyWater;
    }

    public boolean opaque() { return this.opaque; }
    public boolean legacyWater() { return this.legacyWater; }
    /** Generic material water must not be discarded by Sodium's coarse section AABB. */
    public boolean translucentBoundMask() { return this != CONTRACT; }

    public static MetalMaterialPolicy select(boolean bsl, boolean opaqueOptIn) {
        return !bsl ? CONTRACT : opaqueOptIn ? BSL_FULL : BSL_TRANSLUCENT;
    }
}
