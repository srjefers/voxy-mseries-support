package me.cortex.voxy.client.core.util;

import me.cortex.voxy.client.iris.IrisVoxyRenderPipelineData;

/** Contract-1 material decoding, independent of a shader pack's lighting implementation. */
final class MetalMaterialShader {
    private MetalMaterialShader() {}

    static String assemble(IrisVoxyRenderPipelineData data, boolean translucent) {
        String patch = translucent ? data.translucentFragPatch() : data.opaqueFragPatch();
        if (patch == null) return null;
        if (data.getSsboSet() != null && data.getSsboSet().layout() != null && !data.getSsboSet().layout().isBlank()) {
            throw new IllegalArgumentException("Metal contract-1 resolve cannot bind shader storage buffers on OpenGL 4.1");
        }
        var source = new StringBuilder("#version 410 core\n#define texture2D texture\n#define texture3D texture\n#define texture2DLod textureLod\n#define shadow2D texture\n");
        if (data.getUniforms() != null) source.append("layout(std140) uniform ShaderUniformBindings ").append(data.getUniforms().layout()).append(";\n");
        if (data.samplerDecls != null) data.samplerDecls.forEach((name, type) -> source.append("uniform ").append(type).append(' ').append(name).append(";\n"));
        source.append("""
                uniform sampler2DRect uVxAlbedo;
                uniform sampler2DRect uVxTint;
                uniform sampler2DRect uVxMisc;
                uniform sampler2DRect uVxDepth;
                uniform int uVxDepthIsWindow;
                struct VoxyFragmentParameters {
                    vec4 sampledColour;
                    vec2 tile;
                    vec2 uv;
                    uint face;
                    uint modelId;
                    vec2 lightMap;
                    vec4 tinting;
                    uint customId;
                };
                void voxy_emitFragment(VoxyFragmentParameters parameters);
                // Pack globals (e.g. texelCoord) run before main and already need the actual pixel coordinates.
                vec4 vx_fragCoord = gl_FragCoord;
                void main() {
                    ivec2 size = textureSize(uVxDepth);
                    vec2 texel = vec2(gl_FragCoord.x, float(size.y) - gl_FragCoord.y);
                    float depth = dot(texture(uVxDepth, texel).rgb, vec3(1.0, 1.0 / 255.0, 1.0 / 65025.0));
                    if (depth <= 0.0 || depth >= 0.9999999) discard;
                    vec4 albedo = texture(uVxAlbedo, texel);
                    if (albedo.a <= 0.001) discard;
                    vec4 misc = texture(uVxMisc, texel) * 255.0;
                    uint r = uint(misc.r + 0.5), g = uint(misc.g + 0.5);
                    vec2 light = vec2((float(r >> 4u) * 16.0 + 8.0) / 256.0,
                                      (float(g >> 4u) * 16.0 + 8.0) / 256.0);
                    float windowDepth = uVxDepthIsWindow == 1 ? depth : depth * 0.5 + 0.5;
                    gl_FragDepth = windowDepth;
                    vx_fragCoord = vec4(gl_FragCoord.xy, windowDepth, gl_FragCoord.w);
                    voxy_emitFragment(VoxyFragmentParameters(albedo, vec2(0.0), vec2(0.0),
                        (r >> 1u) & 7u, 0u, light, texture(uVxTint, texel),
                        uint(misc.b + 0.5) | (uint(misc.a + 0.5) << 8u)));
                }
                #define gl_FragCoord vx_fragCoord
                """);
        // Preserve pack lighting; only make face bit-operation literal types explicit for Apple's compiler.
        return source.append(patch.replaceAll("(\\.face\\s*(?:&|\\||>>|<<)\\s*)(\\d+)(?![u\\d])", "$1$2u")).append('\n').toString();
    }
}
