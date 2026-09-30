package me.cortex.voxy.common;

import me.cortex.voxy.common.config.Serialization;
import me.cortex.voxy.common.config.compressors.ZSTDCompressor;
import me.cortex.voxy.common.config.section.SectionSerializationStorage;
import me.cortex.voxy.common.config.storage.other.CompressionStorageAdaptor;
import me.cortex.voxy.common.config.storage.rocksdb.RocksDBStorageBackend;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.function.Predicate;
import java.util.function.Supplier;

public class StorageConfigUtil {

    public static <T> T getCreateStorageConfig(Class<T> type, Predicate<T> verifier,
                                              Supplier<T> defaultConfig, Path path) {
        Path json = path.resolve("config.json");
        try {
            Files.createDirectories(path);
            if (Files.exists(json)) {
                T config = Serialization.GSON.fromJson(Files.readString(json), type);
                if (config == null || !verifier.test(config)) {
                    throw new IllegalStateException("Invalid Voxy storage configuration: " + json);
                }
                return config; // Preserve custom settings and unknown fields byte-for-byte.
            }
            T config = defaultConfig.get();
            if (config == null || !verifier.test(config)) throw new IllegalStateException("Invalid default storage configuration");
            Path temporary = Files.createTempFile(path, ".voxy-config-", ".tmp");
            try {
                Files.writeString(temporary, Serialization.GSON.toJson(config));
                Files.move(temporary, json); // Never replace a concurrently created config.
            } finally { Files.deleteIfExists(temporary); }
            return config;
        } catch (Exception error) {
            throw new IllegalStateException("Could not read/create Voxy storage configuration at " + json
                    + "; existing configuration was preserved", error);
        }
    }

    public static SectionSerializationStorage.Config createDefaultSerializer() {
        //Create the default config
        var baseDB = new RocksDBStorageBackend.Config();

        var compressor = new ZSTDCompressor.Config();
        compressor.compressionLevel = 1;

        var compression = new CompressionStorageAdaptor.Config();
        compression.delegate = baseDB;
        compression.compressor = compressor;

        var serializer = new SectionSerializationStorage.Config();
        serializer.storage = compression;

        return serializer;
    }
}
