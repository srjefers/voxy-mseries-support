package me.cortex.voxy.client.mixin.minecraft;

import com.mojang.blaze3d.opengl.GlCommandEncoder;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(GlCommandEncoder.class)
public interface AccessorGlCommandEncoder {
    @Accessor("lastPipeline")
    void voxy$setLastPipeline(RenderPipeline pipeline);
}
