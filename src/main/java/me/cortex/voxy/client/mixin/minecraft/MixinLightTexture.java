package me.cortex.voxy.client.mixin.minecraft;

import me.cortex.voxy.client.core.rendering.util.McLightmapVersion;
import net.minecraft.client.renderer.LightTexture;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Bumps {@link McLightmapVersion} whenever MC actually rewrites its lightmap.
 *
 * {@code updateLightTexture(float)} early-returns unless {@code tick()}
 * armed the update flag (and a level is loaded), so only the method's final
 * RETURN — the TAIL — follows a real render into the lightmap texture.
 * Injecting there gives an exact "the texture changed" signal at client-tick
 * rate that the Metal lightmap mirror gates its GL readback on. Pure
 * counter increment: no behavioural effect on MC or the GL backend.
 */
@Mixin(LightTexture.class)
public class MixinLightTexture {
    @Inject(method = "updateLightTexture", at = @At("TAIL"))
    private void voxy$bumpLightmapVersion(float partialTick, CallbackInfo ci) {
        McLightmapVersion.bump();
    }
}
