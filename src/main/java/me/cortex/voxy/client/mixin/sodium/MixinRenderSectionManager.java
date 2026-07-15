package me.cortex.voxy.client.mixin.sodium;

import me.cortex.voxy.client.ICheekyClientChunkCache;
import me.cortex.voxy.client.config.VoxyConfig;
import me.cortex.voxy.client.core.IGetVoxyRenderSystem;
import me.cortex.voxy.client.core.VoxyRenderSystem;
import me.cortex.voxy.common.world.service.VoxelIngestService;
import me.cortex.voxy.commonImpl.VoxyCommon;
import net.caffeinemc.mods.sodium.client.gl.device.CommandList;
import net.caffeinemc.mods.sodium.client.render.chunk.RenderSection;
import net.caffeinemc.mods.sodium.client.render.chunk.RenderSectionManager;
import net.caffeinemc.mods.sodium.client.render.chunk.compile.executor.ChunkBuilder;
import net.caffeinemc.mods.sodium.client.render.chunk.data.BuiltSectionInfo;
import net.caffeinemc.mods.sodium.client.render.chunk.map.ChunkTrackerHolder;
import net.caffeinemc.mods.sodium.client.render.chunk.translucent_sorting.SortBehavior;
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

    @Shadow @Final private ChunkBuilder builder;

    @Inject(method = "<init>", at = @At("TAIL"))
    private void voxy$resetChunkTracker(ClientLevel level, int renderDistance, SortBehavior sortBehavior, CommandList commandList, CallbackInfo ci) {
        if (level.levelRenderer != null) {
            var system = ((IGetVoxyRenderSystem)(level.levelRenderer)).getVoxyRenderSystem();
            if (system != null) {
                system.chunkBoundRenderer.reset();
            }
        }
        this.bottomSectionY = this.level.getMinY()>>4;
    }

    @Inject(method = "onChunkRemoved", at = @At("HEAD"))
    private void injectIngest(int x, int z, CallbackInfo ci) {
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
        if (this.level.levelRenderer != null && VoxyConfig.CONFIG.ingestEnabled) {
            var cccm = this.level.getChunkSource();
            if (cccm != null) {
                var chunk = cccm.getChunk(x, z, ChunkStatus.FULL, false);
                if (chunk != null) {
                    VoxelIngestService.tryAutoIngestChunk(chunk);
                }
            }
        }
    }

    /*
    @Inject(method = "onChunkRemoved", at = @At("HEAD"))
    private void voxy$trackChunkRemove(int x, int z, CallbackInfo ci) {
        if (this.level.worldRenderer != null) {
            var system = ((IGetVoxyRenderSystem)(this.level.worldRenderer)).getVoxyRenderSystem();
            if (system != null) {
                system.chunkBoundRenderer.removeSection(ChunkPos.toLong(x, z));
            }
        }
    }*/

    @Unique private long cachedChunkPos = -1;
    @Unique private int cachedChunkStatus;
    @Unique private int bottomSectionY;

    @Redirect(method = "updateSectionInfo", at = @At(value = "INVOKE", target = "Lnet/caffeinemc/mods/sodium/client/render/chunk/RenderSection;setInfo(Lnet/caffeinemc/mods/sodium/client/render/chunk/data/BuiltSectionInfo;)Z"))
    private boolean voxy$updateOnUpload(RenderSection instance, BuiltSectionInfo info) {
        boolean wasBuilt = instance.getFlags()!=0;
        int flags = instance.getFlags();
        if (!instance.setInfo(info)) {
            return false;
        }
        if (wasBuilt == (instance.getFlags()!=0)) {//Only want to do stuff on change
            // Rebuild-in-place (built→built, no flag transition): the
            // section's opaque/trans-only class may still have FLIPPED
            // (last opaque block mined out of a water section, …) — the
            // bound mask must re-route it between the real-depth and
            // coverage-epsilon instance sets or the split goes stale.
            if (wasBuilt && instance.getFlags() != 0 && info != null) {
                boolean hasOpaque = voxy$hasOpaque(info);
                long rpos = voxy$boundPos(instance.getChunkX(), instance.getChunkY(), instance.getChunkZ());
                if (me.cortex.voxy.client.core.rendering.ChunkBoundRenderer.mirrorReclass(rpos, hasOpaque)) {
                    VoxyRenderSystem sys = ((IGetVoxyRenderSystem)(this.level.levelRenderer)).getVoxyRenderSystem();
                    if (sys != null) {
                        sys.chunkBoundRenderer.removeSection(rpos);
                        sys.chunkBoundRenderer.addSection(rpos, hasOpaque);
                    }
                }
            }
            return true;
        }

        flags |= instance.getFlags();
        if (flags == 0)//Only process things with stuff
            return true;

        VoxyRenderSystem system = ((IGetVoxyRenderSystem)(this.level.levelRenderer)).getVoxyRenderSystem();
        if (system == null) {
            return true;
        }
        int x = instance.getChunkX(), y = instance.getChunkY(), z = instance.getChunkZ();

        if (wasBuilt && VoxyConfig.CONFIG.ingestEnabled) {
            var tracker = ((AccessorChunkTracker)ChunkTrackerHolder.get(this.level)).getChunkStatus();
            //in theory the cache value could be wrong but is so soso unlikely and at worst means we either duplicate ingest a chunk
            // which... could be bad ;-; or we dont ingest atall which is ok!
            long key = ChunkPos.asLong(x, z);
            if (key != this.cachedChunkPos) {
                this.cachedChunkPos = key;
                this.cachedChunkStatus = tracker.getOrDefault(key, 0);
            }
            if (this.cachedChunkStatus == 3) {//If this chunk still has surrounding chunks
                var section = this.level.getChunk(x,z).getSection(y-this.bottomSectionY);
                var lp = this.level.getLightEngine();

                var csp = SectionPos.of(x,y,z);
                var blp = lp.getLayerListener(LightLayer.BLOCK).getDataLayerData(csp);
                var slp = lp.getLayerListener(LightLayer.SKY).getDataLayerData(csp);

                //Note: we dont do this check and just blindly ingest, it shouldbe ok :tm:
                //if (blp != null || slp != null)
                    VoxelIngestService.rawIngest(system.getEngine(), section, x,y,z, blp==null?null:blp.copy(), slp==null?null:slp.copy());
            }
        }

        long pos = voxy$boundPos(x, y, z);
        if (wasBuilt) {//Remove
            //TODO: on chunk remove do ingest if is surrounded by built chunks (or when the tracker says is ok)

            me.cortex.voxy.client.core.rendering.ChunkBoundRenderer.mirrorRemove(pos);
            system.chunkBoundRenderer.removeSection(pos);
        } else {//Add
            boolean hasOpaque = voxy$hasOpaque(info);
            me.cortex.voxy.client.core.rendering.ChunkBoundRenderer.mirrorAdd(pos, hasOpaque);
            system.chunkBoundRenderer.addSection(pos, hasOpaque);
        }
        return true;
    }

    /**
     * The opaque-geometry bit MixinBuiltSectionInfo captured before Sodium
     * collapsed the pass list. Defaults to TRUE (= today's single-set
     * behavior) if the duck mixin somehow didn't apply — a false negative
     * here would route every section to the coverage-epsilon set and let
     * LODs draw inside the whole loaded-chunk volume.
     */
    @Unique
    private static boolean voxy$hasOpaque(BuiltSectionInfo info) {
        return info == null
                || !(((Object) info) instanceof me.cortex.voxy.client.core.rendering.IVoxyBuiltSectionInfo iv)
                || iv.voxy$hasOpaque();
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

    @org.spongepowered.asm.mixin.injection.Inject(method = "<init>", at = @At("RETURN"))
    private void voxy$resetBoundMirror(org.spongepowered.asm.mixin.injection.callback.CallbackInfo ci) {
        // Fresh RenderSectionManager = all sections rebuild from scratch;
        // clear the bound-mask mirror so stale entries can't discard LODs
        // over chunks Sodium no longer renders.
        me.cortex.voxy.client.core.rendering.ChunkBoundRenderer.mirrorReset();
    }
}
