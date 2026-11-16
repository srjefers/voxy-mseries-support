package me.cortex.voxy.client.mixin.sodium;

import net.caffeinemc.mods.sodium.client.render.frapi.wrapper.QuadViewWrapper;
import net.caffeinemc.mods.sodium.client.render.model.QuadViewImpl;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(value = QuadViewWrapper.class, remap = false)
public interface AccessorQuadViewWrapper {
    @Accessor
    QuadViewImpl getQuad();
}
