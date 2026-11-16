package me.cortex.voxy.client.mixin.minecraft;

import net.minecraft.client.MouseHandler;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * onMove keeps accumulating deltas while a screen is open. If a screen is closed by a key press in
 * the same frame as fast mouse motion, grabMouse() clears the screen and handleAccumulatedMovement()
 * then feeds the motion made inside the GUI to turnPlayer, so the camera jerks after closing.
 * Drop whatever was accumulated before the grab.
 */
@Mixin(MouseHandler.class)
public class MixinMouseHandlerGrab {
    @Shadow private double accumulatedDX;
    @Shadow private double accumulatedDY;

    @Inject(method = "grabMouse", at = @At("TAIL"))
    private void voxy$dropGuiMotion(CallbackInfo ci) {
        this.accumulatedDX = 0;
        this.accumulatedDY = 0;
    }
}
