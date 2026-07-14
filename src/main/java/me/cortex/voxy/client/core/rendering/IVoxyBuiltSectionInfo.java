package me.cortex.voxy.client.core.rendering;

/**
 * Duck interface implemented onto Sodium's {@code BuiltSectionInfo} by
 * {@code MixinBuiltSectionInfo}. Sodium collapses the per-pass build result
 * into a single HAS_BLOCK_GEOMETRY flag, discarding whether any of the
 * section's geometry is OPAQUE — but the chunk-bound mask needs exactly that
 * bit: a TRANSLUCENT-ONLY section (open-ocean water surface) must arm the
 * trans near-cull's coverage test without extending the opaque depth bound,
 * or it discards the LOD seafloor behind its own water (the near pale panes).
 */
public interface IVoxyBuiltSectionInfo {
    /** True when at least one non-translucent TerrainRenderPass produced geometry. */
    boolean voxy$hasOpaque();
}
