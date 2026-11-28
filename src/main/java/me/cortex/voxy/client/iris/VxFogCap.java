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
 *
 * 2026-11-28 H23 (the circle at the RD 16 border, on the sea and on land;
 * A/B on device, H23/agua-* runs): the clamp measured the 3D view distance
 * against `far`, a horizontal radius. Sodium's chunks get the pack's fog at
 * their real distance, so from a camera h blocks above the ground the LOD
 * side of the border got fog(far) while the last real chunk got
 * fog(sqrt(far^2 + h^2)): a step in fog right at the border, growing with
 * height (99 blocks over the sea: LOD water 1.4 L* darker at 272 blocks,
 * 3.5 L* at 400; VOXY_VX_FOG_CAP=0 brought it to +0.2). And past the border
 * the fog stopped growing at once, a kink where the pack's own fog keeps
 * rising. Now the cap is horizontal (the ray is scaled to where it crosses
 * the far radius, so the border pixel gets exactly the fog of the last real
 * chunk) and soft: past the radius the horizontal distance rolls off as
 * far + W*(1 - exp(-(d - far)/W)), W = KNEE*far, same value and slope at the
 * border, never more than far + W. Rain/night fog still stops growing,
 * at far + W instead of far.
 * VOXY_VX_FOG_CAP_XZ=0 restores the old 3D hard clamp (text byte-identical),
 * VOXY_VX_FOG_KNEE tunes W (multiple of the cap distance, default 1.0, 0 =
 * hard horizontal clamp).
 */
public final class VxFogCap {
    private static final float CAP_F = parseEnvF("VOXY_VX_FOG_CAP", 1.0f);
    private static final boolean XZ = !"0".equals(System.getenv("VOXY_VX_FOG_CAP_XZ"));
    private static final float KNEE = Math.max(parseEnvF("VOXY_VX_FOG_KNEE", 1.0f), 0f);
    private static boolean loggedApply, loggedMiss;

    private static float parseEnvF(String name, float def) {
        String v = System.getenv(name);
        if (v == null || v.isBlank()) return def;
        try {
            float f = Float.parseFloat(v.trim());
            return Float.isNaN(f) ? def : f;
        } catch (NumberFormatException e) {
            return def;
        }
    }

    public static float capFactor() {
        return CAP_F;
    }

    /** H23: horizontal soft cap on (false = the old 3D hard clamp). */
    public static boolean horizontal() {
        return XZ;
    }

    /** Short description of the active cap for the [Metal-LODTEST] lines. */
    public static String describe() {
        if (!XZ) return "3D hard clamp (VOXY_VX_FOG_CAP_XZ=0)";
        return KNEE > 0f
                ? String.format(Locale.ROOT, "horizontal, soft knee W=%.2f*cap (VOXY_VX_FOG_CAP_XZ=0 restores the"
                        + " 3D hard clamp, VOXY_VX_FOG_KNEE tunes W, 0 = hard)", KNEE)
                : "horizontal, hard (VOXY_VX_FOG_KNEE=0; VOXY_VX_FOG_CAP_XZ=0 restores the 3D clamp)";
    }

    /**
     * H23: one GLSL statement block (single line) that calls the pack's Fog() on {@code colour} with
     * {@code viewPos} pulled in along its ray so its horizontal distance never exceeds the soft cap.
     * {@code capExpr} is the cap distance in blocks (a GLSL float expression), {@code sfx} keeps the
     * local names unique per site. Needs {@code gbufferModelViewInverse} in scope (deferred1: the
     * uniform; voxy_translucent: the pack's alias of vxModelViewInv). The names start with voxyFogPos
     * so {@link #patchDeferred} still sees the line as patched.
     */
    public static String horizontalCapCall(String colour, String viewPos, String capExpr, String sfx) {
        String p = "voxyFogPos" + sfx, h = "voxyFogH" + sfx, m = "voxyFogMax" + sfx;
        String roll;
        if (KNEE > 0f) {
            String w = "voxyFogW" + sfx;
            roll = String.format(Locale.ROOT,
                    " float %4$s = %3$s * %5$.3f; %1$s *= (%3$s + %4$s * (1.0 - exp((%3$s - %2$s) / %4$s))) / %2$s;",
                    p, h, m, w, KNEE);
        } else {
            roll = String.format(Locale.ROOT, " %1$s *= %3$s / %2$s;", p, h, m);
        }
        return "{ vec3 " + p + " = " + viewPos + "; float " + m + " = " + capExpr + ";"
                + " float " + h + " = length((mat3(gbufferModelViewInverse) * " + p + ").xz);"
                + " if (" + h + " > " + m + ") {" + roll + " }"
                + " Fog(" + colour + ", " + p + "); }";
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
                String capped = XZ
                        ? horizontalCapCall("color.rgb", "viewPos.xyz",
                                String.format(Locale.ROOT, "max(far * %.3f, 64.0)", CAP_F), "")
                        : String.format(Locale.ROOT,
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
                            + " VOXY_VX_FOG_CAP=0 reverts, value tunes the cap multiple; cap %s",
                            pathLabel, CAP_F, describe()));
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
