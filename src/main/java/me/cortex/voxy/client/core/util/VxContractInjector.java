package me.cortex.voxy.client.core.util;

import me.cortex.voxy.client.core.interop.IOSurfaceBridge;
import me.cortex.voxy.client.core.rendering.Viewport;
import me.cortex.voxy.client.iris.IGetIrisVoxyPipelineData;
import me.cortex.voxy.common.Logger;

import static org.lwjgl.opengl.GL11C.*;
import static org.lwjgl.opengl.GL13C.GL_TEXTURE0;
import static org.lwjgl.opengl.GL13C.GL_TEXTURE1;
import static org.lwjgl.opengl.GL13C.GL_ACTIVE_TEXTURE;
import static org.lwjgl.opengl.GL13C.glActiveTexture;
import static org.lwjgl.opengl.GL15C.*;
import static org.lwjgl.opengl.GL20C.*;
import static org.lwjgl.opengl.GL30C.*;
import static org.lwjgl.opengl.GL31C.*;

/**
 * Phase B of the native vx contract on Metal (milestone issue #9): drives
 * the pack's own {@code #ifdef VOXY} integration from the Metal LOD
 * renderer instead of the gbuffer-injection hack.
 *
 * Per frame at the SOLID-head hook (pre-deferred):
 *  1. {@link VxIrisSideChannel} decodes the Metal depth bridge into the
 *     D32F texture Iris serves as {@code vxDepthTexOpaque/Trans} — LOD
 *     depth NEVER touches depthtex0 (both BSL and Complementary declare
 *     {@code excludeLodsFromVanillaDepth: true}), so real-terrain depth
 *     stomping is impossible by construction.
 *  2. The pre-lit LOD colour is written into the pack's declared
 *     {@code voxy.json opaqueDrawBuffers} colortexes (BSL/CR: colortex0 +
 *     colortex6) through a depth-attachment-less FBO. colortex0 gets the
 *     sqrt/scene-linear encoded colour (ALPHA_BLEND 0 convention);
 *     colortex6 gets the shadowMask seed the pack's own voxy_opaque would
 *     have written (r=1 shadow, b=1 "LOD wrote here").
 *
 * The pack's deferred chain (BSL deferred1's {@code vxZ < 1.0} branch) then
 * applies ITS fog, LOD shadows, AO and cloud-distance extension natively —
 * the VOXY define and vx* matrix uniforms already reach packs on Metal
 * (MixinStandardMacros / MixinMatrixUniforms have no backend gates).
 */
public final class VxContractInjector {

    private static final int GL_TEXTURE_RECTANGLE = 0x84F5;
    private static final int GL_TEXTURE_BINDING_RECTANGLE = 0x84F6;

    private static int program;
    private static int vao;
    private static int uColour, uDepthTex, uInjectGamma, uInjectExposure, uInjectSqrt;
    private static int colorFbo;
    private static int[] attachedTargets = new int[0];
    private static boolean warnedFailure;
    private static boolean disabled;

    private static final float INJECT_GAMMA = parseEnvF("VOXY_IRIS_INJECT_GAMMA", 2.2f);
    private static final float INJECT_EXPOSURE = parseEnvF("VOXY_IRIS_INJECT_EXPOSURE", 1.0f);
    private static final int INJECT_SQRT = "0".equals(System.getenv("VOXY_IRIS_INJECT_SQRT")) ? 0 : 1;
    private static final boolean COMPILE_PROBE = "1".equals(System.getenv("VOXY_VX_RESOLVE_COMPILE_TEST"));
    private static boolean compileProbeRan;

    /** Phase D spike (issue #11): run BSL's voxy_translucent over the LOD water
     *  instead of the flat passthrough. A/B kill switch; default OFF. */
    private static final boolean TRANS_RESOLVE = "1".equals(System.getenv("VOXY_VX_TRANS_RESOLVE"));

    private static float parseEnvF(String name, float dflt) {
        String v = System.getenv(name);
        if (v == null || v.isBlank()) return dflt;
        try {
            return Float.parseFloat(v.trim());
        } catch (NumberFormatException e) {
            return dflt;
        }
    }

    private VxContractInjector() {}

    /**
     * @return true if both the side-channel depth resolve and the colour
     *         inject ran (LODs handed to the pack's native VOXY path).
     */
    public static boolean inject(Viewport<?> viewport, IOSurfaceBridge colorBridge, IOSurfaceBridge depthBridge,
                                 IOSurfaceBridge transBridge, IOSurfaceBridge transDepthBridge) {
        if (disabled || viewport == null || colorBridge == null || depthBridge == null) {
            return false;
        }
        try {
            return inject0(colorBridge, depthBridge, transBridge, transDepthBridge);
        } catch (Throwable t) {
            if (!warnedFailure) {
                warnedFailure = true;
                Logger.warn("VxContractInjector: inject failed — falling back to hidden LODs under pack", t);
            }
            return false;
        }
    }

    private static boolean inject0(IOSurfaceBridge colorBridge, IOSurfaceBridge depthBridge,
                                   IOSurfaceBridge transBridge, IOSurfaceBridge transDepthBridge) {
        var pipeline = net.irisshaders.iris.Iris.getPipelineManager().getPipelineNullable();
        if (!(pipeline instanceof IGetIrisVoxyPipelineData dataGetter)) {
            return false;
        }
        var pipeData = dataGetter.voxy$getPipelineData();
        if (pipeData == null) {
            return false;
        }
        // Re-resolve the pack colortex ids with the CURRENT flip state every
        // frame — the build-time snapshot goes stale when Iris's buffer-flip
        // parity changes with frame composition (hand on/off, F1), making
        // the pack read an old frame's buffer side (angle-frozen gray wash).
        int[] opaqueTargets = pipeData.resolveOpaqueTargetsNow(
                (net.irisshaders.iris.pipeline.IrisRenderingPipeline) pipeline);
        int[] translucentTargets = pipeData.resolveTranslucentTargetsNow(
                (net.irisshaders.iris.pipeline.IrisRenderingPipeline) pipeline);

        // Phase C compile probe (VOXY_VX_RESOLVE_COMPILE_TEST=1): assemble
        // the pack's voxy_opaque/translucent resolve programs and verify
        // Apple's GLSL compiler accepts them, once, before any Metal MRT
        // work depends on it. GL context is current here.
        if (COMPILE_PROBE && !compileProbeRan) {
            compileProbeRan = true;
            MetalVxResolvePass.compileProbe(pipeData);
        }

        int fbw = colorBridge.width();
        int fbh = colorBridge.height();

        // Bind/resync the bridges to their GL rect textures (the compositor
        // owns the CGL binding + per-frame resync machinery).
        int colourRect = me.cortex.voxy.client.core.interop.IOSurfaceBridgeCompositor
                .acquireColorRectTex(colorBridge);
        int depthRect = me.cortex.voxy.client.core.interop.IOSurfaceBridgeCompositor
                .acquireDepthRectTex(depthBridge);
        if (colourRect == 0 || depthRect == 0) {
            return false;
        }

        // 1. Depth side-channel: vxDepthTexOpaque/Trans become real.
        boolean depthOk = VxIrisSideChannel.getOrCreate().resolve(
                colourRect, depthRect, fbw, fbh,
                me.cortex.voxy.client.core.rendering.util.MetalMvpUtil.METAL_NDC_REMAP);
        if (!depthOk) {
            return false;
        }

        // 2. Colour into the pack's opaque vx draw targets.
        if (program == 0 && !buildProgram()) {
            disabled = true;
            return false;
        }
        if (!ensureColorFbo(opaqueTargets)) {
            return false;
        }

        int prevDrawFb = glGetInteger(GL_DRAW_FRAMEBUFFER_BINDING);
        int prevReadFb = glGetInteger(GL_READ_FRAMEBUFFER_BINDING);
        int prevProgram = glGetInteger(GL_CURRENT_PROGRAM);
        int prevVao = glGetInteger(GL_VERTEX_ARRAY_BINDING);
        int prevActiveTex = glGetInteger(GL_ACTIVE_TEXTURE);
        int[] prevViewport = new int[4];
        glGetIntegerv(GL_VIEWPORT, prevViewport);
        boolean prevDepthTest = glIsEnabled(GL_DEPTH_TEST);
        boolean prevBlend = glIsEnabled(GL_BLEND);
        boolean prevScissor = glIsEnabled(GL_SCISSOR_TEST);
        glActiveTexture(GL_TEXTURE1);
        int prevTexRect1 = glGetInteger(GL_TEXTURE_BINDING_RECTANGLE);
        int prevSampler1 = glGetInteger(org.lwjgl.opengl.GL33C.GL_SAMPLER_BINDING);
        glActiveTexture(GL_TEXTURE0);
        int prevTexRect0 = glGetInteger(GL_TEXTURE_BINDING_RECTANGLE);
        int prevSampler0 = glGetInteger(org.lwjgl.opengl.GL33C.GL_SAMPLER_BINDING);

        try {
            glBindFramebuffer(GL_FRAMEBUFFER, colorFbo);
            glDisable(GL_SCISSOR_TEST);
            glViewport(0, 0, fbw, fbh);
            // No depth attachment on this FBO: depth test irrelevant, but
            // disable for clarity. No blend — coverage handled by discard.
            glDisable(GL_DEPTH_TEST);
            glDisable(GL_BLEND);
            glDisable(GL_CULL_FACE);

            org.lwjgl.opengl.GL33C.glBindSampler(0, 0);
            org.lwjgl.opengl.GL33C.glBindSampler(1, 0);
            glUseProgram(program);
            glBindVertexArray(vao);
            glActiveTexture(GL_TEXTURE0);
            glBindTexture(GL_TEXTURE_RECTANGLE, colourRect);
            glActiveTexture(GL_TEXTURE1);
            glBindTexture(GL_TEXTURE_RECTANGLE, depthRect);
            glActiveTexture(GL_TEXTURE0);
            glUniform1i(uColour, 0);
            glUniform1i(uDepthTex, 1);
            glUniform1f(uInjectGamma, INJECT_GAMMA);
            glUniform1f(uInjectExposure, INJECT_EXPOSURE);
            glUniform1i(uInjectSqrt, INJECT_SQRT);
            glDrawArrays(GL_TRIANGLE_STRIP, 0, 4);

            // Phase D (issue #11): translucent LOD layer. Decode the
            // translucent depth bridge into vxDepthTexTrans and write the
            // premultiplied water colour into the pack's FIRST translucent
            // draw target (BSL: colortex16, composited by deferred1 with
            // cloud occlusion; CR: colortex0 blend-over). Skipped silently
            // when the Metal split pass hasn't produced bridges yet.
            if (transBridge != null && transDepthBridge != null
                    && translucentTargets != null
                    && translucentTargets.length > 0) {
                int transColourRect = me.cortex.voxy.client.core.interop.IOSurfaceBridgeCompositor
                        .acquireAuxRectTex(transBridge);
                int transDepthRect = me.cortex.voxy.client.core.interop.IOSurfaceBridgeCompositor
                        .acquireAuxRectTex(transDepthBridge);
                if (transColourRect != 0 && transDepthRect != 0) {
                    boolean transDepthOk = VxIrisSideChannel.getOrCreate().resolveTrans(
                            transColourRect, transDepthRect, fbw, fbh,
                            me.cortex.voxy.client.core.rendering.util.MetalMvpUtil.METAL_NDC_REMAP);
                    // Phase D spike (VOXY_VX_TRANS_RESOLVE=1): run the pack's
                    // voxy_translucent over the LOD water instead of the flat
                    // passthrough. Falls back to the passthrough on any failure.
                    boolean resolved = false;
                    if (transDepthOk && TRANS_RESOLVE) {
                        resolved = runTransResolveSpike(pipeData, transColourRect, transDepthRect,
                                fbw, fbh, translucentTargets);
                    }
                    if (!resolved && transDepthOk && ensureTransFbo(translucentTargets[0])) {
                        glBindFramebuffer(GL_FRAMEBUFFER, transFbo);
                        glActiveTexture(GL_TEXTURE0);
                        glBindTexture(GL_TEXTURE_RECTANGLE, transColourRect);
                        glActiveTexture(GL_TEXTURE1);
                        glBindTexture(GL_TEXTURE_RECTANGLE, transDepthRect);
                        glActiveTexture(GL_TEXTURE0);
                        glUseProgram(transProgram);
                        glUniform1i(uTransColour, 0);
                        glUniform1i(uTransDepth, 1);
                        glUniform1f(uTransGamma, INJECT_GAMMA);
                        glUniform1i(uTransSqrt, INJECT_SQRT);
                        glDrawArrays(GL_TRIANGLE_STRIP, 0, 4);
                    }
                }
            }
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
            if (prevDepthTest) glEnable(GL_DEPTH_TEST);
            if (prevBlend) glEnable(GL_BLEND);
            if (prevScissor) glEnable(GL_SCISSOR_TEST);
            glViewport(prevViewport[0], prevViewport[1], prevViewport[2], prevViewport[3]);
            glBindFramebuffer(GL_DRAW_FRAMEBUFFER, prevDrawFb);
            glBindFramebuffer(GL_READ_FRAMEBUFFER, prevReadFb);
        }
    }

    /**
     * (Re)attach the pack's opaque draw-target colortexes. Texture ids
     * change on pack reload and buffer flips — re-attach only when they
     * differ. Writes are restricted to the first two targets (colortex0 +
     * colortex6 for BSL/CR); a third MCBL target is left unwritten.
     */
    private static boolean ensureColorFbo(int[] targets) {
        int n = Math.min(targets.length, 2);
        if (colorFbo == 0) {
            colorFbo = glGenFramebuffers();
            attachedTargets = new int[0];
        }
        boolean dirty = attachedTargets.length != n;
        for (int i = 0; !dirty && i < n; i++) {
            dirty = attachedTargets[i] != targets[i];
        }
        if (!dirty) return true;

        int prevFb = glGetInteger(GL_DRAW_FRAMEBUFFER_BINDING);
        glBindFramebuffer(GL_FRAMEBUFFER, colorFbo);
        for (int i = 0; i < n; i++) {
            glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0 + i,
                    GL_TEXTURE_2D, targets[i], 0);
        }
        int[] bufs = new int[n];
        for (int i = 0; i < n; i++) bufs[i] = GL_COLOR_ATTACHMENT0 + i;
        glDrawBuffers(bufs);
        int status = glCheckFramebufferStatus(GL_FRAMEBUFFER);
        glBindFramebuffer(GL_FRAMEBUFFER, prevFb);
        if (status != GL_FRAMEBUFFER_COMPLETE) {
            Logger.error("VxContractInjector: colour FBO incomplete: 0x" + Integer.toHexString(status));
            return false;
        }
        attachedTargets = java.util.Arrays.copyOf(targets, n);
        Logger.info("VxContractInjector: bound pack vx draw targets " + java.util.Arrays.toString(attachedTargets));
        return true;
    }

    private static int transFbo;
    private static int transAttachedTarget = -1;
    private static int transProgram;
    private static int uTransColour, uTransDepth, uTransGamma, uTransSqrt;

    private static boolean ensureTransFbo(int target) {
        if (transProgram == 0 && !buildTransProgram()) {
            return false;
        }
        if (transFbo == 0) {
            transFbo = glGenFramebuffers();
            transAttachedTarget = -1;
        }
        if (transAttachedTarget == target) return true;
        int prevFb = glGetInteger(GL_DRAW_FRAMEBUFFER_BINDING);
        glBindFramebuffer(GL_FRAMEBUFFER, transFbo);
        glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, target, 0);
        glDrawBuffers(new int[]{GL_COLOR_ATTACHMENT0});
        int status = glCheckFramebufferStatus(GL_FRAMEBUFFER);
        glBindFramebuffer(GL_FRAMEBUFFER, prevFb);
        if (status != GL_FRAMEBUFFER_COMPLETE) {
            Logger.error("VxContractInjector: translucent FBO incomplete: 0x" + Integer.toHexString(status));
            return false;
        }
        transAttachedTarget = target;
        Logger.info("VxContractInjector: bound pack translucent vx target " + target);
        return true;
    }

    private static boolean buildTransProgram() {
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
                uniform sampler2DRect uDepthTex;
                uniform float uInjectGamma;
                uniform int uInjectSqrt;
                in vec2 vUV;
                out vec4 outColor0;
                void main() {
                    ivec2 sz = textureSize(uColour);
                    vec2 texel = vec2(vUV.x * float(sz.x), (1.0 - vUV.y) * float(sz.y));
                    vec4 c = texture(uColour, texel);
                    vec3 dEnc = texture(uDepthTex, texel).rgb;
                    float d = dot(dEnc, vec3(1.0, 1.0 / 255.0, 1.0 / 65025.0));
                    if (c.a <= 0.001 || d <= 0.0 || d >= 0.9999999) discard;
                    // Bridge holds PREMULTIPLIED accumulation. The pack
                    // composites colortex16 as premultiplied sqrt-encoded
                    // colour: un-premultiply, encode like the opaque path,
                    // re-premultiply.
                    vec3 straight = c.rgb / max(c.a, 0.0001);
                    vec3 lin = pow(straight, vec3(uInjectGamma));
                    vec3 enc = (uInjectSqrt == 1) ? sqrt(max(lin, vec3(0.0))) : lin;
                    outColor0 = vec4(enc * c.a, c.a);
                }
                """;
        transProgram = VxIrisSideChannel.compile(vs, fs, "VxContractInjector.trans");
        if (transProgram == 0) return false;
        uTransColour = glGetUniformLocation(transProgram, "uColour");
        uTransDepth = glGetUniformLocation(transProgram, "uDepthTex");
        uTransGamma = glGetUniformLocation(transProgram, "uInjectGamma");
        uTransSqrt = glGetUniformLocation(transProgram, "uInjectSqrt");
        return true;
    }

    // ---- Phase D spike: run the pack's voxy_translucent over the LOD water ----
    private static int rsProgram = -1; // -1 untried, 0 failed, >0 ready
    private static int rsUAlbedo, rsUDepth, rsUDepthIsWindow, rsUWaterId;
    private static int rsUbo, rsUboSize;
    private static long rsUboScratch;
    private static int rsSamplerCount;
    private static int rsWaterId = Integer.MIN_VALUE;
    private static int rsFbo;
    private static int[] rsAttached = new int[0];
    private static boolean rsWarned;

    private static int resolveWaterId() {
        String env = System.getenv("VOXY_VX_WATER_ID");
        if (env != null && !env.isBlank()) {
            try { return Integer.parseInt(env.trim()); } catch (NumberFormatException ignored) {}
        }
        try {
            var ids = net.irisshaders.iris.shaderpack.materialmap.WorldRenderingSettings.INSTANCE.getBlockStateIds();
            if (ids != null) {
                return ids.getInt(net.minecraft.world.level.block.Blocks.WATER.defaultBlockState());
            }
        } catch (Throwable t) {
            Logger.warn("VxContractInjector: could not resolve water customId: " + t.getMessage());
        }
        return -1;
    }

    private static boolean buildTransResolveSpike(me.cortex.voxy.client.iris.IrisVoxyRenderPipelineData pipeData) {
        if (rsProgram != -1) return rsProgram > 0;
        rsProgram = 0; // mark tried (don't retry on failure)
        String fs = MetalVxResolvePass.assembleTranslucentSpike(pipeData);
        if (fs == null) {
            Logger.warn("VxContractInjector: trans resolve spike — pack not resolvable (null assembly)");
            return false;
        }
        int prog = VxIrisSideChannel.compile(MetalVxResolvePass.RESOLVE_VERT, fs, "VxContractInjector.transResolveSpike");
        if (prog == 0) {
            Logger.error("VxContractInjector: trans resolve spike program failed to compile/link");
            return false;
        }
        int prev = glGetInteger(GL_CURRENT_PROGRAM);
        glUseProgram(prog);
        rsUAlbedo = glGetUniformLocation(prog, "uVxAlbedo");
        rsUDepth = glGetUniformLocation(prog, "uVxDepth");
        rsUDepthIsWindow = glGetUniformLocation(prog, "uVxDepthIsWindow");
        rsUWaterId = glGetUniformLocation(prog, "uVxWaterId");
        glUniform1i(rsUAlbedo, 0);
        glUniform1i(rsUDepth, 1);
        glUniform1i(rsUDepthIsWindow, 1);
        // Pack samplers: units 6.. in declaration order (matches bindingFunction.accept(6)).
        rsSamplerCount = 0;
        if (pipeData.samplerDecls != null) {
            int unit = 6;
            for (var name : pipeData.samplerDecls.keySet()) {
                int loc = glGetUniformLocation(prog, name);
                if (loc >= 0) glUniform1i(loc, unit);
                unit++;
                rsSamplerCount++;
            }
        }
        if (pipeData.getUniforms() != null) {
            rsUboSize = pipeData.getUniforms().size();
            int blockIdx = glGetUniformBlockIndex(prog, "ShaderUniformBindings");
            if (blockIdx != GL_INVALID_INDEX) {
                glUniformBlockBinding(prog, blockIdx, 5);
            }
            rsUbo = glGenBuffers();
            rsUboScratch = org.lwjgl.system.MemoryUtil.nmemAlloc(rsUboSize);
        }
        glUseProgram(prev);
        rsWaterId = resolveWaterId();
        rsProgram = prog;
        Logger.info("VxContractInjector: trans resolve spike READY (samplers=" + rsSamplerCount
                + ", uboSize=" + rsUboSize + ", waterId=" + rsWaterId + ")");
        return true;
    }

    private static boolean ensureTransResolveFbo(int[] targets) {
        int n = targets.length;
        if (rsFbo == 0) { rsFbo = glGenFramebuffers(); rsAttached = new int[0]; }
        boolean dirty = rsAttached.length != n;
        for (int i = 0; !dirty && i < n; i++) dirty = rsAttached[i] != targets[i];
        if (!dirty) return true;
        int prevFb = glGetInteger(GL_DRAW_FRAMEBUFFER_BINDING);
        glBindFramebuffer(GL_FRAMEBUFFER, rsFbo);
        int[] bufs = new int[n];
        for (int i = 0; i < n; i++) {
            glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0 + i, GL_TEXTURE_2D, targets[i], 0);
            bufs[i] = GL_COLOR_ATTACHMENT0 + i;
        }
        glDrawBuffers(bufs);
        int status = glCheckFramebufferStatus(GL_FRAMEBUFFER);
        glBindFramebuffer(GL_FRAMEBUFFER, prevFb);
        if (status != GL_FRAMEBUFFER_COMPLETE) {
            Logger.error("VxContractInjector: trans resolve FBO incomplete: 0x" + Integer.toHexString(status));
            return false;
        }
        rsAttached = java.util.Arrays.copyOf(targets, n);
        Logger.info("VxContractInjector: bound trans resolve targets " + java.util.Arrays.toString(rsAttached));
        return true;
    }

    private static boolean runTransResolveSpike(me.cortex.voxy.client.iris.IrisVoxyRenderPipelineData pipeData,
                                                int albedoRect, int depthRect, int fbw, int fbh, int[] targets) {
        if (!buildTransResolveSpike(pipeData)) return false;
        if (rsWaterId <= 0) {
            if (!rsWarned) {
                rsWarned = true;
                Logger.warn("VxContractInjector: trans resolve spike — no usable water customId ("
                        + rsWaterId + "); set VOXY_VX_WATER_ID. Falling back to passthrough.");
            }
            return false;
        }
        if (!ensureTransResolveFbo(targets)) return false;

        glBindFramebuffer(GL_FRAMEBUFFER, rsFbo);
        glViewport(0, 0, fbw, fbh);
        glDisable(GL_DEPTH_TEST);
        glDisable(GL_CULL_FACE);
        glUseProgram(rsProgram);
        glBindVertexArray(vao);

        glActiveTexture(GL_TEXTURE0);
        glBindTexture(GL_TEXTURE_RECTANGLE, albedoRect);
        glActiveTexture(GL_TEXTURE1);
        glBindTexture(GL_TEXTURE_RECTANGLE, depthRect);
        glActiveTexture(GL_TEXTURE0);

        if (pipeData.getImageSet() != null) {
            pipeData.getImageSet().bindingFunction().accept(6);
        }
        if (rsUbo != 0 && pipeData.getUniforms() != null) {
            pipeData.getUniforms().updater().accept(rsUboScratch);
            glBindBuffer(GL_UNIFORM_BUFFER, rsUbo);
            glBufferData(GL_UNIFORM_BUFFER,
                    org.lwjgl.system.MemoryUtil.memByteBuffer(rsUboScratch, rsUboSize), GL_DYNAMIC_DRAW);
            glBindBufferBase(GL_UNIFORM_BUFFER, 5, rsUbo);
        }
        glUniform1ui(rsUWaterId, rsWaterId);

        if (pipeData.getBlender() != null) {
            pipeData.getBlender().run();
        } else {
            glDisable(GL_BLEND);
        }

        glDrawArrays(GL_TRIANGLE_STRIP, 0, 4);

        // Clean the extra units/UBO point we touched (outer finally only restores 0/1).
        for (int i = 0; i < rsSamplerCount; i++) {
            glActiveTexture(GL_TEXTURE0 + 6 + i);
            glBindTexture(GL_TEXTURE_2D, 0);
        }
        glActiveTexture(GL_TEXTURE0);
        glBindBufferBase(GL_UNIFORM_BUFFER, 5, 0);
        glBindBuffer(GL_UNIFORM_BUFFER, 0);
        return true;
    }

    private static boolean buildProgram() {
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
                uniform sampler2DRect uDepthTex;
                uniform float uInjectGamma;
                uniform float uInjectExposure;
                uniform int uInjectSqrt;
                in vec2 vUV;
                out vec4 outColor0;
                out vec4 outColor1;
                void main() {
                    ivec2 sz = textureSize(uColour);
                    vec2 texel = vec2(vUV.x * float(sz.x), (1.0 - vUV.y) * float(sz.y));
                    vec4 c = texture(uColour, texel);
                    vec3 dEnc = texture(uDepthTex, texel).rgb;
                    float d = dot(dEnc, vec3(1.0, 1.0 / 255.0, 1.0 / 65025.0));
                    if (c.a <= 0.001 || d <= 0.0 || d >= 0.9999999) discard;
                    // Pack colour convention (BSL ALPHA_BLEND 0): colortex0
                    // carries sqrt-encoded scene-linear radiance. NATIVE
                    // PARITY: BSL's own voxy_opaque stores
                    // sqrt(GetLighting(pow(albedo, 2.2))) with NO exposure
                    // pre-division — the tonemap's x4 is part of its normal
                    // chain for all content. (The legacy injector's 0.25
                    // factor here made LODs render at half the brightness of
                    // the pack's own LOD output — the "washed grey".)
                    vec3 lin = pow(c.rgb, vec3(uInjectGamma)) * uInjectExposure;
                    outColor0 = vec4((uInjectSqrt == 1) ? sqrt(max(lin, vec3(0.0))) : lin, 1.0);
                    // colortex6 seed: r = shadowMask (fully lit; deferred1's
                    // GetLODShadows refines), b = "LOD wrote here" mask the
                    // pack's own voxy_opaque writes as float(z < 1).
                    outColor1 = vec4(1.0, 0.0, 1.0, 1.0);
                }
                """;
        program = VxIrisSideChannel.compile(vs, fs, "VxContractInjector");
        if (program == 0) return false;
        uColour = glGetUniformLocation(program, "uColour");
        uDepthTex = glGetUniformLocation(program, "uDepthTex");
        uInjectGamma = glGetUniformLocation(program, "uInjectGamma");
        uInjectExposure = glGetUniformLocation(program, "uInjectExposure");
        uInjectSqrt = glGetUniformLocation(program, "uInjectSqrt");
        vao = glGenVertexArrays();
        return vao != 0;
    }
}
