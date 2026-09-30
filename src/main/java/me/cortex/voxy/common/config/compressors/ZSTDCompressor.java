package me.cortex.voxy.common.config.compressors;

import me.cortex.voxy.common.config.ConfigBuildCtx;
import me.cortex.voxy.common.config.section.SectionSerializationStorage;
import me.cortex.voxy.common.util.MemoryBuffer;
import me.cortex.voxy.common.util.ResizingThreadLocalMemoryBuffer;

import static me.cortex.voxy.common.util.GlobalCleaner.CLEANER;
import static org.lwjgl.util.zstd.Zstd.*;

public class ZSTDCompressor implements StorageCompressor {
    private record Ref(long ptr) {}

    private static Ref createCleanableCompressionContext() {
        long ctx = ZSTD_createCCtx();
        var ref = new Ref(ctx);
        CLEANER.register(ref, ()->ZSTD_freeCCtx(ctx));
        return ref;
    }

    private static Ref createCleanableDecompressionContext() {
        long ctx = ZSTD_createDCtx();
        nZSTD_DCtx_setParameter(ctx, ZSTD_d_experimentalParam3, 1);//experimental ZSTD_d_forceIgnoreChecksum
        var ref = new Ref(ctx);
        CLEANER.register(ref, ()->ZSTD_freeDCtx(ctx));
        return ref;
    }

    private static final ThreadLocal<Ref> COMPRESSION_CTX = ThreadLocal.withInitial(ZSTDCompressor::createCleanableCompressionContext);
    private static final ThreadLocal<Ref> DECOMPRESSION_CTX = ThreadLocal.withInitial(ZSTDCompressor::createCleanableDecompressionContext);

    private static final ResizingThreadLocalMemoryBuffer COMPRESSED = new ResizingThreadLocalMemoryBuffer(SectionSerializationStorage.BIGGEST_SERIALIZED_SECTION_SIZE + 1024);

    // Preserve the Mac fork's input snapshot. Input, compressed output and decoded output
    // have distinct storage so borrowed buffers cannot alias native reads and writes.
    private static final ResizingThreadLocalMemoryBuffer STABLE_INPUT = new ResizingThreadLocalMemoryBuffer(SectionSerializationStorage.BIGGEST_SERIALIZED_SECTION_SIZE + 1024);
    private static final ResizingThreadLocalMemoryBuffer DECOMPRESSED = new ResizingThreadLocalMemoryBuffer(SectionSerializationStorage.BIGGEST_SERIALIZED_SECTION_SIZE + 1024);

    private final int level;

    public ZSTDCompressor(int level) {
        this.level = level;
    }

    @Override
    public MemoryBuffer compress(MemoryBuffer saveData) {
        var stableInput = STABLE_INPUT.get(saveData.size);
        org.lwjgl.system.MemoryUtil.memCopy(saveData.address, stableInput.address, saveData.size);
        var compressedData = COMPRESSED.get(ZSTD_COMPRESSBOUND(saveData.size)).createUntrackedUnfreeableReference();
        long compressedSize = nZSTD_compressCCtx(COMPRESSION_CTX.get().ptr, compressedData.address, compressedData.size, stableInput.address, saveData.size, this.level);
        if (ZSTD_isError(compressedSize)) throw new IllegalArgumentException(ZSTD_getErrorName(compressedSize));
        return compressedData.subSize(compressedSize);
    }

    @Override
    public MemoryBuffer decompress(MemoryBuffer saveData) {
        long decodedSize = nZSTD_getFrameContentSize(saveData.address, saveData.size);
        if (decodedSize < 0 || decodedSize > Integer.MAX_VALUE) throw new IllegalArgumentException("Invalid or unbounded ZSTD frame size: " + decodedSize);
        var decompressed = DECOMPRESSED.get(decodedSize).createUntrackedUnfreeableReference();
        long size = nZSTD_decompressDCtx(DECOMPRESSION_CTX.get().ptr, decompressed.address, decompressed.size, saveData.address, saveData.size);
        if (ZSTD_isError(size)) throw new IllegalArgumentException(ZSTD_getErrorName(size));
        return decompressed.subSize(size);
    }

    @Override
    public void close() {

    }

    public static class Config extends CompressorConfig {
        public int compressionLevel;

        @Override
        public StorageCompressor build(ConfigBuildCtx ctx) {
            return new ZSTDCompressor(this.compressionLevel);
        }

        public static String getConfigTypeName() {
            return "ZSTD";
        }
    }
}
