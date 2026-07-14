package me.cortex.voxy.client.mixin.sodium;

import me.cortex.voxy.client.core.rendering.IVoxyBuiltSectionInfo;
import net.caffeinemc.mods.sodium.client.render.chunk.data.BuiltSectionInfo;
import net.caffeinemc.mods.sodium.client.render.chunk.terrain.TerrainRenderPass;
import net.minecraft.client.renderer.chunk.VisibilitySet;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.world.level.block.entity.BlockEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.Collection;

/**
 * Captures whether a built section has any OPAQUE (non-translucent pass)
 * geometry before Sodium's ctor collapses the pass list into the combined
 * HAS_BLOCK_GEOMETRY flag. Consumed by MixinRenderSectionManager to route
 * trans-only sections into the chunk-bound mask's coverage-epsilon set
 * (see ChunkBoundRenderer, VOXY_BOUND_TRANS_SPLIT).
 */
@Mixin(value = BuiltSectionInfo.class, remap = false)
public class MixinBuiltSectionInfo implements IVoxyBuiltSectionInfo {
    @Unique
    private boolean voxy$hasOpaque;

    @Inject(method = "<init>", at = @At("RETURN"))
    private void voxy$captureOpaque(Collection<TerrainRenderPass> blockRenderPasses,
                                    Collection<BlockEntity> globalBlockEntities,
                                    Collection<BlockEntity> culledBlockEntities,
                                    Collection<TextureAtlasSprite> animatedSprites,
                                    VisibilitySet occlusionData,
                                    CallbackInfo ci) {
        // SOLID only (not translucent, no fragment discard). CUTOUT passes
        // must NOT count as opaque: an open-ocean surface section whose only
        // non-water geometry is kelp/seagrass tips (cutout) covers almost
        // none of its AABB's pixels — letting it anchor the real-depth mask
        // discards the LOD seafloor behind its own water exactly like a pure
        // water section (the near pale panes persisted over kelp forests).
        boolean opaque = false;
        for (TerrainRenderPass pass : blockRenderPasses) {
            if (!pass.isTranslucent() && !pass.supportsFragmentDiscard()) {
                opaque = true;
                break;
            }
        }
        this.voxy$hasOpaque = opaque;
    }

    @Override
    public boolean voxy$hasOpaque() {
        return this.voxy$hasOpaque;
    }
}
