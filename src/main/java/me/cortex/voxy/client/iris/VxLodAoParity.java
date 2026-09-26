package me.cortex.voxy.client.iris;

import com.google.common.collect.ImmutableList;
import me.cortex.voxy.client.core.gpu.BackendType;
import me.cortex.voxy.client.core.gpu.RenderBackendFactory;
import me.cortex.voxy.common.Logger;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Pack-text patch (2026-09-25): give LOD pixels the same screen-space
 * ambient-occlusion tuning BSL uses for its vanilla terrain.
 *
 * <p>BSL v10's deferred.glsl computes SSAO into colortex4 with
 * {@code AmbientOcclusion(z, depthtex0, ..., 0.25, blueNoise, false)} for
 * vanilla pixels but, in its {@code #ifdef VOXY} branch,
 * {@code AmbientOcclusion(vxZ, vxDepthTexOpaque, ..., 1.5, blueNoise, true)}
 * followed by {@code ao = pow(ao, 2.0)} for LOD pixels: a 6x larger sample
 * radius, squared. That tuning is meant for coarse far-LOD blocks. On the
 * first LOD ring (1:1 voxels right past Sodium's edge) it blacks out the base
 * of every 1-block plant cross and every LOD step face, so through a spyglass
 * the LOD tufts read as dark bushes and LOD cliff sides read near-black while
 * the vanilla twins a few blocks nearer are pale (pixel census 2026-09-25:
 * tips at ground luminance, lower third -25% mean / -45% peak, blue-shifted
 * like BSL's ambient-only shadow). SHADOW_LOD=false, the foliage contact-march
 * mask and the inject path all left it unchanged because deferred1 multiplies
 * this AO in regardless (deferred1.glsl vx branch, before Fog).
 *
 * <p>The patch is two in-line replacements on the pack's deferred text at
 * Iris include-processing time (same hook as {@link VxFogCap}): the vx radius
 * {@code 1.5} becomes {@code VOXY_VX_LOD_AO_RADIUS} (default 0.25 = vanilla)
 * and {@code pow(ao, 2.0)} becomes {@code pow(ao, VOXY_VX_LOD_AO_POW)}
 * (default 1.0 = vanilla). Line numbering is preserved for shader-error
 * mapping. The Distant Horizons branch is left alone. Metal-only via
 * {@link #enabled()}; {@code VOXY_VX_LOD_AO_PARITY=0} reverts.
 */
public final class VxLodAoParity {
    private VxLodAoParity() {}

    private static final float RADIUS = parseEnvF("VOXY_VX_LOD_AO_RADIUS", 0.25f);
    private static final float POW = parseEnvF("VOXY_VX_LOD_AO_POW", 1.0f);
    private static boolean loggedApply, loggedMiss;

    private static float parseEnvF(String name, float def) {
        String v = System.getenv(name);
        if (v == null || v.isEmpty()) return def;
        try {
            float f = Float.parseFloat(v.trim());
            return (f > 0.0f && f < 64.0f) ? f : def;
        } catch (NumberFormatException e) {
            return def;
        }
    }

    public static boolean enabled() {
        if ("0".equals(System.getenv("VOXY_VX_LOD_AO_PARITY"))) return false;
        try {
            return RenderBackendFactory.get().getType() != BackendType.OPENGL;
        } catch (Throwable t) {
            return false;
        }
    }

    private static final String VX_AO_MARK_A = "AmbientOcclusion(vxZ";
    private static final String VX_AO_MARK_B = "vxDepthTexOpaque";
    private static final String RADIUS_NEEDLE = ", 1.5, blueNoise, true)";
    private static final String POW_NEEDLE = "ao = pow(ao, 2.0);";

    /** @return patched lines, or null when this file carries no vx AO line. */
    public static ImmutableList<String> patch(ImmutableList<String> lines, String pathLabel) {
        if (lines == null) return null;
        int idx = -1;
        for (int i = 0; i < lines.size(); i++) {
            String l = lines.get(i);
            if (l.contains(VX_AO_MARK_A) && l.contains(VX_AO_MARK_B)) { idx = i; break; }
        }
        if (idx < 0) return null;
        String aoLine = lines.get(idx);
        boolean radiusOk = aoLine.contains(RADIUS_NEEDLE);
        int powIdx = -1;
        for (int j = idx + 1; j < Math.min(idx + 3, lines.size()); j++) {
            if (lines.get(j).contains(POW_NEEDLE)) { powIdx = j; break; }
        }
        if (!radiusOk || powIdx < 0) {
            if (!loggedMiss) {
                loggedMiss = true;
                Logger.warn("[Metal-LODTEST] vx LOD AO parity FAILED (deferred AmbientOcclusion(vxZ ... vxDepthTexOpaque) "
                        + (radiusOk ? "pow(ao, 2.0) line" : "radius needle") + " not found in '" + pathLabel
                        + "' — pack text drifted; LOD SSAO keeps the pack's 1.5 / pow 2 tuning)");
            }
            return null;
        }
        List<String> out = new ArrayList<>(lines);
        String radius = String.format(Locale.ROOT, ", %.4f, blueNoise, true)", RADIUS);
        out.set(idx, aoLine.replace(RADIUS_NEEDLE, radius + " // voxy: LOD AO radius parity (VOXY_VX_LOD_AO_PARITY=0 reverts)"));
        String pow = String.format(Locale.ROOT, "ao = pow(ao, %.4f);", POW);
        out.set(powIdx, lines.get(powIdx).replace(POW_NEEDLE, pow + " // voxy: LOD AO pow parity"));
        if (!loggedApply) {
            loggedApply = true;
            Logger.info(String.format(Locale.ROOT,
                    "[Metal-LODTEST] vx LOD AO parity ON (deferred vx AmbientOcclusion radius 1.5->%.2f, pow 2->%.2f; "
                    + "BSL's LOD SSAO blacked out 1-block plant crosses and LOD step faces; VOXY_VX_LOD_AO_PARITY=0 reverts, "
                    + "_RADIUS/_POW tune) in '%s'", RADIUS, POW, pathLabel));
        }
        return ImmutableList.copyOf(out);
    }
}
