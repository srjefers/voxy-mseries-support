package me.cortex.voxy.client.core.util;

import me.cortex.voxy.client.core.VoxyRenderSystem;
import me.cortex.voxy.client.core.rendering.Viewport;
import net.caffeinemc.mods.sodium.client.render.chunk.ChunkRenderMatrices;
import net.caffeinemc.mods.sodium.client.util.FogParameters;
import net.fabricmc.loader.api.FabricLoader;
import net.irisshaders.iris.Iris;
import net.irisshaders.iris.api.v0.IrisApi;
import net.irisshaders.iris.gl.IrisRenderSystem;
import net.irisshaders.iris.shadows.ShadowRenderer;

public class IrisUtil {
    public record CapturedViewportParameters(ChunkRenderMatrices matrices, FogParameters parameters, double x, double y, double z) {
        public Viewport<?> apply(VoxyRenderSystem vrs) {
            return vrs.setupViewport(this.matrices, this.parameters, this.x, this.y, this.z);
        }
    }

    public static CapturedViewportParameters CAPTURED_VIEWPORT_PARAMETERS;

    public static final boolean IRIS_INSTALLED = FabricLoader.getInstance().isModLoaded("iris");
    public static final boolean SHADER_SUPPORT = true;//System.getProperty("voxy.enableExperimentalIrisPipeline", "false").equalsIgnoreCase("true");


    private static boolean irisShadowActive0() {
        return ShadowRenderer.ACTIVE;
    }

    public static boolean irisShadowActive() {
        return IRIS_INSTALLED && irisShadowActive0();
    }

    private static boolean shadowsBeingRendered0() {
        return net.irisshaders.iris.shadows.ShadowRenderingState.areShadowsCurrentlyBeingRendered();
    }

    /**
     * True while Iris is inside its shadow-map render. Sodium's
     * {@code DefaultChunkRenderer.render(SOLID)} RE-ENTERS during that pass,
     * so the Metal LOD render + gbuffer inject must be skipped there (the
     * Metal pipeline must run exactly once per frame, and the inject targets
     * the gbuffer, not the shadow FB). Iris-absent safe.
     */
    public static boolean shadowsBeingRendered() {
        return IRIS_INSTALLED && shadowsBeingRendered0();
    }

    public static void clearIrisSamplers() {
        if (IRIS_INSTALLED) clearIrisSamplers0();
    }

    private static void clearIrisSamplers0() {
        for (int i = 0; i < 16; i++) {
            IrisRenderSystem.bindSamplerToUnit(i, 0);
        }
    }

    private static boolean irisShaderPackEnabled0() {
        return Iris.isPackInUseQuick();
    }

    public static boolean irisShaderPackEnabled() {
        return IRIS_INSTALLED && irisShaderPackEnabled0();
    }

    /**
     * Iris + Metal coexistence: inject the LOD bridge directly into the
     * pack's terrain gbuffer (IrisGbufferInjector) instead of compositing
     * into MC's main RT, which Iris's final image overwrites. Supersedes the
     * failed VOXY_IRIS_LOD_EXPERIMENT late-composite attempts (both the
     * END_MAIN and HUD-time hooks regressed on-device). DEFAULT ON;
     * VOXY_IRIS_GBUFFER_INJECT=0 is the kill switch (LODs then stay hidden
     * under an active pack — the pre-injection behaviour).
     */
    private static final boolean IRIS_GBUFFER_INJECT =
            !"0".equals(System.getenv("VOXY_IRIS_GBUFFER_INJECT"));

    /** True when the Metal LOD output should be injected into Iris's gbuffer this frame. */
    public static boolean irisGbufferInjectMode() {
        return IRIS_GBUFFER_INJECT && IRIS_INSTALLED && irisShaderPackEnabled();
    }

    /**
     * Native vx contract on Metal (milestone issue #9): active when the
     * current pack ships voxy.json (IrisShaderPatch parsed it into pipeline
     * data — that's the "pack supports Voxy natively" signal). Takes
     * precedence over {@link #irisGbufferInjectMode()}; packs WITHOUT the
     * contract fall through to the injection path. VOXY_METAL_VX_CONTRACT=0
     * is the kill switch.
     */
    private static final boolean METAL_VX_CONTRACT =
            !"0".equals(System.getenv("VOXY_METAL_VX_CONTRACT"));

    public static boolean vxContractActive() {
        if (!METAL_VX_CONTRACT || !IRIS_INSTALLED || !irisShaderPackEnabled()) {
            return false;
        }
        return vxContractActive0();
    }

    private static boolean vxContractActive0() {
        var pipeline = net.irisshaders.iris.Iris.getPipelineManager().getPipelineNullable();
        return pipeline instanceof me.cortex.voxy.client.iris.IGetIrisVoxyPipelineData d
                && d.voxy$getPipelineData() != null;
    }
    public static void disableIrisShaders() {
        if(IRIS_INSTALLED) disableIrisShaders0();
    }
    private static void disableIrisShaders0() {
        IrisApi.getInstance().getConfig().setShadersEnabledAndApply(false);//Disable shaders
    }
}
