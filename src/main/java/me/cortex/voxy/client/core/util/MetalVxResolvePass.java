package me.cortex.voxy.client.core.util;

import me.cortex.voxy.client.iris.IrisVoxyRenderPipelineData;
import me.cortex.voxy.common.Logger;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.lwjgl.opengl.GL11C.GL_FALSE;
import static org.lwjgl.opengl.GL20C.*;

/**
 * Phase C of the native vx contract on Metal (milestone issue #10): runs the
 * PACK's own {@code voxy_opaque}/{@code voxy_translucent} fragment code as a
 * GL fullscreen RESOLVE over the Metal-rendered material g-buffer. The
 * contract's pack-side hook is fragment-level ({@code voxy_emitFragment}),
 * which makes this split possible: Metal writes raw material attributes
 * (albedo / tint / packed light+face+customId planes), GL reconstructs
 * {@code VoxyFragmentParameters} per pixel and lets the pack shade.
 *
 * Assembly (#version 410 core — Apple GL max; NO layout(binding=) anywhere,
 * block/sampler bindings assigned post-link):
 *   [version + compat defines]
 *   [std140 uniform block wrapping the pack's requested uniform struct]
 *   [regenerated binding-free sampler declarations from voxy.json]
 *   [g-buffer plane samplers + convention uniform]
 *   [VoxyFragmentParameters struct + voxy_emitFragment fwd decl]
 *   [host main(): decode planes -> set vx_fragCoord/gl_FragDepth -> emit]
 *   [#define gl_FragCoord vx_fragCoord   — packs reconstruct view position
 *    from gl_FragCoord.z; in a fullscreen pass the real .z is the quad's
 *    constant depth, so the pack text must see the decoded LOD depth]
 *   [the pack's pre-expanded patch text]
 *
 * CURRENT STAGE: compile probe (VOXY_VX_RESOLVE_COMPILE_TEST=1) — assembles
 * and compiles+links both programs at first contract frame and dumps the
 * sources + info logs to run/voxy/debug/. De-risks the Apple GLSL
 * compatibility question before the Metal MRT plumbing lands.
 */
public final class MetalVxResolvePass {

    /** GLSL struct mirror — must match quads.frag's VoxyFragmentParameters verbatim. */
    private static final String PARAMS_STRUCT = """
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
            """;

    private MetalVxResolvePass() {}

    /**
     * Assemble the resolve fragment source for one stage.
     *
     * @param translucent selects the pack's translucent patch text.
     * @return assembled GLSL, or null when the pack's contract can't be
     *         resolved this way (SSBO declarations need GL 4.3).
     */
    public static String assembleFragment(IrisVoxyRenderPipelineData data, boolean translucent) {
        String patchText = translucent ? data.translucentFragPatch() : data.opaqueFragPatch();
        if (patchText == null) {
            return null;
        }
        if (data.getSsboSet() != null && data.getSsboSet().layout() != null
                && !data.getSsboSet().layout().isBlank()) {
            Logger.warn("MetalVxResolvePass: pack declares SSBOs (GL 4.3) — not resolvable on Apple GL 4.1");
            return null;
        }

        StringBuilder sb = new StringBuilder();
        sb.append("#version 410 core\n\n");
        // The pack text is pre-expanded by Iris's include graph but may carry
        // legacy sampler calls; core-profile aliases.
        sb.append("#define texture2D texture\n");
        sb.append("#define texture3D texture\n");
        sb.append("#define texture2DLod textureLod\n");
        sb.append("#define shadow2D texture\n\n");

        if (data.getUniforms() != null) {
            // Same wrap as the GL host (IrisVoxyRenderPipeline:204-208) MINUS
            // the binding qualifier — assigned via glUniformBlockBinding.
            sb.append("layout(std140) uniform ShaderUniformBindings ")
                    .append(data.getUniforms().layout())
                    .append(";\n\n");
        }

        if (data.samplerDecls != null) {
            // Regenerated binding-free (the ImageSet layout string uses
            // layout(binding = BASE+n) — GL 4.2+). Units assigned post-link
            // with glUniform1i in declaration order.
            data.samplerDecls.forEach((name, type) ->
                    sb.append("uniform ").append(type).append(' ').append(name).append(";\n"));
            sb.append('\n');
        }

        sb.append("""
                uniform sampler2DRect uVxAlbedo;
                uniform sampler2DRect uVxTint;
                uniform sampler2DRect uVxMisc;
                uniform sampler2DRect uVxDepth;
                uniform int uVxDepthIsWindow;

                """);

        sb.append(PARAMS_STRUCT).append('\n');

        sb.append("""
                vec4 vx_fragCoord;

                void main() {
                    ivec2 vxSz = textureSize(uVxDepth);
                    vec2 vxTexel = vec2(gl_FragCoord.x, float(vxSz.y) - gl_FragCoord.y);
                    vec3 vxDEnc = texture(uVxDepth, vxTexel).rgb;
                    float vxD = dot(vxDEnc, vec3(1.0, 1.0 / 255.0, 1.0 / 65025.0));
                    if (vxD <= 0.0 || vxD >= 0.9999999) discard;
                    vec4 vxAlbedo = texture(uVxAlbedo, vxTexel);
                    if (vxAlbedo.a <= 0.001) discard;
                    vec4 vxTint = texture(uVxTint, vxTexel);
                    vec4 vxMisc = texture(uVxMisc, vxTexel) * 255.0;
                    uint vxMr = uint(vxMisc.r + 0.5);
                    uint vxMg = uint(vxMisc.g + 0.5);
                    uint vxFace = (vxMr >> 1u) & 7u;
                    vec2 vxLight = vec2(
                        (float(vxMr >> 4u) * 16.0 + 8.0) / 256.0,
                        (float(vxMg >> 4u) * 16.0 + 8.0) / 256.0);
                    uint vxCustomId = uint(vxMisc.b + 0.5) | (uint(vxMisc.a + 0.5) << 8u);
                    float vxWz = (uVxDepthIsWindow == 1) ? vxD : vxD * 0.5 + 0.5;
                    gl_FragDepth = vxWz;
                    vx_fragCoord = vec4(gl_FragCoord.xy, vxWz, 1.0);
                    voxy_emitFragment(VoxyFragmentParameters(
                        vxAlbedo, vec2(0.0), vec2(0.0), vxFace, 0u, vxLight, vxTint, vxCustomId));
                }

                #define gl_FragCoord vx_fragCoord
                """);

        sb.append('\n').append(appleStrictCompat(patchText)).append('\n');
        return sb.toString();
    }

    /**
     * Apple's GLSL compiler rejects implicit int→uint conversions that
     * desktop drivers accept, so pack text needs targeted rewrites. Each
     * rule is narrow on purpose — log when applied so new packs' failures
     * stay diagnosable via the dumped sources.
     */
    private static String appleStrictCompat(String src) {
        String out = src;
        // BSL voxy_opaque/translucent: `(parameters.face & 1)` — uint & int.
        String fixed = out.replaceAll("(parameters\\.face\\s*&\\s*)1(?!u)", "$11u");
        if (!fixed.equals(out)) {
            Logger.info("MetalVxResolvePass: apple-compat applied uint-literal fix (face & 1 -> & 1u)");
            out = fixed;
        }
        // General: switch cases over uints written as int literals are fine
        // (case labels convert), but bare bitwise ops with .face need uint.
        fixed = out.replaceAll("(\\.face\\s*(?:&|\\||>>|<<)\\s*)(\\d+)(?![u\\d])", "$1$2u");
        if (!fixed.equals(out)) {
            Logger.info("MetalVxResolvePass: apple-compat applied generic face-bitop uint fix");
            out = fixed;
        }
        return out;
    }

    public static final String RESOLVE_VERT = """
            #version 410 core
            void main() {
                vec2 p = vec2((gl_VertexID & 1) * 2, (gl_VertexID & 2));
                gl_Position = vec4(p * 2.0 - 1.0, 0.0, 1.0);
            }
            """;

    /**
     * Phase D spike (issue #11): assemble a translucent resolve that runs the
     * pack's {@code voxy_translucent} over the LOD water using ONLY the existing
     * Phase D-lite bridges — the premultiplied water colour ({@code uVxAlbedo})
     * and the side-channel depth ({@code uVxDepth}) — with CONSTANT material
     * attributes and a host-supplied water {@code customId} ({@code uVxWaterId}).
     * The full Phase C material g-buffer is NOT required: the translucent LOD
     * layer is ~entirely water, so a constant water id lets the pack's water
     * branch (waves/reflections/depth-grade) run. This answers whether
     * albedo+depth+water-id is enough before investing in the material g-buffer.
     *
     * @return assembled GLSL, or null when the pack can't be resolved this way.
     */
    public static String assembleTranslucentSpike(IrisVoxyRenderPipelineData data) {
        String patchText = data.translucentFragPatch();
        if (patchText == null) {
            return null;
        }
        if (data.getSsboSet() != null && data.getSsboSet().layout() != null
                && !data.getSsboSet().layout().isBlank()) {
            Logger.warn("MetalVxResolvePass spike: pack declares SSBOs (GL 4.3) — not resolvable on Apple GL 4.1");
            return null;
        }

        StringBuilder sb = new StringBuilder();
        sb.append("#version 410 core\n\n");
        sb.append("#define texture2D texture\n");
        sb.append("#define texture3D texture\n");
        sb.append("#define texture2DLod textureLod\n");
        sb.append("#define shadow2D texture\n\n");

        if (data.getUniforms() != null) {
            // Binding-free (Apple GL 4.1) — block binding assigned post-link.
            sb.append("layout(std140) uniform ShaderUniformBindings ")
                    .append(data.getUniforms().layout())
                    .append(";\n\n");
        }

        if (data.samplerDecls != null) {
            // Binding-free sampler decls; units assigned post-link with
            // glUniform1i in declaration order, matching bindingFunction's base.
            data.samplerDecls.forEach((name, type) ->
                    sb.append("uniform ").append(type).append(' ').append(name).append(";\n"));
            sb.append('\n');
        }

        sb.append("""
                uniform sampler2DRect uVxAlbedo;
                uniform sampler2DRect uVxDepth;
                uniform int uVxDepthIsWindow;
                uniform uint uVxWaterId;

                """);

        sb.append(PARAMS_STRUCT).append('\n');

        sb.append("""
                vec4 vx_fragCoord;

                void main() {
                    ivec2 vxSz = textureSize(uVxDepth);
                    vec2 vxTexel = vec2(gl_FragCoord.x, float(vxSz.y) - gl_FragCoord.y);
                    vec3 vxDEnc = texture(uVxDepth, vxTexel).rgb;
                    float vxD = dot(vxDEnc, vec3(1.0, 1.0 / 255.0, 1.0 / 65025.0));
                    if (vxD <= 0.0 || vxD >= 0.9999999) discard;
                    vec4 vxA = texture(uVxAlbedo, vxTexel);
                    if (vxA.a <= 0.001) discard;
                    // Bridge holds premultiplied colour — un-premultiply to straight albedo.
                    vec3 vxStraight = vxA.rgb / max(vxA.a, 0.0001);
                    vec4 vxAlbedo = vec4(vxStraight, vxA.a);
                    float vxWz = (uVxDepthIsWindow == 1) ? vxD : vxD * 0.5 + 0.5;
                    gl_FragDepth = vxWz;
                    vx_fragCoord = vec4(gl_FragCoord.xy, vxWz, 1.0);
                    // Spike: constant material attrs + water customId so the pack's
                    // voxy_translucent shades the whole LOD translucent layer as water.
                    voxy_emitFragment(VoxyFragmentParameters(
                        vxAlbedo, vec2(0.0), vec2(0.0), 1u, 0u, vec2(1.0, 0.0), vec4(1.0), uVxWaterId));
                }

                #define gl_FragCoord vx_fragCoord
                """);

        sb.append('\n').append(appleStrictCompat(patchText)).append('\n');
        return sb.toString();
    }

    /**
     * Compile probe: assemble + compile + link both stages, dump everything
     * to run/voxy/debug/, log verdicts. No rendering side effects. Returns
     * true when the opaque program links.
     */
    public static boolean compileProbe(IrisVoxyRenderPipelineData data) {
        Path debugDir = Path.of("voxy", "debug");
        try {
            Files.createDirectories(debugDir);
        } catch (IOException ignored) {}

        boolean opaqueOk = probeOne(data, false, debugDir);
        boolean transOk = probeOne(data, true, debugDir);
        Logger.info("MetalVxResolvePass COMPILE PROBE: opaque=" + (opaqueOk ? "LINKED" : "FAILED")
                + " translucent=" + (transOk ? "LINKED" : "FAILED")
                + " (sources + logs in " + debugDir.toAbsolutePath() + ")");
        return opaqueOk;
    }

    private static boolean probeOne(IrisVoxyRenderPipelineData data, boolean translucent, Path debugDir) {
        String name = translucent ? "translucent" : "opaque";
        String src = assembleFragment(data, translucent);
        if (src == null) {
            Logger.warn("MetalVxResolvePass probe: no " + name + " source (missing patch or SSBO pack)");
            return false;
        }
        dump(debugDir.resolve("vx_resolve_" + name + ".frag"), src);

        int fs = glCreateShader(GL_FRAGMENT_SHADER);
        glShaderSource(fs, src);
        glCompileShader(fs);
        String fsLog = glGetShaderInfoLog(fs);
        if (glGetShaderi(fs, GL_COMPILE_STATUS) == GL_FALSE) {
            dump(debugDir.resolve("vx_resolve_" + name + ".compile.log"), fsLog);
            Logger.error("MetalVxResolvePass probe [" + name + "] fragment COMPILE FAILED — first lines:\n"
                    + firstLines(fsLog, 12));
            glDeleteShader(fs);
            return false;
        }
        if (!fsLog.isBlank()) {
            dump(debugDir.resolve("vx_resolve_" + name + ".compile.log"), fsLog);
        }

        int vs = glCreateShader(GL_VERTEX_SHADER);
        glShaderSource(vs, RESOLVE_VERT);
        glCompileShader(vs);

        int prog = glCreateProgram();
        glAttachShader(prog, vs);
        glAttachShader(prog, fs);
        glLinkProgram(prog);
        String linkLog = glGetProgramInfoLog(prog);
        boolean linked = glGetProgrami(prog, GL_LINK_STATUS) != GL_FALSE;
        if (!linkLog.isBlank() || !linked) {
            dump(debugDir.resolve("vx_resolve_" + name + ".link.log"), linkLog);
        }
        if (!linked) {
            Logger.error("MetalVxResolvePass probe [" + name + "] LINK FAILED — first lines:\n"
                    + firstLines(linkLog, 12));
        } else {
            int activeUniforms = glGetProgrami(prog, GL_ACTIVE_UNIFORMS);
            Logger.info("MetalVxResolvePass probe [" + name + "] linked OK, activeUniforms=" + activeUniforms);
        }
        glDeleteShader(vs);
        glDeleteShader(fs);
        glDeleteProgram(prog);
        return linked;
    }

    private static void dump(Path file, String content) {
        try {
            Files.writeString(file, content);
        } catch (IOException e) {
            Logger.warn("MetalVxResolvePass: failed to dump " + file + ": " + e.getMessage());
        }
    }

    private static String firstLines(String s, int n) {
        String[] lines = s.split("\n");
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < Math.min(n, lines.length); i++) {
            sb.append(lines[i]).append('\n');
        }
        return sb.toString();
    }
}
