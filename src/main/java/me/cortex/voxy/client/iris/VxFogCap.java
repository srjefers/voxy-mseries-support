package me.cortex.voxy.client.iris;

import com.google.common.collect.ImmutableList;
import me.cortex.voxy.common.Logger;

import java.util.ArrayList;
import java.util.Locale;

/**
 * Fog-distance cap for vx (LOD) pixels (VOXY_VX_FOG_CAP=0 reverts, value =
 * multiple of the vanilla far plane, default 1.0).
 *
 * Root cause (2026-07-14, on-device weather A/B: clear-noon = textured to the
 * horizon, /weather rain = the whole far ring washed to fog colour, /weather
 * clear = textured again — trigger and un-trigger on command): BSL applies its
 * atmospheric fog to LOD pixels in deferred1's {@code vxZ < 1.0} branch with
 * the SAME formula as near terrain, and the density scales with weather
 * (x(10*rainStrength^2+1), xFOG_DENSITY_WEATHER) and time of day
 * (/mix(1/FOG_DENSITY_NIGHT, 1, clearDay)). Vanilla chunks end before the
 * curve saturates, so packs never tuned for 300-500+ blocks — at LOD
 * distances rain/dusk fog reaches ~1.0 and replaces the terrain colour
 * entirely: the intermittent "washed pale-blue far LODs" the user
 * photographed (relief intact, colour gone), flipping as weather/time cycle.
 *
 * Fix: clamp the view position the pack's Fog() sees for LOD pixels to the
 * vanilla far plane — a far LOD is never MORE fogged than the farthest real
 * chunk would be. Near-field weather mood is untouched (real terrain and
 * anything inside `far` fogs exactly as before).
 *
 * The deferred1 patch is applied to Iris's include-processed source lines
 * (MixinIncludeProcessor) — content-anchored inside the vx branch because the
 * Fog call string appears 3x in BSL's deferred1 (:433 vanilla, :451 vx,
 * :470 DH); a global replace would alter vanilla fog too. The LOD-water
 * counterpart lives in MetalVxResolvePass (voxy_translucent rewrite, live
 * uVxRingCull uniform). Single-line replacement keeps the pack's line
 * numbering intact for shader-error mapping.
 */
public final class VxFogCap {
    private static final float CAP_F = parseEnvF();
    private static boolean loggedApply, loggedMiss;

    private static float parseEnvF() {
        String v = System.getenv("VOXY_VX_FOG_CAP");
        if (v == null || v.isBlank()) return 1.0f;
        try {
            return Float.parseFloat(v.trim());
        } catch (NumberFormatException e) {
            return 1.0f;
        }
    }

    public static float capFactor() {
        return CAP_F;
    }

    public static boolean enabled() {
        if (CAP_F <= 0f) return false;
        try {
            return me.cortex.voxy.client.core.gpu.RenderBackendFactory.get().getType()
                    != me.cortex.voxy.client.core.gpu.BackendType.OPENGL;
        } catch (Throwable t) {
            return false;
        }
    }

    private static final String DEFERRED_ANCHOR = "Fog(color.rgb, viewPos.xyz);";
    private static final String VX_BRANCH_ANCHOR = "} else if (vxZ < 1.0) {";

    /**
     * Patch the vx-branch Fog call in an include-processed source (deferred1).
     * Returns the patched lines, or null when this source isn't the target /
     * is already patched / the anchors drifted (fail-open: pack renders as
     * before, one loud warn).
     */
    public static ImmutableList<String> patchDeferred(ImmutableList<String> lines) {
        return patchDeferred(lines, "?");
    }

    public static ImmutableList<String> patchDeferred(ImmutableList<String> lines, String pathLabel) {
        if (lines == null) return null;
        int vxLine = -1;
        for (int i = 0; i < lines.size(); i++) {
            String l = lines.get(i);
            if (l.contains("voxyFogPos")) return null; // already patched
            if (l.contains(VX_BRANCH_ANCHOR)) {
                vxLine = i;
                break;
            }
        }
        if (vxLine < 0) return null; // not deferred1 (or no vx support) — not our target
        for (int i = vxLine + 1; i < lines.size(); i++) {
            String l = lines.get(i);
            // Never run past the vx branch into the Distant Horizons branch.
            if (l.contains("DISTANT_HORIZONS") || l.contains("dhZ")) break;
            if (l.contains(DEFERRED_ANCHOR)) {
                String capped = String.format(Locale.ROOT,
                        "{ vec3 voxyFogPos = viewPos.xyz; float voxyFogLen = length(voxyFogPos);"
                        + " float voxyFogMax = max(far * %.3f, 64.0);"
                        + " if (voxyFogLen > voxyFogMax) voxyFogPos *= voxyFogMax / voxyFogLen;"
                        + " Fog(color.rgb, voxyFogPos); }", CAP_F);
                ArrayList<String> out = new ArrayList<>(lines);
                out.set(i, l.replace(DEFERRED_ANCHOR, capped));
                if (!loggedApply) {
                    loggedApply = true;
                    Logger.info(String.format(Locale.ROOT,
                            "[Metal-LODTEST] vx fog cap ON (%s vx-branch Fog clamped to"
                            + " %.2f*far — rain/night fog saturated at LOD distances and washed"
                            + " the far ring to sky; weather A/B confirmed trigger+untrigger);"
                            + " VOXY_VX_FOG_CAP=0 reverts, value tunes the cap multiple",
                            pathLabel, CAP_F));
                }
                return ImmutableList.copyOf(out);
            }
        }
        if (!loggedMiss) {
            loggedMiss = true;
            Logger.warn("[Metal-LODTEST] vx fog cap: vx branch found but no Fog anchor in '"
                    + pathLabel + "' (other files may still patch — one-shot log);"
                    + " if this is deferred1 itself the cap is inert there");
        }
        return null;
    }

    private VxFogCap() {}
}
