package me.cortex.voxy.client.mixin.sodium;

import me.cortex.voxy.client.ICheekyClientChunkCache;
import me.cortex.voxy.client.config.VoxyConfig;
import me.cortex.voxy.client.core.IVoxyRenderSystemHolder;
import me.cortex.voxy.client.core.VoxyRenderSystem;
import me.cortex.voxy.client.core.rendering.ChunkBoundRenderer;
import me.cortex.voxy.commonImpl.VoxyCommon;
import me.cortex.voxy.client.core.util.IrisUtil;
import me.cortex.voxy.common.world.service.VoxelIngestService;
import net.caffeinemc.mods.sodium.client.render.chunk.RenderSection;
import net.caffeinemc.mods.sodium.client.render.chunk.RenderSectionManager;
import net.caffeinemc.mods.sodium.client.render.chunk.data.BuiltSectionInfo;
import net.caffeinemc.mods.sodium.client.render.chunk.map.ChunkTrackerHolder;
import net.caffeinemc.mods.sodium.client.render.chunk.translucent_sorting.SortBehavior;
import net.caffeinemc.mods.sodium.client.render.viewport.Viewport;
import net.caffeinemc.mods.sodium.client.util.FogParameters;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.SectionPos;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value = RenderSectionManager.class, remap = false)
public class MixinRenderSectionManager {
    @Unique
    private static final boolean BOBBY_INSTALLED = FabricLoader.getInstance().isModLoaded("bobby");

    @Shadow @Final private ClientLevel level;

    @Inject(method = "<init>", at = @At("TAIL"))
    private void voxy$resetChunkTracker(ClientLevel level, int renderDistance, SortBehavior sortBehavior, CallbackInfo ci) {
        this.bottomSectionY = this.level.getMinY()>>4;
    }

    @Inject(method = "renderOutOfGraph", at = @At("HEAD"))
    private void voxy$injectReset1(Viewport viewport, FogParameters fogParameters, CallbackInfo ci) {
        var vrs = IVoxyRenderSystemHolder.getNullable();
        if (vrs != null && !IrisUtil.irisShadowActive() && vrs.visbleSectionStream != null) {
            vrs.visbleSectionStream.reset();
        }
    }

    @Inject(method = "readRenderListFromTree", at = @At(value = "INVOKE", target = "Lnet/caffeinemc/mods/sodium/client/render/chunk/lists/VisibleChunkCollector;<init>(Lnet/caffeinemc/mods/sodium/client/render/chunk/region/RenderRegionManager;I)V"))
    private void voxy$injectReset2(Viewport viewport, FogParameters fogParameters, CallbackInfo ci) {
        var vrs = IVoxyRenderSystemHolder.getNullable();
        if (vrs != null && !IrisUtil.irisShadowActive() && vrs.visbleSectionStream != null) {
            vrs.visbleSectionStream.reset();
        }
    }

    @Inject(method = "onChunkRemoved", at = @At("HEAD"))
    private void voxy$injectIngest(int x, int z, CallbackInfo ci) {
        //TODO: Am not quite sure if this is right
        if (VoxyConfig.CONFIG.ingestEnabled && !BOBBY_INSTALLED) {
            var cccm = (ICheekyClientChunkCache)this.level.getChunkSource();
            if (cccm != null) {
                var chunk = cccm.voxy$cheekyGetChunk(x, z);
                if (chunk != null) {
                    VoxelIngestService.tryAutoIngestChunk(chunk);
                }
            }
        }
    }


    @Inject(method = "onChunkAdded", at = @At("HEAD"))
    private void voxy$ingestOnAdd(int x, int z, CallbackInfo ci) {
        if (this.level != null && VoxyConfig.CONFIG.ingestEnabled) {
            var cccm = this.level.getChunkSource();
            if (cccm != null) {
                var chunk = cccm.getChunk(x, z, ChunkStatus.FULL, false);
                if (chunk != null) {
                    VoxelIngestService.tryAutoIngestChunk(chunk);
                }
            }
        }
    }


    @Unique private long cachedChunkPos = -1;
    @Unique private int cachedChunkStatus;
    @Unique private int bottomSectionY;


    @Redirect(method = "updateSectionInfo", at = @At(value = "INVOKE", target = "Lnet/caffeinemc/mods/sodium/client/render/chunk/RenderSection;setInfo(Lnet/caffeinemc/mods/sodium/client/render/chunk/data/BuiltSectionInfo;)I"))
    private int voxy$updateOnUpload(RenderSection instance, BuiltSectionInfo info) {
        boolean isInvisible = instance.isInvisible();
        int changes = instance.setInfo(info);
        VoxyRenderSystem vrs = IVoxyRenderSystemHolder.getNullable();
        if (vrs == null) {
            return changes;
        }
        int x = instance.getChunkX(), y = instance.getChunkY(), z = instance.getChunkZ();
        // Sodium 0.9 removes a section from its region (clearing its flags) BEFORE
        // this call, so the visible->invisible transition can't be trusted on unload.
        // Drive the loaded-chunk mask from what we track instead.
        voxy$syncBound(vrs, x, y, z, info == null || instance.isInvisible() ? null : info);
        if (changes == 0 || isInvisible == instance.isInvisible()) {
            return changes;
        }

        if (!isInvisible && VoxyConfig.CONFIG.ingestEnabled) {
            var tracker = ((AccessorChunkTracker) ChunkTrackerHolder.get(this.level)).getChunkStatus();
            //in theory the cache value could be wrong but is so soso unlikely and at worst means we either duplicate ingest a chunk
            // which... could be bad ;-; or we dont ingest atall which is ok!
            long key = ChunkPos.pack(x, z);
            if (key != this.cachedChunkPos) {
                this.cachedChunkPos = key;
                this.cachedChunkStatus = tracker.getOrDefault(key, 0);
            }
            if (this.cachedChunkStatus == 3) {//If this chunk still has surrounding chunks
                var cccm = this.level.getChunkSource();
                //var chunk = ((ICheekyClientChunkCache)cccm).voxy$cheekyGetChunk(x, z);
                //Dont thinks need to use cheekyGetChunk here as thats handled by the inject into head of onChunkRemoved
                // but only ingest if the chunkstatus is full and exists
                var chunk = cccm.getChunk(x, z, ChunkStatus.FULL, false);
                if (chunk != null) {
                    var section = chunk.getSection(y - this.bottomSectionY);
                    var lp = this.level.getLightEngine();

                    var csp = SectionPos.of(x, y, z);
                    var blp = lp.getLayerListener(LightLayer.BLOCK).getDataLayerData(csp);
                    var slp = lp.getLayerListener(LightLayer.SKY).getDataLayerData(csp);

                    //Note: we dont do this check and just blindly ingest, it shouldbe ok :tm:
                    //if (blp != null || slp != null)
                    VoxelIngestService.rawIngest(vrs.getEngine(), section, x, y, z, blp == null ? null : blp.copy(), slp == null ? null : slp.copy());
                }
            }
        }

        return changes;
    }

    /**
     * The opaque-geometry bit MixinBuiltSectionInfo captured. Defaults to true if
     * the duck mixin didn't apply: a false negative would let LODs draw inside
     * the whole loaded-chunk volume.
     */
    @Unique
    private static boolean voxy$hasOpaque(BuiltSectionInfo info) {
        return info == null
                || !(((Object) info) instanceof me.cortex.voxy.client.core.rendering.IVoxyBuiltSectionInfo iv)
                || iv.voxy$hasOpaque();
    }

    @Unique
    private static void voxy$syncBound(VoxyRenderSystem vrs, int x, int y, int z, BuiltSectionInfo visibleInfo) {
        long pos = voxy$boundPos(x, y, z);
        boolean tracked = ChunkBoundRenderer.mirrorContains(pos);
        if (visibleInfo == null) {
            ChunkBoundRenderer.pendingRemove(pos);
            if (tracked) {
                ChunkBoundRenderer.mirrorRemove(pos);
                vrs.chunkBoundRenderer.removeSection(pos);
            }
            return;
        }
        boolean hasOpaque = voxy$hasOpaque(visibleInfo);
        if (!tracked) {
            // Masked once Sodium first draws it (see ChunkBoundRenderer.markDrawn).
            ChunkBoundRenderer.pendingPut(pos, hasOpaque);
        } else if (ChunkBoundRenderer.mirrorReclass(pos, hasOpaque)) {
            // Rebuilt in place and flipped opaque <-> translucent-only.
            vrs.chunkBoundRenderer.removeSection(pos);
            vrs.chunkBoundRenderer.addSection(pos, hasOpaque);
        }
    }

    @Unique
    private static long voxy$boundPos(int x, int y, int z) {
        //Do some very cheeky stuff for MiB
        if (VoxyCommon.IS_MINE_IN_ABYSS) {
            int sector = (x+512)>>10;
            x-=sector<<10;
            y+=16+(256-32-sector*30);
        }
        return SectionPos.asLong(x,y,z);
    }

    @Inject(method = "<init>", at = @At("RETURN"))
    private void voxy$resetBoundMirror(CallbackInfo ci) {
        // Fresh RenderSectionManager = everything rebuilds; drop stale bound-mask
        // entries both in the mirror and in the live renderer's sets.
        ChunkBoundRenderer.mirrorReset();
        var vrs = IVoxyRenderSystemHolder.getNullable();
        if (vrs != null) {
            vrs.chunkBoundRenderer.reset();
        }
    }
}
