package me.cortex.voxy.client.mixin.flywheel;

import com.mojang.blaze3d.systems.RenderSystem;
import me.cortex.voxy.client.mixin.minecraft.AccessorGlCommandEncoder;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Flywheel draws through GlStateManager and its reset() leaves depth testing
 * DISABLED. Since 1.21.5 GlCommandEncoder skips applyPipelineState when the
 * next draw uses the same RenderPipeline as the last one, so whatever MC draws
 * next with that pipeline (clouds, particles, items) renders with no depth
 * test and shows through walls. Forgetting the cached pipeline forces MC to
 * re-apply its full state on the next draw.
 */
@Pseudo
@Mixin(targets = {
        "com.zurrtum.create.client.flywheel.backend.engine.MaterialRenderState",
        "dev.engine_room.flywheel.backend.engine.MaterialRenderState"
}, remap = false)
public class MixinFlwMaterialRenderState {
    @Inject(method = "reset", at = @At("TAIL"), require = 0)
    private static void voxy$forgetMcPipeline(CallbackInfo ci) {
        if (RenderSystem.getDevice().createCommandEncoder() instanceof AccessorGlCommandEncoder enc) {
            enc.voxy$setLastPipeline(null);
        }
    }
}
