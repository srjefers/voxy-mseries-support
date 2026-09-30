package me.cortex.voxy.client.mixin.sodium;

import me.cortex.voxy.client.ICheekyClientChunkCache;
import me.cortex.voxy.client.config.VoxyConfig;
import me.cortex.voxy.client.core.IGetVoxyRenderSystem;
import me.cortex.voxy.client.core.VoxyRenderSystem;
import me.cortex.voxy.common.world.service.VoxelIngestService;
import me.cortex.voxy.client.core.rendering.SectionCoverageTracker;
import net.caffeinemc.mods.sodium.client.render.chunk.RenderSectionFlags;
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
        this.voxy$coverageGeneration = SectionCoverageTracker.INSTANCE.beginGeneration();
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

    @Unique private SectionCoverageTracker.Generation voxy$coverageGeneration;
    @Unique private long cachedChunkPos = -1;
    @Unique private int cachedChunkStatus;
    @Unique private int bottomSectionY;

    // Sodium 0.8.11 completes region uploads before updating metadata here.
    // setInfo may return false on a rebuild with unchanged flags; material class can still change.
    @Redirect(method = "updateSectionInfo", at = @At(value = "INVOKE", target = "Lnet/caffeinemc/mods/sodium/client/render/chunk/RenderSection;setInfo(Lnet/caffeinemc/mods/sodium/client/render/chunk/data/BuiltSectionInfo;)Z"))
    private boolean voxy$updateOnUpload(RenderSection instance, BuiltSectionInfo info) {
        boolean wasUploaded = (instance.getFlags() & RenderSectionFlags.MASK_HAS_BLOCK_GEOMETRY) != 0;
        boolean changed = instance.setInfo(info);
        boolean uploaded = instance.isBuilt() && !instance.isDisposed()
                && (instance.getFlags() & RenderSectionFlags.MASK_HAS_BLOCK_GEOMETRY) != 0;
        var coverage = !uploaded ? SectionCoverageTracker.Coverage.NONE
                : voxy$hasOpaque(info) ? SectionCoverageTracker.Coverage.OPAQUE
                : SectionCoverageTracker.Coverage.TRANSLUCENT;
        long position = voxy$boundPos(instance.getChunkX(), instance.getChunkY(), instance.getChunkZ());
        if (!SectionCoverageTracker.INSTANCE.publish(this.voxy$coverageGeneration, position, coverage)) return changed;
        if (wasUploaded && !uploaded && VoxyConfig.CONFIG.ingestEnabled) this.voxy$ingestRemovedSection(instance);
        return changed;
    }

    @Unique
    private void voxy$ingestRemovedSection(RenderSection instance) {
        if (this.level.levelRenderer == null) return;
        VoxyRenderSystem system = ((IGetVoxyRenderSystem)this.level.levelRenderer).getVoxyRenderSystem();
        if (system == null) return;
        int x = instance.getChunkX(), y = instance.getChunkY(), z = instance.getChunkZ();
        var tracker = ((AccessorChunkTracker)ChunkTrackerHolder.get(this.level)).getChunkStatus();
        long key = ChunkPos.asLong(x, z);
        if (key != this.cachedChunkPos) {
            this.cachedChunkPos = key;
            this.cachedChunkStatus = tracker.getOrDefault(key, 0);
        }
        if (this.cachedChunkStatus != 3) return;
        // Do not create a default chunk or ingest a cache slot belonging to another position.
        var chunk = this.level.getChunkSource().getChunk(x, z, ChunkStatus.FULL, false);
        if (chunk == null) return;
        int sectionIndex = y - this.bottomSectionY;
        if (sectionIndex < 0 || sectionIndex >= chunk.getSections().length) return;
        var section = chunk.getSection(sectionIndex);
        var lighting = this.level.getLightEngine();
        var sectionPos = SectionPos.of(x, y, z);
        var block = lighting.getLayerListener(LightLayer.BLOCK).getDataLayerData(sectionPos);
        var sky = lighting.getLayerListener(LightLayer.SKY).getDataLayerData(sectionPos);
        VoxelIngestService.rawIngest(system.getEngine(), section, x, y, z,
                block == null ? null : block.copy(), sky == null ? null : sky.copy());
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

    @Inject(method = "destroy", at = @At("HEAD"))
    private void voxy$releaseCoverage(CallbackInfo ci) {
        SectionCoverageTracker.INSTANCE.endGeneration(this.voxy$coverageGeneration);
    }
}
