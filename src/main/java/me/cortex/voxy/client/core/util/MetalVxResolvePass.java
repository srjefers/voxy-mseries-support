package me.cortex.voxy.client.core.util;

import me.cortex.voxy.client.iris.IrisVoxyRenderPipelineData;
import me.cortex.voxy.client.core.interop.GlInteropState;
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
 *   [initialize pixel coordinates before pack globals; main(): decode planes -> set depth -> emit]
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

    private MetalVxResolvePass() {}

    /**
     * Assemble the resolve fragment source for one stage.
     *
     * @param translucent selects the pack's translucent patch text.
     * @return assembled GLSL, or null when the pack's contract can't be
     *         resolved this way (SSBO declarations need GL 4.3).
     */
    /** BSL's historical rewrites are opt-in; generic contracts preserve pack lighting. */
    public static String assembleFragment(IrisVoxyRenderPipelineData data, boolean translucent) {
        if (BslMaterialCompatibility.enabled()) return BslMaterialCompatibility.assemble(data, translucent);
        return MetalMaterialShader.assemble(data, translucent);
    }

    public static final String RESOLVE_VERT = """
            #version 410 core
            void main() {
                vec2 p = vec2((gl_VertexID & 1) * 2, (gl_VertexID & 2));
                gl_Position = vec4(p * 2.0 - 1.0, 0.0, 1.0);
            }
            """;

    public static String assembleTranslucentSpike(IrisVoxyRenderPipelineData data) {
        return BslMaterialCompatibility.spike(data);
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
        // Water ring parity live radius; -1 for the opaque program / PARITY=0 /
        // needle-miss builds (guarded at use). Prog is destroyed and rebuilt by
        // the pipeline-generation path, so no reset() changes are needed.
        int uRingCull = -1;
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

    // Unbind Iris's leftover per-unit GL sampler objects on units 0-3 for the
    // resolve draw (VOXY_VX_SAMPLER_FIX=0 reverts). A stale sampler with mip
    // filtering makes the mip-less RECT material planes INCOMPLETE, and
    // incomplete samplers return constant (0,0,0,1) — which fed the whole
    // translucent resolve black albedo/tint and customId 65280 (never water)
    // while depth kept working on unit 3, i.e. the dark/opaque/textureless
    // LOD water root cause. See runOne's draw-instant probe notes.
    private static final boolean SAMPLER_FIX = !"0".equals(System.getenv("VOXY_VX_SAMPLER_FIX"));
    private static final float[] TRANS_CLEAR_ZERO = new float[4];

    private static int ct5AltTexture(net.irisshaders.iris.pipeline.IrisRenderingPipeline ipipe) {
        if (!BslMaterialCompatibility.enabled() || !SSR_ALT) return 0;
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

    // ---- Iris pipeline recreation handling (VOXY_VX_RESOLVE_REBUILD=0 reverts) ----
    // The build is keyed to ONE Iris pipeline generation: the GL programs embed
    // that generation's patch text/option set, and uboScratch is nmemAlloc-ed at
    // build time sized for that generation's uniform layout. Iris recreates its
    // pipeline on EVERY world rejoin ("Reloading pipeline on dimension change")
    // and on shader option toggles; each recreation mints a fresh
    // IrisVoxyRenderPipelineData (MixinIrisRenderingPipeline ctor hook). Running
    // the old build against a new generation means stale programs at best and a
    // native scratch OVERRUN at worst — runOne feeds the CURRENT data's
    // getUniforms().updater() the OLD-size allocation, so a larger new layout is
    // heap corruption. Track the identities the build ran against and destroy +
    // lazily rebuild when either changes (both resolve entry points run on the
    // render thread with the GL context current, so destruction here is safe).
    private static IrisVoxyRenderPipelineData builtData;
    private static Object builtIrisPipeline;

    private static void checkPipelineGeneration(IrisVoxyRenderPipelineData data, Object ipipe) {
        if (buildAttempted && (builtData != data || builtIrisPipeline != ipipe)) {
            Logger.info("[Metal-LODTEST] vx resolve: Iris pipeline recreated (dataChanged="
                    + (builtData != data) + " irisChanged=" + (builtIrisPipeline != ipipe)
                    + ") — destroying the stale build and rebuilding against the live pipeline");
            reset();
        }
        builtData = data;
        builtIrisPipeline = ipipe;
    }

    /**
     * Destroy everything build()/the lazy debug paths created — GL programs,
     * FBOs, the UBO + its native scratch, the VAO — and clear the static build
     * state so the next resolve frame rebuilds against the live Iris pipeline.
     * Must run on the render thread with a GL context current. No-op when
     * nothing was ever built (the GL backend never reaches this class's build
     * paths). Resource destruction and layout-generation checks are mandatory.
     */
    public static void reset() {
        resolveFailureCount = 0;
        nextResolveFailureLog = 0;
        boolean hadBuild = buildAttempted || debugProg != -1 || resolveVao != 0;
        if (opaque != null) { freeProg(opaque); opaque = null; }
        if (trans != null) { freeProg(trans); trans = null; }
        if (resolveVao != 0) { glDeleteVertexArrays(resolveVao); resolveVao = 0; }
        if (debugProg > 0) glDeleteProgram(debugProg);
        debugProg = -1; // lazy-compile sentinel — runDebug rebuilds on demand
        if (debugFbo != 0) { glDeleteFramebuffers(debugFbo); debugFbo = 0; }
        debugAttached = new int[0];
        if (aoReadFbo != 0) { glDeleteFramebuffers(aoReadFbo); aoReadFbo = 0; }
        if (planeReadFbo != 0) { glDeleteFramebuffers(planeReadFbo); planeReadFbo = 0; }
        if (ct5ReadFbo != 0) { glDeleteFramebuffers(ct5ReadFbo); ct5ReadFbo = 0; }
        buildAttempted = false;
        buildOk = false;
        builtData = null;
        builtIrisPipeline = null;
        if (hadBuild) {
            Logger.info("[Metal-LODTEST] vx resolve reset: stale GL programs/FBOs/UBO scratch destroyed;"
                    + " next contract frame rebuilds (expect the vx resolve marker lines to re-print)");
        }
    }

    private static void freeProg(Prog p) {
        if (p.prog != 0) glDeleteProgram(p.prog);
        if (p.ubo != 0) glDeleteBuffers(p.ubo);
        if (p.fbo != 0) glDeleteFramebuffers(p.fbo);
        if (p.uboScratch != 0) org.lwjgl.system.MemoryUtil.nmemFree(p.uboScratch);
        p.prog = 0;
        p.ubo = 0;
        p.fbo = 0;
        p.uboScratch = 0;
        p.attached = new int[0];
    }

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
        try (var state = new GlInteropState(resolveTextureUnits(data), 5)) {
            return buildPrograms(data);
        }
    }

    public static boolean prepare(IrisVoxyRenderPipelineData data, Object irisPipeline) {
        checkPipelineGeneration(data, irisPipeline);
        return build(data);
    }

    private static boolean buildPrograms(IrisVoxyRenderPipelineData data) {
        if (buildAttempted) return buildOk;
        buildAttempted = true;
        opaque = buildProg(data, false);
        trans = buildProg(data, true);
        resolveVao = glGenVertexArrays();
        buildOk = opaque != null && (data.translucentFragPatch() == null || trans != null);
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
        // Water ring parity: location fetched once per build, value uploaded per
        // frame in runOne (the radius is live).
        p.uRingCull = glGetUniformLocation(prog, "uVxRingCull");
        if (BslMaterialCompatibility.enabled() && BslMaterialCompatibility.WATER_RING_PARITY && translucent) {
            // loc=-1 on the LIVE translucent build means the injected code (the
            // uniform's only consumer) was compiled out — the parity is dead
            // regardless of what the assembly-time ON log claimed.
            Logger.info(String.format(java.util.Locale.ROOT,
                    "[Metal-LODTEST] vx ring parity runtime: translucent uVxRingCull loc=%d"
                    + " (>=0 means the injected ring code is live in the linked program),"
                    + " first-frame cull=%.1f blocks", p.uRingCull, BslMaterialCompatibility.ringCullNow()));
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
        if (translucent && BslMaterialCompatibility.enabled()) {
            Logger.info("[Metal-LODTEST] vx SSR mirror rebind " + (SSR_ALT && p.gaux2Unit >= 0 ? "ON" : "OFF")
                    + " (gaux2 unit=" + p.gaux2Unit + " -> colortex5 ALT, deferred1's last-frame reflection"
                    + " output); VOXY_VX_SSR_ALT=0 reverts to the MAIN-side binding");
            Logger.info("[Metal-LODTEST] vx colortex16 per-frame clear " + (TRANS_CLEAR ? "ON" : "OFF")
                    + " (Iris never clears the voxy channel -> blended alpha accumulated to 1.0 = opaque water);"
                    + " VOXY_VX_TRANS_CLEAR=0 reverts");
            Logger.info("[Metal-LODTEST] vx resolve sampler-object fix " + (SAMPLER_FIX ? "ON" : "OFF")
                    + " (stale Iris samplers on units 0-3 made the RECT planes read as incomplete ->"
                    + " constant (0,0,0,1): black textureless water); VOXY_VX_SAMPLER_FIX=0 reverts");
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
        resolve(data,ipipe,oP0,oP1,oP2,opaqueDepthRect,tP0,tP1,tP2,transDepthRect,fbw,fbh,0);
    }

    public static void resolve(IrisVoxyRenderPipelineData data,
                               net.irisshaders.iris.pipeline.IrisRenderingPipeline ipipe,
                               int oP0, int oP1, int oP2, int opaqueDepthRect,
                               int tP0, int tP1, int tP2, int transDepthRect,
                               int fbw, int fbh, long uniformSnapshot) {
        try (var state = new GlInteropState(resolveTextureUnits(data), 5)) {
            glActiveTexture(GL_TEXTURE0);
            checkPipelineGeneration(data, ipipe);
            if (!build(data)) return;
            if (oP0 == 0 || opaqueDepthRect == 0) return;
            if (DUMP_OUT) dumpFrame++;
            var sc = me.cortex.voxy.client.core.util.VxIrisSideChannel.getOrCreate();
            var ndc = me.cortex.voxy.client.core.rendering.util.MetalMvpUtil.METAL_NDC_REMAP;
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
            boolean hasTrans = trans != null && tP0 != 0 && transDepthRect != 0;
            // Both current depth layers must be available to either pack program.
            if (hasTrans && !sc.resolveTrans(tP0, transDepthRect, fbw, fbh, ndc)) return;
            if (uniformSnapshot == 0 && data.getUniforms() != null) {
                data.getUniforms().updater().accept(opaque.uboScratch);
                uniformSnapshot = opaque.uboScratch;
            }
            int[] opaqueTargets = data.resolveOpaqueTargetsNow(ipipe);
            runOne(data, opaque, oP0, oP1, oP2, opaqueDepthRect, opaqueTargets, fbw, fbh, false, 0, uniformSnapshot);

            if (hasTrans) {
                int[] transTargets = data.resolveTranslucentTargetsNow(ipipe);
                runOne(data, trans, tP0, tP1, tP2, transDepthRect, transTargets, fbw, fbh, true,
                        ct5AltTexture(ipipe), uniformSnapshot);
            }
            resolveFailureCount = 0;
        } catch (Throwable t) {
            reportResolveFailure(t);
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
        resolveTranslucentOnly(data,ipipe,tP0,tP1,tP2,transDepthRect,fbw,fbh,0);
    }
    public static void resolveTranslucentOnly(IrisVoxyRenderPipelineData data,
                               net.irisshaders.iris.pipeline.IrisRenderingPipeline ipipe,
                               int tP0, int tP1, int tP2, int transDepthRect,
                               int fbw, int fbh, long uniformSnapshot) {
        try (var state = new GlInteropState(resolveTextureUnits(data), 5)) {
            glActiveTexture(GL_TEXTURE0);
            checkPipelineGeneration(data, ipipe);
            if (!build(data)) return;
            if (trans == null || tP0 == 0 || transDepthRect == 0) return;
            var sc = me.cortex.voxy.client.core.util.VxIrisSideChannel.getOrCreate();
            var ndc = me.cortex.voxy.client.core.rendering.util.MetalMvpUtil.METAL_NDC_REMAP;
            if (!sc.resolveTrans(tP0, transDepthRect, fbw, fbh, ndc)) return;
            if (DUMP_OUT && (++dumpFrame % 300 == 100)) {
                // Decisive probe: is the WATER depth per-pixel smooth or chunk-stepped?
                // The pow(8) Fresnel in voxy_translucent amplifies any depth steps into the
                // flat "square" artifacts. Low roughness + dense neighbours => depth is fine,
                // squares are elsewhere; high/stepped => depth-reconstruction is the cause.
                sc.dumpDepthStats("trans", sc.fboTransId(), fbw, fbh);
            }
            int[] transTargets = data.resolveTranslucentTargetsNow(ipipe);
            if (DUMP_OUT && dumpFrame % 300 == 100) {
                // Identity check: attachment 0 must be colortex16 (one of its two
                // sides). If it isn't, the resolve draws into some other target and
                // everything downstream reasons about the wrong texture.
                StringBuilder ids = new StringBuilder();
                try {
                    var rts = ((me.cortex.voxy.client.mixin.iris.IrisRenderingPipelineAccessor) ipipe).getRenderTargets();
                    var ct16 = rts.getOrCreate(16);
                    ids.append(" ct16 main=").append(ct16.getMainTexture()).append(" alt=").append(ct16.getAltTexture());
                } catch (Throwable t) { ids.append(" ct16 lookup failed: ").append(t.getMessage()); }
                Logger.info("[VX-BOUND] trans targets=" + java.util.Arrays.toString(transTargets) + ids);
            }
            runOne(data, trans, tP0, tP1, tP2, transDepthRect, transTargets, fbw, fbh, true,
                    ct5AltTexture(ipipe), uniformSnapshot);
            // Adjudicates the SSR mirror question empirically: which colortex5 side
            // actually carries deferred1's reflection data (and what the undefined
            // side reads), plus whether colortex16's alpha is the designed ~0.7-0.95.
            if (DUMP_OUT && dumpFrame % 300 == 100) dumpCt5Stats(ipipe, fbw, fbh);
            resolveFailureCount = 0;
        } catch (Throwable t) {
            reportResolveFailure(t);
        }
    }

    private static int resolveFailureCount;
    private static long nextResolveFailureLog;

    private static int resolveTextureUnits(IrisVoxyRenderPipelineData data) {
        var images = data.getImageSet();
        return images == null || images.orderedNames() == null ? 4 : 6 + images.orderedNames().size();
    }

    private static void reportResolveFailure(Throwable failure) {
        long now = System.nanoTime();
        if (++resolveFailureCount == 1) {
            Logger.warn("Metal shader resolve failed; skipping incomplete work", failure);
            nextResolveFailureLog = now + 10_000_000_000L;
        } else if (now >= nextResolveFailureLog) {
            Logger.warn("Metal shader resolve still failing (" + resolveFailureCount + " frames): " + failure);
            nextResolveFailureLog = now + 10_000_000_000L;
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
        runOne(data,p,plane0,plane1,plane2,depthRect,targets,fbw,fbh,blend,ssrMirrorTex,0);
    }
    private static void runOne(IrisVoxyRenderPipelineData data, Prog p,
                               int plane0, int plane1, int plane2, int depthRect,
                               int[] targets, int fbw, int fbh, boolean blend, int ssrMirrorTex, long uniformSnapshot) {
        if (p == null || targets == null || targets.length == 0) return;
        if (plane0 == 0 || depthRect == 0) return;
        try (var state = new GlInteropState(Math.max(4, 6 + p.samplerCount), 5)) {
            // Evaluate before clearing or drawing, while callback failures are still scoped.
            if (p.ubo != 0 && data.getUniforms() != null) {
                if (uniformSnapshot == 0) data.getUniforms().updater().accept(p.uboScratch);
                else if (uniformSnapshot != p.uboScratch) org.lwjgl.system.MemoryUtil.memCopy(uniformSnapshot,p.uboScratch,p.uboSize);
            }
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
            glDisable(GL_STENCIL_TEST);
            glColorMask(true, true, true, true);
            glUseProgram(p.prog);
            if (p.uRingCull >= 0) {
                // LIVE near-cull ring radius (mirrors MDICSectionRenderer's
                // voxyLodParams2.x): per-frame, so a mid-session render-distance
                // change retunes the parity ramp immediately (no shader reload).
                glUniform1f(p.uRingCull, BslMaterialCompatibility.ringCullNow());
            }
            glBindVertexArray(resolveVao);
            glActiveTexture(GL_TEXTURE0); glBindTexture(GL_TEXTURE_RECTANGLE, plane0);
            glActiveTexture(GL_TEXTURE1); glBindTexture(GL_TEXTURE_RECTANGLE, plane1);
            glActiveTexture(GL_TEXTURE2); glBindTexture(GL_TEXTURE_RECTANGLE, plane2);
            glActiveTexture(GL_TEXTURE3); glBindTexture(GL_TEXTURE_RECTANGLE, depthRect);
            glActiveTexture(GL_TEXTURE0);
            // A mip-filtering Iris sampler makes mip-less rectangle inputs incomplete.
            if (SAMPLER_FIX) {
                for (int unit = 0; unit < 4; unit++) org.lwjgl.opengl.GL33C.glBindSampler(unit, 0);
            }
            if (data.getImageSet() != null) data.getImageSet().bindingFunction().accept(6);
            if (ssrMirrorTex != 0 && p.gaux2Unit >= 0) {
                // See SSR_ALT: keep the sampler object bindingFunction bound (filtering),
                // replace only the texture on gaux2's unit.
                glActiveTexture(GL_TEXTURE0 + p.gaux2Unit);
                glBindTexture(GL_TEXTURE_2D, ssrMirrorTex);
                glActiveTexture(GL_TEXTURE0);
            }
            if (p.ubo != 0 && data.getUniforms() != null) {
                glBindBuffer(GL_UNIFORM_BUFFER, p.ubo);
                glBufferData(GL_UNIFORM_BUFFER,
                        org.lwjgl.system.MemoryUtil.memByteBuffer(p.uboScratch, p.uboSize), GL_DYNAMIC_DRAW);
                glBindBufferBase(GL_UNIFORM_BUFFER, 5, p.ubo);
            }
            if (blend && data.getBlender() != null) data.getBlender().run();
            else glDisable(GL_BLEND);
            if (DUMP_OUT && blend && dumpFrame % 300 == 100) {
                // Draw-instant state probe: the v3 output proved the shader samples
                // opaque-plane-profiled data (alpha=1/id=0 over 19% of frame) while
                // the texture objects bound above hold trans data. Either the rect
                // bindings were clobbered between our binds and the draw, or the
                // sampler uniforms don't hold units 0-3 (e.g. the pack's
                // layout(binding=...) qualifiers landing on low units at link).
                int[] rectBinds = new int[6];
                for (int u = 0; u < 6; u++) {
                    glActiveTexture(GL_TEXTURE0 + u);
                    rectBinds[u] = glGetInteger(GL_TEXTURE_BINDING_RECTANGLE);
                }
                glActiveTexture(GL_TEXTURE0);
                int[] sunits = new int[4];
                String[] snames = {"uVxAlbedo", "uVxTint", "uVxMisc", "uVxDepth"};
                for (int s = 0; s < 4; s++) {
                    int loc = glGetUniformLocation(p.prog, snames[s]);
                    sunits[s] = loc >= 0 ? org.lwjgl.opengl.GL20C.glGetUniformi(p.prog, loc) : -999;
                }
                Logger.info("[VX-BOUND] draw-instant rectBinds units0-5=" + java.util.Arrays.toString(rectBinds)
                        + " expected=[" + plane0 + "," + plane1 + "," + plane2 + "," + depthRect + "]"
                        + " samplerUnits(uVxAlbedo,uVxTint,uVxMisc,uVxDepth)=" + java.util.Arrays.toString(sunits));
            }
            if (blend && TRANS_CLEAR && BslMaterialCompatibility.enabled()) {
                // See TRANS_CLEAR: Iris does not clear colortex16; without this the
                // blended alpha accumulates to 1.0 -> opaque LOD water.
                glClearBufferfv(GL_COLOR, 0, TRANS_CLEAR_ZERO);
            }
            if (blend) me.cortex.voxy.client.core.MatchedFrameProbe.waterProgram(fbw,fbh);
            glDrawArrays(GL_TRIANGLE_STRIP, 0, 4);
            me.cortex.voxy.client.core.MatchedFrameProbe.material(blend ? "material-translucent" : "material-opaque",
                    data,targets,plane1,plane2,fbw,fbh);
            // Both stages: the translucent program had never had a runtime readback
            // (the old !blend gate), which left the water-resolve output unmeasured.
            if (DUMP_OUT) dumpResolveOutput(p, blend ? "trans" : "opaque", fbw, fbh);
            // GL-side view of the very textures the shader samples. The CPU-side
            // IOSurface probe ([Metal-VXTRANS]) and the resolve's ID debug
            // disagreed wholesale (16 covered samples 100% water vs 19.5% of the
            // frame 100% NON-water), which is only possible if the GL rect
            // textures bound at units 0/2/3 do not show the trans planes'
            // content (stale AUX_RECT_TEXES entry / silent bindToGlTexture
            // failure). This reads them back through a scratch FBO.
            if (DUMP_OUT && blend) dumpBoundPlaneStats(plane0, plane2, depthRect, fbw, fbh);
        }
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

    private static int planeReadFbo;

    /**
     * VOXY_VX_DUMP_OUT=1: read back the GL rect textures the translucent
     * resolve actually samples (unit 0 = uVxAlbedo, 2 = uVxMisc, 3 = uVxDepth)
     * and report coverage/water-id/valid-depth stats. Pairs with the CPU-side
     * [Metal-VXTRANS] IOSurface probe: matching numbers exonerate the GL
     * binding; diverging numbers convict AUX_RECT_TEXES/bindToGlTexture.
     */
    private static void dumpBoundPlaneStats(int albedoTex, int miscTex, int depthTex, int fbw, int fbh) {
        if (dumpFrame % 300 != 100) return;
        int prevReadFb = glGetInteger(GL_READ_FRAMEBUFFER_BINDING);
        int prevRect = glGetInteger(GL_TEXTURE_BINDING_RECTANGLE);
        if (planeReadFbo == 0) planeReadFbo = glGenFramebuffers();
        glBindFramebuffer(GL_READ_FRAMEBUFFER, planeReadFbo);
        java.nio.ByteBuffer buf = null;
        try {
            // Texture dims vs the viewport the resolve renders at. The shader
            // samples vxTexel over 0..fbw/0..fbh with CLAMP_TO_EDGE — if the
            // rect texture is SMALLER than the viewport, everything beyond its
            // extent replicates the edge texel (and a fbw×fbh readback returns
            // undefined bytes outside), which reconciles validDepth=100% with
            // waterId=100% over 0.08%.
            glBindTexture(GL_TEXTURE_RECTANGLE, depthTex);
            int tw = org.lwjgl.opengl.GL11C.glGetTexLevelParameteri(GL_TEXTURE_RECTANGLE, 0, org.lwjgl.opengl.GL11C.GL_TEXTURE_WIDTH);
            int th = org.lwjgl.opengl.GL11C.glGetTexLevelParameteri(GL_TEXTURE_RECTANGLE, 0, org.lwjgl.opengl.GL11C.GL_TEXTURE_HEIGHT);
            glBindTexture(GL_TEXTURE_RECTANGLE, albedoTex);
            int aw = org.lwjgl.opengl.GL11C.glGetTexLevelParameteri(GL_TEXTURE_RECTANGLE, 0, org.lwjgl.opengl.GL11C.GL_TEXTURE_WIDTH);
            int ah = org.lwjgl.opengl.GL11C.glGetTexLevelParameteri(GL_TEXTURE_RECTANGLE, 0, org.lwjgl.opengl.GL11C.GL_TEXTURE_HEIGHT);
            glBindTexture(GL_TEXTURE_RECTANGLE, prevRect);
            Logger.info(String.format("[VX-BOUND] viewport=%dx%d depthTex=%d %dx%d albedoTex=%d %dx%d",
                    fbw, fbh, depthTex, tw, th, albedoTex, aw, ah));
            int rw = Math.min(fbw, Math.max(1, aw)), rh = Math.min(fbh, Math.max(1, ah));
            int n = rw * rh;
            buf = org.lwjgl.system.MemoryUtil.memAlloc(n * 4);
            // uVxAlbedo (unit 0): alpha coverage within the texture's extent
            glFramebufferTexture2D(GL_READ_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_RECTANGLE, albedoTex, 0);
            if (glCheckFramebufferStatus(GL_READ_FRAMEBUFFER) == GL_FRAMEBUFFER_COMPLETE) {
                glReadBuffer(GL_COLOR_ATTACHMENT0);
                org.lwjgl.opengl.GL11C.glReadPixels(0, 0, rw, rh, GL_RGBA, org.lwjgl.opengl.GL11C.GL_UNSIGNED_BYTE, buf);
                int cov = 0;
                for (int i = 0; i < n; i++) if ((buf.get(i * 4 + 3) & 0xFF) > 0) cov++;
                Logger.info(String.format("[VX-BOUND] uVxAlbedo tex=%d alphaCoverage=%.2f%% of %dx%d", albedoTex, 100.0 * cov / n, rw, rh));
            } else Logger.info("[VX-BOUND] uVxAlbedo tex=" + albedoTex + " FBO incomplete");
            // uVxMisc (unit 2): water-id fraction over nonzero pixels
            glFramebufferTexture2D(GL_READ_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_RECTANGLE, miscTex, 0);
            if (glCheckFramebufferStatus(GL_READ_FRAMEBUFFER) == GL_FRAMEBUFFER_COMPLETE) {
                glReadBuffer(GL_COLOR_ATTACHMENT0);
                org.lwjgl.opengl.GL11C.glReadPixels(0, 0, rw, rh, GL_RGBA, org.lwjgl.opengl.GL11C.GL_UNSIGNED_BYTE, buf);
                int nz = 0, water = 0;
                java.util.HashMap<Integer,Integer> hist = new java.util.HashMap<>();
                for (int i = 0; i < n; i++) {
                    int r = buf.get(i * 4) & 0xFF, g = buf.get(i * 4 + 1) & 0xFF;
                    int b = buf.get(i * 4 + 2) & 0xFF, a = buf.get(i * 4 + 3) & 0xFF;
                    if ((r | g | b | a) == 0) continue;
                    nz++;
                    int id = b | (a << 8);
                    if (id / 100 == 200 || id / 100 == 204) water++;
                    else hist.merge(id, 1, Integer::sum);
                }
                StringBuilder top = new StringBuilder();
                hist.entrySet().stream().sorted((p, q) -> q.getValue() - p.getValue()).limit(5)
                        .forEach(e -> top.append(String.format(" id=%d(blk=%d)x%d", e.getKey(), e.getKey() / 100, e.getValue())));
                Logger.info(String.format("[VX-BOUND] uVxMisc tex=%d nonzero=%.2f%% waterId=%.2f%% topOtherIds:%s",
                        miscTex, 100.0 * nz / n, 100.0 * water / Math.max(1, nz), top.length() == 0 ? " none" : top.toString()));
            } else Logger.info("[VX-BOUND] uVxMisc tex=" + miscTex + " FBO incomplete");
            // uVxDepth (unit 3): valid packed-depth fraction (resolve coverage gate)
            glFramebufferTexture2D(GL_READ_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_RECTANGLE, depthTex, 0);
            if (glCheckFramebufferStatus(GL_READ_FRAMEBUFFER) == GL_FRAMEBUFFER_COMPLETE) {
                glReadBuffer(GL_COLOR_ATTACHMENT0);
                org.lwjgl.opengl.GL11C.glReadPixels(0, 0, rw, rh, GL_RGBA, org.lwjgl.opengl.GL11C.GL_UNSIGNED_BYTE, buf);
                int valid = 0;
                for (int i = 0; i < n; i++) {
                    double d = (buf.get(i * 4) & 0xFF) / 255.0
                             + (buf.get(i * 4 + 1) & 0xFF) / (255.0 * 255.0)
                             + (buf.get(i * 4 + 2) & 0xFF) / (255.0 * 65025.0);
                    if (d > 0.0 && d < 0.9999999) valid++;
                }
                Logger.info(String.format("[VX-BOUND] uVxDepth tex=%d validDepth=%.2f%% of %dx%d", depthTex, 100.0 * valid / n, rw, rh));
            } else Logger.info("[VX-BOUND] uVxDepth tex=" + depthTex + " FBO incomplete");
        } catch (Throwable t) {
            Logger.warn("[VX-BOUND] readback failed: " + t.getMessage());
        } finally {
            if (buf != null) org.lwjgl.system.MemoryUtil.memFree(buf);
            glFramebufferTexture2D(GL_READ_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_RECTANGLE, 0, 0);
            glBindFramebuffer(GL_READ_FRAMEBUFFER, prevReadFb);
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
