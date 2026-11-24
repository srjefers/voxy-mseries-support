package me.cortex.voxy.client.iris;

import com.google.common.collect.ImmutableList;
import me.cortex.voxy.client.core.gpu.BackendType;
import me.cortex.voxy.client.core.gpu.RenderBackendFactory;
import me.cortex.voxy.client.core.rendering.clouds.LodClouds;
import me.cortex.voxy.common.Logger;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Pack-text patch: BSL's volumetric clouds reach the LOD distance instead of
 * stopping ~1 750 blocks out (finding H12, the shader-pack half of
 * {@link LodClouds}).
 *
 * <p>BSL v10's {@code DrawCloudVolumetric} (lib/atmospherics/clouds.glsl)
 * already knows the LOD distance: deferred1 passes
 * {@code cloudViewLength = max(2 * far, vxRenderDistance * 16)} and the function
 * keeps it as {@code maxcloudViewLength}; the march stops at LOD terrain through
 * {@code vxDepthTexOpaque}. But its own distance fade ignores it: in normalised
 * units ({@code xzNormalizeFactor = 10 / max(|CLOUD_HEIGHT - 72|, 56)}, 10/73 with
 * the default height) the clouds start fading at {@code fadeStart = 32 / fogDensity}
 * (~234 blocks) and are cut at {@code fadeEnd = 240 / fogDensity} (~1 750
 * blocks, 3 500 at most). Above the clouds the LOD terrain had none, and the near
 * ones faded with distance: the same complaint as without shaders.
 *
 * <p>The patch appends to the {@code xzNormalizeFactor} line (line count kept for
 * shader-error mapping), for the main clouds only ({@code !fadeFaster}; water
 * reflections keep the pack's fade):
 * {@code fadeEnd = max(fadeEnd, maxcloudViewLength * xzNormalizeFactor)} and
 * {@code fadeStart = max(fadeStart, fadeEnd * }{@link LodClouds#FADE_START_FRACTION}{@code )}:
 * full clouds out to 80 % of the LOD distance, then the pack's own fade, the same
 * shape as the no-pack layer. {@code max} never brings the clouds closer than the
 * pack has them.
 *
 * <p>Like the no-pack layer, clouds only form over LOD terrain: after the march's own
 * distance cut ({@code noise *= step(xzNormalizedDistance, fadeEnd);}) the sample's
 * density is multiplied by {@code vxLodCoverage} at its world xz, the toroidal
 * coverage texture of {@link me.cortex.voxy.client.core.rendering.clouds.LodCoverageGlTexture}:
 * {@code (samplePos.xz - cameraPos.xz + vxLodCoverageInfo.xy) * vxLodCoverageInfo.z},
 * i.e. the sample's offset from the camera (BSL builds {@code samplePos} from
 * {@code cameraPos}, Iris's shifted {@code cameraPosition}) plus the real camera
 * position modulo the span. The two uniforms are declared on the function's
 * line. Main clouds only, and only once the texture is valid
 * ({@code vxLodCoverageInfo.w}). Metal only; {@code VOXY_LOD_CLOUDS=0} reverts all of it.
 */
public final class VxLodCloudDistance {
    private VxLodCloudDistance() {}

    private static boolean loggedApply, loggedMiss, loggedNoCut;

    public static boolean enabled() {
        if (!LodClouds.ENABLED) return false;
        try {
            return RenderBackendFactory.get().getType() != BackendType.OPENGL;
        } catch (Throwable t) {
            return false;
        }
    }

    private static final String FUNCTION_MARK = "vec4 DrawCloudVolumetric(";
    private static final String MAX_LENGTH_DECL = "float maxcloudViewLength = cloudViewLength;";
    private static final String FADE_START_DECL = "float fadeStart =";
    private static final String FADE_END_DECL = "float fadeEnd =";
    private static final String NORMALIZE_DECL = "float xzNormalizeFactor =";
    private static final String APPLIED_MARK = "voxy: LOD cloud distance";
    private static final String DISTANCE_CUT = "noise *= step(xzNormalizedDistance, fadeEnd);";

    /** @return patched lines, or null when this text has no volumetric clouds (or is already patched). */
    public static ImmutableList<String> patch(ImmutableList<String> lines, String pathLabel) {
        if (lines == null) return null;
        int fn = -1;
        for (int i = 0; i < lines.size(); i++) {
            if (lines.get(i).contains(FUNCTION_MARK)) { fn = i; break; }
        }
        if (fn < 0) return null;
        // The function's own declarations, in order, before the line we append to.
        int maxLen = -1, fadeStart = -1, fadeEnd = -1, normalize = -1;
        for (int i = fn + 1; i < lines.size(); i++) {
            String l = lines.get(i);
            if (l.startsWith("vec4 ") && l.contains("(")) break; // next function: give up
            if (maxLen < 0 && l.contains(MAX_LENGTH_DECL)) maxLen = i;
            else if (fadeStart < 0 && l.contains(FADE_START_DECL)) fadeStart = i;
            else if (fadeEnd < 0 && l.contains(FADE_END_DECL)) fadeEnd = i;
            else if (l.contains(NORMALIZE_DECL)) { normalize = i; break; }
        }
        // Iris serves the same expanded text through more than one path: already
        // patched text is not a failure.
        if (normalize >= 0 && lines.get(normalize).contains(APPLIED_MARK)) return null;
        if (maxLen < 0 || fadeStart < 0 || fadeEnd < 0 || normalize < 0
                || !(maxLen < fadeStart && fadeStart < fadeEnd && fadeEnd < normalize)
                || !lines.get(normalize).trim().endsWith(";")) {
            if (!loggedMiss) {
                loggedMiss = true;
                Logger.warn("[Metal-CLOUDS] pack cloud distance patch FAILED: DrawCloudVolumetric found in '" + pathLabel
                        + "' but not maxcloudViewLength / fadeStart / fadeEnd / xzNormalizeFactor in that order"
                        + " — pack text drifted; the pack's clouds keep their own distance");
            }
            return null;
        }
        // The march's own distance cut, inside the sample loop: where the coverage
        // multiply goes (after it, so it only ever removes cloud).
        int cut = -1;
        for (int i = normalize + 1; i < lines.size(); i++) {
            String l = lines.get(i);
            if (l.startsWith("vec4 ") && l.contains("(")) break;
            if (l.contains(DISTANCE_CUT) && l.trim().endsWith(";")) { cut = i; break; }
        }

        List<String> out = new ArrayList<>(lines);
        // Same line, block comment: nothing after it is swallowed (see VxLodAoParity).
        out.set(normalize, lines.get(normalize) + String.format(Locale.ROOT,
                " if (!fadeFaster) { fadeEnd = max(fadeEnd, maxcloudViewLength * xzNormalizeFactor);"
                        + " fadeStart = max(fadeStart, fadeEnd * %.4f); } /* %s: VOXY_LOD_CLOUDS=0 reverts */",
                LodClouds.FADE_START_FRACTION, APPLIED_MARK));
        if (cut >= 0) {
            // Global declarations on the function's own line (line count kept). Bound by
            // VoxySamplers / VoxyUniforms; info.w stays 0 until the texture is uploaded.
            out.set(fn, "uniform sampler2D vxLodCoverage; uniform vec4 vxLodCoverageInfo; " + lines.get(fn));
            out.set(cut, lines.get(cut)
                    + " if (!fadeFaster && vxLodCoverageInfo.w > 0.5) noise *= texture2D(vxLodCoverage,"
                    + " (samplePos.xz - cameraPos.xz + vxLodCoverageInfo.xy) * vxLodCoverageInfo.z).r;"
                    + " /* voxy: clouds only over LOD terrain */");
        } else if (!loggedNoCut) {
            loggedNoCut = true;
            Logger.warn("[Metal-CLOUDS] pack clouds over LOD terrain only: '" + DISTANCE_CUT + "' not found in '"
                    + pathLabel + "' — pack text drifted; the distance patch still applies, clouds also form over void");
        }
        if (!loggedApply) {
            loggedApply = true;
            Logger.info("[Metal-CLOUDS] pack cloud distance ON: DrawCloudVolumetric fades out at the LOD distance"
                    + " (maxcloudViewLength) instead of ~1 750 blocks, full clouds to "
                    + Math.round(LodClouds.FADE_START_FRACTION * 100) + " % of it"
                    + (cut >= 0 ? ", only over LOD terrain (vxLodCoverage)" : "")
                    + "; water reflections unchanged; VOXY_LOD_CLOUDS=0 reverts (in '" + pathLabel + "')");
        }
        return ImmutableList.copyOf(out);
    }
}
