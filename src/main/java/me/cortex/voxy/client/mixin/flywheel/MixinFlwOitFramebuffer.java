package me.cortex.voxy.client.mixin.flywheel;

import me.cortex.voxy.common.Logger;
import org.lwjgl.opengl.GL11;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

/**
 * Flywheel's OIT resolve passes write gl_FragDepth into the main depth buffer with GL_ALWAYS.
 * When the reconstructed depth is wrong (seen with lava in Create pipes on macOS), it pushes depth
 * past walls and everything drawn afterwards (entities, particles, clouds) shows through blocks.
 * LEQUAL keeps the occlusion they add but can never move depth farther than what is already there.
 */
@Pseudo
@Mixin(targets = {"com.zurrtum.create.client.flywheel.backend.engine.indirect.OitFramebuffer",
        "dev.engine_room.flywheel.backend.engine.indirect.OitFramebuffer"}, remap = false)
public class MixinFlwOitFramebuffer {
    private static boolean voxy$logged;

    @ModifyArg(method = {"renderDepthFromTransmittance", "composite"},
            at = @At(value = "INVOKE", target = "Lcom/mojang/blaze3d/opengl/GlStateManager;_depthFunc(I)V"),
            require = 0)
    private int voxy$neverPushDepthBack(int func) {
        if (!voxy$logged) { voxy$logged = true; Logger.info("Flywheel OIT depth write clamped to LEQUAL"); }
        return func == GL11.GL_ALWAYS ? GL11.GL_LEQUAL : func;
    }
}
