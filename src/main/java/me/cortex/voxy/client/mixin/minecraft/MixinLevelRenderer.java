package me.cortex.voxy.client.mixin.minecraft;

import me.cortex.voxy.client.VoxyClientInstance;
import me.cortex.voxy.client.config.VoxyConfig;
import me.cortex.voxy.client.core.IGetVoxyRenderSystem;
import me.cortex.voxy.client.core.VoxyRenderSystem;
import me.cortex.voxy.client.core.rendering.clouds.LodClouds;
import me.cortex.voxy.client.core.util.IrisUtil;
import me.cortex.voxy.common.Logger;
import me.cortex.voxy.common.world.WorldEngine;
import me.cortex.voxy.commonImpl.VoxyCommon;
import me.cortex.voxy.commonImpl.WorldIdentifier;
import com.mojang.blaze3d.framegraph.FrameGraphBuilder;
import net.minecraft.client.CloudStatus;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(LevelRenderer.class)
public abstract class MixinLevelRenderer implements IGetVoxyRenderSystem {
    @Shadow private @Nullable ClientLevel level;
    @Unique private VoxyRenderSystem renderer;

    @Override
    public VoxyRenderSystem getVoxyRenderSystem() {
        return this.renderer;
    }

    @Inject(method = "allChanged()V", at = @At("RETURN"), order = 900)//We want to inject before sodium
    private void reloadVoxyRenderer(CallbackInfo ci) {
        this.shutdownRenderer();
        if (this.level != null) {
            this.createRenderer();
        }
    }

    @Inject(method = "setLevel", at = @At("HEAD"))
    private void voxy$captureSetWorld(ClientLevel world, CallbackInfo ci) {
        if (this.level != world) {
            this.shutdownRenderer();
        }
    }

    @Inject(method = "close", at = @At("HEAD"))
    private void injectClose(CallbackInfo ci) {
        this.shutdownRenderer();
    }

    @Override
    public void shutdownRenderer() {
        if (this.renderer != null) {
            this.renderer.shutdown();
            this.renderer = null;
        }
    }

    @Override
    public void createRenderer() {
        if (this.renderer != null) throw new IllegalStateException("Cannot have multiple renderers");
        if (!VoxyConfig.CONFIG.isRenderingEnabled()) {
            Logger.info("Not creating renderer due to disabled");
            return;
        }
        if (this.level == null) {
            Logger.error("Not creating renderer due to null world");
            return;
        }
        var instance = (VoxyClientInstance)VoxyCommon.getInstance();
        if (instance == null) {
            Logger.error("Not creating renderer due to null instance");
            return;
        }
        WorldEngine world = WorldIdentifier.ofEngine(this.level);
        if (world == null) {
            Logger.error("Null world selected");
            return;
        }
        try {
            this.renderer = new VoxyRenderSystem(world, instance.getServiceManager());
        } catch (RuntimeException e) {
            if (IrisUtil.irisShaderPackEnabled()) {
                //Fork (Metal): disable the pack for this session only instead of saving the disable;
                // VOXY_IRIS_DISABLE_PERSIST=1 restores upstream's saved disable. GL is unchanged.
                if (me.cortex.voxy.client.core.gpu.RenderBackendFactory.get().getType()
                        != me.cortex.voxy.client.core.gpu.BackendType.OPENGL
                        && !"1".equals(System.getenv("VOXY_IRIS_DISABLE_PERSIST"))) {
                    IrisUtil.disableIrisShadersForSession(e);
                } else {
                    IrisUtil.disableIrisShaders();
                }
            } else {
                throw e;
            }
        }
        instance.updateDedicatedThreads();
    }

    /**
     * LOD cloud layer: the arguments vanilla draws this frame's clouds with. The
     * clouds pass is added while the frame graph is built, so this runs before the
     * graph executes Sodium's SOLID pass, where the Metal LOD frame draws the layer.
     * Only stores values; nothing is drawn differently here.
     */
    @Inject(method = "addCloudsPass", at = @At("HEAD"))
    private void voxy$captureCloudsPass(FrameGraphBuilder frameGraphBuilder, CloudStatus status, Vec3 cameraPos,
                                        long gameTime, float partialTick, int cloudColor, float cloudHeight,
                                        CallbackInfo ci) {
        LodClouds.captureFrame(status, cameraPos, gameTime, partialTick, cloudColor, cloudHeight);
    }
}
