package me.cortex.voxy.client.core.rendering;

import me.cortex.voxy.client.VoxyCommands;
import me.cortex.voxy.client.mixin.minecraft.MixinClientChunkCache;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.minecraft.client.multiplayer.ClientChunkCache;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.LevelChunk;
import static org.mockito.Mockito.*;

public final class ClientFeatureRegressionTest {
    private static int failures;
    private interface Case { void run() throws Exception; }
    private static void check(String name, Case test) {
        try { test.run(); System.out.println("PASS: " + name); }
        catch (Throwable error) { failures++; System.err.println("FAIL: " + name + ": " + error); }
    }
    public static void main(String[] args) throws Exception {
        SharedConstants.tryDetectVersion(); Bootstrap.bootStrap();
        check("chunk cache rejects another position occupying the same ring-buffer slot", () -> {
            var cache = new MixinClientChunkCache();
            var storage = mock(ClientChunkCache.Storage.class);
            var field = MixinClientChunkCache.class.getDeclaredField("storage"); field.setAccessible(true); field.set(cache, storage);
            var chunk = mock(LevelChunk.class); when(chunk.getPos()).thenReturn(new ChunkPos(99, 99));
            when(storage.getIndex(1, 2)).thenReturn(3); when(storage.getChunk(3)).thenReturn(chunk);
            if (cache.voxy$cheekyGetChunk(1, 2) != null) throw new AssertionError("wrong-position chunk returned");
            when(chunk.getPos()).thenReturn(new ChunkPos(1, 2));
            if (cache.voxy$cheekyGetChunk(1, 2) != chunk) throw new AssertionError("correct-position chunk rejected");
            when(storage.getChunk(3)).thenReturn(null);
            if (cache.voxy$cheekyGetChunk(1, 2) != null) throw new AssertionError("empty slot returned a chunk");
        });
        check("current-world import is registered and reports disabled Voxy without importing", () -> {
            var dispatcher = new com.mojang.brigadier.CommandDispatcher<FabricClientCommandSource>();
            dispatcher.register(VoxyCommands.register());
            var source = mock(FabricClientCommandSource.class);
            int result = dispatcher.execute("voxy import current", source);
            if (result != 1) throw new AssertionError("unexpected disabled-import result");
            verify(source).sendError(any());
        });
        if (failures > 0) throw new AssertionError(failures + " client-feature regressions failed");
    }
}
