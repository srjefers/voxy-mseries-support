package me.cortex.voxy.client.mixin.minecraft;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import me.cortex.voxy.client.core.rendering.clouds.LodClouds;
import net.minecraft.client.renderer.CloudRenderer;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.util.profiling.ProfilerFiller;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.Optional;

/**
 * Vanilla cloud mesh hooks for the LOD cloud layer ({@link LodClouds}). Both are
 * no-ops unless the layer runs this frame (never on the GL backend).
 */
@Mixin(CloudRenderer.class)
public abstract class MixinCloudRenderer {
    @Shadow private boolean needsRebuild;

    @Unique private int voxy$lastMeshChunks = -1;

    /** The layer marches the same cloud cells the mesh is built from. */
    @Inject(method = "apply(Ljava/util/Optional;Lnet/minecraft/server/packs/resources/ResourceManager;Lnet/minecraft/util/profiling/ProfilerFiller;)V",
            at = @At("TAIL"))
    private void voxy$captureCloudTexture(Optional<CloudRenderer.TextureData> data, ResourceManager resourceManager,
                                          ProfilerFiller profiler, CallbackInfo ci) {
        LodClouds.onTextureReloaded(data.orElse(null));
    }

    /**
     * The Cloud Distance the mesh is built with ({@code options.cloudRange().get()},
     * the only {@code OptionInstance.get} in {@code render}). While the layer runs, the
     * mesh must reach past the vanilla terrain (see {@link LodClouds#meshRangeChunks}).
     * The mesh is only rebuilt when the camera changes cell, so a new radius asks for
     * a rebuild.
     */
    @ModifyExpressionValue(method = "render",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/client/OptionInstance;get()Ljava/lang/Object;"))
    private Object voxy$lodCloudMeshRange(Object optionChunks) {
        int chunks = LodClouds.meshRangeChunks((Integer) optionChunks);
        if (this.voxy$lastMeshChunks != -1 && chunks != this.voxy$lastMeshChunks) {
            this.needsRebuild = true;
        }
        this.voxy$lastMeshChunks = chunks;
        return chunks;
    }
}
