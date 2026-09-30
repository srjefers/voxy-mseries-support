package me.cortex.voxy.client.core.interop;

import me.cortex.voxy.client.core.gl.shader.ShaderLoader;
import me.cortex.voxy.client.core.gpu.GraphicsPipelineDesc;
import me.cortex.voxy.client.core.gpu.PipelineState;
import me.cortex.voxy.client.core.gpu.VertexLayout;
import me.cortex.voxy.client.core.metal.MetalRenderBackend;
import me.cortex.voxy.client.core.util.MetalVxGbufferEmitter;
import me.cortex.voxy.common.Logger;

import java.util.LinkedHashMap;

/** Establishes on-device linking before later shader-definition or pipeline refactors. */
public final class MetalTerrainPipelineTest {
    public static void main(String[] args) {
        Logger.SHUTUP = true;
        MetalRenderBackend backend = new MetalRenderBackend();
        try {
            String vertex = ShaderLoader.parseAndStripPrintf("voxy:lod/gl46/quads3.vert");
            String fragment = ShaderLoader.parseAndStripPrintf("voxy:lod/gl46/quads.frag");
            for (boolean material : new boolean[]{false, true}) {
                for (boolean translucent : new boolean[]{false, true}) {
                    var defines = new LinkedHashMap<String, String>();
                    defines.put("NO_SHADE_FACE_TINT", "1.0f");
                    defines.put("UP_FACE_TINT", "1.0f");
                    defines.put("DOWN_FACE_TINT", "0.5f");
                    defines.put("Z_AXIS_FACE_TINT", "0.8f");
                    defines.put("X_AXIS_FACE_TINT", "0.6f");
                    defines.put("VOXY_FORCE_OPAQUE_ALPHA", "");
                    defines.put("VOXY_METAL_TINT", "");
                    defines.put("VOXY_METAL_BI_FIX", "");
                    defines.put("VOXY_LOD_FIXED_MIP", "");
                    defines.put("VOXY_LOD_DIST_MIP", "");
                    defines.put("VOXY_ATLAS_MAX_LOD", "3.0");
                    defines.put("VOXY_LOD_DIST_MIP_BIAS", "0.0000");
                    defines.put("VOXY_LOD_ABS_INDENT", "");
                    defines.put("VOXY_WLOG_TINT_FIX", "");
                    if (material) {
                        defines.put("PATCHED_SHADER", "");
                        defines.put("VOXY_VX_GBUFFER", "");
                    } else {
                        defines.put("USE_ENV_FOG", "");
                        defines.put("VOXY_LOD_BRIGHTNESS", "0.9200");
                    }
                    if (translucent) {
                        defines.put("TRANSLUCENT", "");
                        defines.put("VOXY_WATER_FAR_ALPHA", "");
                        defines.put("VOXY_WATER_DEPTH_BIAS", "0");
                    }
                    String label = (material ? "material" : "shaders-off") + (translucent ? "/water" : "/opaque");
                    var state = new PipelineState(
                            translucent && !material ? PipelineState.DepthState.TEST_NO_WRITE : PipelineState.DepthState.DEFAULT,
                            translucent && !material ? PipelineState.BlendState.PREMULTIPLIED_ALPHA : PipelineState.BlendState.OPAQUE,
                            PipelineState.RasterState.NO_CULL);
                    try (var pipeline = backend.createGraphicsPipeline(new GraphicsPipelineDesc(
                            vertex, material ? fragment + MetalVxGbufferEmitter.SOURCE : fragment, defines,
                            null, null, null, null,
                            material ? new int[]{0x8058, 0x8058, 0x8058} : new int[]{0x8058},
                            VertexLayout.EMPTY, state, label))) {
                        if (pipeline == null) throw new AssertionError("missing " + label);
                        System.out.println("PASS: Metal compiles and links " + label);
                    }
                }
            }
            try (var pipeline = backend.createGraphicsPipeline(new GraphicsPipelineDesc(
                    ShaderLoader.parse("voxy:hiz/blit.vsh"), ShaderLoader.parse("voxy:hiz/blit.fsh"), null,
                    null, null, null, null, 0, VertexLayout.EMPTY, PipelineState.DEFAULT, "Metal Hi-Z"))) {
                if (pipeline == null) throw new AssertionError("missing Metal Hi-Z");
                System.out.println("PASS: Metal compiles and links actual Hi-Z blit pipeline");
            }
        } finally {
            backend.shutdown();
        }
    }
}
