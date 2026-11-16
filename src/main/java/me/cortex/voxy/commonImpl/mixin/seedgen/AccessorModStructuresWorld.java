package me.cortex.voxy.commonImpl.mixin.seedgen;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.gen.Invoker;

@Pseudo
@Mixin(targets = "mobius.voxyseedgen.terrain.ModStructures$World", remap = false)
public interface AccessorModStructuresWorld {
    @Invoker("key")
    static long voxy$key(int set, int regionX, int regionZ) {
        throw new AssertionError();
    }
}
