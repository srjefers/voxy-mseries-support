package me.cortex.voxy.client.core.rendering.section.backend.mdic;

import me.cortex.voxy.client.core.AbstractRenderPipeline;
import me.cortex.voxy.client.core.gpu.*;
import net.minecraft.client.Minecraft;
import net.minecraft.core.Direction;
import static org.lwjgl.opengl.GL11C.GL_RGBA8;

/** Canonical unpatched terrain descriptors, shared by production and driver verification. */
public record TerrainShaderConfiguration(GraphicsPipelineDesc opaque, GraphicsPipelineDesc translucent) {

    /** Shaders-off captures one nearest surface in each translucent category. */
    public GraphicsPipelineDesc prelitLayer(boolean fluid) {
        if (translucent.defines.containsKey("PATCHED_SHADER")) {
            throw new IllegalStateException("Prelit layers require the shaders-off terrain descriptor");
        }
        var defines = new java.util.LinkedHashMap<>(translucent.defines);
        defines.put("VOXY_PRELIT_LAYER", fluid ? "1" : "0");
        return new GraphicsPipelineDesc(translucent.vertexGlsl, translucent.fragmentGlsl, defines,
                null, null, null, null, new int[]{GL_RGBA8, org.lwjgl.opengl.GL30C.GL_R32F},
                translucent.vertexLayout, translucent.state, "MetalPrelitLayer." + (fluid ? "fluid" : "surface"));
    }

    static final float WATER_FAR_ALPHA = parseEnvFloat("VOXY_WATER_FAR_ALPHA", 0.95f);
    static final float WATER_FAR_ALPHA_START = parseEnvFloat("VOXY_WATER_FAR_ALPHA_START", 0.0f);
    static final float WATER_FAR_ALPHA_END = parseEnvFloat("VOXY_WATER_FAR_ALPHA_END", 0.0f);
    static final boolean TRANS_NEAR_CULL_XZ =
            !"0".equals(System.getenv("VOXY_TRANS_NEAR_CULL_XZ"));
    static final boolean TRANS_NEAR_CULL_RADIAL =
            TRANS_NEAR_CULL_XZ && !"0".equals(System.getenv("VOXY_TRANS_NEAR_CULL_RADIAL"));
    static final float TRANS_NEAR_CULL_MARGIN =
            parseEnvFloat("VOXY_TRANS_NEAR_CULL_MARGIN", TRANS_NEAR_CULL_XZ ? 16f : 48f);
    static final boolean TRANS_NEAR_CULL_MASKED_ON =
            !"0".equals(System.getenv("VOXY_TRANS_NEAR_CULL_MASKED"));
    static final boolean TRANS_NEAR_CULL_FULLRING = TRANS_NEAR_CULL_MASKED_ON
            && "1".equals(System.getenv("VOXY_TRANS_NEAR_CULL_FULLRING"));
    private static float parseEnvFloat(String name, float def) {
        String v = System.getenv(name);
        if (v == null || v.isBlank()) return def;
        try {
            return Float.parseFloat(v.trim());
        } catch (NumberFormatException e) {
            return def;
        }
    }

    public static TerrainShaderConfiguration load(AbstractRenderPipeline pipeline, BackendType backend) {
        String vertex = me.cortex.voxy.client.core.gl.shader.ShaderLoader.parse("voxy:lod/gl46/quads3.vert");
        String taa = pipeline.taaFunction("taaShift");
        if (taa != null) vertex += "\n" + taa;
        return create(pipeline, backend, vertex,
                me.cortex.voxy.client.core.gl.shader.ShaderLoader.parse("voxy:lod/gl46/quads.frag"), taa);
    }

    private static TerrainShaderConfiguration create(AbstractRenderPipeline pipeline, BackendType backend,
                                                   String vertex, String frag, String taa) {
        java.util.Map<String, String> commonDefines = buildTerrainDefines(taa);
        java.util.Map<String, String> opaqueDefines = new java.util.LinkedHashMap<>(commonDefines);
        java.util.Map<String, String> translucentDefines = new java.util.LinkedHashMap<>(commonDefines);
        translucentDefines.put("TRANSLUCENT", "");
        boolean vxMaterial = pipeline.vxMaterialMode();
        boolean vxOpaqueMat = pipeline.vxOpaqueMaterialMode();
        String emitter = me.cortex.voxy.client.core.util.MetalVxGbufferEmitter.SOURCE;
        String vxOpaqueFrag = vxOpaqueMat ? frag + emitter : frag;
        String vxTransFrag = vxMaterial ? frag + emitter : frag;
        boolean gbufferDebug = "1".equals(System.getenv("VOXY_VX_GBUFFER_DEBUG"));
        if (vxOpaqueMat) {
            opaqueDefines.put("PATCHED_SHADER", "");
            opaqueDefines.put("VOXY_VX_GBUFFER", "");
            if (gbufferDebug) opaqueDefines.put("VOXY_VX_GBUFFER_DEBUG", "");
        }
        if (vxMaterial) {
            translucentDefines.put("PATCHED_SHADER", "");
            translucentDefines.put("VOXY_VX_GBUFFER", "");
            if (gbufferDebug) translucentDefines.put("VOXY_VX_GBUFFER_DEBUG", "");
        }
        if (backend != BackendType.OPENGL) {
            opaqueDefines.put("VOXY_METAL_TINT", "");
            translucentDefines.put("VOXY_METAL_TINT", "");
            boolean noDepthBound = "1".equals(System.getenv("VOXY_NO_DEPTH_BOUND"));
            boolean boundDebug = !noDepthBound && "1".equals(System.getenv("VOXY_BOUND_DEBUG"));
            if (noDepthBound) {
                opaqueDefines.put("VOXY_NO_DEPTH_BOUND", "");
                translucentDefines.put("VOXY_NO_DEPTH_BOUND", "");
            } else {
                opaqueDefines.put("VOXY_METAL_BOUND_SSBO", "");
                if (pipeline.materialPolicy().translucentBoundMask()) {
                    translucentDefines.put("VOXY_METAL_BOUND_SSBO", "");
                } else {
                    translucentDefines.put("VOXY_NO_DEPTH_BOUND", "");
                }
            }
            if (boundDebug) {
                opaqueDefines.put("VOXY_BOUND_DEBUG", "");
                translucentDefines.put("VOXY_BOUND_DEBUG", "");
            }
            opaqueDefines.put("VOXY_FORCE_OPAQUE_ALPHA", "");
            if ("1".equals(System.getenv("VOXY_OPAQUE_RING_DEBUG"))) {
                opaqueDefines.put("VOXY_OPAQUE_RING_DEBUG", "");
            }
            String fixedMipEnv = System.getenv("VOXY_LOD_FIXED_MIP");
            boolean lodFixedMip = fixedMipEnv == null || !"0".equals(fixedMipEnv.trim());
            boolean lodNoDiscard = "1".equals(System.getenv("VOXY_LOD_NO_DISCARD"));
            if (lodFixedMip) {
                opaqueDefines.put("VOXY_LOD_FIXED_MIP", "");
                translucentDefines.put("VOXY_LOD_FIXED_MIP", "");
            }
            if (lodNoDiscard) {
                opaqueDefines.put("VOXY_LOD_NO_DISCARD", "");
                translucentDefines.put("VOXY_LOD_NO_DISCARD", "");
            }
            String distMipEnv = System.getenv("VOXY_LOD_DIST_MIP");
            boolean lodDistMip = distMipEnv == null || !"0".equals(distMipEnv.trim());
            if (lodDistMip) {
                float mipBias = 0.0f;
                String mb = System.getenv("VOXY_LOD_MIP_BIAS");
                if (mb != null && !mb.isBlank()) {
                    try {
                        mipBias = Float.parseFloat(mb.trim());
                    } catch (NumberFormatException e) {
                        mipBias = 0.0f;
                    }
                }
                String maxLod = String.format(java.util.Locale.ROOT, "%.1f",
                        (float) (me.cortex.voxy.client.core.model.ModelFactory.LAYERS - 1));
                String biasStr = String.format(java.util.Locale.ROOT, "%.4f", mipBias);
                opaqueDefines.put("VOXY_LOD_DIST_MIP", "");
                opaqueDefines.put("VOXY_ATLAS_MAX_LOD", maxLod);
                opaqueDefines.put("VOXY_LOD_DIST_MIP_BIAS", biasStr);
                translucentDefines.put("VOXY_LOD_DIST_MIP", "");
                translucentDefines.put("VOXY_ATLAS_MAX_LOD", maxLod);
                translucentDefines.put("VOXY_LOD_DIST_MIP_BIAS", biasStr);
            }
            if (WATER_FAR_ALPHA > 0.0f && (!vxMaterial || pipeline.materialPolicy().legacyWater())) {
                translucentDefines.put("VOXY_WATER_FAR_ALPHA", "");
            }
            String absIndentEnv = System.getenv("VOXY_LOD_ABS_INDENT");
            boolean absIndent = absIndentEnv == null || !"0".equals(absIndentEnv.trim());
            if (absIndent) {
                opaqueDefines.put("VOXY_LOD_ABS_INDENT", "");
                translucentDefines.put("VOXY_LOD_ABS_INDENT", "");
            }
            String nearCullEnv = System.getenv("VOXY_TRANS_NEAR_CULL");
            boolean transNearCull = (nearCullEnv == null || !"0".equals(nearCullEnv.trim()))
                    && pipeline.materialPolicy().legacyWater();
            if (transNearCull) {
                translucentDefines.put("VOXY_TRANS_NEAR_CULL", "");
                if (TRANS_NEAR_CULL_XZ) {
                    translucentDefines.put("VOXY_TRANS_NEAR_CULL_XZ", "");
                    if (TRANS_NEAR_CULL_RADIAL) {
                        translucentDefines.put("VOXY_TRANS_NEAR_CULL_RADIAL", "");
                    }
                }
                if (TRANS_NEAR_CULL_MASKED_ON) {
                    translucentDefines.put("VOXY_TRANS_NEAR_CULL_MASKED", "");
                    if (!"0".equals(System.getenv("VOXY_TRANS_NEAR_CULL_GHOST"))) {
                        translucentDefines.put("VOXY_TRANS_NEAR_CULL_GHOST", "");
                    }
                }
            }
            String wlogFixEnv = System.getenv("VOXY_WLOG_TINT_FIX");
            if (wlogFixEnv == null || !"0".equals(wlogFixEnv.trim())) {
                opaqueDefines.put("VOXY_WLOG_TINT_FIX", "");
                translucentDefines.put("VOXY_WLOG_TINT_FIX", "");
            }
            if ("1".equals(System.getenv("VOXY_DEBUG_WLOG_TINT"))) {
                opaqueDefines.put("VOXY_DEBUG_WLOG_TINT", "");
                translucentDefines.put("VOXY_DEBUG_WLOG_TINT", "");
            }
            boolean vxContract = me.cortex.voxy.client.core.util.IrisUtil.vxContractActive();
            if (!vxContract) {
                float brightness = 1.0f;
                String b = System.getenv("VOXY_LOD_BRIGHTNESS");
                if (b != null && !b.isBlank()) {
                    try {
                        brightness = Float.parseFloat(b.trim());
                    } catch (NumberFormatException e) {
                        brightness = 1.0f;
                    }
                }
                if (brightness != 1.0f) {
                    opaqueDefines.put("VOXY_LOD_BRIGHTNESS", String.format(java.util.Locale.ROOT, "%.4f", brightness));
                }
            }
            {
                float waterShade = 1.0f;
                float waterMinAlpha = 0.0f;
                String ws = System.getenv("VOXY_WATER_SHADE");
                if (ws != null && !ws.isBlank()) {
                    try {
                        waterShade = Float.parseFloat(ws.trim());
                    } catch (NumberFormatException e) {
                        waterShade = 1.0f;
                    }
                }
                String wa = System.getenv("VOXY_WATER_MIN_ALPHA");
                if (wa != null && !wa.isBlank()) {
                    try {
                        waterMinAlpha = Float.parseFloat(wa.trim());
                    } catch (NumberFormatException e) {
                        waterMinAlpha = 0.0f;
                    }
                }
                if (waterShade != 1.0f) {
                    translucentDefines.put("VOXY_WATER_SHADE", String.format(java.util.Locale.ROOT, "%.4f", waterShade));
                }
                if (waterMinAlpha > 0.0f) {
                    translucentDefines.put("VOXY_WATER_MIN_ALPHA", String.format(java.util.Locale.ROOT, "%.4f", waterMinAlpha));
                }
            }
            if ("1".equals(System.getenv("VOXY_LOD_WATER_DEBUG"))) {
                translucentDefines.put("VOXY_LOD_WATER_DEBUG", "");
            }
            if ("1".equals(System.getenv("VOXY_LOD_OPAQUE_WATER_DEBUG"))) {
                opaqueDefines.put("VOXY_OPAQUE_WATER_DEBUG", "");
            }
            {
                String waterBias = System.getenv("VOXY_WATER_DEPTH_BIAS");
                if (waterBias == null || waterBias.isBlank()) waterBias = "0";
                try {
                    Float.parseFloat(waterBias.trim());
                } catch (NumberFormatException e) {
                    waterBias = "0";
                }
                translucentDefines.put("VOXY_WATER_DEPTH_BIAS", waterBias.trim());
                if (!"0".equals(waterBias.trim())) {
                }
            }
            if ("1".equals(System.getenv("VOXY_LOD_FLAT_WATER"))) {
                translucentDefines.put("VOXY_FLAT_WATER", "");
            }
            boolean bakeryOff = "1".equals(System.getenv("VOXY_BAKERY_OFF"));
            boolean debugMissing = "1".equals(System.getenv("VOXY_BAKERY_DEBUG_MISSING"));
            if (bakeryOff) {
                opaqueDefines.put("VOXY_NO_ATLAS", "");
                translucentDefines.put("VOXY_NO_ATLAS", "");
            } else if (debugMissing) {
                opaqueDefines.put("VOXY_DEBUG_MAGENTA_MISSING", "");
                translucentDefines.put("VOXY_DEBUG_MAGENTA_MISSING", "");
            }
            if (pipeline.useEnvFog()) {
                opaqueDefines.put("USE_ENV_FOG", "");
                translucentDefines.put("USE_ENV_FOG", "");
            }
            opaqueDefines.put("VOXY_METAL_BI_FIX", "");
            translucentDefines.put("VOXY_METAL_BI_FIX", "");
        }
        PipelineState opaqueState
                = PipelineState.OPAQUE_MESH;
        PipelineState translucentState
                = PipelineState.TRANSLUCENT_MESH;
        if (backend != BackendType.OPENGL) {
            boolean lodNoDepth = "1".equals(System.getenv("VOXY_LOD_NO_DEPTH"));
            if (lodNoDepth) {
            }
            opaqueState = new PipelineState(
                    lodNoDepth
                            ? PipelineState.DepthState.DISABLED
                            : PipelineState.DepthState.DEFAULT,
                    PipelineState.BlendState.OPAQUE,
                    PipelineState.RasterState.NO_CULL);
            boolean waterDebugDepth = "1".equals(System.getenv("VOXY_LOD_WATER_DEBUG"));
            // The nearest translucent surface owns this bridge pixel. Without depth writes,
            // later water/back faces inside a section can blend over nearer ice.
            var transDepthState = PipelineState.DepthState.DEFAULT;
            var transBlend = pipeline.materialPolicy() == me.cortex.voxy.client.core.MetalMaterialPolicy.SHADERS_OFF
                    || (vxMaterial && !"1".equals(System.getenv("VOXY_VX_PLANE_BLEND")))
                    ? PipelineState.BlendState.OPAQUE
                    : PipelineState.BlendState.PREMULTIPLIED_ALPHA;
            translucentState = new PipelineState(
                    waterDebugDepth
                            ? PipelineState.DepthState.DISABLED
                            : transDepthState,
                    transBlend,
                    PipelineState.RasterState.NO_CULL);
        }
        int[] threePlane = new int[]{GL_RGBA8, GL_RGBA8, GL_RGBA8};
        int[] onePlane = new int[]{GL_RGBA8};
        int[] opaqueFormats = vxOpaqueMat ? threePlane : onePlane;
        int[] translucentFormats = vxMaterial ? threePlane : onePlane;
        return new TerrainShaderConfiguration(
                new GraphicsPipelineDesc(vertex, vxOpaqueFrag, java.util.Map.copyOf(opaqueDefines),
                        null, null, null, null, opaqueFormats, VertexLayout.EMPTY, opaqueState, "MDIC.terrain"),
                new GraphicsPipelineDesc(vertex, vxTransFrag, java.util.Map.copyOf(translucentDefines),
                        null, null, null, null, translucentFormats, VertexLayout.EMPTY, translucentState, "MDIC.translucentTerrain"));
    }
    private static java.util.Map<String, String> buildTerrainDefines(String taa) {
        var m = new java.util.LinkedHashMap<String, String>();
        net.minecraft.client.multiplayer.ClientLevel level = Minecraft.getInstance().level;
        if (level != null) {
            m.put("NO_SHADE_FACE_TINT", Float.toString(level.getShade(Direction.UP, false)) + "f");
            m.put("UP_FACE_TINT",       Float.toString(level.getShade(Direction.UP, true))  + "f");
            m.put("DOWN_FACE_TINT",     Float.toString(level.getShade(Direction.DOWN, true))+ "f");
            m.put("Z_AXIS_FACE_TINT",   Float.toString(level.getShade(Direction.NORTH, true))+ "f");
            m.put("X_AXIS_FACE_TINT",   Float.toString(level.getShade(Direction.EAST, true)) + "f");
        }
        if (taa != null) m.put("TAA_PATCH", "");
        return m;
    }
}
