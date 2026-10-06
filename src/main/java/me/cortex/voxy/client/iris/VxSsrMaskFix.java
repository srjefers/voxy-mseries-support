package me.cortex.voxy.client.iris;

import com.google.common.collect.ImmutableList;
import me.cortex.voxy.common.Logger;

import java.util.ArrayList;

/**
 * SSR mask fix for REAL pack water over the LOD band (VOXY_VX_SSR_MASK_FIX=0
 * reverts).
 *
 * Root cause (2026-07-16, round 16 — reached after eight on-device exclusion
 * probes proved the pale water panes carry NO Voxy-drawn content at all; they
 * are the pack's OWN water, misshaded): BSL's native vx contract support
 * hijacks the water SSR chain in two places.
 *
 *  (1) deferred1 stamps {@code z = 1.0 - 2e-5} for vx pixels, and its tail
 *      derives {@code reflectionMask = float(z < 1.0)} into colortex5.a — so
 *      the ENTIRE far LOD ocean band advertises itself as a valid SSR
 *      endpoint where stock BSL would write 0 (sky).
 *  (2) raytrace.glsl's {@code #ifdef VOXY} arm substitutes vxDepthTexOpaque
 *      whenever the vanilla depth sample is far — but over a chunk whose
 *      floor exists in NEITHER buffer the vx sample is ALSO 1.0, and
 *      unprojecting depth 1.0 through vxProjInv puts the march sample at the
 *      vx far plane (~16k blocks), so the accept test can never fire and the
 *      ray runs on to the pale horizon band.
 *
 * Combined: real water above a floor that is missing from both vanilla and
 * the LOD bake fetches a flat, pale, glint-free colortex5 endpoint (mask=1)
 * instead of falling back to BSL's analytic wavy sky reflection — the flat
 * pale chunk-shaped panes at grazing pitch (fresnel-weighted, hence the
 * view-angle trigger; per-section depth state, hence the chunk edges).
 *
 * Fix: (1) derive reflectionMask from the ORIGINAL depthtex0 so vx-stamped
 * pixels stop advertising as SSR endpoints; (2) take the vx depth substitute
 * only when it is a real hit (&lt; 1.0). Cost: LOD terrain no longer shows
 * inside real-water SSR reflections (falls back to the analytic sky — the
 * stock BSL look); the kill switch restores the old behaviour. Line
 * REPLACEMENTS keep the pack's line numbering for shader-error mapping.
 */
public final class VxSsrMaskFix {
    private static final boolean ENABLED = !"0".equals(System.getenv("VOXY_VX_SSR_MASK_FIX"));
    private static boolean loggedMask, loggedRay, warnedMask, warnedRay, warnedWaterId;

    public static boolean enabled() {
        if (!ENABLED || !isAllowed(Boolean.getBoolean("voxy.bslCompatibility"))) return false;
        try {
            return me.cortex.voxy.client.core.gpu.RenderBackendFactory.get().getType()
                    != me.cortex.voxy.client.core.gpu.BackendType.OPENGL;
        } catch (Throwable t) {
            return false;
        }
    }

    private static boolean isAllowed(boolean bslCompatibility) {
        return bslCompatibility;
    }

    // deferred1 (and world-dimension copies): identified by the vx branch.
    private static final String VX_BRANCH = "vxZ < 1.0";
    private static final String MASK_ANCHOR = "float reflectionMask = float(z < 1.0);";
    /** 2026-07-17 round-29 hand window (VOXY_VX_SSR_HAND_WINDOW=0 reverts to
     *  the plain round-16 mask): Iris copies depthtex1 AFTER the HAND_SOLID
     *  pass, so the first-person hand's squashed depth (~0.02-0.12) sits in
     *  the SSR march buffer and its albedo sits in colortex5 with a passing
     *  z&lt;1 mask — water SSR endpoints landing on the hand footprint return
     *  a flat 0.5*albedo^0.125 tone (dark item -&gt; dark section panes; bare
     *  arm's skin tone ~ sky -&gt; looked clean; the arm never "fixed" anything).
     *  BSL's own hand convention is depth &lt; 0.56 (composite.glsl:366) —
     *  exclude that window from the endpoint mask and treat it as a miss in
     *  the march. */
    private static final boolean HAND_WINDOW =
            !"0".equals(System.getenv("VOXY_VX_SSR_HAND_WINDOW"));
    private static final String MASK_FIXED = HAND_WINDOW
            ? "float voxyD0 = texture2D(depthtex0, texCoord).r;"
              + " float reflectionMask = float(voxyD0 < 1.0 && voxyD0 > 0.56);"
              + " // voxy ssr-mask fix + hand window (hand footprint advertised as endpoint)"
            : "float reflectionMask = float(texture2D(depthtex0, texCoord).r < 1.0);"
              + " // voxy ssr-mask fix: original depth (vx-stamped z advertised the LOD band)";
    private static final String RAY_HANDSKIP_ANCHOR =
            "float sampleDepth = texture2D(depthtex, pos.xy).r;";
    private static final String RAY_HANDSKIP_FIXED =
            "float sampleDepth = texture2D(depthtex, pos.xy).r;"
            + " if (sampleDepth < 0.56) sampleDepth = 1.0; // voxy hand window";
    private static boolean loggedHandWindow;

    // raytrace.glsl VOXY arm: both lines are unique to that block.
    private static final String RAY_SAMPLE_ANCHOR =
            "sampleDepth = texture2D(vxDepthTexOpaque, pos.xy).r;";
    private static final String RAY_UNPROJ_ANCHOR =
            "rfragpos = nvec3(vxProjInv * nvec4(vec3(pos.xy, sampleDepth) * 2.0 - 1.0));";
    // Round-31: the substitution's purpose is SSR of LOD terrain BEYOND the
    // vanilla render distance; INSIDE the ring it manufactures phantom hits
    // (LOD depth where vanilla says sky) whose ct5 fetches paint flat
    // section-shaped panes on ring water — hand-state-modulated only because
    // the first-person arm's large depthtex1 footprint short-circuits those
    // rays early. Require the vx sample to unproject BEYOND the vanilla far
    // plane before substituting.
    private static final String RAY_GUARD =
            "{ float voxyVxS = texture2D(vxDepthTexOpaque, pos.xy).r;"
            + " vec3 voxyVxP = nvec3(vxProjInv * nvec4(vec3(pos.xy, voxyVxS) * 2.0 - 1.0));"
            + " if (voxyVxS < 1.0 && length(voxyVxP) > far) { ";
    private static final String RAY_GUARD_CLOSE = " } }";

    // 2026-07-17 round-18 diagnostic (VOXY_VX_TRANS_COMPOSITE=0 to arm; no
    // behavior otherwise): kill deferred1's VOXY trans-layer composite —
    // `mix(color, colortex16-content, a)` runs on EVERY pixel gated only by
    // colortex16 alpha + the ghost dT depth test, BEFORE real water draws.
    // A dark/black colortex16 residue with small alpha darkens the whole
    // composited pixel (backdrop included) in LOD-quad shapes: the dark
    // border panes. This composite's colortex16 content comes partly from
    // the injector's Phase-D passthrough, which NO earlier probe ever
    // tinted — explaining the panes' immunity to every resolve-side tint.
    private static final boolean TRANS_COMPOSITE_KILL =
            "0".equals(System.getenv("VOXY_VX_TRANS_COMPOSITE"));
    private static final String TRANS_MIX_ANCHOR =
            "color.rgb = mix(color.rgb, voxyTransparentColor.rgb, voxyTransparentColor.a);";
    private static boolean loggedTransKill;

    // 2026-07-17 round-20 (VOXY_VX_CHUNKFADE=0 to arm): neutralize Iris's
    // chunk-fade in the pack. ChunkFade mixes a section's surface colour
    // toward GetSkyColor while its per-section fade (vertex attribute
    // mc_chunkFade, Iris FADE_VARIABLE feature) is < 1.0. Sections whose fade
    // sticks near 0 under Voxy's Metal draw path render as sky-coloured
    // (pack defaults: the original pale "cloud-reflecting" panes) or
    // dark-sky-coloured (with the diagnostic option cuts) chunk quads —
    // per-section shapes, restart on frustum re-entry (the user's
    // mouse-movement symptom) and on rebuilds, untouched by every option cut,
    // and invisible to all Voxy colour tints since the colour is the pack's
    // own sky mix.
    private static final boolean CHUNKFADE_KILL =
            "0".equals(System.getenv("VOXY_VX_CHUNKFADE"));
    private static final String CHUNKFADE_ANCHOR =
            "ChunkFade(albedo.rgb, viewPos, chunkFade);";
    private static boolean loggedFadeKill;

    // 2026-07-17 round-21 IDENTITY probe (VOXY_VX_WATER_ID=1 to arm; inert
    // otherwise): force the pack's REAL water pass (gbuffers_water) to output
    // solid opaque MAGENTA. Every prior probe tested a suspected WRITER of the
    // dark panes and came back negative; this one instead partitions the
    // pane's position in the FRAME ORDER in a single run:
    //   (A) pane vanishes into flat magenta  -> darkness forms IN/UNDER the
    //       water pass (its shading inputs or the blended backdrop);
    //   (B) pane still darkens the magenta   -> something composites OVER the
    //       finished water (post-water pass / GL state leak);
    //   (C) pane is a NON-magenta hole       -> water fragments are absent
    //       there (discard or Sodium section/occlusion culling — would also
    //       explain the sharp per-orientation thresholds).
    // Anchored on gbuffers_water's single final colour write; the
    // cloudBlendOpacity + mc_Entity discriminator pair excludes the pack's
    // voxy_translucent (fullscreen LOD resolve — no vertex attributes), so
    // ONLY real vanilla-geometry water turns magenta.
    private static final boolean WATER_ID =
            "1".equals(System.getenv("VOXY_VX_WATER_ID"));
    private static final String WATER_DISCRIMINATOR = "albedo.a *= cloudBlendOpacity;";
    private static final String WATER_ATTR_DISCRIMINATOR = "mc_Entity";
    private static final String WATER_TAIL_ANCHOR = "gl_FragData[0] = albedo;";
    private static final String WATER_TAIL_PROBE =
            "gl_FragData[0] = vec4(1.0, 0.0, 1.0, 1.0); // voxy water-id probe";
    private static boolean loggedWaterId;

    // 2026-07-17 round-22 OPACITY probe (VOXY_VX_WATER_OPAQUE=1 to arm; inert
    // otherwise): the round-21 magenta result proved the water fragments exist
    // at pane pixels and nothing composites over them — but opaque magenta
    // hid BOTH remaining suspects at once (the shader's computed colour AND
    // the backdrop seen through water alpha). This arm keeps the water's REAL
    // shading and only forces alpha to 1:
    //   panes persist -> the water SHADING inputs are dark per-section
    //                    (vertex colour / lightmap / texture sample);
    //   panes vanish  -> the BACKDROP under the water (colortex0 at deferred
    //                    time) is dark there — the depth-side vx branch class.
    private static final boolean WATER_OPAQUE =
            "1".equals(System.getenv("VOXY_VX_WATER_OPAQUE"));
    private static final String WATER_OPAQUE_PROBE =
            "gl_FragData[0] = vec4(albedo.rgb, 1.0); // voxy water-opaque probe";
    private static boolean loggedWaterOpaque;

    // 2026-07-17 round-23 LIGHT-vs-TINT probe (VOXY_VX_WATER_FULLBRIGHT=1):
    // round-22 proved the darkening lives in the water shader's per-section
    // inputs (opaque water still shows the panes). Those inputs are the
    // Sodium-baked per-vertex LIGHTMAP and per-vertex COLOUR (biome water
    // tint). This arm forces the water's lightmap to full:
    //   panes vanish  -> per-section LIGHT data is wrong (light engine /
    //                    Voxy light interference at bake time);
    //   panes persist -> per-section biome TINT (glColor) is wrong.
    private static final boolean WATER_FULLBRIGHT =
            "1".equals(System.getenv("VOXY_VX_WATER_FULLBRIGHT"));
    private static final String WATER_LM_ANCHOR =
            "vec2 lightmap = clamp(lmCoord, vec2(0.0), vec2(1.0));";
    private static final String WATER_LM_PROBE =
            "vec2 lightmap = vec2(0.9333, 1.0); // voxy water-fullbright probe";
    private static boolean loggedWaterFullbright;

    // 2026-07-17 round-24 FINAL input probe (VOXY_VX_WATER_WHITECOLOR=1):
    // round-23 excluded per-vertex LIGHT (fullbright left the panes intact).
    // The last standing per-section input is the Sodium-baked per-VERTEX
    // COLOUR (biome water tint x face shade). This arm forces it to white in
    // the water vertex shader: panes vanish -> per-section vertex colour is
    // wrong (biome lookup / meshing race under Voxy); panes persist -> only
    // the texture sample remains, which would be a genuine surprise.
    private static final boolean WATER_WHITECOLOR =
            "1".equals(System.getenv("VOXY_VX_WATER_WHITECOLOR"));
    private static final String WATER_COLOR_ANCHOR = "color = gl_Color;";
    private static final String WATER_COLOR_PROBE =
            "color = vec4(1.0); // voxy water-whitecolor probe";
    private static boolean loggedWaterWhite;

    // 2026-07-17 round-25 BRANCH probe (VOXY_VX_WATER_FORCEMAT=1): rounds
    // 23-24 forced the shared inputs (lightmap, vertex colour) — panes
    // persisted. The remaining per-section variable is the BRANCH SELECTION
    // itself: mc_Entity-derived `mat`. Sections whose water carries a corrupt
    // material id take the generic-translucent (glass) path — no waterColor
    // replacement, different alpha — darker, per-section, immune to the
    // shared-input probes. This arm forces every translucent fragment into
    // the water branch: panes vanish -> per-section material attribute is
    // corrupt (Voxy's Metal vertex/draw path); panes persist -> the water
    // branch itself produces the darkness from a non-forced term.
    // 2026-07-17 round-26 NORMAL probe (VOXY_VX_WATER_FLATLIGHT=1): the last
    // un-forced water-branch terms are the NORMAL-derived lighting factors
    // (NoL, vanillaDiffuse). A per-section corrupt vertex normal darkens the
    // water while being immune to every previously forced input, and would
    // also explain the panes' flat look. This arm forces both to 1 (fully
    // lit, normal-independent).
    private static final boolean WATER_FLATLIGHT =
            "1".equals(System.getenv("VOXY_VX_WATER_FLATLIGHT"));
    private static final String WATER_NOL_A =
            "float NoL = clamp(dot(newNormal, lightVec), 0.0, 1.0);";
    private static final String WATER_NOL_B =
            "float NoL = clamp(dot(newNormal, lightVec) * 0.5 + 0.5, 0.0, 1.0);";
    private static final String WATER_VDIFF =
            "float vanillaDiffuse = (0.25 * NoU + 0.75) + (0.667 - abs(NoE)) * (1.0 - abs(NoU)) * 0.15;";
    private static boolean loggedFlatlight;

    private static final boolean WATER_FORCEMAT =
            "1".equals(System.getenv("VOXY_VX_WATER_FORCEMAT"));
    private static final String WATER_MAT_ANCHOR =
            "float water       = float(mat > 0.98 && mat < 1.02);";
    private static final String WATER_MAT_PROBE =
            "float water       = 1.0; // voxy water-forcemat probe";
    private static boolean loggedWaterMat;

    /**
     * Patch an include-processed source. Returns patched lines, or null when
     * this file isn't a target / is already patched / anchors drifted
     * (fail-open: pack renders as before, one loud warn per patch site).
     */
    public static ImmutableList<String> patch(ImmutableList<String> lines, String pathLabel) {
        if (lines == null) return null;
        // 2026-07-16 first-run FAILURE root cause: Iris serves program sources
        // with their #includes EXPANDED, so deferred1's text inherited the
        // already-guarded raytrace lines and a FILE-level "already patched"
        // check skipped the file before the mask arm could apply — the fix's
        // decisive half never reached the compiled program. Idempotency is
        // therefore PER PATCH: each arm checks only for its own marker.
        boolean hasVxBranch = false, maskDone = false, rayDone = false;
        int maskLine = -1, raySampleLine = -1, rayUnprojLine = -1;
        for (int i = 0; i < lines.size(); i++) {
            String l = lines.get(i);
            if (l.contains("voxy ssr-mask fix")) maskDone = true;
            if (l.contains(RAY_GUARD)) rayDone = true;
            if (l.contains(VX_BRANCH)) hasVxBranch = true;
            if (maskLine < 0 && l.contains(MASK_ANCHOR)) maskLine = i;
            if (raySampleLine < 0 && l.contains(RAY_SAMPLE_ANCHOR)) raySampleLine = i;
            if (raySampleLine >= 0 && i > raySampleLine && rayUnprojLine < 0
                    && l.contains(RAY_UNPROJ_ANCHOR)) {
                rayUnprojLine = i;
            }
        }

        ArrayList<String> out = null;

        if (!maskDone && hasVxBranch && maskLine >= 0) {
            out = new ArrayList<>(lines);
            out.set(maskLine, out.get(maskLine).replace(MASK_ANCHOR, MASK_FIXED));
            if (!loggedMask) {
                loggedMask = true;
                Logger.info("[Metal-LODTEST] vx SSR mask fix ON (" + pathLabel
                        + " reflectionMask now derives from the ORIGINAL depthtex0 — the vx"
                        + " branch's stamped z advertised the whole LOD ocean band as a valid"
                        + " SSR endpoint, so real water at grazing fetched a flat pale mirror"
                        + " instead of the analytic sky = the pale border panes);"
                        + " VOXY_VX_SSR_MASK_FIX=0 reverts");
            }
        } else if (!maskDone && maskLine < 0 && pathLabel.contains("deferred1")
                && hasVxBranch && !warnedMask) {
            // Only deferred1 (and its dimension copies) carries the mask line;
            // a miss THERE means the pack text drifted. Other files legitimately
            // contain 'reflectionMask' via expanded includes — no warn for them.
            warnedMask = true;
            Logger.warn("[Metal-LODTEST] vx SSR mask fix FAILED in '" + pathLabel
                    + "' (vx branch present but the reflectionMask anchor is missing — pack"
                    + " updated?); mask patch inert there");
        }

        if (HAND_WINDOW) {
            for (int i = 0; i < lines.size(); i++) {
                String l = lines.get(i);
                if (l.contains(RAY_HANDSKIP_ANCHOR) && !l.contains("voxy hand window")) {
                    if (out == null) out = new ArrayList<>(lines);
                    out.set(i, out.get(i).replace(RAY_HANDSKIP_ANCHOR, RAY_HANDSKIP_FIXED));
                    if (!loggedHandWindow) {
                        loggedHandWindow = true;
                        Logger.info("[Metal-LODTEST] vx SSR hand window ON (" + pathLabel
                                + " — hand-footprint depth (<0.56, BSL's own convention)"
                                + " treated as a march miss and excluded from the endpoint"
                                + " mask; the first-person hand sat in depthtex1/colortex5"
                                + " and painted flat item-albedo panes on water SSR"
                                + " endpoints); VOXY_VX_SSR_HAND_WINDOW=0 reverts");
                    }
                }
            }
        }

        if (!rayDone && raySampleLine >= 0) {
            if (rayUnprojLine >= 0) {
                if (out == null) out = new ArrayList<>(lines);
                out.set(raySampleLine, out.get(raySampleLine)
                        .replace(RAY_SAMPLE_ANCHOR, RAY_GUARD + RAY_SAMPLE_ANCHOR));
                out.set(rayUnprojLine, out.get(rayUnprojLine)
                        .replace(RAY_UNPROJ_ANCHOR, RAY_UNPROJ_ANCHOR + RAY_GUARD_CLOSE));
                if (!loggedRay) {
                    loggedRay = true;
                    Logger.info("[Metal-LODTEST] vx SSR raytrace guard ON (" + pathLabel
                            + " vx depth substitute taken only when it is a real hit — depth 1.0"
                            + " unprojected through vxProjInv landed the march sample at the"
                            + " ~16k vx far plane and broke SSR hit rejection over chunks with"
                            + " no floor in either buffer); VOXY_VX_SSR_MASK_FIX=0 reverts");
                }
            } else if (!warnedRay) {
                warnedRay = true;
                Logger.warn("[Metal-LODTEST] vx SSR raytrace guard FAILED in '" + pathLabel
                        + "' (vx sample line found but the vxProjInv unproject line did not"
                        + " follow — pack updated?); guard inert there");
            }
        }

        if (CHUNKFADE_KILL) {
            boolean touched = false;
            for (int i = 0; i < lines.size(); i++) {
                String l = lines.get(i);
                if (l.contains(CHUNKFADE_ANCHOR) && !l.contains("voxy chunkfade KILLED")) {
                    if (out == null) out = new ArrayList<>(lines);
                    out.set(i, out.get(i).replace(CHUNKFADE_ANCHOR,
                            "/* voxy chunkfade KILLED (VOXY_VX_CHUNKFADE=0 diagnostic) */"));
                    touched = true;
                }
            }
            if (touched && !loggedFadeKill) {
                loggedFadeKill = true;
                Logger.info("[Metal-LODTEST] vx chunkfade KILLED in " + pathLabel
                        + " (Iris FADE_VARIABLE ChunkFade -> sky-colour mix neutralized;"
                        + " diagnostic for the border panes — stuck per-section fades render"
                        + " chunk quads as sky-coloured/dark); unset VOXY_VX_CHUNKFADE to"
                        + " restore");
            }
        }

        if (TRANS_COMPOSITE_KILL) {
            for (int i = 0; i < lines.size(); i++) {
                String l = lines.get(i);
                if (l.contains(TRANS_MIX_ANCHOR) && !l.contains("voxy trans-composite KILLED")) {
                    if (out == null) out = new ArrayList<>(lines);
                    out.set(i, out.get(i).replace(TRANS_MIX_ANCHOR,
                            "/* voxy trans-composite KILLED (VOXY_VX_TRANS_COMPOSITE=0"
                            + " diagnostic) */"));
                    if (!loggedTransKill) {
                        loggedTransKill = true;
                        Logger.info("[Metal-LODTEST] vx trans composite KILLED in " + pathLabel
                                + " (deferred1's mix(color, colortex16, a) skipped — LOD water"
                                + " will NOT composite through the pack; diagnostic for the"
                                + " dark border panes); unset VOXY_VX_TRANS_COMPOSITE to"
                                + " restore");
                    }
                    break;
                }
            }
        }

        if (WATER_ID) {
            boolean isRealWaterProgram = false, attrPresent = false, probeDone = false;
            int tailLine = -1;
            for (int i = 0; i < lines.size(); i++) {
                String l = lines.get(i);
                if (l.contains(WATER_DISCRIMINATOR)) isRealWaterProgram = true;
                if (l.contains(WATER_ATTR_DISCRIMINATOR)) attrPresent = true;
                if (l.contains("voxy water-id probe")) probeDone = true;
                if (tailLine < 0 && l.contains(WATER_TAIL_ANCHOR)) tailLine = i;
            }
            if (isRealWaterProgram && attrPresent && !probeDone && tailLine >= 0) {
                if (out == null) out = new ArrayList<>(lines);
                out.set(tailLine, out.get(tailLine)
                        .replace(WATER_TAIL_ANCHOR, WATER_TAIL_PROBE));
                if (!loggedWaterId) {
                    loggedWaterId = true;
                    Logger.info("[Metal-LODTEST] vx WATER ID probe ON (" + pathLabel
                            + " — the pack's REAL water pass now writes solid opaque MAGENTA;"
                            + " frame-order trisection for the dark panes: pane vanishes ->"
                            + " forms in/under water; pane darkens the magenta -> composited"
                            + " OVER water; non-magenta hole -> water fragments absent there);"
                            + " unset VOXY_VX_WATER_ID to restore");
                }
            } else if (isRealWaterProgram && attrPresent && !probeDone && tailLine < 0
                    && !warnedWaterId) {
                warnedWaterId = true;
                Logger.warn("[Metal-LODTEST] vx WATER ID probe FAILED in '" + pathLabel
                        + "' (water program found but the gl_FragData[0] tail anchor is"
                        + " missing — pack updated?); probe inert there");
            }
        }

        if (WATER_OPAQUE && !WATER_ID) {
            boolean isRealWaterProgram = false, attrPresent = false, probeDone = false;
            int tailLine = -1;
            for (int i = 0; i < lines.size(); i++) {
                String l = lines.get(i);
                if (l.contains(WATER_DISCRIMINATOR)) isRealWaterProgram = true;
                if (l.contains(WATER_ATTR_DISCRIMINATOR)) attrPresent = true;
                if (l.contains("voxy water-opaque probe")) probeDone = true;
                if (tailLine < 0 && l.contains(WATER_TAIL_ANCHOR)) tailLine = i;
            }
            if (isRealWaterProgram && attrPresent && !probeDone && tailLine >= 0) {
                if (out == null) out = new ArrayList<>(lines);
                out.set(tailLine, out.get(tailLine)
                        .replace(WATER_TAIL_ANCHOR, WATER_OPAQUE_PROBE));
                if (!loggedWaterOpaque) {
                    loggedWaterOpaque = true;
                    Logger.info("[Metal-LODTEST] vx WATER OPAQUE probe ON (" + pathLabel
                            + " — real water keeps its shading but alpha forced to 1.0;"
                            + " panes persist -> water SHADING inputs dark per-section;"
                            + " panes vanish -> the BACKDROP under water is dark there);"
                            + " unset VOXY_VX_WATER_OPAQUE to restore");
                }
            }
        }

        if (WATER_FULLBRIGHT) {
            boolean isRealWaterProgram = false, attrPresent = false, probeDone = false;
            int lmLine = -1;
            for (int i = 0; i < lines.size(); i++) {
                String l = lines.get(i);
                if (l.contains(WATER_DISCRIMINATOR)) isRealWaterProgram = true;
                if (l.contains(WATER_ATTR_DISCRIMINATOR)) attrPresent = true;
                if (l.contains("voxy water-fullbright probe")) probeDone = true;
                if (lmLine < 0 && l.contains(WATER_LM_ANCHOR)) lmLine = i;
            }
            if (isRealWaterProgram && attrPresent && !probeDone && lmLine >= 0) {
                if (out == null) out = new ArrayList<>(lines);
                out.set(lmLine, out.get(lmLine).replace(WATER_LM_ANCHOR, WATER_LM_PROBE));
                if (!loggedWaterFullbright) {
                    loggedWaterFullbright = true;
                    Logger.info("[Metal-LODTEST] vx WATER FULLBRIGHT probe ON (" + pathLabel
                            + " — water lightmap forced to max; panes vanish -> per-section"
                            + " LIGHT data wrong at bake; panes persist -> per-section biome"
                            + " TINT wrong); unset VOXY_VX_WATER_FULLBRIGHT to restore");
                }
            }
        }

        if (WATER_WHITECOLOR) {
            boolean isRealWaterProgram = false, attrPresent = false, probeDone = false;
            int colorLine = -1;
            for (int i = 0; i < lines.size(); i++) {
                String l = lines.get(i);
                if (l.contains(WATER_DISCRIMINATOR)) isRealWaterProgram = true;
                if (l.contains(WATER_ATTR_DISCRIMINATOR)) attrPresent = true;
                if (l.contains("voxy water-whitecolor probe")) probeDone = true;
                if (colorLine < 0 && l.contains(WATER_COLOR_ANCHOR)) colorLine = i;
            }
            if (isRealWaterProgram && attrPresent && !probeDone && colorLine >= 0) {
                if (out == null) out = new ArrayList<>(lines);
                out.set(colorLine, out.get(colorLine)
                        .replace(WATER_COLOR_ANCHOR, WATER_COLOR_PROBE));
                if (!loggedWaterWhite) {
                    loggedWaterWhite = true;
                    Logger.info("[Metal-LODTEST] vx WATER WHITECOLOR probe ON (" + pathLabel
                            + " — water per-vertex colour forced to white; panes vanish ->"
                            + " per-section vertex colour (biome tint/shade) is the corrupt"
                            + " input; panes persist -> texture sample only remains);"
                            + " unset VOXY_VX_WATER_WHITECOLOR to restore");
                }
            }
        }

        if (WATER_FLATLIGHT) {
            boolean isRealWaterProgram = false, attrPresent = false, probeDone = false;
            boolean touched = false;
            for (int i = 0; i < lines.size(); i++) {
                String l = lines.get(i);
                if (l.contains(WATER_DISCRIMINATOR)) isRealWaterProgram = true;
                if (l.contains(WATER_ATTR_DISCRIMINATOR)) attrPresent = true;
                if (l.contains("voxy water-flatlight probe")) probeDone = true;
            }
            if (isRealWaterProgram && attrPresent && !probeDone) {
                if (out == null) out = new ArrayList<>(lines);
                for (int i = 0; i < out.size(); i++) {
                    String l = out.get(i);
                    if (l.contains(WATER_NOL_A)) {
                        out.set(i, l.replace(WATER_NOL_A,
                                "float NoL = 1.0; // voxy water-flatlight probe"));
                        touched = true;
                    } else if (l.contains(WATER_NOL_B)) {
                        out.set(i, l.replace(WATER_NOL_B,
                                "float NoL = 1.0; // voxy water-flatlight probe"));
                        touched = true;
                    } else if (l.contains(WATER_VDIFF)) {
                        out.set(i, l.replace(WATER_VDIFF,
                                "float vanillaDiffuse = 1.0; // voxy water-flatlight probe"));
                        touched = true;
                    }
                }
                if (touched && !loggedFlatlight) {
                    loggedFlatlight = true;
                    Logger.info("[Metal-LODTEST] vx WATER FLATLIGHT probe ON (" + pathLabel
                            + " — NoL and vanillaDiffuse forced to 1; panes vanish -> the"
                            + " per-section vertex NORMAL is the corrupt input; panes persist"
                            + " -> normals exonerated too);"
                            + " unset VOXY_VX_WATER_FLATLIGHT to restore");
                }
            }
        }

        if (WATER_FORCEMAT) {
            boolean isRealWaterProgram = false, attrPresent = false, probeDone = false;
            int matLine = -1;
            for (int i = 0; i < lines.size(); i++) {
                String l = lines.get(i);
                if (l.contains(WATER_DISCRIMINATOR)) isRealWaterProgram = true;
                if (l.contains(WATER_ATTR_DISCRIMINATOR)) attrPresent = true;
                if (l.contains("voxy water-forcemat probe")) probeDone = true;
                if (matLine < 0 && l.contains(WATER_MAT_ANCHOR)) matLine = i;
            }
            if (isRealWaterProgram && attrPresent && !probeDone && matLine >= 0) {
                if (out == null) out = new ArrayList<>(lines);
                out.set(matLine, out.get(matLine)
                        .replace(WATER_MAT_ANCHOR, WATER_MAT_PROBE));
                if (!loggedWaterMat) {
                    loggedWaterMat = true;
                    Logger.info("[Metal-LODTEST] vx WATER FORCEMAT probe ON (" + pathLabel
                            + " — every translucent fragment forced into the WATER branch;"
                            + " panes vanish -> per-section material id (mc_Entity) corrupt"
                            + " under the Metal vertex/draw path; panes persist -> the water"
                            + " branch itself makes the darkness);"
                            + " unset VOXY_VX_WATER_FORCEMAT to restore");
                }
            }
        }

        return out == null ? null : ImmutableList.copyOf(out);
    }

    private VxSsrMaskFix() {}
}
