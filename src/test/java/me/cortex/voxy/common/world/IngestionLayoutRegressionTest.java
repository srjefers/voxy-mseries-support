package me.cortex.voxy.common.world;

import me.cortex.voxy.common.config.section.SectionSerializationStorage;
import me.cortex.voxy.common.config.storage.inmemory.MemoryStorageBackend;
import me.cortex.voxy.common.voxelization.VoxelizedSection;
import me.cortex.voxy.common.voxelization.WorldConversionFactory;
import java.util.Arrays;

/** Behavior baseline for the upstream mip/insertion extraction; expected coordinates are computed independently. */
public final class IngestionLayoutRegressionTest {
    public static void main(String[] args) throws Exception {
        net.minecraft.SharedConstants.tryDetectVersion(); net.minecraft.server.Bootstrap.bootStrap();
        for (int octant = 0; octant < 8; octant++) {
            var engine = new WorldEngine(new SectionSerializationStorage(new MemoryStorageBackend(1)));
            var input = VoxelizedSection.createEmpty();
            input.x = -4 + (octant & 1); input.y = -6 + (octant >> 2); input.z = 8 + ((octant >> 1) & 1);
            input.lvl0NonAirCount = 4096;
            for (int i = 0; i < input.section.length; i++) input.section[i] = (long)(i + 1) << 27;
            var held = new WorldSection[5];
            for (int level = 0; level < 5; level++) held[level] = engine.acquire(level, input.x >> (level + 1), input.y >> (level + 1), input.z >> (level + 1));
            WorldUpdater.insertUpdate(engine, input);
            for (int level = 0; level <= 4; level++) {
                var output = held[level];
                int width = 16 >> level, mask = (1 << (level + 1)) - 1;
                int bx = (input.x & mask) * width, by = (input.y & mask) * width, bz = (input.z & mask) * width;
                int base = VoxelizedSection.getBaseIndexForLevel(level);
                for (int y = 0; y < width; y++) for (int z = 0; z < width; z++) for (int x = 0; x < width; x++) {
                    int source = base + (y * width + z) * width + x;
                    int destination = ((by + y) * 32 + bz + z) * 32 + bx + x;
                    if (output.data[destination] != input.section[source]) throw new AssertionError("wrong insert coordinates at level " + level);
                }
                output.setNotDirty(); output.release();
            }
            engine.free();
        }
        System.out.println("PASS: eight octants and all five insertion levels preserve coordinates");
        var input = VoxelizedSection.createEmpty(); Arrays.fill(input.section, 0, 4096, 0xa5L << 56);
        WorldConversionFactory.mipSection(input, null);
        for (long voxel : input.section) if (voxel != (0xa5L << 56)) throw new AssertionError("mip extraction changed light/layout");
        System.out.println("PASS: all four mip levels preserve independent light nibbles and layout");
    }
}
