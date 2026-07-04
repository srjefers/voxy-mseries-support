package me.cortex.voxy.client.core.interop;

import me.cortex.voxy.client.core.metal.MetalNative;
import me.cortex.voxy.common.Logger;
import net.minecraft.client.Minecraft;

import static org.lwjgl.opengl.GL11C.GL_LINEAR;
import static org.lwjgl.opengl.GL11C.GL_BLEND;
import static org.lwjgl.opengl.GL11C.GL_COLOR_BUFFER_BIT;
import static org.lwjgl.opengl.GL11C.GL_CULL_FACE;
import static org.lwjgl.opengl.GL11C.GL_DEPTH_TEST;
import static org.lwjgl.opengl.GL11C.GL_FALSE;
import static org.lwjgl.opengl.GL11C.GL_NEAREST;
import static org.lwjgl.opengl.GL11C.GL_ONE;
import static org.lwjgl.opengl.GL11C.GL_ONE_MINUS_SRC_ALPHA;
import static org.lwjgl.opengl.GL11C.GL_SRC_ALPHA;
import static org.lwjgl.opengl.GL11C.GL_TEXTURE_MIN_FILTER;
import static org.lwjgl.opengl.GL11C.GL_TEXTURE_MAG_FILTER;
import static org.lwjgl.opengl.GL11C.GL_TEXTURE_WRAP_S;
import static org.lwjgl.opengl.GL11C.GL_TEXTURE_WRAP_T;
import static org.lwjgl.opengl.GL11C.GL_TRIANGLE_STRIP;
import static org.lwjgl.opengl.GL11C.GL_TRUE;
import static org.lwjgl.opengl.GL11C.GL_VIEWPORT;
import static org.lwjgl.opengl.GL11C.glGetInteger;
import static org.lwjgl.opengl.GL11C.glGetIntegerv;
import static org.lwjgl.opengl.GL11C.glViewport;
import static org.lwjgl.opengl.GL11C.glDisable;
import static org.lwjgl.opengl.GL11C.glDrawArrays;
import static org.lwjgl.opengl.GL11C.glEnable;
import static org.lwjgl.opengl.GL11C.glIsEnabled;
import static org.lwjgl.opengl.GL11C.glBindTexture;
import static org.lwjgl.opengl.GL11C.glTexParameteri;
import static org.lwjgl.opengl.GL11C.glDeleteTextures;
import static org.lwjgl.opengl.GL11C.glGenTextures;
import static org.lwjgl.opengl.GL12C.GL_CLAMP_TO_EDGE;
import static org.lwjgl.opengl.GL11C.GL_DEPTH_FUNC;
import static org.lwjgl.opengl.GL11C.GL_DEPTH_WRITEMASK;
import static org.lwjgl.opengl.GL11C.GL_LEQUAL;
import static org.lwjgl.opengl.GL11C.glDepthFunc;
import static org.lwjgl.opengl.GL11C.glDepthMask;
import static org.lwjgl.opengl.GL11C.glGetBoolean;
import static org.lwjgl.opengl.GL13C.GL_ACTIVE_TEXTURE;
import static org.lwjgl.opengl.GL13C.GL_TEXTURE0;
import static org.lwjgl.opengl.GL13C.GL_TEXTURE1;
import static org.lwjgl.opengl.GL13C.glActiveTexture;
import static org.lwjgl.opengl.GL14C.GL_BLEND_DST_ALPHA;
import static org.lwjgl.opengl.GL14C.GL_BLEND_DST_RGB;
import static org.lwjgl.opengl.GL14C.GL_BLEND_SRC_ALPHA;
import static org.lwjgl.opengl.GL14C.GL_BLEND_SRC_RGB;
import static org.lwjgl.opengl.GL14C.glBlendFuncSeparate;
import static org.lwjgl.opengl.GL30C.GL_COLOR_ATTACHMENT0;
import static org.lwjgl.opengl.GL20C.GL_COMPILE_STATUS;
import static org.lwjgl.opengl.GL20C.GL_CURRENT_PROGRAM;
import static org.lwjgl.opengl.GL20C.GL_FRAGMENT_SHADER;
import static org.lwjgl.opengl.GL20C.GL_LINK_STATUS;
import static org.lwjgl.opengl.GL20C.GL_VERTEX_SHADER;
import static org.lwjgl.opengl.GL20C.glAttachShader;
import static org.lwjgl.opengl.GL20C.glCompileShader;
import static org.lwjgl.opengl.GL20C.glCreateProgram;
import static org.lwjgl.opengl.GL20C.glCreateShader;
import static org.lwjgl.opengl.GL20C.glDeleteProgram;
import static org.lwjgl.opengl.GL20C.glDeleteShader;
import static org.lwjgl.opengl.GL20C.glGetProgramInfoLog;
import static org.lwjgl.opengl.GL20C.glGetProgrami;
import static org.lwjgl.opengl.GL20C.glGetShaderInfoLog;
import static org.lwjgl.opengl.GL20C.glGetShaderi;
import static org.lwjgl.opengl.GL20C.glGetUniformLocation;
import static org.lwjgl.opengl.GL20C.glLinkProgram;
import static org.lwjgl.opengl.GL20C.glShaderSource;
import static org.lwjgl.opengl.GL20C.glUniform1f;
import static org.lwjgl.opengl.GL20C.glUniform1i;
import static org.lwjgl.opengl.GL20C.glUniform2f;
import static org.lwjgl.opengl.GL20C.glUniformMatrix4fv;
import static org.lwjgl.opengl.GL20C.glUseProgram;
import static org.lwjgl.opengl.GL30C.GL_VERTEX_ARRAY_BINDING;
import static org.lwjgl.opengl.GL30C.GL_READ_FRAMEBUFFER;
import static org.lwjgl.opengl.GL30C.GL_READ_FRAMEBUFFER_BINDING;
import static org.lwjgl.opengl.GL30C.GL_DRAW_FRAMEBUFFER_BINDING;
import static org.lwjgl.opengl.GL30C.GL_FRAMEBUFFER_COMPLETE;
import static org.lwjgl.opengl.GL30C.glBindFramebuffer;
import static org.lwjgl.opengl.GL30C.glBindVertexArray;
import static org.lwjgl.opengl.GL30C.glCheckFramebufferStatus;
import static org.lwjgl.opengl.GL30C.glDeleteFramebuffers;
import static org.lwjgl.opengl.GL30C.glFramebufferTexture2D;
import static org.lwjgl.opengl.GL30C.glGenFramebuffers;
import static org.lwjgl.opengl.GL30C.glGenVertexArrays;

/**
 * Composites a Voxy IOSurfaceBridge's contents into the currently bound
 * GL_DRAW_FRAMEBUFFER. Lazy-binds the IOSurface to a GL_TEXTURE_RECTANGLE
 * once (via {@code CGLTexImageIOSurface2D}) and reuses a transient source
 * FBO with that texture as COLOR_ATTACHMENT0.
 *
 * Caller responsibility: must invoke from a code path where MC's main
 * render target FBO is the active GL_DRAW_FRAMEBUFFER. Verified call
 * sites are inside Sodium's chunk render (mixin into
 * {@code DefaultChunkRenderer.render} BEFORE its end() call) — there MC
 * has bound the main RT for chunk drawing. Calling from
 * {@code LevelRenderer.renderLevel}'s RETURN doesn't work because by
 * then MC has already unbound to FBO 0, so a blit there ends up in the
 * window backbuffer (which MC then overwrites with its own RT→window
 * blit, hiding our output).
 */
public final class IOSurfaceBridgeCompositor {

    private static int compositeFbo;
    private static int compositeGlTex;
    private static int compositeProgram;
    private static int compositeVao;
    private static int uniformBridge;
    private static int uniformSize;
    private static long boundIoSurface;
    private static int blitFrameCounter;
    private static boolean disabled;
    private static final int GL_TEXTURE_RECTANGLE = 0x84F5;
    private static final int GL_TEXTURE_BINDING_RECTANGLE = 0x84F6;

    // --- Iris gbuffer injection state (compositeIrisGbuffer) ---
    // The depth bridge needs its own cached GL texture + bound-surface
    // tracker (the compositeGlTex/boundIoSurface pair above stays dedicated
    // to the color bridge so the default blit path is untouched).
    private static int gbufferDepthGlTex;
    private static long boundDepthIoSurface;
    private static int gbufferProgram;
    private static int gbufferVao;
    private static int gbUniformColor;
    private static int gbUniformDepth;
    private static int gbUniformSize;
    private static int gbUniformInvVoxyMVP;
    private static int gbUniformMcMVP;
    private static int gbUniformVoxyDepthIsWindow;
    private static int gbUniformInjectGamma;
    private static int gbUniformInjectExposure;
    private static int gbUniformMaxNdcZ;
    private static int gbUniformDebugMode;
    private static int gbUniformInjectSqrt;
    /**
     * VOXY_INJECT_DEBUG paints diagnostics instead of LOD colour:
     * 1 = discard-gate palette (magenta/red/blue/green),
     * 2 = raw depth-bridge RGB as sampled (pre-decode),
     * 3 = colour-bridge alpha as grayscale.
     */
    private static final int INJECT_DEBUG = parseEnvI("VOXY_INJECT_DEBUG");
    private static int parseEnvI(String name) {
        String v = System.getenv(name);
        if (v == null || v.isBlank()) return 0;
        try {
            return Integer.parseInt(v.trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }
    /** sRGB->linear power applied to injected LOD colour (packs tonemap linear input). */
    private static final float INJECT_GAMMA = parseEnvF("VOXY_IRIS_INJECT_GAMMA", 2.2f);
    /** Linear-space multiplier for matching pack exposure. */
    private static final float INJECT_EXPOSURE = parseEnvF("VOXY_IRIS_INJECT_EXPOSURE", 1.0f);
    /** Pack colour convention: 1 = sqrt-encode scene-linear (BSL ALPHA_BLEND 0). VOXY_IRIS_INJECT_SQRT=0 reverts to plain linear. */
    private static final int INJECT_SQRT = "0".equals(System.getenv("VOXY_IRIS_INJECT_SQRT")) ? 0 : 1;

    private static float parseEnvF(String name, float dflt) {
        String v = System.getenv(name);
        if (v == null || v.isBlank()) return dflt;
        try {
            return Float.parseFloat(v.trim());
        } catch (NumberFormatException e) {
            return dflt;
        }
    }
    private static boolean gbufferDisabled;
    private static int gbufferFrameCounter;

    /**
     * Default is the opaque glBlitFramebuffer composite: the bridge (fog
     * clear + LOD) replaces MC's far field wholesale. The 2026-06-09 attempt
     * to default the alpha-discard shader composite ("Fix A": undrawn pixels
     * keep MC's backdrop) FAILED in-game on MC 1.21.11: at the head of
     * Sodium's SOLID pass the main RT does NOT contain the rendered sky —
     * discarded pixels exposed the cleared-black buffer (black sky at noon,
     * clouds intact since they draw later) and opaque land LOD vanished too.
     * Until the composite is depth/sky-order aware, the blit + temporally
     * smoothed fog (VoxyRenderSystem.smoothFogParameters — the actual fix
     * for the eye-crossing fog strobe) is the stable combination.
     * VOXY_COMPOSITE_SHADER=1 opts back into the experimental shader path.
     */
    public static final boolean USE_BLIT = !"1".equals(System.getenv("VOXY_COMPOSITE_SHADER"));

    private IOSurfaceBridgeCompositor() {}

    /** Composite the bridge's contents into the currently bound DRAW framebuffer. */
    public static void composite(IOSurfaceBridge bridge) {
        composite(bridge, USE_BLIT);
    }

    /**
     * Composite with an explicit mode. {@code useBlit=false} forces the
     * alpha-discard shader composite regardless of {@link #USE_BLIT}.
     * (The Iris-pack path no longer goes through here — it uses
     * {@link #compositeIrisGbuffer} to draw into the pack's terrain gbuffer.)
     */
    public static void composite(IOSurfaceBridge bridge, boolean useBlit) {
        if (disabled || bridge == null || bridge.ioSurfaceHandle() == 0) return;

        // (Re)bind on first use or after the bridge re-allocated (resize).
        if (compositeGlTex == 0 || boundIoSurface != bridge.ioSurfaceHandle()) {
            if (!rebind(bridge)) {
                disabled = true;
                return;
            }
        }

        // Fix (2026-05-25, Step 1): re-specify the GL texture from the
        // IOSurface every frame so GL picks up Metal's latest completed write.
        // The IOSurface↔GL texture is bound once (CGLTexImageIOSurface2D in
        // rebind); after that GL serves it from its own texture cache, which
        // does NOT reliably observe Metal's external writes. submit()'s
        // waitUntilCompleted already guarantees Metal finished writing the
        // IOSurface before we get here, but without re-specifying, GL
        // alternately sees the fresh content and a stale/previous copy
        // frame-to-frame — that incoherence is the LOD "flicker between
        // texture and transparent" the user sees even with a static camera
        // and atlas (all bake/atlas/geometry counters flat). Re-calling
        // CGLTexImageIOSurface2D invalidates GL's cache and re-points the
        // texture at the IOSurface's current bytes. Save/restore the
        // rectangle-texture binding because composite() runs inside Sodium's
        // chunk render and must not leak GL state.
        int prevActiveTex0 = glGetInteger(GL_ACTIVE_TEXTURE);
        glActiveTexture(GL_TEXTURE0);
        int prevTexRect0 = glGetInteger(GL_TEXTURE_BINDING_RECTANGLE);
        bridge.bindToGlTexture(compositeGlTex);
        glBindTexture(GL_TEXTURE_RECTANGLE, prevTexRect0);
        glActiveTexture(prevActiveTex0);

        var mc = Minecraft.getInstance();
        var mainRT = mc.getMainRenderTarget();
        int fbw = mainRT.width;
        int fbh = mainRT.height;

        int prevReadFb = glGetInteger(GL_READ_FRAMEBUFFER_BINDING);
        int prevDrawFb = glGetInteger(GL_DRAW_FRAMEBUFFER_BINDING);

        // 2026-05-14: at HEAD of Sodium's render(), DRAW_FRAMEBUFFER is
        // typically FBO 0 (the system default), NOT MC's main RT. With
        // MC 1.21+'s blaze3d, the level renders into MC's RenderTarget,
        // not the system FB. So a blit-to-FBO-0 lands somewhere the user
        // never sees (or gets overwritten by MC's final present). Resolve
        // the GL FBO id from MC's RenderTarget.colorTexture (a GlTexture)
        // via reflection on the private `firstFboId` field — public API
        // doesn't expose it on this MC version.
        int mcDrawFbo = resolveMcMainFbo(mainRT);
        if (mcDrawFbo <= 0) {
            // Fall back to whatever DRAW_FBO was bound — preserves M12
            // behaviour if resolution fails.
            mcDrawFbo = prevDrawFb;
        }

        if (useBlit) {
            glBindFramebuffer(org.lwjgl.opengl.GL30C.GL_DRAW_FRAMEBUFFER, mcDrawFbo);
            glBindFramebuffer(GL_READ_FRAMEBUFFER, compositeFbo);
            // Y-flip: Metal textures are top-left origin, GL framebuffers bottom-left.
            org.lwjgl.opengl.GL30C.glBlitFramebuffer(0, 0, fbw, fbh,
                              0, fbh, fbw, 0,
                              GL_COLOR_BUFFER_BIT, GL_LINEAR);
            glBindFramebuffer(GL_READ_FRAMEBUFFER, prevReadFb);
            glBindFramebuffer(org.lwjgl.opengl.GL30C.GL_DRAW_FRAMEBUFFER, prevDrawFb);

            blitFrameCounter++;
            if ((blitFrameCounter % 600) == 1) {
                Logger.info("IOSurfaceBridgeCompositor: blit to MC mainRT +IOSurface-resync (fbo=" + mcDrawFbo + ", prevDraw=" + prevDrawFb + ") size " + fbw + "x" + fbh + " frame=" + blitFrameCounter);
            }
            return;
        }

        // Alpha-discard shader composite. composite() runs inside Sodium's
        // chunk render, so every state we touch is saved and restored
        // bit-for-bit or Sodium's SOLID pass would draw with our program.
        if (!ensureCompositeProgram()) {
            disabled = true;
            return;
        }
        int prevProgram = glGetInteger(GL_CURRENT_PROGRAM);
        int prevVao = glGetInteger(GL_VERTEX_ARRAY_BINDING);
        int prevActiveTex = glGetInteger(GL_ACTIVE_TEXTURE);
        int[] prevViewport = new int[4];
        glGetIntegerv(GL_VIEWPORT, prevViewport);
        boolean prevBlend = glIsEnabled(GL_BLEND);
        boolean prevDepth = glIsEnabled(GL_DEPTH_TEST);
        boolean prevCull  = glIsEnabled(GL_CULL_FACE);
        int prevBlendSrcRgb   = glGetInteger(GL_BLEND_SRC_RGB);
        int prevBlendDstRgb   = glGetInteger(GL_BLEND_DST_RGB);
        int prevBlendSrcAlpha = glGetInteger(GL_BLEND_SRC_ALPHA);
        int prevBlendDstAlpha = glGetInteger(GL_BLEND_DST_ALPHA);
        glActiveTexture(GL_TEXTURE0);
        int prevTexRect = glGetInteger(GL_TEXTURE_BINDING_RECTANGLE);

        glBindFramebuffer(org.lwjgl.opengl.GL30C.GL_DRAW_FRAMEBUFFER, mcDrawFbo);
        // Y-flip happens in the vertex shader's (1.0 - uv.y). Sampler2DRect
        // takes texel coords (0..size), so uSize is the size in pixels.
        glViewport(0, 0, fbw, fbh);
        glDisable(GL_DEPTH_TEST);
        glDisable(GL_CULL_FACE);
        // The shader hard-discards alpha<=0.001 pixels and writes the rest
        // straight to colour — no blending: water pixels already blended
        // against the fog-coloured clear in the bridge, so re-blending here
        // would double-darken them. Disabling BLEND also sidesteps Sodium's
        // previous BLEND state polluting our writes.
        glDisable(GL_BLEND);

        glUseProgram(compositeProgram);
        glBindVertexArray(compositeVao);
        glBindTexture(GL_TEXTURE_RECTANGLE, compositeGlTex);
        glUniform1i(uniformBridge, 0);
        glUniform2f(uniformSize, (float) fbw, (float) fbh);
        glDrawArrays(GL_TRIANGLE_STRIP, 0, 4);

        // Restore — order matters: rebind the texture target before
        // switching active unit back, restore blend func before re-enabling
        // BLEND so Sodium's next draw sees the right state.
        glBindTexture(GL_TEXTURE_RECTANGLE, prevTexRect);
        glActiveTexture(prevActiveTex);
        glUseProgram(prevProgram);
        glBindVertexArray(prevVao);
        glBlendFuncSeparate(prevBlendSrcRgb, prevBlendDstRgb,
                            prevBlendSrcAlpha, prevBlendDstAlpha);
        if (prevBlend) glEnable(GL_BLEND); else glDisable(GL_BLEND);
        if (prevDepth) glEnable(GL_DEPTH_TEST);
        if (prevCull)  glEnable(GL_CULL_FACE);
        glViewport(prevViewport[0], prevViewport[1], prevViewport[2], prevViewport[3]);
        glBindFramebuffer(org.lwjgl.opengl.GL30C.GL_DRAW_FRAMEBUFFER, prevDrawFb);
        glBindFramebuffer(GL_READ_FRAMEBUFFER, prevReadFb);

        blitFrameCounter++;
        if ((blitFrameCounter % 600) == 1) {
            Logger.info("IOSurfaceBridgeCompositor: shader-composite to MC mainRT (fbo=" + mcDrawFbo + ", prevDraw=" + prevDrawFb + ") size " + fbw + "x" + fbh + " frame=" + blitFrameCounter);
        }
    }

    /**
     * Iris gbuffer injection: draw the LOD color bridge into the CURRENTLY
     * BOUND draw framebuffer (the caller — {@code IrisGbufferInjector} —
     * binds Iris's SOLID terrain {@code GlFramebuffer} and restricts
     * glDrawBuffers to attachment 0 first), unprojecting the Metal depth
     * bridge back through Voxy's MVP and reprojecting through MC's so
     * {@code gl_FragDepth} lands in the pack's depthtex0 convention. Depth
     * test LEQUAL + write ON: the pack sky sits at depth 1.0 before the
     * SOLID pass, so LOD pixels (depth &lt; 1.0) survive the pack's
     * deferred/composite/final chain; Iris's near terrain then draws over
     * them with its own depth test.
     *
     * Unlike {@link #composite}, this does NOT touch the FBO binding — the
     * caller owns it (and restores draw buffers + prior bindings after).
     *
     * @param voxyDepthIsWindowConvention true when the LOD pass encoded with
     *        the {@code VOXY_LOD_METAL_NDC} remap (depth stored as
     *        {@code 0.5*z+0.5} window convention); false for the raw
     *        GL-convention MVP where stored depth IS GL NDC z.
     * @return true if the draw was issued.
     */
    /**
     * Bind-or-resync the colour bridge to its cached GL rect texture and
     * return the texture id (0 on failure). Extracted for the vx-contract
     * path (VxContractInjector) which drives its own draws but reuses the
     * compositor's CGL binding + per-frame resync machinery.
     */
    public static int acquireColorRectTex(IOSurfaceBridge bridge) {
        if (disabled || bridge == null || bridge.ioSurfaceHandle() == 0) return 0;
        if (compositeGlTex == 0 || boundIoSurface != bridge.ioSurfaceHandle()) {
            if (!rebind(bridge)) {
                disabled = true;
                return 0;
            }
        }
        resync(bridge, compositeGlTex);
        return compositeGlTex;
    }

    /** Depth-bridge counterpart of {@link #acquireColorRectTex}. */
    public static int acquireDepthRectTex(IOSurfaceBridge depthBridge) {
        if (gbufferDisabled || depthBridge == null || depthBridge.ioSurfaceHandle() == 0) return 0;
        if (gbufferDepthGlTex == 0 || boundDepthIoSurface != depthBridge.ioSurfaceHandle()) {
            if (!rebindDepth(depthBridge)) {
                gbufferDisabled = true;
                return 0;
            }
        }
        resync(depthBridge, gbufferDepthGlTex);
        return gbufferDepthGlTex;
    }

    // Phase D aux bridges (translucent colour + translucent depth): generic
    // per-surface GL rect-texture cache. Keyed by IOSurface handle; rebinds
    // on reallocation, resyncs per call like the primary bridges.
    private static final it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap AUX_RECT_TEXES =
            new it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap();

    public static int acquireAuxRectTex(IOSurfaceBridge bridge) {
        if (bridge == null || bridge.ioSurfaceHandle() == 0) return 0;
        long handle = bridge.ioSurfaceHandle();
        int tex = AUX_RECT_TEXES.getOrDefault(handle, 0);
        if (tex == 0) {
            int prevRect = glGetInteger(GL_TEXTURE_BINDING_RECTANGLE);
            tex = glGenTextures();
            glBindTexture(GL_TEXTURE_RECTANGLE, tex);
            glTexParameteri(GL_TEXTURE_RECTANGLE, GL_TEXTURE_MIN_FILTER, GL_NEAREST);
            glTexParameteri(GL_TEXTURE_RECTANGLE, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
            glTexParameteri(GL_TEXTURE_RECTANGLE, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
            glTexParameteri(GL_TEXTURE_RECTANGLE, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
            boolean ok = bridge.bindToGlTexture(tex);
            glBindTexture(GL_TEXTURE_RECTANGLE, prevRect);
            if (!ok) {
                glDeleteTextures(tex);
                return 0;
            }
            AUX_RECT_TEXES.put(handle, tex);
            Logger.info("IOSurfaceBridgeCompositor: aux bridge bound to GL tex " + tex);
        } else {
            resync(bridge, tex);
        }
        return tex;
    }

    /**
     * Per-frame IOSurface re-specification — GL only reliably observes
     * Metal's external writes when the texture is re-specified from the
     * IOSurface each frame (see composite()'s 2026-05-25 note).
     */
    private static void resync(IOSurfaceBridge bridge, int glTex) {
        int prevActiveTex = glGetInteger(GL_ACTIVE_TEXTURE);
        glActiveTexture(GL_TEXTURE0);
        int prevTexRect = glGetInteger(GL_TEXTURE_BINDING_RECTANGLE);
        bridge.bindToGlTexture(glTex);
        glBindTexture(GL_TEXTURE_RECTANGLE, prevTexRect);
        glActiveTexture(prevActiveTex);
    }

    public static boolean compositeIrisGbuffer(IOSurfaceBridge colorBridge, IOSurfaceBridge depthBridge,
                                               org.joml.Matrix4f invVoxyMVP, org.joml.Matrix4f mcMVP,
                                               boolean voxyDepthIsWindowConvention, float maxNdcZ) {
        if (gbufferDisabled || disabled
                || colorBridge == null || colorBridge.ioSurfaceHandle() == 0
                || depthBridge == null || depthBridge.ioSurfaceHandle() == 0) {
            return false;
        }

        // (Re)bind on first use or after either bridge re-allocated (resize).
        // The color bridge shares rebind() with the blit path (same GL tex).
        if (compositeGlTex == 0 || boundIoSurface != colorBridge.ioSurfaceHandle()) {
            if (!rebind(colorBridge)) {
                disabled = true;
                return false;
            }
        }
        if (gbufferDepthGlTex == 0 || boundDepthIoSurface != depthBridge.ioSurfaceHandle()) {
            if (!rebindDepth(depthBridge)) {
                gbufferDisabled = true;
                return false;
            }
        }
        if (!ensureGbufferProgram()) {
            gbufferDisabled = true;
            return false;
        }

        // Per-frame IOSurface resync for BOTH bridges — same GL texture-cache
        // incoherence as composite() (see the 2026-05-25 comment there): GL
        // only reliably observes Metal's external writes when the texture is
        // re-specified from the IOSurface each frame.
        int prevActiveTexR = glGetInteger(GL_ACTIVE_TEXTURE);
        glActiveTexture(GL_TEXTURE0);
        int prevTexRectR = glGetInteger(GL_TEXTURE_BINDING_RECTANGLE);
        colorBridge.bindToGlTexture(compositeGlTex);
        depthBridge.bindToGlTexture(gbufferDepthGlTex);
        glBindTexture(GL_TEXTURE_RECTANGLE, prevTexRectR);
        glActiveTexture(prevActiveTexR);

        int fbw = colorBridge.width();
        int fbh = colorBridge.height();

        // Full state save — this runs inside Sodium's SOLID pass with Iris's
        // terrain FB bound, so every touched state is restored bit-for-bit,
        // INCLUDING depth func and depth write mask (we force LEQUAL+write).
        int prevProgram = glGetInteger(GL_CURRENT_PROGRAM);
        int prevVao = glGetInteger(GL_VERTEX_ARRAY_BINDING);
        int prevActiveTex = glGetInteger(GL_ACTIVE_TEXTURE);
        int[] prevViewport = new int[4];
        glGetIntegerv(GL_VIEWPORT, prevViewport);
        boolean prevBlend = glIsEnabled(GL_BLEND);
        boolean prevDepth = glIsEnabled(GL_DEPTH_TEST);
        boolean prevCull  = glIsEnabled(GL_CULL_FACE);
        int prevDepthFunc = glGetInteger(GL_DEPTH_FUNC);
        boolean prevDepthMask = glGetBoolean(GL_DEPTH_WRITEMASK);
        int prevBlendSrcRgb   = glGetInteger(GL_BLEND_SRC_RGB);
        int prevBlendDstRgb   = glGetInteger(GL_BLEND_DST_RGB);
        int prevBlendSrcAlpha = glGetInteger(GL_BLEND_SRC_ALPHA);
        int prevBlendDstAlpha = glGetInteger(GL_BLEND_DST_ALPHA);
        glActiveTexture(GL_TEXTURE1);
        int prevTexRect1 = glGetInteger(GL_TEXTURE_BINDING_RECTANGLE);
        int prevSampler1 = glGetInteger(org.lwjgl.opengl.GL33C.GL_SAMPLER_BINDING);
        glActiveTexture(GL_TEXTURE0);
        int prevTexRect0 = glGetInteger(GL_TEXTURE_BINDING_RECTANGLE);
        int prevSampler0 = glGetInteger(org.lwjgl.opengl.GL33C.GL_SAMPLER_BINDING);
        // Iris binds GL SAMPLER OBJECTS to texture units for its own
        // textures; a bound sampler overrides our texture params, and a
        // mipmapped sampler makes a RECTANGLE texture incomplete → it
        // samples BLACK (root cause of the depth bridge reading zeros GL-side
        // while the IOSurface verifiably contained packed depth — colour on
        // unit 0 happened to escape, depth on unit 1 did not).
        org.lwjgl.opengl.GL33C.glBindSampler(0, 0);
        org.lwjgl.opengl.GL33C.glBindSampler(1, 0);

        glViewport(0, 0, fbw, fbh);
        // Depth-tested overlay: LEQUAL against the pack's depthtex0 (sky at
        // 1.0; any earlier writes win), depth write ON so the LOD depth
        // protects its pixels through the pack's deferred passes. No blend —
        // bridge pixels are already fog-blended; the shader discards a<=0.001.
        glEnable(GL_DEPTH_TEST);
        glDepthFunc(GL_LEQUAL);
        glDepthMask(true);
        glDisable(GL_BLEND);
        glDisable(GL_CULL_FACE);
        // Hygiene (round 23 audit): a leaked scissor rect would clip the
        // fullscreen inject; leaked stencil writes could corrupt pack
        // stencil state. Both saved/restored.
        boolean prevScissor = glIsEnabled(org.lwjgl.opengl.GL11C.GL_SCISSOR_TEST);
        int prevStencilMask = glGetInteger(org.lwjgl.opengl.GL11C.GL_STENCIL_WRITEMASK);
        org.lwjgl.opengl.GL11C.glDisable(org.lwjgl.opengl.GL11C.GL_SCISSOR_TEST);
        org.lwjgl.opengl.GL11C.glStencilMask(0);

        glUseProgram(gbufferProgram);
        glBindVertexArray(gbufferVao);
        glActiveTexture(GL_TEXTURE0);
        glBindTexture(GL_TEXTURE_RECTANGLE, compositeGlTex);
        glActiveTexture(GL_TEXTURE1);
        glBindTexture(GL_TEXTURE_RECTANGLE, gbufferDepthGlTex);
        glActiveTexture(GL_TEXTURE0);
        glUniform1i(gbUniformColor, 0);
        glUniform1i(gbUniformDepth, 1);
        glUniform2f(gbUniformSize, (float) fbw, (float) fbh);
        float[] mat = new float[16];
        invVoxyMVP.get(mat);
        glUniformMatrix4fv(gbUniformInvVoxyMVP, false, mat);
        mcMVP.get(mat);
        glUniformMatrix4fv(gbUniformMcMVP, false, mat);
        glUniform1i(gbUniformVoxyDepthIsWindow, voxyDepthIsWindowConvention ? 1 : 0);
        glUniform1f(gbUniformInjectGamma, INJECT_GAMMA);
        glUniform1f(gbUniformInjectExposure, INJECT_EXPOSURE);
        glUniform1f(gbUniformMaxNdcZ, maxNdcZ);
        glUniform1i(gbUniformDebugMode, INJECT_DEBUG);
        glUniform1i(gbUniformInjectSqrt, INJECT_SQRT);
        glDrawArrays(GL_TRIANGLE_STRIP, 0, 4);

        // Restore — texture targets before active unit, blend func before
        // BLEND enable, depth func/mask before handing back to Sodium.
        if (prevScissor) org.lwjgl.opengl.GL11C.glEnable(org.lwjgl.opengl.GL11C.GL_SCISSOR_TEST);
        org.lwjgl.opengl.GL11C.glStencilMask(prevStencilMask);
        org.lwjgl.opengl.GL33C.glBindSampler(0, prevSampler0);
        org.lwjgl.opengl.GL33C.glBindSampler(1, prevSampler1);
        glBindTexture(GL_TEXTURE_RECTANGLE, prevTexRect0);
        glActiveTexture(GL_TEXTURE1);
        glBindTexture(GL_TEXTURE_RECTANGLE, prevTexRect1);
        glActiveTexture(prevActiveTex);
        glUseProgram(prevProgram);
        glBindVertexArray(prevVao);
        glBlendFuncSeparate(prevBlendSrcRgb, prevBlendDstRgb,
                            prevBlendSrcAlpha, prevBlendDstAlpha);
        if (prevBlend) glEnable(GL_BLEND); else glDisable(GL_BLEND);
        if (prevDepth) glEnable(GL_DEPTH_TEST); else glDisable(GL_DEPTH_TEST);
        if (prevCull)  glEnable(GL_CULL_FACE);  else glDisable(GL_CULL_FACE);
        glDepthFunc(prevDepthFunc);
        glDepthMask(prevDepthMask);
        glViewport(prevViewport[0], prevViewport[1], prevViewport[2], prevViewport[3]);

        gbufferFrameCounter++;
        if ((gbufferFrameCounter % 600) == 1) {
            Logger.info("IOSurfaceBridgeCompositor: Iris gbuffer inject (size " + fbw + "x" + fbh
                    + ", voxyDepthIsWindow=" + voxyDepthIsWindowConvention
                    + ") frame=" + gbufferFrameCounter);
        }
        return true;
    }

    private static boolean rebindDepth(IOSurfaceBridge depthBridge) {
        int prevRectBinding = glGetInteger(GL_TEXTURE_BINDING_RECTANGLE);
        try {
            return rebindDepth0(depthBridge);
        } finally {
            glBindTexture(GL_TEXTURE_RECTANGLE, prevRectBinding);
        }
    }

    private static boolean rebindDepth0(IOSurfaceBridge depthBridge) {
        if (gbufferDepthGlTex != 0) {
            glDeleteTextures(gbufferDepthGlTex);
            gbufferDepthGlTex = 0;
        }
        if (MetalNative.cglGetCurrentContext() == 0) {
            Logger.warn("IOSurfaceBridgeCompositor: no current CGL context — gbuffer inject disabled");
            return false;
        }
        gbufferDepthGlTex = glGenTextures();
        if (gbufferDepthGlTex == 0) {
            Logger.error("IOSurfaceBridgeCompositor: glGenTextures returned 0 (depth bridge)");
            return false;
        }
        glBindTexture(GL_TEXTURE_RECTANGLE, gbufferDepthGlTex);
        glTexParameteri(GL_TEXTURE_RECTANGLE, GL_TEXTURE_MIN_FILTER, GL_NEAREST);
        glTexParameteri(GL_TEXTURE_RECTANGLE, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
        glTexParameteri(GL_TEXTURE_RECTANGLE, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
        glTexParameteri(GL_TEXTURE_RECTANGLE, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
        if (!depthBridge.bindToGlTexture(gbufferDepthGlTex)) {
            Logger.error("IOSurfaceBridgeCompositor: bindToGlTexture failed (depth bridge)");
            glDeleteTextures(gbufferDepthGlTex);
            gbufferDepthGlTex = 0;
            return false;
        }
        boundDepthIoSurface = depthBridge.ioSurfaceHandle();
        Logger.info("IOSurfaceBridgeCompositor: depth bridge bound to GL tex " + gbufferDepthGlTex + ", ready");
        return true;
    }

    private static boolean ensureGbufferProgram() {
        if (gbufferProgram != 0) {
            return true;
        }
        gbufferVao = glGenVertexArrays();
        if (gbufferVao == 0) {
            Logger.error("IOSurfaceBridgeCompositor: failed to allocate gbuffer VAO");
            return false;
        }

        // #version 150 core — Apple's GL is 4.1 core; do NOT reuse the 430
        // blit shaders here. vUV is the UNFLIPPED 0..1 screen uv: clip-space
        // XY for the unprojection must match the on-screen NDC position,
        // while the rect-texture SAMPLE coordinate is v-flipped (Metal
        // top-left origin — same uSize/(1-uv.y) scheme as the program above).
        int vs = compileShader(GL_VERTEX_SHADER, """
                #version 150 core
                out vec2 vUV;
                void main() {
                    vec2 pos;
                    if (gl_VertexID == 0) pos = vec2(-1.0, -1.0);
                    else if (gl_VertexID == 1) pos = vec2(1.0, -1.0);
                    else if (gl_VertexID == 2) pos = vec2(-1.0, 1.0);
                    else pos = vec2(1.0, 1.0);
                    vUV = pos * 0.5 + 0.5;
                    gl_Position = vec4(pos, 0.0, 1.0);
                }
                """);
        // Depth math: stored Metal depth d is either GL NDC z (raw-convention
        // MVP, the default) or window 0.5*z+0.5 (VOXY_LOD_METAL_NDC remap) —
        // uVoxyDepthIsWindow selects the back-map. Unproject through Voxy's
        // inverse MVP (16..48000 block frustum), reproject through MC's
        // vanilla MVP, and clamp below 1.0 (MAX_DEPTH backs off ~2 ulp of a
        // 24-bit depth buffer) so LOD pixels never alias with the pack's
        // sky-at-1.0 test in deferred/composite passes.
        int fs = compileShader(GL_FRAGMENT_SHADER, """
                #version 150 core
                uniform sampler2DRect uColor;
                uniform sampler2DRect uDepth;
                uniform vec2 uSize;
                uniform mat4 uInvVoxyMVP;
                uniform mat4 uMcMVP;
                uniform int uVoxyDepthIsWindow;
                uniform float uInjectGamma;
                uniform float uInjectExposure;
                uniform float uMaxNdcZ;
                uniform int uDebugMode;
                uniform int uInjectSqrt;
                in vec2 vUV;
                out vec4 fragColor;
                const float MAX_DEPTH = 1.0 - 2.0 / 16777215.0;
                void main() {
                    vec2 texel = vec2(vUV.x * uSize.x, (1.0 - vUV.y) * uSize.y);
                    vec4 c = texture(uColor, texel);
                    // Depth arrives 24-bit-packed in RGB (Apple GL samples
                    // zeros from R32F IOSurfaces) — decode EncodeFloatRGB.
                    vec3 dEnc = texture(uDepth, texel).rgb;
                    float d = dot(dEnc, vec3(1.0, 1.0 / 255.0, 1.0 / 65025.0));
                    // VOXY_INJECT_DEBUG=1: visualize which gate would discard.
                    // magenta=alpha<=0.001, red=depth<=0, blue=depth>=1,
                    // green=all gates pass. Drawn at a fixed near depth so the
                    // result is visible regardless of depth correctness.
                    if (uDebugMode == 1) {
                        gl_FragDepth = 0.4;
                        if (c.a <= 0.001)     { fragColor = vec4(1.0, 0.0, 1.0, 1.0); return; }
                        if (d <= 0.0)         { fragColor = vec4(1.0, 0.0, 0.0, 1.0); return; }
                        if (d >= 1.0)         { fragColor = vec4(0.0, 0.0, 1.0, 1.0); return; }
                        fragColor = vec4(0.0, 1.0, 0.0, 1.0);
                        return;
                    }
                    // VOXY_INJECT_DEBUG=2: raw depth-bridge bytes as colour
                    // (what GL actually sampled, before decode). =3: colour
                    // bridge alpha as grayscale. Both bypass all gates.
                    if (uDebugMode == 2) {
                        gl_FragDepth = 0.4;
                        fragColor = vec4(dEnc, 1.0);
                        return;
                    }
                    if (uDebugMode == 3) {
                        gl_FragDepth = 0.4;
                        fragColor = vec4(c.a, c.a, c.a, 1.0);
                        return;
                    }
                    if (c.a <= 0.001 || d <= 0.0 || d >= 1.0) discard;
                    float zndc = (uVoxyDepthIsWindow == 1) ? d * 2.0 - 1.0 : d;
                    vec4 p = uInvVoxyMVP * vec4(vUV * 2.0 - 1.0, zndc, 1.0);
                    p /= p.w;
                    vec4 q = uMcMVP * vec4(p.xyz, 1.0);
                    float z = q.z / q.w;
                    gl_FragDepth = 0.5 * min(z, min(uMaxNdcZ, MAX_DEPTH)) + 0.5;
                    // Colour convention (ground-truthed from BSL's GLSL):
                    // pre-deferred colortex0 holds SQRT-ENCODED scene-linear
                    // radiance (ALPHA_BLEND 0) at pre-exposure magnitudes —
                    // the tonemap later multiplies by exp2(2+EXPOSURE)=4 and
                    // compresses. So: display-sRGB -> linear (pow uInjectGamma),
                    // pre-divide the tonemap exposure (uInjectExposure/4),
                    // sqrt-encode. uInjectSqrt=0 (VOXY_IRIS_INJECT_SQRT=0)
                    // reverts to plain linear for packs without the sqrt
                    // convention.
                    vec3 lin = pow(c.rgb, vec3(uInjectGamma)) * (uInjectExposure * 0.25);
                    c.rgb = (uInjectSqrt == 1) ? sqrt(max(lin, vec3(0.0))) : lin;
                    fragColor = c;
                }
                """);
        if (vs == 0 || fs == 0) {
            if (vs != 0) glDeleteShader(vs);
            if (fs != 0) glDeleteShader(fs);
            return false;
        }

        int program = glCreateProgram();
        glAttachShader(program, vs);
        glAttachShader(program, fs);
        glLinkProgram(program);
        glDeleteShader(vs);
        glDeleteShader(fs);
        if (glGetProgrami(program, GL_LINK_STATUS) != GL_TRUE) {
            Logger.error("IOSurfaceBridgeCompositor: gbuffer program link failed: "
                    + glGetProgramInfoLog(program));
            glDeleteProgram(program);
            return false;
        }
        gbufferProgram = program;
        gbUniformColor = glGetUniformLocation(program, "uColor");
        gbUniformDepth = glGetUniformLocation(program, "uDepth");
        gbUniformSize = glGetUniformLocation(program, "uSize");
        gbUniformInvVoxyMVP = glGetUniformLocation(program, "uInvVoxyMVP");
        gbUniformMcMVP = glGetUniformLocation(program, "uMcMVP");
        gbUniformVoxyDepthIsWindow = glGetUniformLocation(program, "uVoxyDepthIsWindow");
        gbUniformInjectGamma = glGetUniformLocation(program, "uInjectGamma");
        gbUniformInjectExposure = glGetUniformLocation(program, "uInjectExposure");
        gbUniformMaxNdcZ = glGetUniformLocation(program, "uMaxNdcZ");
        gbUniformDebugMode = glGetUniformLocation(program, "uDebugMode");
        gbUniformInjectSqrt = glGetUniformLocation(program, "uInjectSqrt");
        return true;
    }

    /**
     * Resolve the GL framebuffer id backing MC's main RenderTarget. MC 1.21+
     * doesn't expose this directly; we go through the color GpuTexture which
     * on the GL backend is a {@code com.mojang.blaze3d.opengl.GlTexture}
     * with a private {@code firstFboId}. Cached after first successful read.
     */
    private static int cachedMcMainFbo = -1;
    private static Object cachedMcMainColorTex;
    private static java.lang.reflect.Field firstFboIdField;
    private static int resolveMcMainFbo(com.mojang.blaze3d.pipeline.RenderTarget mainRT) {
        try {
            Object colorTex = mainRT.getColorTexture();
            if (colorTex == null) return cachedMcMainFbo;
            if (colorTex == cachedMcMainColorTex && cachedMcMainFbo > 0) {
                return cachedMcMainFbo;
            }
            cachedMcMainColorTex = colorTex;
            if (firstFboIdField == null
                    || !firstFboIdField.getDeclaringClass().isInstance(colorTex)) {
                Class<?> c = colorTex.getClass();
                while (c != null && c != Object.class) {
                    try {
                        firstFboIdField = c.getDeclaredField("firstFboId");
                        firstFboIdField.setAccessible(true);
                        break;
                    } catch (NoSuchFieldException ignored) {
                        c = c.getSuperclass();
                    }
                }
                if (firstFboIdField == null) {
                    Logger.warn("IOSurfaceBridgeCompositor: could not locate firstFboId on " + colorTex.getClass().getName());
                    return cachedMcMainFbo;
                }
            }
            int fbo = firstFboIdField.getInt(colorTex);
            if (fbo > 0) {
                cachedMcMainFbo = fbo;
            }
            return cachedMcMainFbo;
        } catch (Throwable t) {
            Logger.warn("IOSurfaceBridgeCompositor: failed to resolve MC mainRT FBO", t);
            return cachedMcMainFbo;
        }
    }

    private static boolean rebind(IOSurfaceBridge bridge) {
        int prevRectBinding = glGetInteger(GL_TEXTURE_BINDING_RECTANGLE);
        try {
            return rebind0(bridge);
        } finally {
            glBindTexture(GL_TEXTURE_RECTANGLE, prevRectBinding);
        }
    }

    private static boolean rebind0(IOSurfaceBridge bridge) {
        if (compositeGlTex != 0) {
            glDeleteTextures(compositeGlTex);
            compositeGlTex = 0;
        }
        if (compositeFbo != 0) {
            glDeleteFramebuffers(compositeFbo);
            compositeFbo = 0;
        }
        if (MetalNative.cglGetCurrentContext() == 0) {
            Logger.warn("IOSurfaceBridgeCompositor: no current CGL context — composite disabled");
            return false;
        }
        compositeGlTex = glGenTextures();
        if (compositeGlTex == 0) {
            Logger.error("IOSurfaceBridgeCompositor: glGenTextures returned 0");
            return false;
        }
        glBindTexture(GL_TEXTURE_RECTANGLE, compositeGlTex);
        glTexParameteri(GL_TEXTURE_RECTANGLE, GL_TEXTURE_MIN_FILTER, GL_NEAREST);
        glTexParameteri(GL_TEXTURE_RECTANGLE, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
        glTexParameteri(GL_TEXTURE_RECTANGLE, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
        glTexParameteri(GL_TEXTURE_RECTANGLE, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
        if (!bridge.bindToGlTexture(compositeGlTex)) {
            Logger.error("IOSurfaceBridgeCompositor: bindToGlTexture failed");
            glDeleteTextures(compositeGlTex);
            compositeGlTex = 0;
            return false;
        }
        boundIoSurface = bridge.ioSurfaceHandle();

        compositeFbo = glGenFramebuffers();
        int prevReadFb = glGetInteger(GL_READ_FRAMEBUFFER_BINDING);
        glBindFramebuffer(GL_READ_FRAMEBUFFER, compositeFbo);
        glFramebufferTexture2D(GL_READ_FRAMEBUFFER, GL_COLOR_ATTACHMENT0,
                GL_TEXTURE_RECTANGLE, compositeGlTex, 0);
        int status = glCheckFramebufferStatus(GL_READ_FRAMEBUFFER);
        glBindFramebuffer(GL_READ_FRAMEBUFFER, prevReadFb);
        if (status != GL_FRAMEBUFFER_COMPLETE) {
            Logger.error("IOSurfaceBridgeCompositor: source FBO incomplete (status=0x"
                    + Integer.toHexString(status) + ")");
            glDeleteFramebuffers(compositeFbo);
            compositeFbo = 0;
            glDeleteTextures(compositeGlTex);
            compositeGlTex = 0;
            return false;
        }

        Logger.info("IOSurfaceBridgeCompositor: bridge bound to GL tex " + compositeGlTex
                + ", composite FBO " + compositeFbo + " (status=COMPLETE), ready");
        return true;
    }

    private static boolean ensureCompositeProgram() {
        if (compositeProgram != 0) {
            return true;
        }
        compositeVao = glGenVertexArrays();
        if (compositeVao == 0) {
            Logger.error("IOSurfaceBridgeCompositor: failed to allocate composite VAO");
            return false;
        }

        int vs = compileShader(GL_VERTEX_SHADER, """
                #version 150 core
                uniform vec2 uSize;
                out vec2 vTexCoord;
                void main() {
                    vec2 pos;
                    if (gl_VertexID == 0) pos = vec2(-1.0, -1.0);
                    else if (gl_VertexID == 1) pos = vec2(1.0, -1.0);
                    else if (gl_VertexID == 2) pos = vec2(-1.0, 1.0);
                    else pos = vec2(1.0, 1.0);
                    vec2 uv = (pos + vec2(1.0)) * 0.5;
                    vTexCoord = vec2(uv.x * uSize.x, (1.0 - uv.y) * uSize.y);
                    gl_Position = vec4(pos, 0.0, 1.0);
                }
                """);
        int fs = compileShader(GL_FRAGMENT_SHADER, """
                #version 150 core
                uniform sampler2DRect uBridge;
                in vec2 vTexCoord;
                out vec4 fragColor;
                void main() {
                    vec4 c = texture(uBridge, vTexCoord);
                    if (c.a <= 0.001) discard;
                    fragColor = c;
                }
                """);
        if (vs == 0 || fs == 0) {
            if (vs != 0) glDeleteShader(vs);
            if (fs != 0) glDeleteShader(fs);
            return false;
        }

        int program = glCreateProgram();
        glAttachShader(program, vs);
        glAttachShader(program, fs);
        glLinkProgram(program);
        glDeleteShader(vs);
        glDeleteShader(fs);
        if (glGetProgrami(program, GL_LINK_STATUS) != GL_TRUE) {
            Logger.error("IOSurfaceBridgeCompositor: composite program link failed: "
                    + glGetProgramInfoLog(program));
            glDeleteProgram(program);
            return false;
        }
        compositeProgram = program;
        uniformBridge = glGetUniformLocation(program, "uBridge");
        uniformSize = glGetUniformLocation(program, "uSize");
        return true;
    }

    private static int compileShader(int type, String source) {
        int shader = glCreateShader(type);
        glShaderSource(shader, source);
        glCompileShader(shader);
        if (glGetShaderi(shader, GL_COMPILE_STATUS) != GL_TRUE) {
            Logger.error("IOSurfaceBridgeCompositor: composite shader compile failed: "
                    + glGetShaderInfoLog(shader));
            glDeleteShader(shader);
            return 0;
        }
        return shader;
    }
}
