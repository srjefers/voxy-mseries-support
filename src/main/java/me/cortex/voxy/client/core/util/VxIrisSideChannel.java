package me.cortex.voxy.client.core.util;

import me.cortex.voxy.client.core.interop.IOSurfaceBridge;
import me.cortex.voxy.common.Logger;

import static org.lwjgl.opengl.GL11C.*;
import static org.lwjgl.opengl.GL30C.GL_DEPTH_COMPONENT32F;
import static org.lwjgl.opengl.GL12C.GL_CLAMP_TO_EDGE;
import static org.lwjgl.opengl.GL13C.GL_TEXTURE0;
import static org.lwjgl.opengl.GL13C.GL_TEXTURE1;
import static org.lwjgl.opengl.GL13C.glActiveTexture;
import static org.lwjgl.opengl.GL14C.GL_TEXTURE_COMPARE_MODE;
import static org.lwjgl.opengl.GL20C.*;
import static org.lwjgl.opengl.GL30C.*;

/**
 * Phase B of the native vx contract on Metal (milestone issue #9): the GL
 * side-channel that turns the Metal LOD depth bridge (24-bit EncodeFloatRGB
 * in a BGRA8 IOSurface) into a REAL {@code GL_DEPTH_COMPONENT32F} texture
 * that Iris's dynamic samplers serve to packs as {@code vxDepthTexOpaque} /
 * {@code vxDepthTexTrans}. Packs unproject it themselves via the
 * {@code vxProjInv} uniform (fed by the existing MixinMatrixUniforms layer,
 * which has no backend gate), so NO reprojection into MC clip space happens
 * here — the only convention applied is VOXY-NDC → window depth
 * ({@code d*0.5+0.5}) matching what the GL host's LOD raster would have
 * written to its depth attachment.
 *
 * Phase B serves the opaque depth for BOTH sampler names (translucent LOD is
 * baked into the injected colour); Phase C gives translucency its own depth.
 *
 * Raw-GL implementation (no DSA — Apple GL 4.1), same compile/state-hygiene
 * patterns as IOSurfaceBridgeCompositor.
 */
public final class VxIrisSideChannel {

    private static final int GL_TEXTURE_RECTANGLE = 0x84F5;
    private static final int GL_TEXTURE_BINDING_RECTANGLE = 0x84F6;
    private static final int GL_NONE_BUF = 0;

    private static VxIrisSideChannel INSTANCE;

    public static VxIrisSideChannel get() {
        return INSTANCE;
    }

    /** Lazily create on first contract frame (render thread, GL current). */
    public static VxIrisSideChannel getOrCreate() {
        if (INSTANCE == null) {
            INSTANCE = new VxIrisSideChannel();
        }
        return INSTANCE;
    }

    public static void destroy() {
        if (INSTANCE != null) {
            INSTANCE.free();
            INSTANCE = null;
        }
    }

    private int depthTexOpaque;
    private int fboOpaque;
    private int depthTexTrans;
    private int fboTrans;
    private int program;
    private int vao;
    private int uColour, uDepth, uDepthIsWindow;
    // Abyss-fill depth pass (0 = unbuilt, -1 = build failed once, stay off).
    private int abyssProgram;
    private int aUColour, aUDepth, aUTransDepth, aUDepthIsWindow;
    private int aUProj, aUProjInv, aUUpView, aUMaxDist, aUPush, aUSeaOffset;
    private int width, height;
    private boolean broken;

    private VxIrisSideChannel() {}

    public int opaqueDepthTexId() {
        return this.depthTexOpaque;
    }

    /** Diagnostic accessor: the FBO whose GL_DEPTH_ATTACHMENT is the opaque LOD depth (D32F). */
    public int fboOpaqueId() {
        return this.fboOpaque;
    }

    /**
     * Diagnostic (VOXY_VX_DUMP_OUT=1): read back vxDepthTexOpaque over a grid and report
     * how many LOD pixels are covered (depth &lt; 1.0) vs empty (== 1.0), plus the covered
     * mean/min/max. BSL's deferred1 gates LOD lighting on {@code vxZ &lt; 1.0}: if this is
     * ~all 1.0 the LOD falls to BSL's sky branch (sky-gray); if it's &lt;1.0 but clustered
     * near 1.0 the unprojection/fog is wrong (fog-gray). Disambiguates the two dark modes.
     */
    /** The FBO holding the translucent (water) LOD depth (D32F), for diagnostics. */
    public int fboTransId() {
        return this.fboTrans;
    }

    public void dumpDepthStats(int fbw, int fbh) {
        dumpDepthStats("opaque", this.fboOpaque, fbw, fbh);
    }

    public void dumpDepthStats(String label, int fbo, int fbw, int fbh) {
        if (fbo == 0 || this.width == 0) return;
        int prevRead = glGetInteger(GL_READ_FRAMEBUFFER_BINDING);
        java.nio.FloatBuffer fb = org.lwjgl.system.MemoryUtil.memAllocFloat(fbw * fbh);
        try {
            glBindFramebuffer(GL_READ_FRAMEBUFFER, fbo);
            glReadPixels(0, 0, fbw, fbh, GL_DEPTH_COMPONENT, GL_FLOAT, fb);
            int covered = 0, empty = 0, total = 0;
            double sum = 0; float mn = 2f, mx = -1f;
            // Neighbour-density probe: for each covered grid pixel, count how many of its 8
            // immediate (+-1px) neighbours are also covered. BSL's SSAO reconstructs the
            // surface normal from +-1px neighbour depths; if those are empty (1.0) the normal
            // is garbage -> AO collapses -> black. High density (~8/8) = contiguous coverage
            // (SSAO sees a real surface); low = sparse/dithered (the SSAO-breaking case).
            long nbrCoveredSum = 0; int nbrSampleN = 0;
            // Local depth roughness: mean |d - neighbourAvg| over covered pixels with full
            // covered neighbourhood — high = noisy depth (also breaks normal reconstruction).
            double roughSum = 0; int roughN = 0;
            for (int ry = 0; ry < 24; ry++) {
                int y = fbh / 3 + ry * (fbh * 2 / 3) / 24;
                for (int rx = 0; rx < 48; rx++) {
                    int x = rx * fbw / 48;
                    float d = fb.get(y * fbw + x);
                    total++;
                    if (d < 0.99999f) {
                        covered++; sum += d; if (d < mn) mn = d; if (d > mx) mx = d;
                        if (x >= 1 && x < fbw - 1 && y >= 1 && y < fbh - 1) {
                            int nc = 0; double navg = 0; int nn = 0;
                            for (int dy = -1; dy <= 1; dy++) for (int dx = -1; dx <= 1; dx++) {
                                if (dx == 0 && dy == 0) continue;
                                float nd = fb.get((y + dy) * fbw + (x + dx));
                                if (nd < 0.99999f) { nc++; navg += nd; nn++; }
                            }
                            nbrCoveredSum += nc; nbrSampleN++;
                            if (nn == 8) { roughSum += Math.abs(d - navg / nn); roughN++; }
                        }
                    } else empty++;
                }
            }
            Logger.info(String.format("[VX-OUT] %s depth grid: covered(<1)=%d empty(==1)=%d /%d  coveredDepth mean=%.5f min=%.5f max=%.5f",
                    label, covered, empty, total, covered > 0 ? sum / covered : -1, covered > 0 ? mn : -1, covered > 0 ? mx : -1));
            Logger.info(String.format("[VX-OUT] %s coverage density: avg covered neighbours=%.2f/8 (samples=%d)  localRoughness(meanAbsDevWindowZ)=%.6f (n=%d)",
                    label, nbrSampleN > 0 ? (double) nbrCoveredSum / nbrSampleN : -1, nbrSampleN,
                    roughN > 0 ? roughSum / roughN : -1, roughN));
        } catch (Throwable t) {
            Logger.warn("[VX-OUT] depth readback failed: " + t.getMessage());
        } finally {
            glBindFramebuffer(GL_READ_FRAMEBUFFER, prevRead);
            org.lwjgl.system.MemoryUtil.memFree(fb);
        }
    }

    /**
     * Phase D: real translucent depth when the split pass ran this frame;
     * falls back to the opaque depth until then.
     */
    public int transDepthTexId() {
        return this.depthTexTrans != 0 ? this.depthTexTrans : this.depthTexOpaque;
    }

    /**
     * Decode the packed depth bridge into the D32F vx depth texture. Caller
     * has already resynced/bound the bridges to their GL rect textures
     * (colour on unit 0, depth on unit 1 — the standard inject layout).
     * Gates mirror the inject shader: colour alpha = LOD coverage, far-clear
     * stays at the cleared 1.0 (discard leaves it at glClearDepth 1.0).
     */
    public boolean resolve(int colourRectTex, int depthRectTex, int fbw, int fbh, boolean depthIsWindow) {
        if (this.broken) return false;
        if (!this.ensureResources(fbw, fbh)) {
            this.broken = true;
            return false;
        }
        return this.resolveInto(this.fboOpaque, colourRectTex, depthRectTex, fbw, fbh, depthIsWindow);
    }

    /**
     * Phase D: decode the TRANSLUCENT depth bridge into vxDepthTexTrans.
     * Same decode/gates; gating alpha comes from the translucent colour
     * bridge (premultiplied accumulation — water alpha well above the
     * threshold). Creates the translucent target lazily on first use.
     */
    public boolean resolveTrans(int transColourRectTex, int transDepthRectTex, int fbw, int fbh, boolean depthIsWindow) {
        if (this.broken) return false;
        if (!this.ensureTransResources(fbw, fbh)) {
            return false;
        }
        return this.resolveInto(this.fboTrans, transColourRectTex, transDepthRectTex, fbw, fbh, depthIsWindow);
    }

    private boolean resolveInto(int targetFbo, int colourRectTex, int depthRectTex, int fbw, int fbh, boolean depthIsWindow) {

        int prevDrawFb = glGetInteger(GL_DRAW_FRAMEBUFFER_BINDING);
        int prevReadFb = glGetInteger(GL_READ_FRAMEBUFFER_BINDING);
        int prevProgram = glGetInteger(GL_CURRENT_PROGRAM);
        int prevVao = glGetInteger(GL_VERTEX_ARRAY_BINDING);
        int prevActiveTex = glGetInteger(GL_ACTIVE_TEXTURE);
        int[] prevViewport = new int[4];
        glGetIntegerv(GL_VIEWPORT, prevViewport);
        boolean prevDepthTest = glIsEnabled(GL_DEPTH_TEST);
        int prevDepthFunc = glGetInteger(GL_DEPTH_FUNC);
        boolean prevDepthMask = glGetBoolean(GL_DEPTH_WRITEMASK);
        boolean prevScissor = glIsEnabled(GL_SCISSOR_TEST);
        glActiveTexture(GL_TEXTURE1);
        int prevTexRect1 = glGetInteger(GL_TEXTURE_BINDING_RECTANGLE);
        int prevSampler1 = glGetInteger(org.lwjgl.opengl.GL33C.GL_SAMPLER_BINDING);
        glActiveTexture(GL_TEXTURE0);
        int prevTexRect0 = glGetInteger(GL_TEXTURE_BINDING_RECTANGLE);
        int prevSampler0 = glGetInteger(org.lwjgl.opengl.GL33C.GL_SAMPLER_BINDING);

        try {
            glBindFramebuffer(GL_FRAMEBUFFER, targetFbo);
            glDisable(GL_SCISSOR_TEST);
            glViewport(0, 0, fbw, fbh);
            glClearDepth(1.0);
            glEnable(GL_DEPTH_TEST);
            glDepthFunc(GL_ALWAYS);
            glDepthMask(true);
            glClear(GL_DEPTH_BUFFER_BIT);

            org.lwjgl.opengl.GL33C.glBindSampler(0, 0);
            org.lwjgl.opengl.GL33C.glBindSampler(1, 0);
            glUseProgram(this.program);
            glBindVertexArray(this.vao);
            glActiveTexture(GL_TEXTURE0);
            glBindTexture(GL_TEXTURE_RECTANGLE, colourRectTex);
            glActiveTexture(GL_TEXTURE1);
            glBindTexture(GL_TEXTURE_RECTANGLE, depthRectTex);
            glActiveTexture(GL_TEXTURE0);
            glUniform1i(this.uColour, 0);
            glUniform1i(this.uDepth, 1);
            glUniform1i(this.uDepthIsWindow, depthIsWindow ? 1 : 0);
            glDrawArrays(GL_TRIANGLE_STRIP, 0, 4);
            return true;
        } finally {
            glBindTexture(GL_TEXTURE_RECTANGLE, prevTexRect0);
            org.lwjgl.opengl.GL33C.glBindSampler(0, prevSampler0);
            glActiveTexture(GL_TEXTURE1);
            glBindTexture(GL_TEXTURE_RECTANGLE, prevTexRect1);
            org.lwjgl.opengl.GL33C.glBindSampler(1, prevSampler1);
            glActiveTexture(prevActiveTex);
            glUseProgram(prevProgram);
            glBindVertexArray(prevVao);
            if (prevDepthTest) glEnable(GL_DEPTH_TEST); else glDisable(GL_DEPTH_TEST);
            glDepthFunc(prevDepthFunc);
            glDepthMask(prevDepthMask);
            if (prevScissor) glEnable(GL_SCISSOR_TEST);
            glViewport(prevViewport[0], prevViewport[1], prevViewport[2], prevViewport[3]);
            glBindFramebuffer(GL_DRAW_FRAMEBUFFER, prevDrawFb);
            glBindFramebuffer(GL_READ_FRAMEBUFFER, prevReadFb);
        }
    }

    /**
     * ABYSS FILL depth half (VOXY_VX_ABYSS_FILL — see VxContractInjector's
     * colour branch; the two condition chains MUST stay bit-identical): where
     * the opaque bridge has NO LOD but the trans bridge carries an LOD
     * water-surface depth, write a synthetic seabed depth into
     * vxDepthTexOpaque so the pack composites the injected dark fill as a
     * real LOD pixel. LOD depth never touches depthtex0
     * (excludeLodsFromVanillaDepth), so this side channel is the ONLY way a
     * fill pixel can pass the pack's {@code vxZ < 1.0} branch — colour
     * without this depth gets stomped by the pack's sky composite (the
     * injector gates its colour branch on this method returning true), depth
     * without colour would composite stale colortex0 (why the conditions
     * must match).
     *
     * Runs as a second draw into the already-resolved opaque FBO: NO clear,
     * GL_ALWAYS — pixels holding real LOD depth discard via the LOD-present
     * gate, so the main resolve's data is untouched. The synthetic depth is
     * the water-surface view position pushed {@code push} blocks further
     * along the view ray, reprojected: always behind the water surface,
     * always in front of the far plane, and any real geometry drawn later
     * wins the depth test (self-healing once LOD data arrives).
     */
    public boolean abyssDepth(int colourRectTex, int depthRectTex, int transDepthRectTex,
                              int fbw, int fbh, boolean depthIsWindow,
                              org.joml.Matrix4f proj, org.joml.Matrix4f projInv,
                              org.joml.Vector3f upView, float maxDist, float push,
                              float seaOffset) {
        if (this.broken || this.fboOpaque == 0 || transDepthRectTex == 0 || this.vao == 0) {
            return false;
        }
        if (this.abyssProgram == 0 && !this.buildAbyssProgram()) {
            this.abyssProgram = -1;
            Logger.warn("VxIrisSideChannel: abyss depth program failed to build — fill stays off");
        }
        if (this.abyssProgram <= 0) return false;

        int prevDrawFb = glGetInteger(GL_DRAW_FRAMEBUFFER_BINDING);
        int prevReadFb = glGetInteger(GL_READ_FRAMEBUFFER_BINDING);
        int prevProgram = glGetInteger(GL_CURRENT_PROGRAM);
        int prevVao = glGetInteger(GL_VERTEX_ARRAY_BINDING);
        int prevActiveTex = glGetInteger(GL_ACTIVE_TEXTURE);
        int[] prevViewport = new int[4];
        glGetIntegerv(GL_VIEWPORT, prevViewport);
        boolean prevDepthTest = glIsEnabled(GL_DEPTH_TEST);
        int prevDepthFunc = glGetInteger(GL_DEPTH_FUNC);
        boolean prevDepthMask = glGetBoolean(GL_DEPTH_WRITEMASK);
        boolean prevScissor = glIsEnabled(GL_SCISSOR_TEST);
        glActiveTexture(GL_TEXTURE0 + 2);
        int prevTexRect2 = glGetInteger(GL_TEXTURE_BINDING_RECTANGLE);
        int prevSampler2 = glGetInteger(org.lwjgl.opengl.GL33C.GL_SAMPLER_BINDING);
        glActiveTexture(GL_TEXTURE1);
        int prevTexRect1 = glGetInteger(GL_TEXTURE_BINDING_RECTANGLE);
        int prevSampler1 = glGetInteger(org.lwjgl.opengl.GL33C.GL_SAMPLER_BINDING);
        glActiveTexture(GL_TEXTURE0);
        int prevTexRect0 = glGetInteger(GL_TEXTURE_BINDING_RECTANGLE);
        int prevSampler0 = glGetInteger(org.lwjgl.opengl.GL33C.GL_SAMPLER_BINDING);

        try {
            glBindFramebuffer(GL_FRAMEBUFFER, this.fboOpaque);
            glDisable(GL_SCISSOR_TEST);
            glViewport(0, 0, fbw, fbh);
            glEnable(GL_DEPTH_TEST);
            glDepthFunc(GL_ALWAYS);
            glDepthMask(true);
            // Deliberately NO glClear here — resolve() cleared and wrote the
            // real LOD depth this frame already.

            org.lwjgl.opengl.GL33C.glBindSampler(0, 0);
            org.lwjgl.opengl.GL33C.glBindSampler(1, 0);
            org.lwjgl.opengl.GL33C.glBindSampler(2, 0);
            glUseProgram(this.abyssProgram);
            glBindVertexArray(this.vao);
            glActiveTexture(GL_TEXTURE0);
            glBindTexture(GL_TEXTURE_RECTANGLE, colourRectTex);
            glActiveTexture(GL_TEXTURE1);
            glBindTexture(GL_TEXTURE_RECTANGLE, depthRectTex);
            glActiveTexture(GL_TEXTURE0 + 2);
            glBindTexture(GL_TEXTURE_RECTANGLE, transDepthRectTex);
            glActiveTexture(GL_TEXTURE0);
            glUniform1i(this.aUColour, 0);
            glUniform1i(this.aUDepth, 1);
            glUniform1i(this.aUTransDepth, 2);
            glUniform1i(this.aUDepthIsWindow, depthIsWindow ? 1 : 0);
            glUniformMatrix4fv(this.aUProj, false, proj.get(new float[16]));
            glUniformMatrix4fv(this.aUProjInv, false, projInv.get(new float[16]));
            glUniform3f(this.aUUpView, upView.x, upView.y, upView.z);
            glUniform1f(this.aUMaxDist, maxDist);
            glUniform1f(this.aUPush, push);
            glUniform1f(this.aUSeaOffset, seaOffset);
            glDrawArrays(GL_TRIANGLE_STRIP, 0, 4);
            return true;
        } finally {
            glBindTexture(GL_TEXTURE_RECTANGLE, prevTexRect0);
            org.lwjgl.opengl.GL33C.glBindSampler(0, prevSampler0);
            glActiveTexture(GL_TEXTURE1);
            glBindTexture(GL_TEXTURE_RECTANGLE, prevTexRect1);
            org.lwjgl.opengl.GL33C.glBindSampler(1, prevSampler1);
            glActiveTexture(GL_TEXTURE0 + 2);
            glBindTexture(GL_TEXTURE_RECTANGLE, prevTexRect2);
            org.lwjgl.opengl.GL33C.glBindSampler(2, prevSampler2);
            glActiveTexture(prevActiveTex);
            glUseProgram(prevProgram);
            glBindVertexArray(prevVao);
            if (prevDepthTest) glEnable(GL_DEPTH_TEST); else glDisable(GL_DEPTH_TEST);
            glDepthFunc(prevDepthFunc);
            glDepthMask(prevDepthMask);
            if (prevScissor) glEnable(GL_SCISSOR_TEST);
            glViewport(prevViewport[0], prevViewport[1], prevViewport[2], prevViewport[3]);
            glBindFramebuffer(GL_DRAW_FRAMEBUFFER, prevDrawFb);
            glBindFramebuffer(GL_READ_FRAMEBUFFER, prevReadFb);
        }
    }

    private boolean buildAbyssProgram() {
        String vs = """
                #version 150 core
                out vec2 vUV;
                void main() {
                    vec2 p = vec2((gl_VertexID & 1) * 2, (gl_VertexID & 2));
                    vUV = p;
                    gl_Position = vec4(p * 2.0 - 1.0, 0.0, 1.0);
                }
                """;
        String fs = """
                #version 150 core
                uniform sampler2DRect uColour;
                uniform sampler2DRect uDepth;
                uniform sampler2DRect uTransDepth;
                uniform int uDepthIsWindow;
                uniform mat4 uProj;
                uniform mat4 uProjInv;
                uniform vec3 uUpView;
                uniform float uMaxDist;
                uniform float uPush;
                uniform float uSeaOffset;
                in vec2 vUV;
                void main() {
                    ivec2 sz = textureSize(uDepth);
                    vec2 texel = vec2(vUV.x * float(sz.x), (1.0 - vUV.y) * float(sz.y));
                    float a = texture(uColour, texel).a;
                    vec3 dEnc = texture(uDepth, texel).rgb;
                    float d = dot(dEnc, vec3(1.0, 1.0 / 255.0, 1.0 / 65025.0));
                    // LOD present: the main resolve already wrote the real
                    // depth this frame — keep it (conditions below mirror
                    // VxContractInjector's abyss colour branch EXACTLY).
                    if (!(a <= 0.001 || d <= 0.0 || d >= 0.9999999)) discard;
                    vec3 pT = vec3(0.0);
                    bool have = false;
                    vec3 tEnc = texture(uTransDepth, texel).rgb;
                    float dT = dot(tEnc, vec3(1.0, 1.0 / 255.0, 1.0 / 65025.0));
                    if (dT > 0.0 && dT < 0.9999995) {
                        // Preferred: the LOD water-surface depth kept in the
                        // trans bridge (the ghost cull preserves it at zero
                        // alpha exactly for reconstruction).
                        float zT = (uDepthIsWindow == 1) ? dT * 2.0 - 1.0 : dT;
                        vec4 pT4 = uProjInv * vec4(vUV * 2.0 - 1.0, zT, 1.0);
                        pT = pT4.xyz / pT4.w;
                        have = true;
                    } else if (uSeaOffset < -0.5) {
                        // Analytic sea-plane fallback: during the first
                        // seconds of join churn even the LOD WATER sections
                        // haven't uploaded, so the trans bridge is empty at
                        // exactly the pixels that pane out (the green-tint
                        // probe: panes took no fill). Intersect the view ray
                        // with the world sea-level plane instead — uSeaOffset
                        // = seaLevel - cameraY in view-space up units, valid
                        // only with the camera >0.5 blocks above the sea.
                        vec4 r4 = uProjInv * vec4(vUV * 2.0 - 1.0, 0.0, 1.0);
                        vec3 r = normalize(r4.xyz / r4.w);
                        float ur = dot(r, uUpView);
                        if (ur < -0.05) {
                            pT = r * (uSeaOffset / ur);
                            have = true;
                        }
                    }
                    if (!have) discard;
                    float len = length(pT);
                    // Downward rays only, inside the trans near-cull ring
                    // (beyond it the LOD water + analytic mirror own the look).
                    if (len <= 1e-3 || dot(pT / len, uUpView) >= -0.05) discard;
                    float horiz = length(pT - uUpView * dot(pT, uUpView));
                    if (uMaxDist > 0.0 && horiz >= uMaxDist) discard;
                    vec3 pF = pT * ((len + uPush) / len);
                    vec4 q = uProj * vec4(pF, 1.0);
                    if (q.w < 1e-6) discard;
                    // Same window encoding the main resolve writes under the
                    // NDC remap; 0.99999 cap keeps the fill below the pack's
                    // vxZ < 1.0 gate and the far clear.
                    gl_FragDepth = clamp((q.z / q.w) * 0.5 + 0.5, 0.0, 0.99999);
                }
                """;
        int prog = compile(vs, fs, "VxIrisSideChannel.abyss");
        if (prog == 0) return false;
        this.abyssProgram = prog;
        this.aUColour = glGetUniformLocation(prog, "uColour");
        this.aUDepth = glGetUniformLocation(prog, "uDepth");
        this.aUTransDepth = glGetUniformLocation(prog, "uTransDepth");
        this.aUDepthIsWindow = glGetUniformLocation(prog, "uDepthIsWindow");
        this.aUProj = glGetUniformLocation(prog, "uProj");
        this.aUProjInv = glGetUniformLocation(prog, "uProjInv");
        this.aUUpView = glGetUniformLocation(prog, "uUpView");
        this.aUMaxDist = glGetUniformLocation(prog, "uMaxDist");
        this.aUPush = glGetUniformLocation(prog, "uPush");
        this.aUSeaOffset = glGetUniformLocation(prog, "uSeaOffset");
        return true;
    }

    private boolean ensureTransResources(int fbw, int fbh) {
        if (this.program == 0 && !this.buildProgram()) {
            return false;
        }
        if (this.depthTexTrans == 0 || this.width != fbw || this.height != fbh) {
            // ensureResources runs first each frame and handles the resize of
            // width/height; here only (re)create the trans pair when missing
            // or stale relative to the current size.
            if (this.depthTexTrans != 0) glDeleteTextures(this.depthTexTrans);
            if (this.fboTrans != 0) glDeleteFramebuffers(this.fboTrans);
            int[] created = createDepthTexAndFbo(fbw, fbh, "vxDepthTexTrans");
            if (created == null) return false;
            this.depthTexTrans = created[0];
            this.fboTrans = created[1];
        }
        return true;
    }

    private static int[] createDepthTexAndFbo(int fbw, int fbh, String label) {
        int prevTex2d = glGetInteger(GL_TEXTURE_BINDING_2D);
        int tex = glGenTextures();
        glBindTexture(GL_TEXTURE_2D, tex);
        glTexImage2D(GL_TEXTURE_2D, 0, GL_DEPTH_COMPONENT32F, fbw, fbh, 0,
                GL_DEPTH_COMPONENT, GL_FLOAT, (java.nio.ByteBuffer) null);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_NEAREST);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_COMPARE_MODE, GL_NONE);
        glBindTexture(GL_TEXTURE_2D, prevTex2d);

        int prevFb = glGetInteger(GL_DRAW_FRAMEBUFFER_BINDING);
        int fbo = glGenFramebuffers();
        glBindFramebuffer(GL_FRAMEBUFFER, fbo);
        glFramebufferTexture2D(GL_FRAMEBUFFER, GL_DEPTH_ATTACHMENT, GL_TEXTURE_2D, tex, 0);
        glDrawBuffers(GL_NONE_BUF);
        glReadBuffer(GL_NONE_BUF);
        int status = glCheckFramebufferStatus(GL_FRAMEBUFFER);
        glBindFramebuffer(GL_FRAMEBUFFER, prevFb);
        if (status != GL_FRAMEBUFFER_COMPLETE) {
            Logger.error("VxIrisSideChannel: " + label + " FBO incomplete: 0x" + Integer.toHexString(status));
            glDeleteTextures(tex);
            glDeleteFramebuffers(fbo);
            return null;
        }
        Logger.info("VxIrisSideChannel: " + label + " ready (" + fbw + "x" + fbh + ", D32F tex " + tex + ")");
        return new int[]{tex, fbo};
    }

    private boolean ensureResources(int fbw, int fbh) {
        if (this.program == 0 && !this.buildProgram()) {
            return false;
        }
        if (this.depthTexOpaque == 0 || this.width != fbw || this.height != fbh) {
            if (this.depthTexOpaque != 0) glDeleteTextures(this.depthTexOpaque);
            if (this.fboOpaque != 0) glDeleteFramebuffers(this.fboOpaque);
            int prevTex2d = glGetInteger(GL_TEXTURE_BINDING_2D);
            this.depthTexOpaque = glGenTextures();
            glBindTexture(GL_TEXTURE_2D, this.depthTexOpaque);
            glTexImage2D(GL_TEXTURE_2D, 0, GL_DEPTH_COMPONENT32F, fbw, fbh, 0,
                    GL_DEPTH_COMPONENT, GL_FLOAT, (java.nio.ByteBuffer) null);
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_NEAREST);
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
            // Packs sample this as plain sampler2D .r — compare mode MUST be
            // off or texture() returns comparison results, not depth.
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_COMPARE_MODE, GL_NONE);
            glBindTexture(GL_TEXTURE_2D, prevTex2d);

            int prevFb = glGetInteger(GL_DRAW_FRAMEBUFFER_BINDING);
            this.fboOpaque = glGenFramebuffers();
            glBindFramebuffer(GL_FRAMEBUFFER, this.fboOpaque);
            glFramebufferTexture2D(GL_FRAMEBUFFER, GL_DEPTH_ATTACHMENT, GL_TEXTURE_2D, this.depthTexOpaque, 0);
            glDrawBuffers(GL_NONE_BUF);
            glReadBuffer(GL_NONE_BUF);
            int status = glCheckFramebufferStatus(GL_FRAMEBUFFER);
            glBindFramebuffer(GL_FRAMEBUFFER, prevFb);
            if (status != GL_FRAMEBUFFER_COMPLETE) {
                Logger.error("VxIrisSideChannel: depth FBO incomplete: 0x" + Integer.toHexString(status));
                return false;
            }
            // Resize invalidates the translucent pair too (recreated lazily).
            if (this.depthTexTrans != 0) { glDeleteTextures(this.depthTexTrans); this.depthTexTrans = 0; }
            if (this.fboTrans != 0) { glDeleteFramebuffers(this.fboTrans); this.fboTrans = 0; }
            this.width = fbw;
            this.height = fbh;
            Logger.info("VxIrisSideChannel: vx depth side-channel ready (" + fbw + "x" + fbh
                    + ", D32F tex " + this.depthTexOpaque + ")");
        }
        return true;
    }

    private boolean buildProgram() {
        String vs = """
                #version 150 core
                out vec2 vUV;
                void main() {
                    vec2 p = vec2((gl_VertexID & 1) * 2, (gl_VertexID & 2));
                    vUV = p;
                    gl_Position = vec4(p * 2.0 - 1.0, 0.0, 1.0);
                }
                """;
        String fs = """
                #version 150 core
                uniform sampler2DRect uColour;
                uniform sampler2DRect uDepth;
                uniform int uDepthIsWindow;
                in vec2 vUV;
                void main() {
                    // Bridge contents are Metal top-left origin; GL FBO output
                    // is bottom-left — same Y-flip as the inject shader, so
                    // the vx depth texture comes out GL-oriented like the GL
                    // host's depth attachment would.
                    ivec2 sz = textureSize(uDepth);
                    vec2 texel = vec2(vUV.x * float(sz.x), (1.0 - vUV.y) * float(sz.y));
                    float a = texture(uColour, texel).a;
                    vec3 dEnc = texture(uDepth, texel).rgb;
                    float d = dot(dEnc, vec3(1.0, 1.0 / 255.0, 1.0 / 65025.0));
                    if (a <= 0.001 || d <= 0.0 || d >= 0.9999999) discard;
                    gl_FragDepth = (uDepthIsWindow == 1) ? d : d * 0.5 + 0.5;
                }
                """;
        this.program = compile(vs, fs, "VxIrisSideChannel");
        if (this.program == 0) return false;
        this.uColour = glGetUniformLocation(this.program, "uColour");
        this.uDepth = glGetUniformLocation(this.program, "uDepth");
        this.uDepthIsWindow = glGetUniformLocation(this.program, "uDepthIsWindow");
        this.vao = glGenVertexArrays();
        return this.vao != 0;
    }

    static int compile(String vsSrc, String fsSrc, String label) {
        int vs = glCreateShader(GL_VERTEX_SHADER);
        glShaderSource(vs, vsSrc);
        glCompileShader(vs);
        if (glGetShaderi(vs, GL_COMPILE_STATUS) == GL_FALSE) {
            Logger.error(label + " vertex compile failed: " + glGetShaderInfoLog(vs));
            glDeleteShader(vs);
            return 0;
        }
        int fs = glCreateShader(GL_FRAGMENT_SHADER);
        glShaderSource(fs, fsSrc);
        glCompileShader(fs);
        if (glGetShaderi(fs, GL_COMPILE_STATUS) == GL_FALSE) {
            Logger.error(label + " fragment compile failed: " + glGetShaderInfoLog(fs));
            glDeleteShader(vs);
            glDeleteShader(fs);
            return 0;
        }
        int program = glCreateProgram();
        glAttachShader(program, vs);
        glAttachShader(program, fs);
        // Frag-data locations for MRT users must bind pre-link; harmless for
        // programs without those outputs.
        glBindFragDataLocation(program, 0, "outColor0");
        glBindFragDataLocation(program, 1, "outColor1");
        glLinkProgram(program);
        glDeleteShader(vs);
        glDeleteShader(fs);
        if (glGetProgrami(program, GL_LINK_STATUS) == GL_FALSE) {
            Logger.error(label + " link failed: " + glGetProgramInfoLog(program));
            glDeleteProgram(program);
            return 0;
        }
        return program;
    }

    private void free() {
        if (this.depthTexOpaque != 0) glDeleteTextures(this.depthTexOpaque);
        if (this.fboOpaque != 0) glDeleteFramebuffers(this.fboOpaque);
        if (this.depthTexTrans != 0) glDeleteTextures(this.depthTexTrans);
        if (this.fboTrans != 0) glDeleteFramebuffers(this.fboTrans);
        this.depthTexTrans = 0;
        this.fboTrans = 0;
        if (this.program != 0) glDeleteProgram(this.program);
        if (this.abyssProgram > 0) glDeleteProgram(this.abyssProgram);
        if (this.vao != 0) glDeleteVertexArrays(this.vao);
        this.depthTexOpaque = 0;
        this.fboOpaque = 0;
        this.program = 0;
        this.abyssProgram = 0;
        this.vao = 0;
    }
}
