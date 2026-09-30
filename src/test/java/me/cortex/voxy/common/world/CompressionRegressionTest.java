package me.cortex.voxy.common.world;

import me.cortex.voxy.common.config.compressors.*;
import me.cortex.voxy.common.config.storage.inmemory.MemoryStorageBackend;
import me.cortex.voxy.common.config.storage.other.CompressionStorageAdaptor;
import me.cortex.voxy.common.util.MemoryBuffer;
import java.util.Arrays;

public final class CompressionRegressionTest {
    private static int failures;
    private interface Case { void run() throws Exception; }
    private static void check(String name, Case test) {
        try { test.run(); System.out.println("PASS: " + name); }
        catch (Throwable error) { failures++; System.err.println("FAIL: " + name + ": " + error); }
    }
    private static byte[] bytes(MemoryBuffer buffer) { byte[] out = new byte[(int)buffer.size]; buffer.asByteBuffer().get(out); return out; }
    private static void freeIfOwned(MemoryBuffer buffer) throws Exception {
        var field = MemoryBuffer.class.getDeclaredField("freeable"); field.setAccessible(true);
        if (field.getBoolean(buffer)) buffer.free();
    }
    public static void main(String[] args) {
        for (StorageCompressor compressor : new StorageCompressor[]{new LZ4Compressor(), new ZSTDCompressor(3)}) {
            String name = compressor.getClass().getSimpleName();
            check(name + " borrowed compression output can be decompressed without aliasing", () -> {
                var input = new MemoryBuffer(40_000).zero();
                try {
                    var packed = compressor.compress(input);
                    try {
                        var decoded = compressor.decompress(packed);
                        if (!Arrays.equals(bytes(input), bytes(decoded))) throw new AssertionError("wrong decompressed length/data");
                    } finally { freeIfOwned(packed); }
                } finally { input.free(); }
            });
            check(name + " storage round-trip supports larger data and reuses compression capacity", () -> {
                var input = new MemoryBuffer(600_000).zero();
                var scratch = new MemoryBuffer(650_000);
                var storage = new CompressionStorageAdaptor(compressor, new MemoryStorageBackend(1));
                try {
                    storage.setSectionData(1, input); var decoded = storage.getSectionData(1, scratch.createUntrackedUnfreeableReference());
                    if (!Arrays.equals(bytes(input), bytes(decoded))) throw new AssertionError("large storage round-trip failed");
                    var first = compressor.compress(input); var second = compressor.compress(input);
                    try { if (first.address != second.address) throw new AssertionError("compression output still allocates per save"); }
                    finally { freeIfOwned(first); freeIfOwned(second); }
                } finally { input.free(); scratch.free(); storage.close(); }
            });
        }
        if (failures > 0) throw new AssertionError(failures + " compression regressions failed");
    }
}
