package me.cortex.voxy.client.mixin.iris;

import com.google.common.collect.ImmutableList;
import me.cortex.voxy.client.config.VoxyConfig;
import me.cortex.voxy.client.core.util.IrisUtil;
import me.cortex.voxy.client.iris.VxFogCap;
import net.irisshaders.iris.shaderpack.include.AbsolutePackPath;
import net.irisshaders.iris.shaderpack.include.IncludeProcessor;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Every program source Iris compiles (deferred1 included) flows through
 * {@link IncludeProcessor#getIncludedFile} as include-processed lines — the
 * one choke point where the pack's deferred1 text can be adjusted before
 * compilation. Used for the vx fog cap (see {@link VxFogCap}): the patch is
 * content-anchored (only sources containing the {@code vxZ < 1.0} branch),
 * so every other file passes through untouched. The processor's internal
 * cache keeps the ORIGINAL lines (we only substitute the returned value), so
 * the patch re-applies per lookup — pack-load-time only, never per frame.
 */
@Mixin(value = IncludeProcessor.class, remap = false)
public class MixinIncludeProcessor {
    @Inject(method = "getIncludedFile", at = @At("RETURN"), cancellable = true)
    private void voxy$applyVxFogCap(AbsolutePackPath path,
                                    CallbackInfoReturnable<ImmutableList<String>> cir) {
        if (!VoxyConfig.CONFIG.isRenderingEnabled() || !IrisUtil.SHADER_SUPPORT) {
            return;
        }
        if (VxFogCap.enabled()) {
            ImmutableList<String> patched = VxFogCap.patchDeferred(cir.getReturnValue(),
                    String.valueOf(path));
            if (patched != null) {
                cir.setReturnValue(patched);
            }
        }
        // 2026-07-16 round-16 pane fix (see VxSsrMaskFix): reflectionMask +
        // raytrace vx-substitute guard. Independent line targets — safe to
        // stack after the fog cap on the same source.
        if (me.cortex.voxy.client.iris.VxSsrMaskFix.enabled()) {
            ImmutableList<String> fixed = me.cortex.voxy.client.iris.VxSsrMaskFix.patch(
                    cir.getReturnValue(), String.valueOf(path));
            if (fixed != null) {
                cir.setReturnValue(fixed);
            }
        }
        // 2026-09-25 far-LOD tufts: LOD SSAO tuning parity with vanilla terrain
        // (deferred.glsl vx branch radius 1.5 / pow 2 -> 0.25 / 1). Own line
        // targets; stacks after the two patches above.
        if (me.cortex.voxy.client.iris.VxLodAoParity.enabled()) {
            ImmutableList<String> ao = me.cortex.voxy.client.iris.VxLodAoParity.patch(
                    cir.getReturnValue(), String.valueOf(path));
            if (ao != null) {
                cir.setReturnValue(ao);
            }
        }
    }
}
