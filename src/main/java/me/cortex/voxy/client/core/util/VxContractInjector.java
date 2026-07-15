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
    private static int uColour, uDepthTex, uInjectGamma, uInjectExposure, uInjectSqrt, uShadowMask;
    private static int uProjInv, uLightVec, uMaskAuto, uMaskScale, uDepthIsWindow;
    private static int uProj, uLongShadows, uShadowDim, uShadowSteps;
    private static int uSeafloorDimLoc, uTransDepthTexLoc, uSeafloorAttenLoc, uSeafloorFloorLoc, uUpViewLoc, uSeafloorDebugLoc, uSeafloorMaxDistLoc;
    private static int uAbyssLoc, uAbyssColorLoc, uSeaOffsetLoc;
    private static int colorFbo;
    private static int[] attachedTargets = new int[0];
    private static boolean warnedFailure;
    private static boolean disabled;

    private static final float INJECT_GAMMA = parseEnvF("VOXY_IRIS_INJECT_GAMMA", 2.2f);
    private static final float INJECT_EXPOSURE = parseEnvF("VOXY_IRIS_INJECT_EXPOSURE", 1.0f);
    private static final int INJECT_SQRT = "0".equals(System.getenv("VOXY_IRIS_INJECT_SQRT")) ? 0 : 1;
    private static final boolean COMPILE_PROBE = "1".equals(System.getenv("VOXY_VX_RESOLVE_COMPILE_TEST"));
    private static boolean compileProbeRan;

    /** 2026-07-03 gray-veil fix: the shadowMask seed written into the pack's
     *  colortex6.r. The pack's own voxy_opaque computes
     *  shadow.r * mix(NoL,1,ss) * (1-emission) * lightmap.y^2 * shadowFade —
     *  usually well below 1 and ZERO on sun-averted faces. Seeding a constant
     *  1.0 made BSL deferred1's GetLODShadows apply its maximum multiplicative
     *  gray (shadowCol = ambientCol/lightCol) to every LOD pixel whose 16-step
     *  sun-ward screen march stayed in bounds: a flat veil covering the screen
     *  side OPPOSITE the sun, vanishing under spyglass zoom (vxProj scales the
     *  step so every ray exits the screen immediately) and when facing away.
     *  Default 0 makes GetLODShadows a provable no-op for injected LODs —
     *  correct, since the injected colour (MC lightmap x face shade) contains
     *  no lightCol-scaled NoL term for the pack to remove.
     *  VOXY_VX_SHADOW_MASK=1 restores the old behaviour exactly for A/B. */
    private static final float VX_SHADOW_MASK = parseEnvF("VOXY_VX_SHADOW_MASK", 0.0f);

    /** 2026-07-03 round 5: per-pixel shadowMask seed (user: "las sombras no se
     *  cargan al 100% en todos los lods"). The constant-0 veil fix above made
     *  GetLODShadows a no-op, which removed ALL directional shadows from LOD
     *  terrain — the pack's real shadowmap only covers shadowDistance, so
     *  GetLODShadows IS BSL's intended LOD shadow mechanism and it needs a
     *  shaped per-pixel mask (the pack's own voxy_opaque writes
     *  shadow.r * mix(NoL,1,ss) * (1-emission) * lightmap.y^2 * shadowFade).
     *  AUTO mode reconstructs the dominant NoL term in the injector: view-space
     *  position from vxProjInv-equivalent (the viewport projection inverse) x
     *  the decoded bridge depth, face normal from dFdx/dFdy, dotted with
     *  Iris's view-space shadow-light vector. shadow.r/lightmap.y^2/shadowFade
     *  approximate to 1 at LOD range. Sun-averted faces get mask~0 (no veil
     *  revival: the veil needed mask=1 on EVERY pixel).
     *  VOXY_VX_SHADOW_MASK_AUTO=0 reverts to the constant seed (default 0 =
     *  shadows off); an explicit VOXY_VX_SHADOW_MASK=<v> also disables auto;
     *  VOXY_VX_SHADOW_MASK_SCALE (default 1.0) tames march blotchiness. */
    private static final boolean VX_SHADOW_MASK_AUTO =
            System.getenv("VOXY_VX_SHADOW_MASK") == null
                    && !"0".equals(System.getenv("VOXY_VX_SHADOW_MASK_AUTO"));
    private static final float VX_SHADOW_MASK_SCALE = parseEnvF("VOXY_VX_SHADOW_MASK_SCALE", 1.0f);

    /** 2026-07-04 (issue 3, "mejorar las sombras mas"): terrain-scale cast
     *  shadows. The colortex6 seed above can NEVER produce them — BSL's
     *  GetLODShadows uses the seed only as the darkening DEPTH where its own
     *  16-step view-space march (reach ~0.25..64 view blocks, sized for
     *  DH-near ranges) finds an occluder; at 1000+ block LOD distances those
     *  steps are sub-pixel, so unoccluded pixels return 1.0 and mountains
     *  never shadow valleys. Fix: march the LOD depth bridge (already bound
     *  as uDepthTex) toward the sun HERE and darken the injected colour
     *  directly. The march starts at >=64 view blocks — exactly where BSL's
     *  contact march ends — so the two never double-darken; the seed is also
     *  scaled by (1-occlusion) so BSL's march can't re-darken inside ours.
     *  Requires auto-mask mode (needs uProjInv/uLightVec).
     *  VOXY_VX_LOD_LONG_SHADOWS=0 kills; VOXY_VX_LOD_SHADOW_DIM=<f> sets the
     *  max darkening factor; VOXY_VX_LOD_SHADOW_STEPS=<n> the tap count. */
    private static final boolean VX_LOD_LONG_SHADOWS =
            !"0".equals(System.getenv("VOXY_VX_LOD_LONG_SHADOWS"));
    private static final float VX_LOD_SHADOW_DIM = parseEnvF("VOXY_VX_LOD_SHADOW_DIM", 0.55f);
    private static final int VX_LOD_SHADOW_STEPS =
            Math.max(4, Math.min(24, (int) parseEnvF("VOXY_VX_LOD_SHADOW_STEPS", 12f)));

    /** 2026-07-09 long-shadow march V2 ("far-LOD shadows wrong", confirmed
     *  visible WITHOUT the spyglass). The v1 geometry above had four verified
     *  failure modes: (1) t0 = max(64, 0.03*dist) skipped the t ~= 115..400
     *  near-ray window where the real ridge occluders live across the 4k-15k
     *  band; (2) the tMax > 1.5*t0 gate cut ALL shadows past ~22.7k
     *  (0.045*dist outgrows the 1024 tMax cap) — the outer ~29% of a 32k
     *  world had strictly none; (3) the fixed zDelta accept window (0.5..2.0
     *  rising edge) turned the hundreds of blocks of view depth a single far
     *  grazing texel spans into per-pixel occlusion noise; (4) the 24-bit
     *  bridge depth quantum (metal_depth_export.frag RGB pack) linearizes to
     *  ~ D^2 * 2^-24 / nearPlane view blocks and crosses the fixed 0.5-block
     *  acne bias around ~16k. V2: constant t0=64 (never lower — BSL's contact
     *  march covers ~0.25..54 blocks and the no-double-darken invariant above
     *  depends on starting past it), D^2-scaled precision bias, fwidth-based
     *  grazing tolerance capped at 4x the base rising-edge span, and a
     *  distance fade (default 6000..10000) as the PRIMARY far containment.
     *  Beyond the fade the un-shadowed state IS the BSL-matched baseline (the
     *  pack applies no shadow term to LOD pixels there either) so the
     *  transition is invisible — this trades "wrong/noisy far shadows" for
     *  "clean shadows to ~6-10k, smoothly none beyond" until a sun-space LOD
     *  shadow map lands. VOXY_VX_LOD_SHADOW_V2=0 re-emits the exact v1 math
     *  for A/B; VOXY_VX_LOD_SHADOW_FADE="start,end" moves the fade (a value
     *  of 0 disables it); VOXY_VX_LOD_SHADOW_BIAS_K tunes the precision-bias
     *  safety factor (baked into the shader at build). */
    private static final boolean VX_LOD_SHADOW_V2 =
            !"0".equals(System.getenv("VOXY_VX_LOD_SHADOW_V2"));
    private static final float VX_LOD_SHADOW_BIAS_K = parseEnvF("VOXY_VX_LOD_SHADOW_BIAS_K", 2.0f);
    /** {start, end} in view blocks, or null when the fade is disabled. */
    private static final float[] VX_LOD_SHADOW_FADE = parseShadowFade();

    private static float[] parseShadowFade() {
        float start = 6000f, end = 10000f;
        String v = System.getenv("VOXY_VX_LOD_SHADOW_FADE");
        if (v != null && !v.isBlank()) {
            try {
                String[] parts = v.trim().split(",");
                if (parts.length == 1) {
                    if (Float.parseFloat(parts[0].trim()) == 0f) return null; // fade off
                } else if (parts.length >= 2) {
                    float s = Float.parseFloat(parts[0].trim());
                    float e = Float.parseFloat(parts[1].trim());
                    if (e <= 0f) return null; // "x,0" also reads as off
                    if (e > s && s >= 0f) {
                        start = s;
                        end = e;
                    }
                }
            } catch (NumberFormatException ignored) {
                // malformed -> keep defaults, never break the inject
            }
        }
        return new float[]{start, end};
    }

    /** Cleared if the Iris celestial API throws — degrades to constant mode. */
    private static boolean shadowMaskAutoOk = true;
    private static boolean warnedShadowAuto;

    /** Phase D spike (issue #11): run BSL's voxy_translucent over the LOD water
     *  instead of the flat passthrough. A/B kill switch; default OFF. */
    private static final boolean TRANS_RESOLVE = "1".equals(System.getenv("VOXY_VX_TRANS_RESOLVE"));

    /** Seafloor water-column dim (VOXY_VX_SEAFLOOR_DIM=0 reverts). The mip
     *  rep-voxel carries NEAR-SURFACE sky light down to submerged floor quads
     *  (Mipper's max(opacity<<4|corner) tiebreak favours the topmost corner of
     *  uniform water columns, and floor faces read the light of the water cell
     *  above them), so LOD seafloors inject as bright as beach sand and bleed
     *  pale through BSL's constant-0.70-alpha fallback water. Real BSL floors
     *  (WATER_FOG=0 in the user profile) are dark purely via MC lightmap
     *  attenuation (~1 sky level per water block) — reconstruct the water
     *  column per pixel from the trans/opaque depth pair and apply the
     *  equivalent transmission, converging LOD water to real water term for
     *  term. ATTEN is the per-water-block linear transmission (0.88 ~= one
     *  daytime-lightmap sky level per block), FLOOR the ambient minimum so
     *  deep floors never go fully black. */
    private static final boolean VX_SEAFLOOR_DIM = !"0".equals(System.getenv("VOXY_VX_SEAFLOOR_DIM"));
    private static final float VX_SEAFLOOR_DIM_ATTEN = parseEnvF("VOXY_VX_SEAFLOOR_DIM_ATTEN", 0.88f);
    private static final float VX_SEAFLOOR_DIM_FLOOR = parseEnvF("VOXY_VX_SEAFLOOR_DIM_FLOOR", 0.10f);
    /** 2026-07-14 regression fix: the dim's premise (converge the LOD floor to
     *  the dark real-BSL seafloor) only holds INSIDE the trans near-cull ring,
     *  where REAL MC/BSL water overlays the injected floor — the pale-patch
     *  zone. Beyond the ring the LOD water surface + analytic mirror own the
     *  look, and the unbounded dim crushed far kelp/seafloor texture to the
     *  0.10 floor ("las algas no tienen texturas"). Gate by horizontal camera
     *  distance, mirroring MDICSectionRenderer's cull radius (rdBlocks - 16).
     *  VOXY_VX_SEAFLOOR_MAX_DIST: unset/-1 = auto (the ring), 0 = unlimited
     *  (the old behaviour), >0 = explicit blocks. */
    private static final float VX_SEAFLOOR_MAX_DIST = parseEnvF("VOXY_VX_SEAFLOOR_MAX_DIST", -1f);
    /** Diagnostic (VOXY_BOUND_DEBUG house style): paint every pixel the
     *  seafloor dim actually touches red, so "dim engaged but insufficient"
     *  and "dim never engages" are distinguishable on a screenshot. */
    private static final boolean VX_SEAFLOOR_DEBUG = "1".equals(System.getenv("VOXY_VX_SEAFLOOR_DEBUG"));
    /** Round-9: align the seafloor dim's interior fade with the water ring
     *  parity ramp (0.95*maxDist..maxDist) instead of the legacy 96-block band
     *  (maxDist-96..maxDist). The legacy fade was ~90% released by 480 blocks —
     *  exactly the 471..496 outer band where kept-fallback water concentrates —
     *  so the bright Mipper floor bled nearly undimmed through the pale quads.
     *  Zero change beyond maxDist either way (fade is 0 there in both shapes).
     *  VOXY_VX_SEAFLOOR_FADE_V2=0 reverts to the legacy fade. */
    private static final boolean VX_SEAFLOOR_FADE_V2 =
            !"0".equals(System.getenv("VOXY_VX_SEAFLOOR_FADE_V2"));
    private static boolean sfLoggedFirst, sfLoggedEngaged;

    /** Abyss fill (VOXY_VX_ABYSS_FILL=0 reverts): during the world-join churn
     *  window, near-ocean FLOOR sections can lag Voxy's bake/upload pipeline
     *  by minutes while Sodium already renders the real water above them —
     *  the water then shades against nothing and composites as the flat pale
     *  panes (pane RGB tracked BSL's raw sky; the tint probe confirmed NO LOD
     *  fragment of any kind existed at those pixels). Where the trans bridge
     *  carries an LOD water-surface depth (the ghost cull preserves it at
     *  zero alpha) but the opaque bridge has no LOD, present a synthetic dark
     *  seabed AS a vx LOD pixel: this colour branch writes the fill into the
     *  pack's opaque vx targets while VxIrisSideChannel.abyssDepth writes the
     *  matching synthetic depth into vxDepthTexOpaque (LOD depth never
     *  touches depthtex0, so without the side channel the pack's sky
     *  composite would stomp the colour — the colour branch is gated on the
     *  depth pass succeeding). Self-healing: once real LOD data arrives the
     *  LOD-absent gate fails and the real floor injects; real geometry drawn
     *  later always wins the depth test against the pushed-back fill.
     *  PUSH = blocks the fill sits behind the water surface along the view
     *  ray; RGB = linear fill colour (default deep-ocean dark). */
    private static final boolean VX_ABYSS_FILL = !"0".equals(System.getenv("VOXY_VX_ABYSS_FILL"));
    private static final float VX_ABYSS_PUSH = parseEnvF("VOXY_VX_ABYSS_PUSH", 24f);
    /** Diagnostic (house style): paint the fill bright green so "fill covers
     *  the panes" vs "fill never engages" is unmistakable on a screenshot. */
    private static final boolean VX_ABYSS_DEBUG = "1".equals(System.getenv("VOXY_VX_ABYSS_DEBUG"));
    private static final float[] VX_ABYSS_RGB = parseEnvRgb("VOXY_VX_ABYSS_RGB", 0.010f, 0.024f, 0.032f);
    private static boolean abyssLoggedFirst;

    private static float[] parseEnvRgb(String name, float r, float g, float b) {
        String v = System.getenv(name);
        if (v != null && !v.isBlank()) {
            try {
                String[] parts = v.trim().split(",");
                if (parts.length == 3) {
                    return new float[]{Float.parseFloat(parts[0].trim()),
                            Float.parseFloat(parts[1].trim()),
                            Float.parseFloat(parts[2].trim())};
                }
            } catch (NumberFormatException ignored) {
            }
        }
        return new float[]{r, g, b};
    }

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
            return inject0(viewport, colorBridge, depthBridge, transBridge, transDepthBridge);
        } catch (Throwable t) {
            if (!warnedFailure) {
                warnedFailure = true;
                Logger.warn("VxContractInjector: inject failed — falling back to hidden LODs under pack", t);
            }
            return false;
        }
    }

    private static boolean inject0(Viewport<?> viewport,
                                   IOSurfaceBridge colorBridge, IOSurfaceBridge depthBridge,
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
        // Unit 2 carries the trans-depth bridge for the seafloor dim.
        glActiveTexture(GL_TEXTURE0 + 2);
        int prevTexRect2 = glGetInteger(GL_TEXTURE_BINDING_RECTANGLE);
        int prevSampler2 = glGetInteger(org.lwjgl.opengl.GL33C.GL_SAMPLER_BINDING);
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
            org.lwjgl.opengl.GL33C.glBindSampler(2, 0);
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
            glUniform1f(uShadowMask, VX_SHADOW_MASK);
            boolean maskAuto = VX_SHADOW_MASK_AUTO && shadowMaskAutoOk;
            if (maskAuto) {
                try {
                    // Same projection the pack receives as vxProj (VoxyUniforms),
                    // so the reconstruction matches deferred1's vxProjInv math.
                    org.joml.Matrix4f projInv = new org.joml.Matrix4f(viewport.projection).invert();
                    glUniformMatrix4fv(uProjInv, false, projInv.get(new float[16]));
                    // Forward projection for the long-shadow march (view-space
                    // sun-ray sample points reprojected back to bridge texels).
                    glUniformMatrix4fv(uProj, false,
                            new org.joml.Matrix4f(viewport.projection).get(new float[16]));
                    // Iris's view-space shadow-light vector — the source of BSL's
                    // lightVec. Public API on 1.10.7; any drift degrades to the
                    // constant seed instead of crashing the inject.
                    float spr = ((net.irisshaders.iris.pipeline.IrisRenderingPipeline) pipeline)
                            .getSunPathRotation();
                    var slp = new net.irisshaders.iris.uniforms.CelestialUniforms(spr)
                            .getShadowLightPosition();
                    org.joml.Vector3f lv = new org.joml.Vector3f(slp.x(), slp.y(), slp.z());
                    if (lv.lengthSquared() > 1e-6f) lv.normalize(); else maskAuto = false;
                    glUniform3f(uLightVec, lv.x, lv.y, lv.z);
                } catch (Throwable t) {
                    shadowMaskAutoOk = false;
                    maskAuto = false;
                    if (!warnedShadowAuto) {
                        warnedShadowAuto = true;
                        Logger.warn("[Metal-LODTEST] vx shadowMask auto DEGRADED to constant "
                                + VX_SHADOW_MASK + " (Iris celestial API failed)", t);
                    }
                }
            }
            glUniform1i(uMaskAuto, maskAuto ? 1 : 0);
            glUniform1f(uMaskScale, VX_SHADOW_MASK_SCALE);
            glUniform1i(uLongShadows, (maskAuto && VX_LOD_LONG_SHADOWS) ? 1 : 0);
            glUniform1f(uShadowDim, VX_LOD_SHADOW_DIM);
            glUniform1i(uShadowSteps, VX_LOD_SHADOW_STEPS);
            // Seafloor water-column dim inputs: the trans-depth bridge on
            // unit 2 plus the view-space up vector (the column metric is the
            // VERTICAL water thickness, not the slanted view-ray length).
            // uProjInv is normally uploaded by the shadow-auto block above;
            // when that path is off/degraded, upload it here — the dim's
            // reconstruction needs it regardless.
            boolean seafloorDim = false;
            boolean abyss = false;
            int sfRect = 0;
            org.joml.Vector3f up = null;
            if ((VX_SEAFLOOR_DIM || VX_ABYSS_FILL) && transDepthBridge != null) {
                try {
                    sfRect = me.cortex.voxy.client.core.interop.IOSurfaceBridgeCompositor
                            .acquireAuxRectTex(transDepthBridge);
                    if (sfRect != 0) {
                        up = new org.joml.Matrix4f(viewport.modelView)
                                .transformDirection(new org.joml.Vector3f(0, 1, 0));
                        if (up.lengthSquared() > 1e-6f) {
                            up.normalize();
                            if (!maskAuto) {
                                org.joml.Matrix4f projInv =
                                        new org.joml.Matrix4f(viewport.projection).invert();
                                glUniformMatrix4fv(uProjInv, false, projInv.get(new float[16]));
                            }
                            glUniform3f(uUpViewLoc, up.x, up.y, up.z);
                            glActiveTexture(GL_TEXTURE0 + 2);
                            glBindTexture(GL_TEXTURE_RECTANGLE, sfRect);
                            glActiveTexture(GL_TEXTURE0);
                            seafloorDim = VX_SEAFLOOR_DIM;
                            abyss = VX_ABYSS_FILL;
                        } else {
                            up = null;
                        }
                    }
                } catch (Throwable t) {
                    // fail-open to the undimmed/unfilled (current) behaviour
                    seafloorDim = false;
                    abyss = false;
                    up = null;
                }
            }
            if (!sfLoggedFirst) {
                sfLoggedFirst = true;
                Logger.info("[Metal-LODTEST] seafloor dim first inject frame: engaged=" + seafloorDim
                        + " bridge=" + (transDepthBridge != null)
                        + " maskAuto=" + maskAuto
                        + (VX_SEAFLOOR_DEBUG ? " DEBUG-TINT ON (dimmed pixels red)" : ""));
            }
            // Auto radius mirrors MDICSectionRenderer's near-cull ring
            // (max(rdBlocks,32)-16, floor 64): the only zone where real
            // MC/BSL water overlays the injected LOD floor. Recomputed per
            // frame — render distance is live-editable in video settings.
            float sfMaxDist = VX_SEAFLOOR_MAX_DIST;
            if (sfMaxDist < 0f) {
                float rdBlocks = Math.max(net.minecraft.client.Minecraft.getInstance()
                        .gameRenderer.getRenderDistance(), 32f);
                sfMaxDist = Math.max(rdBlocks - 16f, 64f);
            }
            if (seafloorDim && !sfLoggedEngaged) {
                sfLoggedEngaged = true;
                Logger.info("[Metal-LODTEST] seafloor dim ENGAGED (trans-depth RECT bound on unit 2; "
                        + "red tint " + (VX_SEAFLOOR_DEBUG ? "ON" : "off")
                        + "; maxDist=" + (sfMaxDist > 0f ? sfMaxDist + " blocks" : "unlimited") + ")");
            }
            glUniform1i(uSeafloorDimLoc, seafloorDim ? 1 : 0);
            glUniform1i(uTransDepthTexLoc, 2);
            glUniform1f(uSeafloorAttenLoc, VX_SEAFLOOR_DIM_ATTEN);
            glUniform1f(uSeafloorFloorLoc, VX_SEAFLOOR_DIM_FLOOR);
            glUniform1i(uSeafloorDebugLoc, VX_SEAFLOOR_DEBUG ? 1 : 0);
            glUniform1f(uSeafloorMaxDistLoc, sfMaxDist);
            glUniform1i(uDepthIsWindow,
                    me.cortex.voxy.client.core.rendering.util.MetalMvpUtil.METAL_NDC_REMAP ? 1 : 0);
            // Abyss fill: run the synthetic-depth half FIRST and gate the
            // colour branch on its success — fill colour whose depth never
            // reached vxDepthTexOpaque would be stomped by the pack's sky
            // composite (vxZ == 1.0), i.e. the pale panes unchanged.
            // seaOffset = seaLevel - cameraY (view-space up units): the
            // analytic sea-plane fallback for pixels where even the LOD
            // water sections haven't uploaded yet; >-0.5 (camera at/under
            // sea level) disables that branch in both shaders.
            float seaOffset = 1.0f;
            if (abyss && up != null) {
                boolean abyssDepthOk;
                try {
                    var mc = net.minecraft.client.Minecraft.getInstance();
                    if (mc.level != null && mc.gameRenderer.getMainCamera() != null) {
                        seaOffset = (float) (mc.level.getSeaLevel()
                                - mc.gameRenderer.getMainCamera().position().y);
                    }
                    abyssDepthOk = VxIrisSideChannel.getOrCreate().abyssDepth(
                            colourRect, depthRect, sfRect, fbw, fbh,
                            me.cortex.voxy.client.core.rendering.util.MetalMvpUtil.METAL_NDC_REMAP,
                            new org.joml.Matrix4f(viewport.projection),
                            new org.joml.Matrix4f(viewport.projection).invert(),
                            up, sfMaxDist, VX_ABYSS_PUSH, seaOffset);
                } catch (Throwable t) {
                    abyssDepthOk = false;
                }
                abyss = abyssDepthOk;
            } else {
                abyss = false;
            }
            glUniform1f(uSeaOffsetLoc, seaOffset);
            glUniform1i(uAbyssLoc, abyss ? 1 : 0);
            if (VX_ABYSS_DEBUG) {
                glUniform3f(uAbyssColorLoc, 0.0f, 1.0f, 0.0f);
            } else {
                glUniform3f(uAbyssColorLoc, VX_ABYSS_RGB[0], VX_ABYSS_RGB[1], VX_ABYSS_RGB[2]);
            }
            // One-shot: fires the first frame the fill actually ENGAGES (the
            // trans bridge appears a few frames after the first inject), or
            // immediately when the kill switch has it off.
            if (!abyssLoggedFirst && (abyss || !VX_ABYSS_FILL)) {
                abyssLoggedFirst = true;
                Logger.info("[Metal-LODTEST] vx abyss fill " + (abyss
                        ? "ENGAGED (synthetic dark seabed where real water has no LOD floor yet;"
                        + " push=" + VX_ABYSS_PUSH + " blocks, rgb=" + VX_ABYSS_RGB[0] + ","
                        + VX_ABYSS_RGB[1] + "," + VX_ABYSS_RGB[2]
                        + (VX_ABYSS_DEBUG ? ", DEBUG-TINT GREEN" : "")
                        + "; VOXY_VX_ABYSS_FILL=0 reverts, _PUSH/_RGB tune)"
                        : "OFF (VOXY_VX_ABYSS_FILL=0)"));
            }
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
            glActiveTexture(GL_TEXTURE0 + 2);
            glBindTexture(GL_TEXTURE_RECTANGLE, prevTexRect2);
            org.lwjgl.opengl.GL33C.glBindSampler(2, prevSampler2);
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
        String fsHead = """
                #version 150 core
                uniform sampler2DRect uColour;
                uniform sampler2DRect uDepthTex;
                uniform float uInjectGamma;
                uniform float uInjectExposure;
                uniform int uInjectSqrt;
                uniform float uShadowMask;
                uniform mat4 uProjInv;
                uniform mat4 uProj;
                uniform vec3 uLightVec;
                uniform float uMaskScale;
                uniform int uMaskAuto;
                uniform int uDepthIsWindow;
                uniform int uLongShadows;
                uniform float uShadowDim;
                uniform int uShadowSteps;
                uniform sampler2DRect uTransDepthTex;
                uniform int uSeafloorDim;
                uniform float uSeafloorAtten;
                uniform float uSeafloorFloor;
                uniform vec3 uUpView;
                uniform int uSeafloorDebug;
                uniform float uSeafloorMaxDist;
                uniform int uAbyss;
                uniform vec3 uAbyssColor;
                uniform float uSeaOffset;
                in vec2 vUV;
                out vec4 outColor0;
                out vec4 outColor1;
                void main() {
                    ivec2 sz = textureSize(uColour);
                    vec2 texel = vec2(vUV.x * float(sz.x), (1.0 - vUV.y) * float(sz.y));
                    vec4 c = texture(uColour, texel);
                    vec3 dEnc = texture(uDepthTex, texel).rgb;
                    float d = dot(dEnc, vec3(1.0, 1.0 / 255.0, 1.0 / 65025.0));
                    if (c.a <= 0.001 || d <= 0.0 || d >= 0.9999999) {
                        // ABYSS FILL colour branch (VOXY_VX_ABYSS_FILL=0
                        // reverts) — conditions MUST stay bit-identical with
                        // VxIrisSideChannel's abyssDepth pass: colour without
                        // depth gets stomped by the pack's sky composite,
                        // depth without colour composites stale colortex0.
                        // The trans bridge carries the LOD water-surface
                        // depth here (the ghost cull keeps depth at zero
                        // alpha exactly so reconstruction survives the masked
                        // cull); a valid dT with NO LOD behind it is the
                        // pale-pane case — real water over a floor Voxy
                        // hasn't baked/uploaded yet.
                        if (uAbyss == 1) {
                            vec3 abP = vec3(0.0);
                            bool abHave = false;
                            vec3 abEnc = texture(uTransDepthTex, texel).rgb;
                            float abDT = dot(abEnc, vec3(1.0, 1.0 / 255.0, 1.0 / 65025.0));
                            if (abDT > 0.0 && abDT < 0.9999995) {
                                float abZ = (uDepthIsWindow == 1) ? abDT * 2.0 - 1.0 : abDT;
                                vec4 abP4 = uProjInv * vec4(vUV * 2.0 - 1.0, abZ, 1.0);
                                abP = abP4.xyz / abP4.w;
                                abHave = true;
                            } else if (uSeaOffset < -0.5) {
                                // Analytic sea-plane fallback — see the
                                // abyssDepth shader; during early join churn
                                // even the LOD water sections are missing, so
                                // the trans bridge can't locate the surface.
                                vec4 abR4 = uProjInv * vec4(vUV * 2.0 - 1.0, 0.0, 1.0);
                                vec3 abR = normalize(abR4.xyz / abR4.w);
                                float abUr = dot(abR, uUpView);
                                if (abUr < -0.05) {
                                    abP = abR * (uSeaOffset / abUr);
                                    abHave = true;
                                }
                            }
                            if (abHave) {
                                float abLen = length(abP);
                                if (abLen > 1e-3 && dot(abP / abLen, uUpView) < -0.05) {
                                    float abHoriz = length(abP - uUpView * dot(abP, uUpView));
                                    if (uSeafloorMaxDist <= 0.0 || abHoriz < uSeafloorMaxDist) {
                                        // colortex6 seed: r=0 (a deep seabed
                                        // receives no direct sun), b=1 "LOD
                                        // wrote here" — same convention as
                                        // the real branch below.
                                        vec3 abLin = max(uAbyssColor, vec3(0.0));
                                        outColor0 = vec4((uInjectSqrt == 1) ? sqrt(abLin) : abLin, 1.0);
                                        outColor1 = vec4(0.0, 0.0, 1.0, 1.0);
                                        return;
                                    }
                                }
                            }
                        }
                        discard;
                    }
                    // Pack colour convention (BSL ALPHA_BLEND 0): colortex0
                    // carries sqrt-encoded scene-linear radiance. NATIVE
                    // PARITY: BSL's own voxy_opaque stores
                    // sqrt(GetLighting(pow(albedo, 2.2))) with NO exposure
                    // pre-division — the tonemap's x4 is part of its normal
                    // chain for all content. (The legacy injector's 0.25
                    // factor here made LODs render at half the brightness of
                    // the pack's own LOD output — the "washed grey".)
                    vec3 lin = pow(c.rgb, vec3(uInjectGamma)) * uInjectExposure;
                    // Seafloor water-column dim (VOXY_VX_SEAFLOOR_DIM=0
                    // reverts): the mip rep-voxel carries near-surface sky
                    // light down to submerged floor quads (Mipper top-corner
                    // tiebreak), so LOD seafloors inject bright and bleed pale
                    // through BSL's 0.70-alpha fallback water. Real BSL floors
                    // (WATER_FOG=0) are dark purely via lightmap attenuation
                    // (~1 sky level per water block). Reconstruct the vertical
                    // column from the trans/opaque depth pair and apply the
                    // equivalent dim. Where no LOD water drew, the trans
                    // bridge holds the RESTORED opaque depth, so dT == d
                    // bit-identically and the eps guard no-ops: land and
                    // floors behind built MC sections are untouched by
                    // construction.
                    if (uSeafloorDim == 1) {
                        vec3 tEnc = texture(uTransDepthTex, texel).rgb;
                        float dT = dot(tEnc, vec3(1.0, 1.0 / 255.0, 1.0 / 65025.0));
                        if (dT > 0.0 && dT < 0.9999999 && dT < d - 0.0000002) {
                            float zO = (uDepthIsWindow == 1) ? d  * 2.0 - 1.0 : d;
                            float zT = (uDepthIsWindow == 1) ? dT * 2.0 - 1.0 : dT;
                            vec2 sfNdc = vUV * 2.0 - 1.0;
                            vec4 pO = uProjInv * vec4(sfNdc, zO, 1.0);
                            vec4 pT = uProjInv * vec4(sfNdc, zT, 1.0);
                            vec3 pTv = pT.xyz / pT.w;
                            float column = abs(dot(pO.xyz / pO.w - pTv, uUpView));
                            // 2026-07-14 gate: dim only inside the trans
                            // near-cull ring (where real MC/BSL water covers
                            // the injected floor — the pale-patch zone). The
                            // unbounded dim crushed FAR kelp/seafloor texture
                            // to the 0.10 floor. Horizontal camera distance =
                            // view-space position minus its vertical part
                            // (camera at origin), matching the RADIAL cull
                            // metric; interior fade (__SF_FADE_START__, resolved
                            // at build: V2 = 0.95*maxDist, legacy = maxDist-96)
                            // so the ring edge has no seam. uSeafloorMaxDist
                            // <= 0 = unlimited.
                            float sfFade = 1.0;
                            if (uSeafloorMaxDist > 0.0) {
                                float sfDist = length(pTv - uUpView * dot(pTv, uUpView));
                                sfFade = 1.0 - smoothstep(__SF_FADE_START__,
                                                          uSeafloorMaxDist, sfDist);
                            }
                            if (sfFade > 0.0) {
                                float sfDim = max(pow(uSeafloorAtten, column), uSeafloorFloor);
                                lin *= mix(1.0, sfDim, sfFade);
                                if (uSeafloorDebug == 1) lin = mix(lin, vec3(1.0, 0.0, 0.0), 0.6 * sfFade);
                            }
                        } else if (uSeafloorDebug == 1 && uSeafloorMaxDist > 0.0) {
                            // Classifier (debug-only): an injected LOD pixel INSIDE
                            // the ring whose trans depth equals its own depth — no
                            // LOD water wrote above it. The near pale panes showed
                            // no tint from any water-path arm; if they turn VIOLET
                            // here, the trans pass wrote no depth there at all
                            // (water geometry missing / never rasterized), ruling
                            // out ghost-depth and pointing at meshing/coverage.
                            float zO2 = (uDepthIsWindow == 1) ? d * 2.0 - 1.0 : d;
                            vec4 pO2 = uProjInv * vec4(vUV * 2.0 - 1.0, zO2, 1.0);
                            vec3 pOv = pO2.xyz / pO2.w;
                            float sfDist2 = length(pOv - uUpView * dot(pOv, uUpView));
                            if (sfDist2 < uSeafloorMaxDist) lin = mix(lin, vec3(0.5, 0.0, 1.0), 0.6);
                        }
                    }
                    // colortex6 seed: r = shadowMask (see VX_SHADOW_MASK note —
                    // constant 1.0 was the left-of-sun gray-veil bug), b = "LOD
                    // wrote here" mask the pack's own voxy_opaque writes as
                    // float(z < 1).
                    float vxMask = uShadowMask;
                    if (uMaskAuto == 1) {
                        // Per-pixel NoL: view-space position from the decoded
                        // bridge depth (mirror MetalVxResolvePass's vxWz
                        // convention, inverted to NDC), face normal from
                        // screen derivatives, dotted with Iris's view-space
                        // shadow-light vector. Silhouette guard: a large depth
                        // derivative means the 2x2 quad straddles a depth edge
                        // and the derived normal is garbage -> mask 0.
                        float zNdc = (uDepthIsWindow == 1) ? d * 2.0 - 1.0 : d;
                        vec4 vpH = uProjInv * vec4(vUV * 2.0 - 1.0, zNdc, 1.0);
                        vec3 vpos = vpH.xyz / vpH.w;
                        vec3 nrm = cross(dFdx(vpos), dFdy(vpos));
                        float n2 = dot(nrm, nrm);
                        float dEdge = abs(dFdx(d)) + abs(dFdy(d));
                """;
        // V2-only: the grazing-tolerance input must be computed HERE — next
        // to the existing dFdx/dFdy, still inside the dynamically-uniform
        // uMaskAuto branch — because derivatives are undefined inside the
        // non-uniform (noL > 0) branch the march itself lives in.
        String fsGrad = !VX_LOD_SHADOW_V2 ? "" : """
                        // V2 grazing tolerance: linear-depth footprint of this
                        // pixel (blocks of view depth per screen texel).
                        float linGrad = fwidth(vpos.z);
                """;
        String fsMid = """
                        float noL = 0.0;
                        if (n2 > 1e-12 && dEdge < 0.05) {
                            nrm *= inversesqrt(n2);
                            nrm *= -sign(dot(nrm, vpos));
                            noL = clamp(dot(nrm, uLightVec), 0.0, 1.0);
                            vxMask = noL * uMaskScale;
                        } else {
                            vxMask = 0.0;
                        }
                        // Long-shadow march (see VX_LOD_LONG_SHADOWS note):
                        // exponential taps along the sun ray through the LOD
                        // height field, starting at >=64 view blocks where
                        // BSL's own contact march ends. Sun-averted faces
                        // (noL 0) are already dark via vanilla face shade —
                        // skip them so nothing double-darkens.
                """;
        // The exact v1 march, emitted verbatim by VOXY_VX_LOD_SHADOW_V2=0
        // for A/B. See the VX_LOD_SHADOW_V2 note for its verified failure
        // geometry (no shadows past ~22.7k, skipped 4k-15k ridge occluders,
        // grazing noise, ~16k acne).
        String fsMarchV1 = """
                        if (uLongShadows == 1 && noL > 0.0) {
                            float dist = length(vpos);
                            float t0 = max(64.0, 0.03 * dist);
                            float tMax = min(1024.0, 0.6 * dist);
                            if (tMax > t0 * 1.5) {
                                float stepE = log2(tMax / t0) / float(uShadowSteps - 1);
                                float dither = fract(sin(dot(gl_FragCoord.xy,
                                        vec2(12.9898, 78.233))) * 43758.5453);
                                float occ = 0.0;
                                for (int k = 0; k < uShadowSteps; k++) {
                                    float t = t0 * exp2((float(k) + dither) * stepE);
                                    vec3 sp = vpos + uLightVec * t;
                                    vec4 clipP = uProj * vec4(sp, 1.0);
                                    if (clipP.w <= 0.0) break;
                                    vec2 ndcXY = clipP.xy / clipP.w;
                                    vec2 uv = ndcXY * 0.5 + 0.5;
                                    if (uv.x < 0.0 || uv.x > 1.0 || uv.y < 0.0 || uv.y > 1.0) break;
                                    vec2 st = vec2(uv.x * float(sz.x), (1.0 - uv.y) * float(sz.y));
                                    float dS = dot(texture(uDepthTex, st).rgb,
                                            vec3(1.0, 1.0 / 255.0, 1.0 / 65025.0));
                                    // Bridge clears to far: no LOD occluder at
                                    // this texel (near-field MC terrain is
                                    // BSL's own depthtex0 march's job).
                                    if (dS <= 0.0 || dS >= 0.9999999) continue;
                                    float zNdcS = (uDepthIsWindow == 1) ? dS * 2.0 - 1.0 : dS;
                                    vec4 svH = uProjInv * vec4(ndcXY, zNdcS, 1.0);
                                    vec3 sv = svH.xyz / svH.w;
                                    // Occluded when the height-field surface at
                                    // this texel is nearer than the ray point,
                                    // within a distance-scaled slab thickness
                                    // (height-field assumption); 0.5-block bias
                                    // against acne on the emitting surface.
                                    float zDelta = sv.z - sp.z;
                                    float thick = 0.25 * t + 4.0;
                                    float tapOcc = smoothstep(0.5, 2.0, zDelta)
                                            * (1.0 - smoothstep(thick * 0.75, thick, zDelta));
                                    // Screen-edge fade so rays leaving the
                                    // frame release smoothly instead of popping.
                                    vec2 ef2 = min(smoothstep(vec2(0.0), vec2(0.05), uv),
                                                   vec2(1.0) - smoothstep(vec2(0.95), vec2(1.0), uv));
                                    occ = max(occ, tapOcc * min(ef2.x, ef2.y));
                                    if (occ >= 0.99) break;
                                }
                                lin *= mix(1.0, uShadowDim, occ * noL);
                                // Keep BSL's contact march from re-darkening
                                // inside our shadow.
                                vxMask *= 1.0 - occ;
                            }
                        }
                """;
        // V2 precision constant: the bridge depth crosses as a 24-bit RGB
        // pack (metal_depth_export.frag EncodeFloatRGB, quantum 2^-24 in the
        // stored [0,1) value), so one quantum at pixel view distance D
        // linearizes to ~ D^2 * 2^-24 / NEARQ view blocks, where NEARQ is
        // the LOD projection near plane (VoxyRenderSystem.computeProjectionMat:
        // 8 below a 32-block render distance, else 16; 0.1 in the
        // disable-sodium debug mode). K covers the residual convention slack
        // (window-vs-NDC storage under VOXY_LOD_METAL_NDC differs by up to
        // 2x) — a too-large bias only lifts far shadow contact slightly, a
        // too-small one acnes.
        float nearQ = 16f;
        if (VX_LOD_SHADOW_V2) {
            try {
                nearQ = net.minecraft.client.Minecraft.getInstance().gameRenderer
                        .getRenderDistance() <= 32.0f ? 8f : 16f;
                if (me.cortex.voxy.client.VoxyClient.disableSodiumChunkRender()) nearQ = 0.1f;
            } catch (Throwable ignored) {
                // pre-world edge case: keep the common-case 16
            }
        }
        String kcStr = String.format(java.util.Locale.ROOT, "%.9e",
                VX_LOD_SHADOW_BIAS_K / (16777216.0 * nearQ));
        String fadeGlsl = "";
        if (VX_LOD_SHADOW_FADE != null) {
            fadeGlsl = String.format(java.util.Locale.ROOT,
                    "                // Distance fade — the PRIMARY far containment. Beyond\n"
                  + "                // it the un-shadowed state IS the BSL-matched baseline\n"
                  + "                // (the pack applies no shadow term to LOD pixels out\n"
                  + "                // here either), so the transition is invisible.\n"
                  + "                occ *= 1.0 - smoothstep(%.1f, %.1f, dist);\n",
                    VX_LOD_SHADOW_FADE[0], VX_LOD_SHADOW_FADE[1]);
        }
        String fsMarchV2 = String.format(java.util.Locale.ROOT, """
                        if (uLongShadows == 1 && noL > 0.0) {
                            float dist = length(vpos);
                            // V2 (VOXY_VX_LOD_SHADOW_V2=0 re-emits the v1 math):
                            // constant t0 — v1's max(64, 0.03*dist) floor skipped
                            // the t ~= 115..400 near-ray window where the real
                            // ridge occluders live across the 4k-15k band. NEVER
                            // below 64: BSL's contact march covers ~0.25..54 view
                            // blocks and the no-double-darken invariant above
                            // depends on our march starting past it.
                            float t0 = 64.0;
                            float tMax = min(1024.0, 0.6 * dist);
                            // Hard gate kept from v1. With constant t0 it only
                            // skips pixels nearer than ~160 view blocks (contact-
                            // march territory). v1's ~22.7k no-shadow cutoff came
                            // from its GROWING t0 crossing the tMax cap here;
                            // with t0 constant that cutoff is gone and the
                            // distance fade below is the SOLE far containment —
                            // pushing VOXY_VX_LOD_SHADOW_FADE past ~23k no longer
                            // hits a hard stop behind it.
                            if (tMax > t0 * 1.5) {
                                // Precision-aware acne bias (v1: fixed 0.5): one
                                // 24-bit bridge-depth quantum at view distance D
                                // is ~ D*D * 2^-24 / nearPlane view blocks; past
                                // ~8k the fixed edge sinks under it and every tap
                                // acnes. Constant baked Java-side: K * 2^-24 / NEARQ.
                                float bias = max(0.5, %s * dist * dist);
                                // Grazing tolerance: one far grazing texel spans
                                // hundreds of blocks of view depth — a fixed edge
                                // turns that footprint into per-pixel occlusion
                                // noise. Widen by the linear-depth footprint,
                                // CAPPED at 4x the base rising-edge span (3*bias):
                                // uncapped, every far tap occludes and the gray-
                                // veil failure class returns (see the colortex6
                                // note above).
                                float rise = bias + clamp(linGrad, 0.0, 12.0 * bias);
                                float stepE = log2(tMax / t0) / float(uShadowSteps - 1);
                                float dither = fract(sin(dot(gl_FragCoord.xy,
                                        vec2(12.9898, 78.233))) * 43758.5453);
                                float occ = 0.0;
                                for (int k = 0; k < uShadowSteps; k++) {
                                    float t = t0 * exp2((float(k) + dither) * stepE);
                                    vec3 sp = vpos + uLightVec * t;
                                    vec4 clipP = uProj * vec4(sp, 1.0);
                                    if (clipP.w <= 0.0) break;
                                    vec2 ndcXY = clipP.xy / clipP.w;
                                    vec2 uv = ndcXY * 0.5 + 0.5;
                                    if (uv.x < 0.0 || uv.x > 1.0 || uv.y < 0.0 || uv.y > 1.0) break;
                                    vec2 st = vec2(uv.x * float(sz.x), (1.0 - uv.y) * float(sz.y));
                                    float dS = dot(texture(uDepthTex, st).rgb,
                                            vec3(1.0, 1.0 / 255.0, 1.0 / 65025.0));
                                    // Bridge clears to far: no LOD occluder at
                                    // this texel (near-field MC terrain is
                                    // BSL's own depthtex0 march's job).
                                    if (dS <= 0.0 || dS >= 0.9999999) continue;
                                    float zNdcS = (uDepthIsWindow == 1) ? dS * 2.0 - 1.0 : dS;
                                    vec4 svH = uProjInv * vec4(ndcXY, zNdcS, 1.0);
                                    vec3 sv = svH.xyz / svH.w;
                                    // Occluded when the height-field surface at
                                    // this texel is nearer than the ray point,
                                    // within a distance-scaled slab thickness
                                    // (height-field assumption); rising edge =
                                    // precision bias + grazing widen (v1: 0.5..2.0,
                                    // which this reproduces at bias=0.5, widen=0).
                                    float zDelta = sv.z - sp.z;
                                    float thick = 0.25 * t + 4.0;
                                    float tapOcc = smoothstep(rise, rise * 4.0, zDelta)
                                            * (1.0 - smoothstep(thick * 0.75, thick, zDelta));
                                    // Screen-edge fade so rays leaving the
                                    // frame release smoothly instead of popping.
                                    vec2 ef2 = min(smoothstep(vec2(0.0), vec2(0.05), uv),
                                                   vec2(1.0) - smoothstep(vec2(0.95), vec2(1.0), uv));
                                    occ = max(occ, tapOcc * min(ef2.x, ef2.y));
                                    if (occ >= 0.99) break;
                                }
                """, kcStr)
                + fadeGlsl
                + """
                                lin *= mix(1.0, uShadowDim, occ * noL);
                                // Keep BSL's contact march from re-darkening
                                // inside our shadow.
                                vxMask *= 1.0 - occ;
                            }
                        }
                """;
        String fsTail = """
                    }
                    outColor0 = vec4((uInjectSqrt == 1) ? sqrt(max(lin, vec3(0.0))) : lin, 1.0);
                    outColor1 = vec4(vxMask, 0.0, 1.0, 1.0);
                }
                """;
        String fs = fsHead + fsGrad + fsMid
                + (VX_LOD_SHADOW_V2 ? fsMarchV2 : fsMarchV1) + fsTail;
        fs = fs.replace("__SF_FADE_START__",
                VX_SEAFLOOR_FADE_V2 ? "uSeafloorMaxDist * 0.95" : "uSeafloorMaxDist - 96.0");
        Logger.info("[Metal-LODTEST] vx seafloor fade " + (VX_SEAFLOOR_FADE_V2
                ? "V2 ON (interior fade 0.95*maxDist..maxDist, aligned with the water ring"
                  + " parity ramp so the floor stays dimmed through the 471..496 fallback band;"
                  + " the legacy 96-block fade was ~90% released there)"
                : "LEGACY (maxDist-96..maxDist band)")
                + "; VOXY_VX_SEAFLOOR_FADE_V2=0 reverts");
        program = VxIrisSideChannel.compile(vs, fs, "VxContractInjector");
        if (program == 0) return false;
        uColour = glGetUniformLocation(program, "uColour");
        uDepthTex = glGetUniformLocation(program, "uDepthTex");
        uInjectGamma = glGetUniformLocation(program, "uInjectGamma");
        uInjectExposure = glGetUniformLocation(program, "uInjectExposure");
        uInjectSqrt = glGetUniformLocation(program, "uInjectSqrt");
        uShadowMask = glGetUniformLocation(program, "uShadowMask");
        uProjInv = glGetUniformLocation(program, "uProjInv");
        uLightVec = glGetUniformLocation(program, "uLightVec");
        uMaskAuto = glGetUniformLocation(program, "uMaskAuto");
        uMaskScale = glGetUniformLocation(program, "uMaskScale");
        uDepthIsWindow = glGetUniformLocation(program, "uDepthIsWindow");
        uProj = glGetUniformLocation(program, "uProj");
        uLongShadows = glGetUniformLocation(program, "uLongShadows");
        uShadowDim = glGetUniformLocation(program, "uShadowDim");
        uShadowSteps = glGetUniformLocation(program, "uShadowSteps");
        uSeafloorDimLoc = glGetUniformLocation(program, "uSeafloorDim");
        uTransDepthTexLoc = glGetUniformLocation(program, "uTransDepthTex");
        uSeafloorDebugLoc = glGetUniformLocation(program, "uSeafloorDebug");
        uSeafloorAttenLoc = glGetUniformLocation(program, "uSeafloorAtten");
        uSeafloorFloorLoc = glGetUniformLocation(program, "uSeafloorFloor");
        uUpViewLoc = glGetUniformLocation(program, "uUpView");
        uSeafloorMaxDistLoc = glGetUniformLocation(program, "uSeafloorMaxDist");
        uAbyssLoc = glGetUniformLocation(program, "uAbyss");
        uAbyssColorLoc = glGetUniformLocation(program, "uAbyssColor");
        uSeaOffsetLoc = glGetUniformLocation(program, "uSeaOffset");
        Logger.info("[Metal-LODTEST] vx seafloor water-column dim "
                + (VX_SEAFLOOR_DIM ? "ON" : "OFF")
                + " (LOD floor under LOD water darkened by atten^blocks — the mip rep-voxel"
                + " carries near-surface sky light to submerged floors, so they injected"
                + " beach-bright and bled pale through the 0.70-alpha fallback water;"
                + " atten=" + VX_SEAFLOOR_DIM_ATTEN + ", floor=" + VX_SEAFLOOR_DIM_FLOOR
                + ", maxDist=" + (VX_SEAFLOOR_MAX_DIST < 0f ? "auto (near-cull ring)"
                        : VX_SEAFLOOR_MAX_DIST == 0f ? "unlimited" : VX_SEAFLOOR_MAX_DIST + " blocks")
                + "); VOXY_VX_SEAFLOOR_DIM=0 reverts, _ATTEN/_FLOOR tune, "
                + "_MAX_DIST gates (0 = unlimited)");
        Logger.info("[Metal-LODTEST] vx colortex6 shadowMask mode="
                + (VX_SHADOW_MASK_AUTO ? "auto (per-pixel NoL, scale=" + VX_SHADOW_MASK_SCALE + ")"
                                       : "constant " + VX_SHADOW_MASK)
                + "; VOXY_VX_SHADOW_MASK_AUTO=0 -> constant mode (default 0 = LOD shadows off), "
                + "VOXY_VX_SHADOW_MASK=<v> sets the constant, VOXY_VX_SHADOW_MASK_SCALE tunes auto");
        Logger.info("[Metal-LODTEST] vx LOD long-shadow march "
                + (VX_LOD_LONG_SHADOWS ? "ON" : "OFF")
                + ", v2=" + (VX_LOD_SHADOW_V2 ? "ON" : "OFF (exact v1 math)")
                + " (terrain casts shadows beyond BSL's contact march; dim="
                + VX_LOD_SHADOW_DIM + ", steps=" + VX_LOD_SHADOW_STEPS
                + ", biasK=" + VX_LOD_SHADOW_BIAS_K + ", nearQ=" + nearQ
                + ", fade=" + (VX_LOD_SHADOW_FADE == null ? "OFF"
                        : VX_LOD_SHADOW_FADE[0] + ".." + VX_LOD_SHADOW_FADE[1])
                + "); VOXY_VX_LOD_LONG_SHADOWS=0 kills, VOXY_VX_LOD_SHADOW_V2=0 -> v1, "
                + "VOXY_VX_LOD_SHADOW_FADE=start,end (0 disables), "
                + "VOXY_VX_LOD_SHADOW_DIM/_STEPS/_BIAS_K tune");
        vao = glGenVertexArrays();
        return vao != 0;
    }
}
