package me.cortex.voxy.commonImpl.mixin.seedgen;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.gen.Accessor;

@Pseudo
@Mixin(targets = "mobius.voxyseedgen.terrain.ModStructures$Set", remap = false)
public interface AccessorModStructuresSet {
    @Accessor("index")
    int voxy$getIndex();

    @Accessor("reach")
    int voxy$getReach();
}
