package me.cortex.voxy.client.core.util;

import me.cortex.voxy.client.core.interop.IOSurfaceBridge;
import me.cortex.voxy.client.core.interop.IOSurfaceBridgeCompositor;
import me.cortex.voxy.client.core.rendering.Viewport;
import me.cortex.voxy.client.core.rendering.util.MetalMvpUtil;
import me.cortex.voxy.common.Logger;
import net.caffeinemc.mods.sodium.client.render.chunk.terrain.DefaultTerrainRenderPasses;
import org.joml.Matrix4f;

import static org.lwjgl.opengl.GL11C.glGetInteger;
import static org.lwjgl.opengl.GL20C.GL_DRAW_BUFFER0;
import static org.lwjgl.opengl.GL20C.GL_MAX_DRAW_BUFFERS;
import static org.lwjgl.opengl.GL20C.glDrawBuffers;
import static org.lwjgl.opengl.GL30C.GL_COLOR_ATTACHMENT0;
import static org.lwjgl.opengl.GL30C.GL_DRAW_FRAMEBUFFER;
import static org.lwjgl.opengl.GL30C.GL_DRAW_FRAMEBUFFER_BINDING;
import static org.lwjgl.opengl.GL30C.GL_READ_FRAMEBUFFER;
import static org.lwjgl.opengl.GL30C.GL_READ_FRAMEBUFFER_BINDING;
import static org.lwjgl.opengl.GL30C.glBindFramebuffer;

/**
 * Injects the Metal LOD bridge into Iris's terrain gbuffer so Voxy's LODs
 * render WITH a shader pack active. At the HEAD-of-render(SOLID) hook Iris
 * has NOT yet bound its terrain framebuffer (it binds inside
 * {@code ShaderChunkRenderer.begin} via redirect), so we bind it explicitly:
 * the pack's SOLID {@code GlFramebuffer} has the pack colortex at attachment
 * 0 (per its DRAWBUFFERS — glDrawBuffers is per-FBO state, hence the
 * save/restore dance) and MC's main-RT depth — the pack's depthtex0 — as its
 * depth attachment. The pack sky is already in colortex0 at depth 1.0 before
 * SOLID, so LOD pixels written with depth &lt; 1.0 survive the pack's
 * deferred/composite/final chain, and Iris's own terrain draws then
 * depth-test over them.
 *
 * The framebuffer is re-fetched EVERY frame — Iris recreates it on pack
 * reload, and {@code GlFramebuffer.bind()} binds GL_FRAMEBUFFER (draw+read).
 *
 * Iris classes are referenced ONLY here (and in IrisUtil) and every step is
 * guarded — any null / instanceof / GL failure degrades to returning false,
 * leaving the frame exactly as the no-inject path would.
 */
public final class IrisGbufferInjector {

    /**
     * Fraction of MC's far plane where injected LOD depth clamps (env
     * VOXY_IRIS_DEPTH_CLAMP_FRAC). 0.95 (round 22; was 0.75): the lower
     * clamp parked far LODs NEARER than deep-projecting REAL content, which
     * then lost the depth test against them — real terrain textures dropped
     * out at altitude, and the pack's clouds (depth-tested/raymarched
     * against scene depth) vanished over the whole LOD band. The original
     * 0.75 motivation (BSL border-fog repainting far-plane-parked pixels as
     * sky) no longer applies at full strength now that colour injects
     * post-deferred.
     */
    private static final float DEPTH_CLAMP_FRAC = parseFrac();
    private static float parseFrac() {
        String v = System.getenv("VOXY_IRIS_DEPTH_CLAMP_FRAC");
        if (v == null || v.isBlank()) return 0.95f;
        try {
            return Math.max(0.1f, Math.min(0.99f, Float.parseFloat(v.trim())));
        } catch (NumberFormatException e) {
            return 0.95f;
        }
    }

    private static boolean warnedFailure;

    private IrisGbufferInjector() {}

    /**
     * Single-phase injection at SOLID-head, pre-deferred (round 23 — ground-
     * truthed against BSL's GLSL and Iris 1.10.x source). Colour + depth
     * land in the pack's SOLID gbuffer framebuffer BEFORE the deferred
     * passes run (Iris runs deferred strictly between Sodium's SOLID and
     * TRANSLUCENT passes), so the pack's ONLY fog pass (BSL: deferred1),
     * its volumetric clouds (blended in deferred1, occluded to scene
     * depth), its water cloud-distance discard (gaux1) and the depthtex1
     * pre-translucent snapshot all see and process LOD pixels exactly like
     * real terrain. The round-21 "albedo mis-lighting" theory was wrong —
     * the wash was a colour-convention mismatch, fixed in the inject shader
     * (sqrt/scene-linear encoding), not a staging problem.
     * @return true if the LOD bridge was drawn into the pack's gbuffer.
     */
    public static boolean inject(Viewport<?> viewport, IOSurfaceBridge colorBridge, IOSurfaceBridge depthBridge) {
        if (!IrisUtil.IRIS_INSTALLED
                || viewport == null || colorBridge == null || depthBridge == null) {
            return false;
        }
        try {
            return inject0(viewport, colorBridge, depthBridge);
        } catch (Throwable t) {
            if (!warnedFailure) {
                warnedFailure = true;
                Logger.warn("IrisGbufferInjector: inject failed — LODs will be hidden under the pack", t);
            }
            return false;
        }
    }

    private static boolean inject0(Viewport<?> viewport, IOSurfaceBridge colorBridge, IOSurfaceBridge depthBridge) {
        var pipeline = net.irisshaders.iris.Iris.getPipelineManager().getPipelineNullable();
        if (!(pipeline instanceof net.irisshaders.iris.pipeline.IrisRenderingPipeline irisPipeline)) {
            return false;
        }
        var programs = irisPipeline.getSodiumPrograms();
        if (programs == null) {
            return false;
        }
        net.irisshaders.iris.gl.framebuffer.GlFramebuffer framebuffer =
                programs.getFramebuffer(DefaultTerrainRenderPasses.SOLID);
        if (framebuffer == null) {
            return false;
        }

        int prevDrawFb = glGetInteger(GL_DRAW_FRAMEBUFFER_BINDING);
        int prevReadFb = glGetInteger(GL_READ_FRAMEBUFFER_BINDING);
        framebuffer.bind(); // binds GL_FRAMEBUFFER (draw + read)

        // Save the FBO's full draw-buffer list. glDrawBuffers is per-FBO
        // state and the pack's DRAWBUFFERS directive may route SOLID to
        // several colortexes; we restrict to attachment 0 (the main color
        // colortex) for the inject and restore the exact list after so the
        // pack's own terrain draws fan out as authored.
        int maxDrawBuffers = Math.min(glGetInteger(GL_MAX_DRAW_BUFFERS), 8);
        int[] savedDrawBuffers = new int[maxDrawBuffers];
        for (int i = 0; i < maxDrawBuffers; i++) {
            savedDrawBuffers[i] = glGetInteger(GL_DRAW_BUFFER0 + i);
        }
        glDrawBuffers(new int[]{GL_COLOR_ATTACHMENT0});

        boolean drawn;
        try {
            // invVoxyMVP unprojects the stored LOD depth. It must invert the
            // EXACT matrix the LOD pass rendered with: MDIC uploads
            // viewport.MVP TRANSLATED by -innerTranslation (sub-section camera
            // offset) — inverting the untranslated MVP carried a systematic
            // ~32-block depth error. NDC remap applies only when
            // VOXY_LOD_METAL_NDC=1, which the flag mirrors. mcMVP reprojects
            // into the vanilla clip space the pack's depthtex0 uses.
            Matrix4f invVoxyMVP = new Matrix4f(viewport.MVP)
                    .translate(-viewport.innerTranslation.x, -viewport.innerTranslation.y, -viewport.innerTranslation.z)
                    .invert();
            Matrix4f mcMVP = new Matrix4f(viewport.vanillaProjection).mul(viewport.modelView);
            // Depth clamp BELOW the far plane: parking beyond-MC-far LODs AT
            // the far plane lands them exactly in the pack's border-fog
            // saturation band — BSL repaints that band as pure sky, dissolving
            // the entire far field ("transparent terrain"). Clamp at a
            // fraction of MC far instead: still deeper than all near terrain
            // (which ends at renderDistance << frac*far), still < 1.0 for the
            // pack's sky test, but outside the saturated fog band.
            // VOXY_IRIS_DEPTH_CLAMP_FRAC tunes (default 0.75).
            float mcFar = net.minecraft.client.Minecraft.getInstance().gameRenderer.getDepthFar();
            org.joml.Vector4f clampPoint = new org.joml.Vector4f(0, 0, -DEPTH_CLAMP_FRAC * mcFar, 1)
                    .mul(viewport.vanillaProjection);
            float maxNdcZ = clampPoint.z / clampPoint.w;
            drawn = IOSurfaceBridgeCompositor.compositeIrisGbuffer(
                    colorBridge, depthBridge, invVoxyMVP, mcMVP,
                    MetalMvpUtil.METAL_NDC_REMAP, maxNdcZ);
        } finally {
            glDrawBuffers(savedDrawBuffers);
            glBindFramebuffer(GL_DRAW_FRAMEBUFFER, prevDrawFb);
            glBindFramebuffer(GL_READ_FRAMEBUFFER, prevReadFb);
        }
        return drawn;
    }
}
