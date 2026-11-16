package me.cortex.voxy.common.voxelization;

import me.cortex.voxy.common.world.other.Mapper;

// Upstream moved mipSection here; addons (Voxy SeedGen) link against this name.
public class WorldVoxilizedSectionMipper {
    public static void mipSection(VoxelizedSection section, Mapper mapper) {
        WorldConversionFactory.mipSection(section, mapper);
    }
}
