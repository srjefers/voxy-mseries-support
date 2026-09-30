package me.cortex.voxy.common.config.compressors;

import me.cortex.voxy.common.util.MemoryBuffer;

public interface StorageCompressor {
    /** Borrowed thread-local output, valid until the next compress call on this thread; do not free. */
    MemoryBuffer compress(MemoryBuffer saveData);

    /** Borrowed thread-local output, valid until the next decompress call on this thread; do not free. */
    MemoryBuffer decompress(MemoryBuffer saveData);

    void close();
}
