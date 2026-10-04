package me.cortex.voxy.client.core.util;

import me.cortex.voxy.client.core.VoxyRenderSystem;
import me.cortex.voxy.client.core.rendering.Viewport;
import net.caffeinemc.mods.sodium.client.render.chunk.ChunkRenderMatrices;
import net.caffeinemc.mods.sodium.client.util.FogParameters;
import net.fabricmc.loader.api.FabricLoader;
import net.irisshaders.iris.Iris;
import me.cortex.voxy.common.Logger;
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

    /**
     * Upstream 1952d3df swapped {@code Iris.isPackInUseQuick()} (the live pipeline is an
     * IrisRenderingPipeline) for {@code Iris.getCurrentPack().isPresent()} (a pack is loaded).
     * On the GL backend the fork keeps pure upstream ("current"): the GL path must match upstream.
     * On Metal the predicate also selects gbuffer-inject vs IOSurface composite, so the pure form
     * would drop the LOD frame whenever a pack is loaded but Iris runs its vanilla fallback pipeline
     * (pack compile failure); the Metal default is "hybrid": a pack is loaded AND the live pipeline
     * is either not built yet (null during pipeline recreation, the window upstream fixed) or an
     * IrisRenderingPipeline. VOXY_IRIS_PACK_PREDICATE=hybrid|quick|current overrides on both
     * backends (quick = pre-sync). Resolved on first use, after the render backend exists.
     */
    private static volatile String packPredicate;

    private static String packPredicate() {
        String mode = packPredicate;
        if (mode == null) {
            String v = System.getenv("VOXY_IRIS_PACK_PREDICATE");
            boolean gl = me.cortex.voxy.client.core.gpu.RenderBackendFactory.get().getType()
                    == me.cortex.voxy.client.core.gpu.BackendType.OPENGL;
            mode = (v == null || v.isBlank()) ? (gl ? "current" : "hybrid") : v.trim().toLowerCase(java.util.Locale.ROOT);
            if (!mode.equals("hybrid") && !mode.equals("quick") && !mode.equals("current")) mode = gl ? "current" : "hybrid";
            packPredicate = mode;
            me.cortex.voxy.common.Logger.info("[Voxy-SYNC] Iris pack predicate: " + mode + " (upstream 1952d3df; GL default current, Metal default hybrid; VOXY_IRIS_PACK_PREDICATE=quick restores pre-sync)");
        }
        return mode;
    }

    private static boolean irisShaderPackEnabled0() {
        switch (packPredicate()) {
            case "quick": return Iris.isPackInUseQuick();
            case "current": return Iris.getCurrentPack().isPresent();
            default: {
                if (Iris.getCurrentPack().isEmpty()) return false;
                var pipeline = Iris.getPipelineManager().getPipelineNullable();
                return pipeline == null || pipeline instanceof net.irisshaders.iris.pipeline.IrisRenderingPipeline;
            }
        }
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

    /**
     * Fork (Metal): like {@link #disableIrisShaders()}, but without saving the disable. Iris's only public
     * disable (setShadersEnabledAndApply) saves enableShaders=false and then reloads, and the reload
     * re-reads the file, so the save cannot be skipped. The finally blocks put the user's saved value
     * back on disk once the reload is done, including when the nested renderer rebuild inside that
     * reload throws, and leave the in-memory state off. Before this a single renderer failure with a pack
     * active (the A11 test knob, a shader transpile error) silently turned the pack off for every later
     * launch. Iris re-reads the file on its own reloads, so reloading shaders (R) or turning them back on
     * tries the pack again; changing an Iris setting in the same session saves the in-memory value.
     */
    public static void disableIrisShadersForSession(Throwable cause) {
        if (IRIS_INSTALLED) disableIrisShadersForSession0(cause);
    }
    private static void disableIrisShadersForSession0(Throwable cause) {
        var cfg = net.irisshaders.iris.Iris.getIrisConfig();
        boolean saved = cfg.areShadersEnabled();
        String pack = cfg.getShaderPackName().orElse("?");
        Logger.error("Voxy could not build its renderer with shader pack '" + pack + "'; shaders are OFF until"
                + " you reload them (R) or turn them back on in Options > Video Settings > Shader Packs, which"
                + " tries the pack again. iris.properties keeps enableShaders=" + saved + ", so the next launch"
                + " tries it too. VOXY_IRIS_DISABLE_PERSIST=1 restores upstream's saved disable. Cause: " + cause);
        try {
            IrisApi.getInstance().getConfig().setShadersEnabledAndApply(false);
        } finally {
            try {
                cfg.setShadersEnabled(saved);
                cfg.save();
            } catch (java.io.IOException io) {
                Logger.error("Could not restore enableShaders in iris.properties", io);
            } finally {
                cfg.setShadersEnabled(false);
            }
        }
    }
}
