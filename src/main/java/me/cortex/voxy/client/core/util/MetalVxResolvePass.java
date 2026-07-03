package me.cortex.voxy.client.core.util;

import me.cortex.voxy.client.iris.IrisVoxyRenderPipelineData;
import me.cortex.voxy.common.Logger;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.lwjgl.opengl.GL11C.*;
import static org.lwjgl.opengl.GL13C.*;
import static org.lwjgl.opengl.GL15C.*;
import static org.lwjgl.opengl.GL20C.*;
import static org.lwjgl.opengl.GL30C.*;
import static org.lwjgl.opengl.GL31C.*;

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

        // Sky-light floor (VOXY_VX_SKY_FLOOR=0..15, default 0 = off). The LOD bake
        // under-propagates sky light for many far/mipped sections (CPU plane dump:
        // ~56% of opaque pixels read sky=0 at noon, the count rising as the world
        // settles). BSL's GetLighting squares sky light (skylightSqr = lightmap.y^2)
        // and gates ALL sun/scene lighting on it, so sky=0 -> near-black; the normal
        // (non-vx) path tolerates the same data because MC's lightmap texture has a
        // non-zero daytime floor. This clamps the resolve's decoded sky light up to a
        // floor so BSL can light the LODs comparably. floor=15 is the confirm-the-cause
        // setting (everything should go bright); a moderate floor is the candidate fix.
        // 2026-07-03: DEFAULT 12/15 (was 0/off). Mechanism located for the
        // handoff's hypothesis A: Mipper's representative-voxel pick carries
        // the underwater-attenuated sky light of whichever corner voxel
        // survives the mip — per-cell variance shows up as chunk-aligned
        // brightness squares once BSL scales its water sky reflection by
        // skyLight^2, and the value trends to 0 at high LOD (flat dark far
        // water). A moderate floor removes both without flattening block
        // light. Water-scoped in the default trans-only material mode
        // (resolveTranslucentOnly runs only the translucent program).
        // VOXY_VX_SKY_FLOOR=0 disables; =0..15 overrides.
        float skyFloor = 12.0f / 15.0f;
        String skyFloorEnv = System.getenv("VOXY_VX_SKY_FLOOR");
        if (skyFloorEnv != null) {
            try { skyFloor = Math.max(0, Math.min(15, Integer.parseInt(skyFloorEnv.trim()))) / 15.0f; }
            catch (NumberFormatException ignored) {}
        }
        String skyFloorLine = skyFloor > 0.0f
                ? String.format("vxLight.y = max(vxLight.y, %.5f);\n", skyFloor)
                : "";
        if (skyFloor > 0.0f) {
            Logger.info("MetalVxResolvePass: VOXY_VX_SKY_FLOOR active, sky light floored to " + skyFloor
                    + " (" + skyFloorEnv + "/15)");
        }

        // Water-scoped sky-light MAX (VOXY_VX_WATER_SKY_MAX=0 reverts). Mipper's
        // representative-voxel mip carries one corner's underwater-attenuated sky nibble
        // verbatim, measured live as BIMODAL {0,15} per water cell. BSL squares the value
        // into waterSkyOcclusion (skyReflection *= lightmap.y^2), so even the 12/15 floor
        // leaves a 0.672-vs-1.0 reflection-brightness STEP between neighbouring chunks —
        // the chunk-aligned lighter/darker panels riding the water surface, strongest at
        // grazing angles where the fresnel weight peaks. A distant LOD water TOP surface
        // is by construction sky-exposed, so max — not the rep-voxel — is the correct mip
        // semantics for water; scoping by customId keeps other translucents on the floor
        // and leaves Mipper (common code, persisted LODs, GL byte-identity) untouched.
        // 0.96875 = the sky-15 decode ((15*16+8)/256); BSL's lightmap remap turns it into
        // exactly 1.0.
        boolean waterSkyMax = !"0".equals(System.getenv("VOXY_VX_WATER_SKY_MAX"));
        String waterSkyMaxLine = waterSkyMax
                ? "if ((vxCustomId / 100u) == 200u || (vxCustomId / 100u) == 204u) vxLight.y = max(vxLight.y, 0.96875);\n"
                : "";
        Logger.info("[Metal-LODTEST] vx water sky-light max " + (waterSkyMax ? "ON" : "OFF")
                + " (water customId -> sky=15 in the resolve, kills chunk-step reflection squares);"
                + " VOXY_VX_WATER_SKY_MAX=0 reverts");

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
                    __SKY_FLOOR____WATER_SKY_MAX__float vxWz = (uVxDepthIsWindow == 1) ? vxD : vxD * 0.5 + 0.5;
                    gl_FragDepth = vxWz;
                    vx_fragCoord = vec4(gl_FragCoord.xy, vxWz, 1.0);
                    voxy_emitFragment(VoxyFragmentParameters(
                        vxAlbedo, vec2(0.0), vec2(0.0), vxFace, 0u, vxLight, vxTint, vxCustomId));
                }

                #define gl_FragCoord vx_fragCoord
                """.replace("__SKY_FLOOR__", skyFloorLine)
                   .replace("__WATER_SKY_MAX__", waterSkyMaxLine));

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

    // =================== Phase C resolve runtime (increment 5) ===================
    private static final int GL_TEXTURE_RECTANGLE = 0x84F5;

    private static final class Prog {
        int prog;
        int uDepthIsWindow;
        int samplerCount;
        int ubo, uboSize;
        long uboScratch;
        int fbo;
        int[] attached = new int[0];
        // Texture unit carrying the pack's `gaux2` sampler (colortex5), or -1.
        // See SSR_ALT: the translucent program needs this unit re-pointed at
        // colortex5's ALT side.
        int gaux2Unit = -1;
    }

    // BSL's voxy_translucent reflects LOD water via SimpleReflection -> texture(gaux2).
    // The ImageSet binds gaux2 with Iris's getFlippedAfterPrepare snapshot = colortex5
    // MAIN — but under BSL defaults colortex5's ONLY writer is deferred1 writing the
    // ALT side (and it runs AFTER our SOLID-head hook; colortex5Clear=false keeps ALT
    // across frames). So the resolve's mirror sampled an undefined, never-written
    // texture: grazing rays DO hit (the pack's voxy Raytrace patch falls back to
    // vxDepthTexOpaque), the undefined alpha reads ~1 which skips the bright sky arm,
    // and reflection.rgb = pow(garbage*2, 8) ~ black. A black mirror at fresnel
    // weight ~0.8 = the flat dark opaque untextured LOD water. Re-pointing gaux2 at
    // colortex5 ALT gives the pack exactly the one-frame-stale reflection data its
    // own reprojection block expects. Metal-bridge-only code; GL path untouched.
    // VOXY_VX_SSR_ALT=0 restores the old (MAIN) binding.
    private static final boolean SSR_ALT = !"0".equals(System.getenv("VOXY_VX_SSR_ALT"));

    // Clear colortex16 before the blended translucent resolve (VOXY_VX_TRANS_CLEAR=0
    // reverts). Iris never clears the pack's voxy channel (measured: covered-pixel
    // alpha pinned at 255 while BSL's designed water alpha is mix(0.70,1,fresnel)
    // ~0.85; stale coverage persisting across camera moves). The pack's alpha
    // blend func (ONE, ONE_MINUS_SRC_ALPHA) over a never-cleared target converges
    // to 1.0 within ~4 frames, so deferred1's `mix(color, rgb, a)` composited the
    // water FULLY OPAQUE and smeared each frame's waves into a temporal average.
    // Only draw buffer 0 (colortex16) is cleared — attachment 1 is colortex1,
    // which carries the frame's real gbuffer data.
    private static final boolean TRANS_CLEAR = !"0".equals(System.getenv("VOXY_VX_TRANS_CLEAR"));
    private static final float[] TRANS_CLEAR_ZERO = new float[4];

    private static int ct5AltTexture(net.irisshaders.iris.pipeline.IrisRenderingPipeline ipipe) {
        if (!SSR_ALT) return 0;
        try {
            var rt = ((me.cortex.voxy.client.mixin.iris.IrisRenderingPipelineAccessor) ipipe).getRenderTargets();
            return rt.getOrCreate(5).getAltTexture();
        } catch (Throwable t) {
            return 0;
        }
    }

    private static boolean buildAttempted;
    private static boolean buildOk;
    private static Prog opaque;
    private static Prog trans;
    private static int resolveVao;

    // Diagnostic (VOXY_VX_DUMP_OUT=1): objectively measure what BSL's resolve actually
    // produces. gbufferData0 here is the LIT albedo (this BSL voxy program applies
    // GetLighting in-place in the gbuffer pass, not a deferred pass), so reading back the
    // output colortex's mean RGB tells us if BSL output is dark (UBO/uniform fault) vs
    // bright-but-darkened-downstream. Also dumps the first UBO scratch floats so a
    // zero/identity vxModelView (broken normal -> NoL=0 -> no sun) is visible.
    private static final boolean DUMP_OUT = "1".equals(System.getenv("VOXY_VX_DUMP_OUT"));
    private static int dumpFrame;

    // Diagnostic: VOXY_VX_DEBUG_PLANE=albedo|tint|misc|depth bypasses the pack and
    // writes the chosen raw material plane straight to the pack's first colortex,
    // so we can see whether the planes themselves carry data (textures/colour) or
    // the problem is in the pack invocation.
    private static final int DEBUG_PLANE = parseDebugPlane();
    private static int debugProg = -1;
    private static int uDbgPlane;
    private static int debugFbo;
    private static int[] debugAttached = new int[0];

    private static int parseDebugPlane() {
        String v = System.getenv("VOXY_VX_DEBUG_PLANE");
        if (v == null) return -1;
        return switch (v.trim().toLowerCase()) {
            case "albedo", "0" -> 0;
            case "tint", "1" -> 1;
            case "misc", "2" -> 2;
            case "depth", "3" -> 3;
            default -> -1;
        };
    }

    private static final String DEBUG_FS = """
            #version 410 core
            uniform sampler2DRect uVxAlbedo;
            uniform sampler2DRect uVxTint;
            uniform sampler2DRect uVxMisc;
            uniform sampler2DRect uVxDepth;
            uniform int uVxDebugPlane;
            out vec4 o0;
            void main() {
                ivec2 sz = textureSize(uVxDepth);
                vec2 t = vec2(gl_FragCoord.x, float(sz.y) - gl_FragCoord.y);
                float d = dot(texture(uVxDepth, t).rgb, vec3(1.0, 1.0/255.0, 1.0/65025.0));
                if (d <= 0.0 || d >= 0.9999999) discard;
                vec4 a = texture(uVxAlbedo, t);
                vec3 c;
                if (uVxDebugPlane == 0) c = a.rgb / max(a.a, 0.0001);
                else if (uVxDebugPlane == 1) c = texture(uVxTint, t).rgb;
                else if (uVxDebugPlane == 2) c = texture(uVxMisc, t).rgb;
                else c = vec3(d);
                o0 = vec4(c, 1.0);
            }
            """;

    /** Compile both resolve programs once. Returns true if the opaque program links. */
    public static boolean build(IrisVoxyRenderPipelineData data) {
        if (buildAttempted) return buildOk;
        buildAttempted = true;
        opaque = buildProg(data, false);
        trans = buildProg(data, true);
        resolveVao = glGenVertexArrays();
        buildOk = opaque != null;
        Logger.info("MetalVxResolvePass.build: opaque=" + (opaque != null ? "OK" : "FAIL")
                + " translucent=" + (trans != null ? "OK" : "FAIL"));
        if (DUMP_OUT && data.getUniforms() != null) {
            String lay = data.getUniforms().layout();
            Logger.info("[VX-OUT] UBO size=" + data.getUniforms().size() + " layoutChars=" + (lay == null ? 0 : lay.length()));
            if (lay != null) Logger.info("[VX-OUT] UBO layout head: " + lay.substring(0, Math.min(900, lay.length())).replace("\n", " "));
        }
        return buildOk;
    }

    private static Prog buildProg(IrisVoxyRenderPipelineData data, boolean translucent) {
        String fs = assembleFragment(data, translucent);
        if (fs == null) return null;
        int prog = me.cortex.voxy.client.core.util.VxIrisSideChannel.compile(
                RESOLVE_VERT, fs, "MetalVxResolve." + (translucent ? "trans" : "opaque"));
        if (prog == 0) return null;
        Prog p = new Prog();
        p.prog = prog;
        int prev = glGetInteger(GL_CURRENT_PROGRAM);
        glUseProgram(prog);
        setTexUnit(prog, "uVxAlbedo", 0);
        setTexUnit(prog, "uVxTint", 1);
        setTexUnit(prog, "uVxMisc", 2);
        setTexUnit(prog, "uVxDepth", 3);
        p.uDepthIsWindow = glGetUniformLocation(prog, "uVxDepthIsWindow");
        // Match the working VxIrisSideChannel depth convention: the bridge packs the
        // LOD depth needing a *0.5+0.5 window remap unless VOXY_LOD_METAL_NDC is set.
        if (p.uDepthIsWindow >= 0) {
            glUniform1i(p.uDepthIsWindow,
                    me.cortex.voxy.client.core.rendering.util.MetalMvpUtil.METAL_NDC_REMAP ? 1 : 0);
        }
        // Pack samplers: assign units 6.. in the ImageSet's bindingFunction order
        // (Iris's addGbufferOrShadowSamplers order), NOT voxy.json/samplerDecls order —
        // otherwise every pack sampler lands on the wrong unit and BSL reads the wrong
        // shadow/depth/light textures (the uniform-dark LOD bug).
        if (data.getImageSet() != null && data.getImageSet().orderedNames() != null) {
            int unit = 6;
            for (var name : data.getImageSet().orderedNames()) {
                int loc = glGetUniformLocation(prog, name);
                if (loc >= 0) glUniform1i(loc, unit);
                if (translucent && "gaux2".equals(name)) p.gaux2Unit = unit;
                unit++; p.samplerCount++;
            }
        }
        if (translucent) {
            Logger.info("[Metal-LODTEST] vx SSR mirror rebind " + (SSR_ALT && p.gaux2Unit >= 0 ? "ON" : "OFF")
                    + " (gaux2 unit=" + p.gaux2Unit + " -> colortex5 ALT, deferred1's last-frame reflection"
                    + " output); VOXY_VX_SSR_ALT=0 reverts to the MAIN-side binding");
            Logger.info("[Metal-LODTEST] vx colortex16 per-frame clear " + (TRANS_CLEAR ? "ON" : "OFF")
                    + " (Iris never clears the voxy channel -> blended alpha accumulated to 1.0 = opaque water);"
                    + " VOXY_VX_TRANS_CLEAR=0 reverts");
        }
        if (data.getUniforms() != null) {
            p.uboSize = data.getUniforms().size();
            int bi = glGetUniformBlockIndex(prog, "ShaderUniformBindings");
            if (bi != GL_INVALID_INDEX) glUniformBlockBinding(prog, bi, 5);
            p.ubo = glGenBuffers();
            p.uboScratch = org.lwjgl.system.MemoryUtil.nmemAlloc(p.uboSize);
        }
        p.fbo = glGenFramebuffers();
        glUseProgram(prev);
        return p;
    }

    private static void setTexUnit(int prog, String name, int unit) {
        int loc = glGetUniformLocation(prog, name);
        if (loc >= 0) glUniform1i(loc, unit);
    }

    /**
     * Per-frame resolve: decode the LOD depth into the pack's vxDepthTex* samplers,
     * then run the pack's voxy_opaque + voxy_translucent over the Metal material
     * g-buffer planes, writing the pack's colortexes. depthRect args are the RAW
     * packed depth bridges. GL state is saved/restored.
     */
    public static void resolve(IrisVoxyRenderPipelineData data,
                               net.irisshaders.iris.pipeline.IrisRenderingPipeline ipipe,
                               int oP0, int oP1, int oP2, int opaqueDepthRect,
                               int tP0, int tP1, int tP2, int transDepthRect,
                               int fbw, int fbh) {
        if (!build(data)) return;
        if (oP0 == 0 || opaqueDepthRect == 0) return;
        if (DUMP_OUT) dumpFrame++;
        var sc = me.cortex.voxy.client.core.util.VxIrisSideChannel.getOrCreate();
        var ndc = me.cortex.voxy.client.core.rendering.util.MetalMvpUtil.METAL_NDC_REMAP;

        int prevDrawFb = glGetInteger(GL_DRAW_FRAMEBUFFER_BINDING);
        int prevReadFb = glGetInteger(GL_READ_FRAMEBUFFER_BINDING);
        int prevProgram = glGetInteger(GL_CURRENT_PROGRAM);
        int prevVao = glGetInteger(GL_VERTEX_ARRAY_BINDING);
        int prevActiveTex = glGetInteger(GL_ACTIVE_TEXTURE);
        int[] prevViewport = new int[4];
        glGetIntegerv(GL_VIEWPORT, prevViewport);
        boolean prevDepth = glIsEnabled(GL_DEPTH_TEST);
        boolean prevBlend = glIsEnabled(GL_BLEND);
        boolean prevCull = glIsEnabled(GL_CULL_FACE);
        boolean prevScissor = glIsEnabled(GL_SCISSOR_TEST);
        try {
            if (DEBUG_PLANE >= 0) {
                // Diagnostic: write the raw chosen plane straight to colortex0, bypassing
                // the pack — shows whether the material g-buffer planes carry data.
                runDebug(oP0, oP1, oP2, opaqueDepthRect, data.resolveOpaqueTargetsNow(ipipe), fbw, fbh);
                return;
            }
            // Fill the pack's vxDepthTexOpaque/Trans samplers (the pack reads them
            // for its own depth needs; our resolve reads the raw bridge for gl_FragDepth).
            if (!sc.resolve(oP0, opaqueDepthRect, fbw, fbh, ndc)) return;
            if (DUMP_OUT && dumpFrame % 300 == 100) { sc.dumpDepthStats(fbw, fbh); dumpAoStats(ipipe, sc, fbw, fbh); }
            int[] opaqueTargets = data.resolveOpaqueTargetsNow(ipipe);
            runOne(data, opaque, oP0, oP1, oP2, opaqueDepthRect, opaqueTargets, fbw, fbh, false, 0);

            if (trans != null && tP0 != 0 && transDepthRect != 0) {
                sc.resolveTrans(tP0, transDepthRect, fbw, fbh, ndc);
                int[] transTargets = data.resolveTranslucentTargetsNow(ipipe);
                runOne(data, trans, tP0, tP1, tP2, transDepthRect, transTargets, fbw, fbh, true,
                        ct5AltTexture(ipipe));
            }
        } catch (Throwable t) {
            Logger.warn("MetalVxResolvePass.resolve failed: " + t.getMessage());
        } finally {
            glUseProgram(prevProgram);
            glBindVertexArray(prevVao);
            glActiveTexture(prevActiveTex);
            if (prevDepth) glEnable(GL_DEPTH_TEST); else glDisable(GL_DEPTH_TEST);
            if (prevBlend) glEnable(GL_BLEND); else glDisable(GL_BLEND);
            if (prevCull) glEnable(GL_CULL_FACE); else glDisable(GL_CULL_FACE);
            if (prevScissor) glEnable(GL_SCISSOR_TEST); else glDisable(GL_SCISSOR_TEST);
            glViewport(prevViewport[0], prevViewport[1], prevViewport[2], prevViewport[3]);
            glBindBufferBase(GL_UNIFORM_BUFFER, 5, 0);
            glBindFramebuffer(GL_DRAW_FRAMEBUFFER, prevDrawFb);
            glBindFramebuffer(GL_READ_FRAMEBUFFER, prevReadFb);
        }
    }

    /**
     * Translucent-only resolve (issue #11 mergeable shape): runs ONLY the pack's
     * voxy_translucent over the translucent (water) material g-buffer → the pack's
     * translucent targets (colortex16), leaving the OPAQUE LOD layer entirely on the base
     * path (VxContractInjector handles opaque colour + vxDepthTexOpaque). So water gets real
     * BSL water shading while opaque LODs stay byte-identical to dev. GL state saved/restored.
     */
    public static void resolveTranslucentOnly(IrisVoxyRenderPipelineData data,
                               net.irisshaders.iris.pipeline.IrisRenderingPipeline ipipe,
                               int tP0, int tP1, int tP2, int transDepthRect,
                               int fbw, int fbh) {
        if (!build(data)) return;
        if (trans == null || tP0 == 0 || transDepthRect == 0) return;
        var sc = me.cortex.voxy.client.core.util.VxIrisSideChannel.getOrCreate();
        var ndc = me.cortex.voxy.client.core.rendering.util.MetalMvpUtil.METAL_NDC_REMAP;

        int prevDrawFb = glGetInteger(GL_DRAW_FRAMEBUFFER_BINDING);
        int prevReadFb = glGetInteger(GL_READ_FRAMEBUFFER_BINDING);
        int prevProgram = glGetInteger(GL_CURRENT_PROGRAM);
        int prevVao = glGetInteger(GL_VERTEX_ARRAY_BINDING);
        int prevActiveTex = glGetInteger(GL_ACTIVE_TEXTURE);
        int[] prevViewport = new int[4];
        glGetIntegerv(GL_VIEWPORT, prevViewport);
        boolean prevDepth = glIsEnabled(GL_DEPTH_TEST);
        boolean prevBlend = glIsEnabled(GL_BLEND);
        boolean prevCull = glIsEnabled(GL_CULL_FACE);
        boolean prevScissor = glIsEnabled(GL_SCISSOR_TEST);
        try {
            sc.resolveTrans(tP0, transDepthRect, fbw, fbh, ndc);
            if (DUMP_OUT && (++dumpFrame % 300 == 100)) {
                // Decisive probe: is the WATER depth per-pixel smooth or chunk-stepped?
                // The pow(8) Fresnel in voxy_translucent amplifies any depth steps into the
                // flat "square" artifacts. Low roughness + dense neighbours => depth is fine,
                // squares are elsewhere; high/stepped => depth-reconstruction is the cause.
                sc.dumpDepthStats("trans", sc.fboTransId(), fbw, fbh);
            }
            int[] transTargets = data.resolveTranslucentTargetsNow(ipipe);
            runOne(data, trans, tP0, tP1, tP2, transDepthRect, transTargets, fbw, fbh, true,
                    ct5AltTexture(ipipe));
            // Adjudicates the SSR mirror question empirically: which colortex5 side
            // actually carries deferred1's reflection data (and what the undefined
            // side reads), plus whether colortex16's alpha is the designed ~0.7-0.95.
            if (DUMP_OUT && dumpFrame % 300 == 100) dumpCt5Stats(ipipe, fbw, fbh);
        } catch (Throwable t) {
            Logger.warn("MetalVxResolvePass.resolveTranslucentOnly failed: " + t.getMessage());
        } finally {
            glUseProgram(prevProgram);
            glBindVertexArray(prevVao);
            glActiveTexture(prevActiveTex);
            if (prevDepth) glEnable(GL_DEPTH_TEST); else glDisable(GL_DEPTH_TEST);
            if (prevBlend) glEnable(GL_BLEND); else glDisable(GL_BLEND);
            if (prevCull) glEnable(GL_CULL_FACE); else glDisable(GL_CULL_FACE);
            if (prevScissor) glEnable(GL_SCISSOR_TEST); else glDisable(GL_SCISSOR_TEST);
            glViewport(prevViewport[0], prevViewport[1], prevViewport[2], prevViewport[3]);
            glBindBufferBase(GL_UNIFORM_BUFFER, 5, 0);
            glBindFramebuffer(GL_DRAW_FRAMEBUFFER, prevDrawFb);
            glBindFramebuffer(GL_READ_FRAMEBUFFER, prevReadFb);
        }
    }

    private static int aoReadFbo;

    /**
     * VOXY_VX_DUMP_OUT=1 objective confirmation that BSL's SSAO (colortex4) is what blacks
     * out opaque LODs. Reads back colortex4 (deferred.glsl writes ao into .r) correlated
     * with vxDepthTexOpaque coverage: for LOD pixels (vxZ&lt;1) reports the AO mean/min and a
     * histogram. colortex4 is last-frame at this hook, but with a static camera that's the
     * value deferred1 multiplies the LOD colour by. If AO is ~0 over LOD pixels -> confirmed.
     */
    private static void dumpAoStats(net.irisshaders.iris.pipeline.IrisRenderingPipeline ipipe,
                                    me.cortex.voxy.client.core.util.VxIrisSideChannel sc, int fbw, int fbh) {
        try {
            var rt = ((me.cortex.voxy.client.mixin.iris.IrisRenderingPipelineAccessor) ipipe).getRenderTargets();
            var ct4 = rt.getOrCreate(4);
            int prevRead = glGetInteger(GL_READ_FRAMEBUFFER_BINDING);
            if (aoReadFbo == 0) aoReadFbo = glGenFramebuffers();
            // Read vxDepthTexOpaque (coverage) and colortex4 (AO) over the same grid.
            java.nio.FloatBuffer depthBuf = org.lwjgl.system.MemoryUtil.memAllocFloat(fbw * fbh);
            java.nio.FloatBuffer aoMain = org.lwjgl.system.MemoryUtil.memAllocFloat(fbw * fbh);
            java.nio.FloatBuffer aoAlt = org.lwjgl.system.MemoryUtil.memAllocFloat(fbw * fbh);
            try {
                glBindFramebuffer(GL_READ_FRAMEBUFFER, sc.fboOpaqueId());
                glReadPixels(0, 0, fbw, fbh, GL_DEPTH_COMPONENT, GL_FLOAT, depthBuf);
                for (int side = 0; side < 2; side++) {
                    int tex = side == 0 ? ct4.getMainTexture() : ct4.getAltTexture();
                    glBindFramebuffer(GL_READ_FRAMEBUFFER, aoReadFbo);
                    glFramebufferTexture2D(GL_READ_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, tex, 0);
                    glReadBuffer(GL_COLOR_ATTACHMENT0);
                    glReadPixels(0, 0, fbw, fbh, GL_RED, GL_FLOAT, side == 0 ? aoMain : aoAlt);
                }
                // Correlate at the same 24x48 grid the depth stat uses.
                for (int side = 0; side < 2; side++) {
                    var ao = side == 0 ? aoMain : aoAlt;
                    int lodN = 0, lodDark = 0, emptyN = 0; double lodSum = 0, emptySum = 0; float lodMin = 2f, lodMax = -1f;
                    for (int ry = 0; ry < 24; ry++) {
                        int y = fbh / 3 + ry * (fbh * 2 / 3) / 24;
                        for (int rx = 0; rx < 48; rx++) {
                            int x = rx * fbw / 48;
                            float d = depthBuf.get(y * fbw + x);
                            float a = ao.get(y * fbw + x);
                            if (d < 0.99999f) { lodN++; lodSum += a; if (a < 0.1f) lodDark++; if (a < lodMin) lodMin = a; if (a > lodMax) lodMax = a; }
                            else { emptyN++; emptySum += a; }
                        }
                    }
                    Logger.info(String.format("[VX-OUT] colortex4 AO (%s): LODpx=%d aoMean=%.3f aoMin=%.3f aoMax=%.3f darkFrac(<0.1)=%.0f%% | emptyAoMean=%.3f",
                            side == 0 ? "main" : "alt", lodN, lodN > 0 ? lodSum / lodN : -1, lodN > 0 ? lodMin : -1,
                            lodN > 0 ? lodMax : -1, 100.0 * lodDark / Math.max(1, lodN), emptyN > 0 ? emptySum / emptyN : -1));
                }
            } finally {
                glBindFramebuffer(GL_READ_FRAMEBUFFER, prevRead);
                org.lwjgl.system.MemoryUtil.memFree(depthBuf);
                org.lwjgl.system.MemoryUtil.memFree(aoMain);
                org.lwjgl.system.MemoryUtil.memFree(aoAlt);
            }
        } catch (Throwable t) {
            Logger.warn("[VX-OUT] AO readback failed: " + t.getMessage());
        }
    }

    private static void runDebug(int p0, int p1, int p2, int depthRect, int[] targets, int fbw, int fbh) {
        if (targets == null || targets.length == 0 || p0 == 0 || depthRect == 0) return;
        if (resolveVao == 0) resolveVao = glGenVertexArrays();
        if (debugProg == -1) {
            debugProg = me.cortex.voxy.client.core.util.VxIrisSideChannel.compile(RESOLVE_VERT, DEBUG_FS, "MetalVxResolve.debug");
            if (debugProg != 0) {
                int prev = glGetInteger(GL_CURRENT_PROGRAM);
                glUseProgram(debugProg);
                setTexUnit(debugProg, "uVxAlbedo", 0);
                setTexUnit(debugProg, "uVxTint", 1);
                setTexUnit(debugProg, "uVxMisc", 2);
                setTexUnit(debugProg, "uVxDepth", 3);
                uDbgPlane = glGetUniformLocation(debugProg, "uVxDebugPlane");
                glUseProgram(prev);
                debugFbo = glGenFramebuffers();
            }
            Logger.info("MetalVxResolvePass DEBUG plane=" + DEBUG_PLANE + " prog=" + debugProg);
        }
        if (debugProg == 0) return;
        boolean dirty = debugAttached.length != targets.length;
        for (int i = 0; !dirty && i < targets.length; i++) dirty = debugAttached[i] != targets[i];
        glBindFramebuffer(GL_FRAMEBUFFER, debugFbo);
        if (dirty) {
            int[] bufs = new int[targets.length];
            for (int i = 0; i < targets.length; i++) {
                glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0 + i, GL_TEXTURE_2D, targets[i], 0);
                bufs[i] = GL_COLOR_ATTACHMENT0 + i;
            }
            glDrawBuffers(bufs);
            debugAttached = java.util.Arrays.copyOf(targets, targets.length);
        }
        glViewport(0, 0, fbw, fbh);
        glDisable(GL_DEPTH_TEST); glDisable(GL_CULL_FACE); glDisable(GL_SCISSOR_TEST); glDisable(GL_BLEND);
        glUseProgram(debugProg);
        glBindVertexArray(resolveVao);
        glActiveTexture(GL_TEXTURE0); glBindTexture(GL_TEXTURE_RECTANGLE, p0);
        glActiveTexture(GL_TEXTURE1); glBindTexture(GL_TEXTURE_RECTANGLE, p1);
        glActiveTexture(GL_TEXTURE2); glBindTexture(GL_TEXTURE_RECTANGLE, p2);
        glActiveTexture(GL_TEXTURE3); glBindTexture(GL_TEXTURE_RECTANGLE, depthRect);
        glActiveTexture(GL_TEXTURE0);
        glUniform1i(uDbgPlane, DEBUG_PLANE);
        glDrawArrays(GL_TRIANGLE_STRIP, 0, 4);
    }

    private static void runOne(IrisVoxyRenderPipelineData data, Prog p,
                               int plane0, int plane1, int plane2, int depthRect,
                               int[] targets, int fbw, int fbh, boolean blend, int ssrMirrorTex) {
        if (p == null || targets == null || targets.length == 0) return;
        if (plane0 == 0 || depthRect == 0) return;
        boolean dirty = p.attached.length != targets.length;
        for (int i = 0; !dirty && i < targets.length; i++) dirty = p.attached[i] != targets[i];
        glBindFramebuffer(GL_FRAMEBUFFER, p.fbo);
        if (dirty) {
            int[] bufs = new int[targets.length];
            for (int i = 0; i < targets.length; i++) {
                glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0 + i, GL_TEXTURE_2D, targets[i], 0);
                bufs[i] = GL_COLOR_ATTACHMENT0 + i;
            }
            glDrawBuffers(bufs);
            if (glCheckFramebufferStatus(GL_FRAMEBUFFER) != GL_FRAMEBUFFER_COMPLETE) {
                Logger.error("MetalVxResolvePass: resolve FBO incomplete");
                return;
            }
            p.attached = java.util.Arrays.copyOf(targets, targets.length);
        }
        glViewport(0, 0, fbw, fbh);
        glDisable(GL_DEPTH_TEST);
        glDisable(GL_CULL_FACE);
        glDisable(GL_SCISSOR_TEST);
        if (blend && TRANS_CLEAR) {
            // See TRANS_CLEAR: Iris does not clear colortex16; without this the
            // blended alpha accumulates to 1.0 -> opaque LOD water.
            glClearBufferfv(GL_COLOR, 0, TRANS_CLEAR_ZERO);
        }
        glUseProgram(p.prog);
        glBindVertexArray(resolveVao);
        glActiveTexture(GL_TEXTURE0); glBindTexture(GL_TEXTURE_RECTANGLE, plane0);
        glActiveTexture(GL_TEXTURE1); glBindTexture(GL_TEXTURE_RECTANGLE, plane1);
        glActiveTexture(GL_TEXTURE2); glBindTexture(GL_TEXTURE_RECTANGLE, plane2);
        glActiveTexture(GL_TEXTURE3); glBindTexture(GL_TEXTURE_RECTANGLE, depthRect);
        glActiveTexture(GL_TEXTURE0);
        if (data.getImageSet() != null) data.getImageSet().bindingFunction().accept(6);
        if (ssrMirrorTex != 0 && p.gaux2Unit >= 0) {
            // See SSR_ALT: keep the sampler object bindingFunction bound (filtering),
            // replace only the texture on gaux2's unit.
            glActiveTexture(GL_TEXTURE0 + p.gaux2Unit);
            glBindTexture(GL_TEXTURE_2D, ssrMirrorTex);
            glActiveTexture(GL_TEXTURE0);
        }
        if (p.ubo != 0 && data.getUniforms() != null) {
            data.getUniforms().updater().accept(p.uboScratch);
            glBindBuffer(GL_UNIFORM_BUFFER, p.ubo);
            glBufferData(GL_UNIFORM_BUFFER,
                    org.lwjgl.system.MemoryUtil.memByteBuffer(p.uboScratch, p.uboSize), GL_DYNAMIC_DRAW);
            glBindBufferBase(GL_UNIFORM_BUFFER, 5, p.ubo);
        }
        if (blend && data.getBlender() != null) data.getBlender().run();
        else glDisable(GL_BLEND);
        glDrawArrays(GL_TRIANGLE_STRIP, 0, 4);
        // Both stages: the translucent program had never had a runtime readback
        // (the old !blend gate), which left the water-resolve output unmeasured.
        if (DUMP_OUT) dumpResolveOutput(p, blend ? "trans" : "opaque", fbw, fbh);
        for (int i = 0; i < p.samplerCount; i++) {
            glActiveTexture(GL_TEXTURE0 + 6 + i);
            glBindTexture(GL_TEXTURE_2D, 0);
        }
        glActiveTexture(GL_TEXTURE0);
    }

    /**
     * VOXY_VX_DUMP_OUT=1 objective ground truth: read back the resolve's attachment 0
     * (opaque: colortex0's LIT albedo; trans: colortex16's premultiplied water) and report
     * the mean RGBA over the pixels the resolve actually wrote (alpha &gt; 0 — the target is
     * frame-cleared to 0 before our SOLID-head hook), plus the first UBO scratch floats
     * (vxModelView etc.; pair with the [VX-OUT] UBO layout head log) so zero/identity
     * matrices or bad scalars (isEyeInWater and friends) are directly visible.
     * Periodic to keep the readback off the per-frame hot path.
     */
    private static void dumpResolveOutput(Prog p, String label, int fbw, int fbh) {
        if (dumpFrame % 300 != 100) return;
        if (p.uboScratch != 0 && p.uboSize >= 192) {
            StringBuilder fl = new StringBuilder();
            for (int i = 0; i < 48; i++) fl.append(String.format(" %.3f", org.lwjgl.system.MemoryUtil.memGetFloat(p.uboScratch + (long) i * 4)));
            Logger.info("[VX-OUT] " + label + " uboScratch[0..47]:" + fl);
        }
        java.nio.ByteBuffer buf = org.lwjgl.system.MemoryUtil.memAlloc(fbw * fbh * 4);
        try {
            glReadBuffer(GL_COLOR_ATTACHMENT0);
            org.lwjgl.opengl.GL11C.glReadPixels(0, 0, fbw, fbh, GL_RGBA, org.lwjgl.opengl.GL11C.GL_UNSIGNED_BYTE, buf);
            long sr = 0, sg = 0, sb = 0, sa = 0; int covered = 0;
            int n = fbw * fbh;
            for (int i = 0; i < n; i++) {
                int a = buf.get(i * 4 + 3) & 0xFF;
                if (a == 0) continue;
                covered++;
                sr += buf.get(i * 4) & 0xFF; sg += buf.get(i * 4 + 1) & 0xFF;
                sb += buf.get(i * 4 + 2) & 0xFF; sa += a;
            }
            int c = Math.max(1, covered);
            Logger.info(String.format("[VX-OUT] %s resolve out meanRGBA=(%d,%d,%d,%d) over %d covered px (%.2f%% of %dx%d)",
                    label, sr / c, sg / c, sb / c, sa / c, covered, 100.0 * covered / n, fbw, fbh));
        } catch (Throwable t) {
            Logger.warn("[VX-OUT] " + label + " readback failed: " + t.getMessage());
        } finally {
            org.lwjgl.system.MemoryUtil.memFree(buf);
        }
    }

    private static int ct5ReadFbo;

    /**
     * VOXY_VX_DUMP_OUT=1: mean RGBA of colortex5 MAIN and ALT. Decides the SSR mirror
     * question with data instead of the inferred Iris flip convention: ALT should carry
     * deferred1's bright reflection image (non-zero mean, alpha 1 over terrain / 0 over
     * sky), MAIN should be the never-written side. If it comes back reversed, flip the
     * SSR_ALT bind to the other side.
     */
    private static void dumpCt5Stats(net.irisshaders.iris.pipeline.IrisRenderingPipeline ipipe, int fbw, int fbh) {
        try {
            var rt = ((me.cortex.voxy.client.mixin.iris.IrisRenderingPipelineAccessor) ipipe).getRenderTargets();
            var ct5 = rt.getOrCreate(5);
            int prevRead = glGetInteger(GL_READ_FRAMEBUFFER_BINDING);
            if (ct5ReadFbo == 0) ct5ReadFbo = glGenFramebuffers();
            java.nio.ByteBuffer buf = org.lwjgl.system.MemoryUtil.memAlloc(fbw * fbh * 4);
            try {
                for (int side = 0; side < 2; side++) {
                    int tex = side == 0 ? ct5.getMainTexture() : ct5.getAltTexture();
                    glBindFramebuffer(GL_READ_FRAMEBUFFER, ct5ReadFbo);
                    glFramebufferTexture2D(GL_READ_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, tex, 0);
                    glReadBuffer(GL_COLOR_ATTACHMENT0);
                    org.lwjgl.opengl.GL11C.glReadPixels(0, 0, fbw, fbh, GL_RGBA, org.lwjgl.opengl.GL11C.GL_UNSIGNED_BYTE, buf);
                    long sr = 0, sg = 0, sb = 0, sa = 0; int aHigh = 0;
                    int n = fbw * fbh;
                    for (int i = 0; i < n; i++) {
                        sr += buf.get(i * 4) & 0xFF; sg += buf.get(i * 4 + 1) & 0xFF;
                        sb += buf.get(i * 4 + 2) & 0xFF;
                        int a = buf.get(i * 4 + 3) & 0xFF;
                        sa += a; if (a > 128) aHigh++;
                    }
                    Logger.info(String.format("[VX-OUT] colortex5 (%s) tex=%d meanRGBA=(%d,%d,%d,%d) aOver0.5=%.1f%%",
                            side == 0 ? "main" : "alt", tex, sr / n, sg / n, sb / n, sa / n, 100.0 * aHigh / n));
                }
            } finally {
                glBindFramebuffer(GL_READ_FRAMEBUFFER, prevRead);
                org.lwjgl.system.MemoryUtil.memFree(buf);
            }
        } catch (Throwable t) {
            Logger.warn("[VX-OUT] colortex5 readback failed: " + t.getMessage());
        }
    }
}
