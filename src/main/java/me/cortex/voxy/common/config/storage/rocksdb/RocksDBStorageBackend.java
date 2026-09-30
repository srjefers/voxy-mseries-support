package me.cortex.voxy.common.config.storage.rocksdb;

import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import me.cortex.voxy.common.config.ConfigBuildCtx;
import me.cortex.voxy.common.config.storage.StorageBackend;
import me.cortex.voxy.common.config.storage.StorageConfig;
import me.cortex.voxy.common.util.MemoryBuffer;
import me.cortex.voxy.common.world.WorldEngine;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.rocksdb.*;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.function.LongConsumer;

public class RocksDBStorageBackend extends StorageBackend {
    private final RocksDB db;
    private final ColumnFamilyHandle worldSections;
    private final ColumnFamilyHandle idMappings;
    private final ReadOptions sectionReadOps;
    private final WriteOptions sectionWriteOps;

    // Registered immediately; reverse allocation order releases dependants before owners.
    private final List<AbstractImmutableNativeReference> closeList = new ArrayList<>();
    private boolean closed;

    private <T extends AbstractImmutableNativeReference> T own(T resource) {
        this.closeList.add(resource);
        return resource;
    }

    public RocksDBStorageBackend(String path) {
        RocksDB.loadLibrary();
        List<ColumnFamilyHandle> handles = new ArrayList<>();
        try {
            var cache = own(new HyperClockCache(128*1024L*1024L, 0, 4, false));
            var filter = own(new BloomFilter(10));
            var cfOpts = own(new ColumnFamilyOptions())
                    .setCompressionType(CompressionType.ZSTD_COMPRESSION).optimizeForSmallDb();
            var cfWorldSecOpts = own(new ColumnFamilyOptions())
                    .setCompressionType(CompressionType.NO_COMPRESSION)
                    .setCompactionPriority(CompactionPriority.MinOverlappingRatio)
                    .setLevelCompactionDynamicLevelBytes(true).optimizeForPointLookup(128);
            cfWorldSecOpts.setTableFormatConfig(new BlockBasedTableConfig()
                    .setCacheIndexAndFilterBlocksWithHighPriority(true).setBlockCache(cache)
                    .setDataBlockHashTableUtilRatio(0.75)
                    .setDataBlockIndexType(DataBlockIndexType.kDataBlockBinaryAndHash).setFilterPolicy(filter));
            var descriptors = List.of(
                    new ColumnFamilyDescriptor(RocksDB.DEFAULT_COLUMN_FAMILY, cfOpts),
                    new ColumnFamilyDescriptor("world_sections".getBytes(java.nio.charset.StandardCharsets.UTF_8), cfWorldSecOpts),
                    new ColumnFamilyDescriptor("id_mappings".getBytes(java.nio.charset.StandardCharsets.UTF_8), cfOpts));
            var options = own(new DBOptions()).setAvoidUnnecessaryBlockingIO(true)
                    .setIncreaseParallelism(2).setCreateIfMissing(true).setCreateMissingColumnFamilies(true)
                    .setMaxTotalWalSize(1024L*1024*128);
            try {
                this.db = own(RocksDB.open(options, path, descriptors, handles));
            } finally {
                // An unsuccessful native open may still have allocated column-family handles.
                this.closeList.addAll(handles);
            }
            this.sectionReadOps = own(new ReadOptions());
            this.sectionWriteOps = own(new WriteOptions());
            this.worldSections = handles.get(1);
            this.idMappings = handles.get(2);
            this.db.flushWal(true);
        } catch (Throwable failure) {
            closeOwned(failure);
            if (failure instanceof Error error) throw error;
            throw new RuntimeException("Could not open Voxy RocksDB storage at " + path, failure);
        }
    }

    private Throwable closeOwned(Throwable failure) {
        for (int i = this.closeList.size()-1; i >= 0; i--) {
            try {
                var resource = this.closeList.get(i);
                if (resource instanceof RocksDB database) database.closeE();
                else resource.close();
            } catch (Throwable closeFailure) {
                if (failure == null) failure = closeFailure;
                else if (failure != closeFailure) failure.addSuppressed(closeFailure);
            }
        }
        this.closeList.clear();
        return failure;
    }

    @Override
    public void iteratePositions(int level, LongConsumer consumer) {
        try (var stack = MemoryStack.stackPush()) {
            try (var iter = this.db.newIterator(this.worldSections, this.sectionReadOps)) {
                ByteBuffer keyBuff = stack.calloc(8);
                long keyBuffPtr = MemoryUtil.memAddress(keyBuff);
                //TODO: this can be optimized if needed by useing a prefix-seek https://github.com/facebook/rocksdb/wiki/Prefix-Seek

                if (level != -1) {//-1 means iterate all
                    var seekBuff = stack.calloc(8);
                    MemoryUtil.memPutLong(MemoryUtil.memAddress(seekBuff), Long.reverseBytes(Integer.toUnsignedLong(level) << 60));
                    iter.seek(seekBuff);//we seak to the first level
                } else {
                    iter.seekToFirst();
                }
                while (iter.isValid()) {
                    keyBuff.clear();
                    iter.key(keyBuff);
                    long key = Long.reverseBytes(MemoryUtil.memGetLong(keyBuffPtr));
                    if (level != -1 && WorldEngine.getLevel(key) != level) {
                        break;
                    }
                    consumer.accept(key);
                    iter.next();
                }
            }
        }
    }

    @Override
    public MemoryBuffer getSectionData(long key, MemoryBuffer scratch) {
        try (var stack = MemoryStack.stackPush()){
            var buffer = stack.malloc(8);
            //HATE JAVA HATE JAVA HATE JAVA, Long.reverseBytes()
            //THIS WILL ONLY WORK ON LITTLE ENDIAN SYSTEM AAAAAAAAA ;-;

            MemoryUtil.memPutLong(MemoryUtil.memAddress(buffer), Long.reverseBytes(swizzlePos(key)));

            var result = this.db.get(this.worldSections,
                    this.sectionReadOps,
                    buffer,
                    MemoryUtil.memByteBuffer(scratch.address, (int) (scratch.size)));

            if (result == RocksDB.NOT_FOUND) {
                return null;
            }

            return scratch.subSize(result);
        } catch (RocksDBException e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public void setSectionData(long key, MemoryBuffer data) {
        try (var stack = MemoryStack.stackPush()) {
            var keyBuff = stack.calloc(8);
            MemoryUtil.memPutLong(MemoryUtil.memAddress(keyBuff), Long.reverseBytes(swizzlePos(key)));
            this.db.put(this.worldSections, this.sectionWriteOps, keyBuff, data.asByteBuffer());
        } catch (RocksDBException e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public void deleteSectionData(long key) {
        try {
            this.db.delete(this.worldSections, longToBytes(swizzlePos(key)));
        } catch (RocksDBException e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public void putIdMapping(int id, ByteBuffer data) {
        try {
            var buffer = new byte[data.remaining()];
            data.get(buffer);
            data.rewind();
            this.db.put(this.idMappings, intToBytes(id), buffer);
        } catch (
                RocksDBException e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public Int2ObjectOpenHashMap<byte[]> getIdMappingsData() {
        var out = new Int2ObjectOpenHashMap<byte[]>();
        try (var iterator = this.db.newIterator(this.idMappings)) {
            for (iterator.seekToFirst(); iterator.isValid(); iterator.next()) {
                out.put(bytesToInt(iterator.key()), iterator.value());
            }
        }
        return out;
    }

    @Override
    public void flush() {
        try {
            this.db.flushWal(true);
        } catch (RocksDBException e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public void close() {
        if (this.closed) return;
        this.closed = true;
        Throwable failure = null;
        try { this.flush(); } catch (Throwable error) { failure = error; }
        failure = this.closeOwned(failure);
        if (failure instanceof Error error) throw error;
        if (failure != null) throw new RuntimeException("Could not close Voxy RocksDB storage", failure);
    }

    private static byte[] intToBytes(int i) {
        return new byte[] {(byte)(i>>24), (byte)(i>>16), (byte)(i>>8), (byte) i};
    }
    private static int bytesToInt(byte[] i) {
        return (Byte.toUnsignedInt(i[0])<<24)|(Byte.toUnsignedInt(i[1])<<16)|(Byte.toUnsignedInt(i[2])<<8)|(Byte.toUnsignedInt(i[3]));
    }

    private static byte[] longToBytes(long l) {
        byte[] result = new byte[Long.BYTES];
        for (int i = Long.BYTES - 1; i >= 0; i--) {
            result[i] = (byte)(l & 0xFF);
            l >>= Byte.SIZE;
        }
        return result;
    }

    private static long bytesToLong(final byte[] b) {
        long result = 0;
        for (int i = 0; i < Long.BYTES; i++) {
            result <<= Byte.SIZE;
            result |= (b[i] & 0xFF);
        }
        return result;
    }

    public static class Config extends StorageConfig {
        @Override
        public StorageBackend build(ConfigBuildCtx ctx) {
            return new RocksDBStorageBackend(ctx.ensurePathExists(ctx.substituteString(ctx.resolvePath())));
        }

        public static String getConfigTypeName() {
            return "RocksDB";
        }
    }

    private static long swizzlePos(long key) {
        if (true) {
            return key;
        }
        if (WorldEngine.POS_FORMAT_VERSION != 1) throw new IllegalStateException("TODO: UPDATE THIS");
        return  (key&(0xFL<<60)) |
                Long.expand((key>>> 4)&((1L<<24)-1), 0b01010101010101010101010101010101_001001001001001001001001L) |
                Long.expand((key>>>52)&0xFF,         0b00000000000000000000000000000000_100100100100100100100100L) |
                Long.expand((key>>>28)&((1L<<24)-1), 0b10101010101010101010101010101010_010010010010010010010010L);
    }
}
