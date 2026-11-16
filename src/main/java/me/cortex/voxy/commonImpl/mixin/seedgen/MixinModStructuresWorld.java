package me.cortex.voxy.commonImpl.mixin.seedgen;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import me.cortex.voxy.commonImpl.SeedGenDeferredLayout;
import me.cortex.voxy.commonImpl.SeedGenLayoutLocks;
import org.objectweb.asm.Opcodes;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Lets Voxy SeedGen (0.2.0) lay out structure regions in parallel. consider() checks its ConcurrentHashMap of solved
 * regions, then re-checks and solves inside synchronized(layout), one lock for the whole world. The state that solving
 * touches is already concurrent (solved, captures, unread; vanilla jigsaw layout runs on many worldgen threads), and
 * solve() never re-enters consider(), so a stripe per region key keeps the double check without serialising regions.
 * collect() also skips regions another worker is laying out until its other regions are done, see SeedGenDeferredLayout.
 */
@Pseudo
@Mixin(targets = "mobius.voxyseedgen.terrain.ModStructures$World", remap = false)
public class MixinModStructuresWorld {
    @Shadow @Final private ConcurrentHashMap<Long, ?> solved;

    @ModifyExpressionValue(method = "consider",
            at = @At(value = "FIELD", target = "Lmobius/voxyseedgen/terrain/ModStructures$World;layout:Ljava/lang/Object;", opcode = Opcodes.GETFIELD))
    private Object voxy$lockPerRegion(Object layout, @Local(ordinal = 0) long key) {
        return SeedGenLayoutLocks.forRegion(key);
    }

    // solve() raises a set's reach with an unguarded check-then-write, which was safe only under the single lock
    @WrapOperation(method = "solve",
            at = @At(value = "FIELD", target = "Lmobius/voxyseedgen/terrain/ModStructures$Set;reach:I", opcode = Opcodes.PUTFIELD))
    private void voxy$keepWidestReach(@Coerce Object set, int reach, Operation<Void> original) {
        synchronized (set) {
            if (reach > ((AccessorModStructuresSet) set).voxy$getReach()) {
                original.call(set, reach);
            }
        }
    }

    @Inject(method = "collect", at = @At("HEAD"))
    private void voxy$beginCollect(CallbackInfo ci) {
        SeedGenDeferredLayout.begin();
    }

    // consider(sampler, set, regionX, regionZ, x, y, z, placements) keys its region as key(set.index, regionX, regionZ)
    @WrapOperation(method = "collect",
            at = @At(value = "INVOKE", target = "Lmobius/voxyseedgen/terrain/ModStructures$World;consider(Lmobius/voxyseedgen/sample/SeedColumnSampler;Lmobius/voxyseedgen/terrain/ModStructures$Set;IIIIILjava/util/List;)V"))
    private void voxy$skipBusyRegion(@Coerce Object world, @Coerce Object sampler, @Coerce Object set, int regionX, int regionZ,
                                     int x, int y, int z, List<Object> placements, Operation<Void> original) {
        long key = AccessorModStructuresWorld.voxy$key(((AccessorModStructuresSet) set).voxy$getIndex(), regionX, regionZ);
        SeedGenDeferredLayout.consider(world, this.solved, key, original, new Object[]{world, sampler, set, regionX, regionZ, x, y, z, placements});
    }

    @Inject(method = "collect", at = @At("RETURN"))
    private void voxy$finishCollect(CallbackInfo ci, @Local(argsOnly = true) List<Object> placements) {
        SeedGenDeferredLayout.finish(placements);
    }
}
