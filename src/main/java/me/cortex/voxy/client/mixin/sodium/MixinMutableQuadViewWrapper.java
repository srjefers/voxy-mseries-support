package me.cortex.voxy.client.mixin.sodium;

import net.caffeinemc.mods.sodium.client.render.frapi.wrapper.MutableQuadViewWrapper;
import net.caffeinemc.mods.sodium.client.render.frapi.wrapper.QuadViewWrapper;
import net.caffeinemc.mods.sodium.client.render.model.MutableQuadViewImpl;
import net.fabricmc.fabric.api.renderer.v1.mesh.QuadEmitter;
import net.fabricmc.fabric.api.renderer.v1.mesh.QuadView;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Sodium 0.8.1's copyFrom casts its argument straight to QuadViewImpl, but quads handed
 * out by Sodium's own MutableMesh.forEach are QuadViewWrappers, so mods that re-emit a
 * mesh (Terrain Slabs' SlabOffsetModel) crash chunk meshing with a ClassCastException.
 * Unwrap first; this fork is pinned to 0.8.1 so the upstream fix in 0.8.2+ isn't available.
 */
@Mixin(value = MutableQuadViewWrapper.class, remap = false)
public class MixinMutableQuadViewWrapper {
    @Shadow @Final private MutableQuadViewImpl mutableQuad;

    @Inject(method = "copyFrom(Lnet/fabricmc/fabric/api/renderer/v1/mesh/QuadView;)Lnet/fabricmc/fabric/api/renderer/v1/mesh/QuadEmitter;",
            at = @At("HEAD"), cancellable = true)
    private void voxy$unwrapCopyFrom(QuadView quad, CallbackInfoReturnable<QuadEmitter> cir) {
        if (quad instanceof QuadViewWrapper wrapper) {
            this.mutableQuad.copyFrom(((AccessorQuadViewWrapper) wrapper).getQuad());
            cir.setReturnValue((QuadEmitter) this);
        }
    }
}
