package me.cortex.voxy.common.world;

import me.cortex.voxy.client.VoxyClientInstance;
import java.nio.file.Files;
import java.nio.file.Path;

public final class StorageConfigRegressionTest {
    public static void main(String[] args) throws Exception {
        net.minecraft.SharedConstants.tryDetectVersion(); net.minecraft.server.Bootstrap.bootStrap();
        // Install the real production adapters for the defaults, without requiring a Fabric launch.
        Class<?> adapter = Class.forName("me.cortex.voxy.common.config.Serialization$GsonConfigSerialization");
        var constructor=adapter.getDeclaredConstructor(Class.class);constructor.setAccessible(true);
        var register=adapter.getDeclaredMethod("register",String.class,Class.class);register.setAccessible(true);
        var gson=new com.google.gson.GsonBuilder();
        for(var entry:java.util.Map.of(
                me.cortex.voxy.common.config.section.SectionStorageConfig.class, java.util.List.of(me.cortex.voxy.common.config.section.SectionSerializationStorage.Config.class),
                me.cortex.voxy.common.config.storage.StorageConfig.class, java.util.List.of(me.cortex.voxy.common.config.storage.rocksdb.RocksDBStorageBackend.Config.class,me.cortex.voxy.common.config.storage.other.CompressionStorageAdaptor.Config.class),
                me.cortex.voxy.common.config.compressors.CompressorConfig.class, java.util.List.of(me.cortex.voxy.common.config.compressors.ZSTDCompressor.Config.class)).entrySet()) {
            Object factory=constructor.newInstance(entry.getKey());
            for(Class<?> config:entry.getValue()) register.invoke(factory,config.getMethod("getConfigTypeName").invoke(null),config);
            gson.registerTypeAdapterFactory((com.google.gson.TypeAdapterFactory)factory);
        }
        me.cortex.voxy.common.config.Serialization.GSON=gson.create();
        Path root=Files.createTempDirectory("voxy-storage-config-");
        for(String original:new String[]{"{not valid JSON", "{\"version\":1,\"sectionStorageConfig\":null}"}) {
            Path directory=Files.createTempDirectory(root,"invalid-");
            Path config=directory.resolve("config.json");Files.writeString(config,original);
            boolean rejected=false;
            try { VoxyClientInstance.getCreateStorageConfig(directory); }
            catch(IllegalStateException expected) { rejected=true; }
            if(!rejected || !Files.readString(config).equals(original)) throw new AssertionError("invalid custom storage config was replaced");
        }
        Path fresh=root.resolve("fresh");
        if(VoxyClientInstance.getCreateStorageConfig(fresh)==null) throw new AssertionError("fresh storage has no defaults");
        String valid=Files.readString(fresh.resolve("config.json"));
        if(VoxyClientInstance.getCreateStorageConfig(fresh)==null || !valid.equals(Files.readString(fresh.resolve("config.json")))) throw new AssertionError("reopening valid storage changed configuration");
        System.out.println("PASS: invalid storage configuration fails without replacing it; fresh and existing defaults reopen");
    }
}
