package me.cortex.voxy.client.core.model;

import net.minecraft.client.renderer.texture.SpriteContents;

/** NativeImage RGBA interpolation with the fluid renderer's baked alpha multiplier. */
public final class WaterAnimationFrames {
    private WaterAnimationFrames() {}
    public record Position(int frame, int next, float fraction) {}

    public static Position position(SpriteContents.AnimationState state) {
        int frame = state.frame;
        var frames = state.animationInfo.frames;
        int next = (frame + 1) % frames.size();
        return new Position(frame, next, (float)state.subFrame / Math.max(1, frames.get(frame).time()));
    }

    public static int blend(int current, int next, float fraction, float opacity) {
        float weight = Math.max(0, Math.min(1, fraction));
        int result = 0;
        for (int channel = 0; channel < 4; channel++) {
            int shift = channel * 8;
            float a = (current >>> shift) & 255, b = (next >>> shift) & 255;
            float value = a + (b - a) * weight;
            int encoded = channel == 3 ? Math.round(value * Math.max(0, Math.min(1, opacity))) : (int)value;
            result |= Math.min(255, encoded) << shift;
        }
        return result;
    }
}
