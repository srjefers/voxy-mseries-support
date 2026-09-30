package me.cortex.voxy.client.core.util;

import me.cortex.voxy.client.iris.IrisVoxyRenderPipelineData;
import me.cortex.voxy.common.Logger;

/** Legacy BSL-specific rewrites. Generic material contracts never enter this path. */
final class BslMaterialCompatibility {
    private BslMaterialCompatibility() {}
    static boolean enabled() { return Boolean.getBoolean("voxy.bslCompatibility"); }

    /** GLSL struct mirror — must match quads.frag's VoxyFragmentParameters verbatim. */
    private static final String PARAMS_STRUCT = """
            struct VoxyFragmentParameters {
                vec4 sampledColour;
                vec2 tile;
                vec2 uv;
                uint face;
                uint modelId;
                vec2 lightMap;
                vec4 tinting;
                uint customId;
            };

            void voxy_emitFragment(VoxyFragmentParameters parameters);
            """;

    // Sky-mirror dim factor for LOD water kept as near fallback inside the
    // masked near-cull ring — see the anchor patch in assembleFragment.
    // 1 (or >=1) disables the dim entirely. With V2 (default) the value is
    // PERCEIVED dim (the square is what gets injected, cancelling the pack's
    // final sqrt encode).
    private static final float NEAR_MIRROR_DIM = parseEnvFloat("VOXY_VX_NEAR_MIRROR_DIM", 0.45f);

    // VOXY_VX_MIRROR_DIM_V2=0 reverts the near-fallback mirror dim to the old
    // math (linear factor, ramp start at 0.75*cull) for A/B. The old math had
    // two verified defects: the pack's ALPHA_BLEND==0 sqrt encode halved the
    // dim's perceived strength, and the 0.75*cull ramp start released the dim
    // across the outer band where most kept-fallback water actually lives.
    private static final boolean MIRROR_DIM_V2 = !"0".equals(System.getenv("VOXY_VX_MIRROR_DIM_V2"));

    // ---- Round-9 water ring parity (pale near-fallback water quads) ----
    // Inside the near-cull ring the ONLY water pixels this resolve shades are the
    // kept-fallback quads: ghost-culled water over BUILT sections emits alpha 0 and
    // dies at the host's vxAlbedo.a<=0.001 discard, and real BSL water is
    // gbuffers_water, never this pass — so a water+ring gate selects EXACTLY the
    // pale quads with no per-pixel flag. The pale composite is 0.706*surface +
    // 0.294*floor: the whole-pixel dim reaches 100% of the surface at EVERY angle
    // (the mirror dim was fresnel-capped to <=10% top-down — the exhausted lever),
    // and the alpha lift cuts the bright Mipper-floor bleed from ~29% to ~15%
    // (~= real water's 29% of a floor that is ~2x darker). The fallback surface
    // math is near-identical to gbuffers_water (waves + GGX both present in
    // voxy_translucent), so the ALPHA LIFT is the primary lever and the dim mainly
    // compensates the un-dimmable residual floor bleed.
    // VOXY_VX_WATER_RING_PARITY=0 kills everything (mirror dim resumes; assembled
    // shader text byte-identical to today).
    static final boolean WATER_RING_PARITY =
            !"0".equals(System.getenv("VOXY_VX_WATER_RING_PARITY"));
    // PERCEIVED whole-pixel dim on in-ring LOD water (injected SQUARED into linear
    // colour to cancel the pack's ALPHA_BLEND==0 sqrt encode — the c0127d91 V2
    // lesson). >=1 disables the rgb dim (alpha-lift-only mode). TUNING DIRECTION:
    // if in-ring water reads TOO DARK at grazing/sunset, RAISE this toward 1.0
    // (less dim) — NOT lower.
    private static final float WATER_RING_DIM = parseEnvFloat("VOXY_VX_WATER_RING_DIM", 0.75f);
    // In-ring alpha floor via max(albedo.a, tgt) so the grazing fresnel lift is
    // never reduced. <=0 disables (dim-only mode).
    private static final float WATER_RING_ALPHA = parseEnvFloat("VOXY_VX_WATER_RING_ALPHA", 0.85f);
    // Ramp start as a fraction of the live cull radius. Pre-agreed graded response
    // to a residual pale rim in the 471..496 outer band: 0.90 (slight seam risk).
    private static final float WATER_RING_RAMP = parseEnvFloat("VOXY_VX_WATER_RING_RAMP", 0.95f);
    // Optional: release the rgb dim as fresnel rises (full strength top-down where
    // the bug lives; restores the bright mirror at grazing). Default OFF.
    private static final boolean WATER_RING_FRESNEL = "1".equals(System.getenv("VOXY_VX_WATER_RING_FRESNEL"));
    // Grazing arm of the ring parity (VOXY_VX_RING_MIRROR_DIM, >=1 disables just
    // this arm; the assembled text is then byte-identical to the parity-only
    // build). The parity's whole-pixel dim lands AFTER the pack's fresnel mix,
    // so at grazing (fresnel -> 1) a kept-fallback quad is ~100% undimmed
    // skyReflection — the pale panes the mirror dim (5bab4da4) fixed and the
    // parity (cac60d5a) un-fixed by subsuming that dim. This re-dims
    // skyReflection at the old anchor but on the parity's LIVE ring gate and
    // metric (no slant aerial release, no baked-radius staleness — the two
    // defects that got the old dim replaced). PERCEIVED value, injected SQUARED;
    // valid by construction because the parity anchor IS the ALPHA_BLEND==0
    // sqrt-encode line. 0.60 x the parity's 0.75 whole-pixel dim = the old
    // user-verified 0.45 combined at grazing.
    // 2026-07-16 round-14 FALSIFIED as the pane fix: the tinted probe run showed
    // the panes never enter the resolve water branch (untinted), so this dim
    // cannot touch them. Default 1.0 = arm off / byte-identical text; the env
    // re-enables it for A/B only.
    private static final float RING_MIRROR_DIM = parseEnvFloat("VOXY_VX_RING_MIRROR_DIM", 1.0f);
    // Magenta engagement tint (VOXY_VX_SEAFLOOR_DEBUG house style): one user run
    // distinguishes "never engages" from "engages but insufficient".
    private static final boolean WATER_RING_DEBUG = "1".equals(System.getenv("VOXY_VX_WATER_RING_DEBUG"));
    // Ring margin parsed once (env is process-constant); RD is read per frame.
    private static final float RING_MARGIN = parseEnvFloat("VOXY_TRANS_NEAR_CULL_MARGIN",
            !"0".equals(System.getenv("VOXY_TRANS_NEAR_CULL_XZ")) ? 16f : 48f);
    // 2026-07-15 border-band panes: mirrors MDICSectionRenderer's FULLRING mode
    // (masked cull radius extended to the full vanilla border so the mask gate
    // is reachable in the border overlap ring). Same env pair so the cull and
    // every ring-gated dim move together.
    // 2026-07-16 round-14 FALSIFIED as the pane fix (panes persisted with it
    // live and untinted in the probe run) — now opt-in via
    // VOXY_TRANS_NEAR_CULL_FULLRING=1, default back to the -margin radius.
    private static final boolean RING_FULLRING =
            !"0".equals(System.getenv("VOXY_TRANS_NEAR_CULL_MASKED"))
            && "1".equals(System.getenv("VOXY_TRANS_NEAR_CULL_FULLRING"));
    // Hold the ring dims (parity + grazing mirror dim) at FULL strength all the
    // way to the cull radius and release them across the RELEASE blocks BEYOND
    // it, instead of fading them out across the last 5% INSIDE the ring. The
    // old inside-fade released the dims exactly over the border band where the
    // kept-fallback panes live (the 0.95*cull..cull band); the release belt
    // beyond the border is pure-LOD territory, so the fallback tone graduates
    // into the pack's bright far-water convention where there is no vanilla
    // water left to mismatch. VOXY_VX_RING_RELEASE tunes the belt width in blocks.
    // 2026-07-16 round-14 FALSIFIED as the pane fix, and the release belt dimmed
    // the first 48 blocks of continuous LOD water BEYOND the border — the
    // user-reported "shaded band that interrupts the water" seam at the
    // vanilla/LOD boundary. Now opt-in via VOXY_VX_RING_HOLD=1; default back to
    // the inside 0.95*cull..cull fade (no dim outside the ring).
    private static final boolean RING_HOLD = "1".equals(System.getenv("VOXY_VX_RING_HOLD"));
    private static final float RING_RELEASE = parseEnvFloat("VOXY_VX_RING_RELEASE", 48f);

    /** LIVE near-cull ring radius; mirrors MDICSectionRenderer's voxyLodParams2.x
     *  (FULLRING: max(max(rd,32), 64) = 512 at RD 32; legacy: -margin = 496) so a
     *  mid-session render-distance change retunes the parity ramp the same frame
     *  — the mirror dim's documented baked-radius staleness defect does not recur
     *  here. Any future change to MDIC's formula must be mirrored here
     *  (intentionally identical). Consumers: parity/grazing-dim ring gates and
     *  the LOD-water fog cap (cap = uVxRingCull + margin ~= the vanilla far
     *  plane; FULLRING overshoots it by the margin — negligible for a fog clamp). */
    static float ringCullNow() {
        float rdBlocks;
        try {
            rdBlocks = Math.max(net.minecraft.client.Minecraft.getInstance()
                    .gameRenderer.getRenderDistance(), 32f);
        } catch (Throwable t) {
            rdBlocks = 192f;
        }
        return RING_FULLRING ? Math.max(rdBlocks, 64f) : Math.max(rdBlocks - RING_MARGIN, 64f);
    }

    private static float parseEnvFloat(String name, float def) {
        String v = System.getenv(name);
        if (v == null || v.isBlank()) return def;
        try {
            return Float.parseFloat(v.trim());
        } catch (NumberFormatException e) {
            return def;
        }
    }

    static String assemble(IrisVoxyRenderPipelineData data, boolean translucent) {
        String patchText = translucent ? data.translucentFragPatch() : data.opaqueFragPatch();
        if (patchText == null) {
            return null;
        }
        if (data.getSsboSet() != null && data.getSsboSet().layout() != null
                && !data.getSsboSet().layout().isBlank()) {
            Logger.warn("MetalVxResolvePass: pack declares SSBOs (GL 4.3) — not resolvable on Apple GL 4.1");
            return null;
        }

        StringBuilder sb = new StringBuilder();
        sb.append("#version 410 core\n\n");
        // The pack text is pre-expanded by Iris's include graph but may carry
        // legacy sampler calls; core-profile aliases.
        sb.append("#define texture2D texture\n");
        sb.append("#define texture3D texture\n");
        sb.append("#define texture2DLod textureLod\n");
        sb.append("#define shadow2D texture\n\n");

        if (data.getUniforms() != null) {
            // Same wrap as the GL host (IrisVoxyRenderPipeline:204-208) MINUS
            // the binding qualifier — assigned via glUniformBlockBinding.
            sb.append("layout(std140) uniform ShaderUniformBindings ")
                    .append(data.getUniforms().layout())
                    .append(";\n\n");
        }

        if (data.samplerDecls != null) {
            // Regenerated binding-free (the ImageSet layout string uses
            // layout(binding = BASE+n) — GL 4.2+). Units assigned post-link
            // with glUniform1i in declaration order.
            data.samplerDecls.forEach((name, type) ->
                    sb.append("uniform ").append(type).append(' ').append(name).append(";\n"));
            sb.append('\n');
        }

        sb.append("""
                uniform sampler2DRect uVxAlbedo;
                uniform sampler2DRect uVxTint;
                uniform sampler2DRect uVxMisc;
                uniform sampler2DRect uVxDepth;
                uniform int uVxDepthIsWindow;
                """);
        if (WATER_RING_PARITY || me.cortex.voxy.client.iris.VxFogCap.enabled()) {
            // Live near-cull ring radius for the water ring parity injection
            // AND the vx fog cap's live far-plane proxy (ring + margin ==
            // rdBlocks). Declared only while a consumer env is on so killing
            // both keeps the assembled shader text byte-identical
            // (kill-switch contract). Unused in the opaque program ->
            // optimized out -> location -1 (guarded).
            sb.append("uniform float uVxRingCull;\n");
        }
        sb.append('\n');

        sb.append(PARAMS_STRUCT).append('\n');

        // Sky-light floor (VOXY_VX_SKY_FLOOR=0..15, default 0 = off). The LOD bake
        // under-propagates sky light for many far/mipped sections (CPU plane dump:
        // ~56% of opaque pixels read sky=0 at noon, the count rising as the world
        // settles). BSL's GetLighting squares sky light (skylightSqr = lightmap.y^2)
        // and gates ALL sun/scene lighting on it, so sky=0 -> near-black; the normal
        // (non-vx) path tolerates the same data because MC's lightmap texture has a
        // non-zero daytime floor. This clamps the resolve's decoded sky light up to a
        // floor so BSL can light the LODs comparably. floor=15 is the confirm-the-cause
        // setting (everything should go bright); a moderate floor is the candidate fix.
        // 2026-07-03: DEFAULT 12/15 (was 0/off). Mechanism located for the
        // handoff's hypothesis A: Mipper's representative-voxel pick carries
        // the underwater-attenuated sky light of whichever corner voxel
        // survives the mip — per-cell variance shows up as chunk-aligned
        // brightness squares once BSL scales its water sky reflection by
        // skyLight^2, and the value trends to 0 at high LOD (flat dark far
        // water). A moderate floor removes both without flattening block
        // light. Water-scoped in the default trans-only material mode
        // (resolveTranslucentOnly runs only the translucent program).
        // VOXY_VX_SKY_FLOOR=0 disables; =0..15 overrides.
        float skyFloor = 12.0f / 15.0f;
        String skyFloorEnv = System.getenv("VOXY_VX_SKY_FLOOR");
        if (skyFloorEnv != null) {
            try { skyFloor = Math.max(0, Math.min(15, Integer.parseInt(skyFloorEnv.trim()))) / 15.0f; }
            catch (NumberFormatException ignored) {}
        }
        String skyFloorLine = skyFloor > 0.0f
                ? String.format("vxLight.y = max(vxLight.y, %.5f);\n", skyFloor)
                : "";
        if (skyFloor > 0.0f) {
            Logger.info("MetalVxResolvePass: VOXY_VX_SKY_FLOOR active, sky light floored to " + skyFloor
                    + " (" + skyFloorEnv + "/15)");
        }

        // Water-scoped sky-light MAX (VOXY_VX_WATER_SKY_MAX=0 reverts). Mipper's
        // representative-voxel mip carries one corner's underwater-attenuated sky nibble
        // verbatim, measured live as BIMODAL {0,15} per water cell. BSL squares the value
        // into waterSkyOcclusion (skyReflection *= lightmap.y^2), so even the 12/15 floor
        // leaves a 0.672-vs-1.0 reflection-brightness STEP between neighbouring chunks —
        // the chunk-aligned lighter/darker panels riding the water surface, strongest at
        // grazing angles where the fresnel weight peaks. A distant LOD water TOP surface
        // is by construction sky-exposed, so max — not the rep-voxel — is the correct mip
        // semantics for water; scoping by customId keeps other translucents on the floor
        // and leaves Mipper (common code, persisted LODs, GL byte-identity) untouched.
        // 0.96875 = the sky-15 decode ((15*16+8)/256); BSL's lightmap remap turns it into
        // exactly 1.0.
        boolean waterSkyMax = !"0".equals(System.getenv("VOXY_VX_WATER_SKY_MAX"));
        String waterSkyMaxLine = waterSkyMax
                ? "if ((vxCustomId / 100u) == 200u || (vxCustomId / 100u) == 204u) vxLight.y = max(vxLight.y, 0.96875);\n"
                : "";
        Logger.info("[Metal-LODTEST] vx water sky-light max " + (waterSkyMax ? "ON" : "OFF")
                + " (water customId -> sky=15 in the resolve, kills chunk-step reflection squares);"
                + " VOXY_VX_WATER_SKY_MAX=0 reverts");

        // Diagnostic coverage/id visualizer (VOXY_VX_ID_DEBUG=1). The far LOD
        // water band stays dark with SSR off AND shows no tint under the
        // skyReflection magenta discriminant — either those pixels never take
        // the pack's water branch (customId != 200xx/204xx at high mips) or
        // the trans resolve never covers them at all (we'd be seeing the
        // opaque seafloor). Read the TRUE id to pick a flat debug albedo
        // (magenta = water id, yellow = anything else), then hand the pack a
        // NEUTRAL id so it renders plain lit albedo — no water branch, no
        // fresnel replacing the albedo at grazing. Three outcomes: magenta
        // band = coverage+id OK (hunt multipliers); yellow band = id degraded
        // at high mips; unchanged dark band = no trans coverage there.
        // v2: an albedo-level debug colour was washed out by the pack's
        // GetLighting+Fog at LOD distances (no magenta visible anywhere even
        // though translucent=3077 draws/frame). Rewrite the FINAL write
        // instead (gbufferData0 = albedo, after lighting/fog/encode) so the
        // flat colour reaches colortex16 verbatim — see the patchText rewrite
        // below.
        boolean idDebug = "1".equals(System.getenv("VOXY_VX_ID_DEBUG"));
        String idDebugLine = "";

        sb.append("""
                vec4 vx_fragCoord;

                void main() {
                    ivec2 vxSz = textureSize(uVxDepth);
                    vec2 vxTexel = vec2(gl_FragCoord.x, float(vxSz.y) - gl_FragCoord.y);
                    vec3 vxDEnc = texture(uVxDepth, vxTexel).rgb;
                    float vxD = dot(vxDEnc, vec3(1.0, 1.0 / 255.0, 1.0 / 65025.0));
                    if (vxD <= 0.0 || vxD >= 0.9999999) discard;
                    vec4 vxAlbedo = texture(uVxAlbedo, vxTexel);
                    if (vxAlbedo.a <= 0.001) discard;
                    vec4 vxTint = texture(uVxTint, vxTexel);
                    vec4 vxMisc = texture(uVxMisc, vxTexel) * 255.0;
                    uint vxMr = uint(vxMisc.r + 0.5);
                    uint vxMg = uint(vxMisc.g + 0.5);
                    uint vxFace = (vxMr >> 1u) & 7u;
                    vec2 vxLight = vec2(
                        (float(vxMr >> 4u) * 16.0 + 8.0) / 256.0,
                        (float(vxMg >> 4u) * 16.0 + 8.0) / 256.0);
                    uint vxCustomId = uint(vxMisc.b + 0.5) | (uint(vxMisc.a + 0.5) << 8u);
                    __SKY_FLOOR____WATER_SKY_MAX____ID_DEBUG__float vxWz = (uVxDepthIsWindow == 1) ? vxD : vxD * 0.5 + 0.5;
                    gl_FragDepth = vxWz;
                    vx_fragCoord = vec4(gl_FragCoord.xy, vxWz, 1.0);
                    voxy_emitFragment(VoxyFragmentParameters(
                        vxAlbedo, vec2(0.0), vec2(0.0), vxFace, 0u, vxLight, vxTint, vxCustomId));
                }

                #define gl_FragCoord vx_fragCoord
                """.replace("__SKY_FLOOR__", skyFloorLine)
                   .replace("__WATER_SKY_MAX__", waterSkyMaxLine)
                   .replace("__ID_DEBUG__", idDebugLine));

        // Disable the pack's screen-space reflection inside the RESOLVE only
        // (VOXY_VX_NO_SSR=0 restores). At our SOLID-head hook SSR is a binary
        // artifact generator over LOD water: deferred1's voxy z-patch makes
        // colortex5.a=1 over every LOD pixel (incl. the seafloor UNDER the
        // water), and the raytrace's voxy vxDepthTexOpaque fallback lets
        // upward reflection rays "hit" geometry behind/below the reflector —
        // so grazing rays almost always register a sloppy hit and decode
        // pow(ct5*2, 8) of the dark LOD scene (~0.03-0.12 linear = the flat
        // dark far-water strip), while rays that exit the screen flip per
        // LOD quad per frame (dithered march) to the bright analytic sky arm
        // (the strobing light-cyan parallelogram panels). Genuine BSL far
        // water always takes the analytic sky arm (no voxy fallback => rays
        // beyond real depth never hit), so forcing the miss path converges
        // LOD water to the pack's own far-water convention. reflectionMask=0
        // also makes the specular factor identical to the pack's miss path.
        boolean noSsr = !"0".equals(System.getenv("VOXY_VX_NO_SSR"));
        if (translucent && noSsr) {
            String needle = "reflection = SimpleReflection(viewPos, newNormal, dither, reflectionMask);";
            boolean found = patchText.contains(needle);
            if (found) {
                patchText = patchText.replace(needle,
                        "reflectionMask = 0.0; // voxy resolve: SSR disabled -> analytic sky arm (VOXY_VX_NO_SSR=0 restores)");
            }
            Logger.info("[Metal-LODTEST] vx resolve SSR " + (found
                    ? "DISABLED (SimpleReflection call replaced, water mirror = analytic sky+clouds)"
                    : "rewrite FAILED (SimpleReflection needle not found — pack text drifted, SSR still live)")
                    + "; VOXY_VX_NO_SSR=0 restores the pack's SSR");
        }

        // Near-fallback mirror dim (VOXY_VX_NEAR_MIRROR_DIM, =1 disables). The
        // masked near-cull (VOXY_TRANS_NEAR_CULL_MASKED) keeps LOD water INSIDE
        // the MC render ring over Sodium sections that aren't built yet; with
        // SSR off those pixels take the analytic sky arm at full strength plus
        // the water sky-light MAX above — a flat bright sky-mirror tone that
        // reads as pale gray patches against BSL's near water. Dim skyReflection
        // for pixels whose view distance is inside the near-cull radius so the
        // kept fallback approximates the surrounding BSL tone; full strength
        // returns past the ring where LOD water is the only water. Anchored on
        // the final sky/SSR mix, where both skyReflection and viewPos (decoded
        // LOD depth via vx_fragCoord) are in scope. The cull distance mirrors
        // MDICSectionRenderer's voxyLodParams2.x computation but is BAKED at
        // shader build — a render-distance change mid-session won't retune the
        // ramp until the vx programs rebuild (shader reload / relaunch).
        if (translucent) {
            String nearCullEnv = System.getenv("VOXY_TRANS_NEAR_CULL");
            boolean nearCullOn = nearCullEnv == null || !"0".equals(nearCullEnv.trim());
            // Round-9 water ring parity: decide injectability FIRST (needle presence
            // included) so pack-text drift falls back to the shipped mirror dim
            // instead of silently disabling BOTH dims (house inert-on-drift rule).
            // Anchor = the pack's final sqrt encode: unique in BSL v10.1.3
            // voxy_translucent (:401), inside the inner block where water/viewPos/
            // worldPos/fresnel/albedo are all live, after the fresnel mix and Fog.
            // Iris pre-expands every #if before we see the text, so under
            // ALPHA_BLEND!=0 the line is textually ABSENT -> loud FAILED here.
            String parityNeedle = "albedo.rgb = sqrt(max(albedo.rgb, vec3(0.0)));";
            boolean parityWanted = WATER_RING_PARITY && nearCullOn
                    && (WATER_RING_DIM < 1.0f || WATER_RING_ALPHA > 0.0f);
            boolean parityInjectable = parityWanted && patchText.contains(parityNeedle);
            if (parityWanted && !parityInjectable) {
                Logger.warn("[Metal-LODTEST] vx water ring parity rewrite FAILED"
                        + " (sqrt-encode anchor not found — pack text drifted, or the pack"
                        + " runs ALPHA_BLEND!=0 and Iris preprocessed the line away);"
                        + " VOXY_VX_WATER_RING_* inert, shipped mirror dim resumes");
            }
            if (!nearCullOn || NEAR_MIRROR_DIM >= 1.0f || parityInjectable) {
                Logger.info("[Metal-LODTEST] vx near-fallback mirror dim OFF ("
                        + (parityInjectable
                            ? "subsumed by water ring parity — one dim inside the ring;"
                              + " VOXY_VX_WATER_RING_PARITY=0 restores"
                            : (nearCullOn
                                ? "VOXY_VX_NEAR_MIRROR_DIM>=1"
                                : "near-cull disabled, no fallback ring")
                              + "); default dims the kept-fallback ring's sky mirror"
                              + " to a perceived 0.45")
                        + (parityInjectable ? ")" : ""));
            } else {
                String needle = "reflection.rgb = max(mix(skyReflection, reflection.rgb, reflection.a), vec3(0.0));";
                if (patchText.contains(needle)) {
                    // Same margin default as MDICSectionRenderer.TRANS_NEAR_CULL_MARGIN
                    // (16 in XZ mode, 48 for the legacy slant metric).
                    float margin = parseEnvFloat("VOXY_TRANS_NEAR_CULL_MARGIN",
                            !"0".equals(System.getenv("VOXY_TRANS_NEAR_CULL_XZ")) ? 16f : 48f);
                    float rdBlocks;
                    try {
                        rdBlocks = Math.max(net.minecraft.client.Minecraft.getInstance()
                                .gameRenderer.getRenderDistance(), 32f);
                    } catch (Throwable t) {
                        rdBlocks = 192f;
                    }
                    float cull = Math.max(rdBlocks - margin, 64f);
                    float dim = Math.max(NEAR_MIRROR_DIM, 0.0f);
                    // V2 math (VOXY_VX_MIRROR_DIM_V2=0 reverts for A/B): the dim
                    // multiplies LINEAR light but voxy_translucent sqrt-encodes
                    // the final colour (ALPHA_BLEND==0), so a linear 0.20 was
                    // perceived as ~0.45 — inject dim^2 so the env value means
                    // the PERCEIVED dim. And most kept-fallback water lives in
                    // the outer band just inside the cull radius (outer-ring
                    // Sodium sections rarely build), which the old 0.75*cull
                    // ramp start had already mostly released — hold full
                    // strength to 0.95*cull and only release in the last ~5%
                    // to avoid a hard pop at the cull boundary.
                    float injectedDim = MIRROR_DIM_V2 ? dim * dim : dim;
                    float rampStart = (MIRROR_DIM_V2 ? 0.95f : 0.75f) * cull;
                    // Locale.ROOT: a comma decimal separator would emit broken GLSL.
                    patchText = patchText.replace(needle, String.format(java.util.Locale.ROOT,
                            "skyReflection *= mix(%.4f, 1.0, smoothstep(%.1f, %.1f, length(viewPos))); ",
                            injectedDim, rampStart, cull) + needle);
                    Logger.info(String.format(java.util.Locale.ROOT,
                            "[Metal-LODTEST] vx near-fallback mirror dim ON (%s: skyReflection *= %.4f"
                            + " linear = ~%.2f perceived after the pack's sqrt encode, ramp %.0f..%.0f"
                            + " blocks; baked at shader build, RD change needs shader reload);"
                            + " VOXY_VX_NEAR_MIRROR_DIM=1 disables, VOXY_VX_MIRROR_DIM_V2=0 reverts"
                            + " to the old linear/0.75-ramp math",
                            MIRROR_DIM_V2 ? "V2 perceptual" : "V1 linear",
                            injectedDim, Math.sqrt(injectedDim), rampStart, cull));
                } else {
                    Logger.warn("[Metal-LODTEST] vx near-fallback mirror dim SKIPPED"
                            + " (reflection-mix anchor not found — pack text drifted; patch not applied);"
                            + " VOXY_VX_NEAR_MIRROR_DIM inert");
                }
            }
            if (parityInjectable) {
                // Metric must match quads.frag's near-cull metric (:212-218) /
                // MDICSectionRenderer statics (:234-247): RADIAL is gated on XZ.
                // worldPos is BSL camera-relative player space (GetWaterNormal
                // re-adds cameraPosition), so length(worldPos.xz) == the emitter's
                // radial XZ distance — unlike the mirror dim's slant length(viewPos),
                // which released early at aerial angles.
                boolean mXz = !"0".equals(System.getenv("VOXY_TRANS_NEAR_CULL_XZ"));
                boolean mRadial = mXz && !"0".equals(System.getenv("VOXY_TRANS_NEAR_CULL_RADIAL"));
                String metric = !mXz ? "length(viewPos)"
                        : (mRadial ? "length(worldPos.xz)"
                                   : "max(abs(worldPos.x), abs(worldPos.z))");
                float dim = Math.min(Math.max(WATER_RING_DIM, 0.05f), 1.0f);
                float dim2 = dim * dim; // env value = PERCEIVED (pack sqrt-encodes after)
                float aTgt = Math.min(WATER_RING_ALPHA, 0.98f);
                float ramp = Math.min(Math.max(WATER_RING_RAMP, 0.5f), 0.999f);
                // Ring fade shared by the parity and the grazing mirror dim so the
                // two release at exactly the same distance. HOLD (default) keeps
                // full strength to the cull radius and releases across the belt
                // BEYOND it (border-band fix — the old inside fade zeroed the dims
                // exactly over the border overlap ring where the panes live);
                // VOXY_VX_RING_HOLD=0 reverts to the inside 0.95*cull..cull fade.
                // Locale.ROOT: a comma decimal separator would emit broken GLSL.
                String ringFade = RING_HOLD
                        ? String.format(java.util.Locale.ROOT,
                                "smoothstep(uVxRingCull, uVxRingCull + %.1f, %s)",
                                Math.max(RING_RELEASE, 1f), metric)
                        : String.format(java.util.Locale.ROOT,
                                "smoothstep(uVxRingCull * %.3f, uVxRingCull, %s)", ramp, metric);
                StringBuilder inj = new StringBuilder();
                inj.append("if (water > 0.5) { float vxRingT = 1.0 - ").append(ringFade).append("; ");
                if (dim < 1.0f) {
                    String dimExpr = String.format(java.util.Locale.ROOT, "%.4f", dim2);
                    if (WATER_RING_FRESNEL) {
                        // Release the dim as fresnel rises: full strength top-down,
                        // bright mirror preserved at grazing. fresnel is in scope
                        // (inner block, post 0.98+0.02 remap when REFLECTION>0).
                        dimExpr = "mix(" + dimExpr + ", 1.0, clamp((fresnel - 0.05) * 4.0, 0.0, 1.0))";
                    }
                    inj.append("albedo.rgb *= mix(1.0, ").append(dimExpr).append(", vxRingT); ");
                }
                if (aTgt > 0.0f) {
                    inj.append(String.format(java.util.Locale.ROOT,
                            "albedo.a = mix(albedo.a, max(albedo.a, %.4f), vxRingT); ", aTgt));
                }
                if (WATER_RING_DEBUG) {
                    inj.append("albedo.rgb = mix(albedo.rgb, vec3(1.0, 0.0, 0.5), 0.5 * vxRingT); ");
                    // Positive control: far LOD water is ALWAYS the translucent
                    // resolve, so if the ring never engages (no magenta) but the
                    // injection is live, everything beyond the ring reads cyan.
                    // No cyan anywhere = the injected code isn't running at all.
                    inj.append("albedo.rgb = mix(albedo.rgb, vec3(0.0, 1.0, 1.0), 0.35 * (1.0 - vxRingT)); ");
                }
                inj.append("} ");
                patchText = patchText.replace(parityNeedle, inj + parityNeedle);
                Logger.info(String.format(java.util.Locale.ROOT,
                        "[Metal-LODTEST] vx water ring parity ON (water dim %.2f perceived -> *= %.4f"
                        + " linear%s, alpha lift -> max(a, %.2f), metric %s, fade %s,"
                        + " cull = LIVE uVxRingCull per frame — RD changes retune without reload);"
                        + " VOXY_VX_WATER_RING_PARITY=0 kills (mirror dim resumes);"
                        + " _DIM/_ALPHA/_RAMP tune — if in-ring water reads TOO DARK at grazing/sunset"
                        + " RAISE _DIM toward 1.0; _DEBUG=1 magenta-tints engagement",
                        dim, dim2, WATER_RING_FRESNEL ? " with fresnel release" : "",
                        aTgt, metric,
                        RING_HOLD
                            ? String.format(java.util.Locale.ROOT,
                                "HOLD to cull, release cull..cull+%.0f blocks (VOXY_VX_RING_HOLD=0 reverts)",
                                Math.max(RING_RELEASE, 1f))
                            : String.format(java.util.Locale.ROOT, "%.2f*cull..cull inside", ramp)));

                // Grazing arm (see RING_MIRROR_DIM): the parity above dims albedo
                // AFTER the pack's fresnel mix, so at grazing the pixel is still
                // ~100% undimmed skyReflection. Re-dim skyReflection at the old
                // mirror-dim anchor, gated on the same live uVxRingCull + metric.
                if (RING_MIRROR_DIM < 1.0f) {
                    String mirrorNeedle = "reflection.rgb = max(mix(skyReflection, reflection.rgb, reflection.a), vec3(0.0));";
                    if (patchText.contains(mirrorNeedle)) {
                        float mDim = Math.min(Math.max(RING_MIRROR_DIM, 0.05f), 1.0f);
                        float mDim2 = mDim * mDim; // env value = PERCEIVED (sqrt encode guaranteed by parityInjectable)
                        // Locale.ROOT: a comma decimal separator would emit broken GLSL.
                        patchText = patchText.replace(mirrorNeedle, String.format(java.util.Locale.ROOT,
                                "skyReflection *= mix(%.4f, 1.0, %s); ",
                                mDim2, ringFade) + mirrorNeedle);
                        Logger.info(String.format(java.util.Locale.ROOT,
                                "[Metal-LODTEST] vx ring grazing mirror dim ON (skyReflection *= %.4f"
                                + " linear = %.2f perceived in-ring, ~%.2f combined with the parity dim"
                                + " at grazing; metric %s, live uVxRingCull — RD changes retune without"
                                + " reload); VOXY_VX_RING_MIRROR_DIM=1 disables"
                                + " (VOXY_VX_NEAR_MIRROR_DIM stays subsumed by the parity)",
                                mDim2, mDim, mDim * (dim < 1.0f ? dim : 1.0f), metric));
                        if (WATER_RING_FRESNEL) {
                            Logger.warn("[Metal-LODTEST] vx ring: VOXY_VX_WATER_RING_FRESNEL=1 releases"
                                    + " the parity dim at grazing while the grazing mirror dim re-dims"
                                    + " skyReflection there — the two fight; set"
                                    + " VOXY_VX_RING_MIRROR_DIM=1 to keep the bright grazing mirror");
                        }
                    } else {
                        Logger.warn("[Metal-LODTEST] vx ring grazing mirror dim FAILED (reflection-mix"
                                + " anchor not found — pack text drifted, or REFLECTION=0 pre-expanded"
                                + " the block away, where no sky mirror exists anyway);"
                                + " VOXY_VX_RING_MIRROR_DIM inert");
                    }
                }
            }

            // vx fog cap, LOD-water half (see VxFogCap for the deferred1/opaque
            // half and the weather-A/B root cause): voxy_translucent applies the
            // pack's Fog() to LOD water with the same rain/night-scaled density
            // that washed the far opaque ring. Clamp the view position Fog sees
            // to the vanilla far plane (uVxRingCull + near-cull margin ==
            // rdBlocks, live per frame) so LOD water never fogs harder than the
            // farthest real chunk. VOXY_VX_FOG_CAP=0 keeps the text byte-identical.
            if (me.cortex.voxy.client.iris.VxFogCap.enabled()) {
                String fogNeedle = "Fog(albedo.rgb, viewPos);";
                if (patchText.contains(fogNeedle)) {
                    // Locale.ROOT: a comma decimal separator would emit broken GLSL.
                    patchText = patchText.replace(fogNeedle, String.format(java.util.Locale.ROOT,
                            "{ vec3 voxyFogPosT = viewPos; float voxyFogLenT = length(voxyFogPosT);"
                            + " float voxyFogMaxT = max(uVxRingCull + 16.0, 64.0) * %.3f;"
                            + " if (voxyFogLenT > voxyFogMaxT) voxyFogPosT *= voxyFogMaxT / voxyFogLenT;"
                            + " Fog(albedo.rgb, voxyFogPosT); }",
                            me.cortex.voxy.client.iris.VxFogCap.capFactor()));
                    Logger.info(String.format(java.util.Locale.ROOT,
                            "[Metal-LODTEST] vx fog cap ON for LOD water (voxy_translucent Fog"
                            + " clamped to %.2f*(uVxRingCull+16) live blocks);"
                            + " VOXY_VX_FOG_CAP=0 reverts", me.cortex.voxy.client.iris.VxFogCap.capFactor()));
                } else {
                    Logger.warn("[Metal-LODTEST] vx fog cap FAILED for LOD water (Fog anchor not"
                            + " found in voxy_translucent — pack text drifted); cap inert there");
                }
            }
        }

        // Probe arm (VOXY_VX_WATER_RING_DEBUG=1 only, no behavior otherwise):
        // yellow-tint water customIds that reach the OPAQUE resolve. The parity
        // fix assumes the pale near quads are kept-fallback TRANSLUCENT water;
        // if they light up yellow instead, they were classified into the opaque
        // LOD layer and voxy_translucent (parity included) never touches them.
        if (!translucent && WATER_RING_DEBUG) {
            String needle = "albedo.rgb = sqrt(max(albedo.rgb, vec3(0.0)));";
            if (patchText.contains(needle)) {
                patchText = patchText.replace(needle,
                        "if (blockID == 200 || blockID == 204) {"
                        + " albedo.rgb = mix(albedo.rgb, vec3(1.0, 1.0, 0.0), 0.6); } " + needle);
                Logger.info("[Metal-LODTEST] vx ring parity DEBUG: opaque-resolve water tint"
                        + " YELLOW armed (any yellow in-game = water customId classified into"
                        + " the OPAQUE LOD layer, outside voxy_translucent/parity reach)");
            } else {
                Logger.warn("[Metal-LODTEST] vx ring parity DEBUG: opaque sqrt-encode anchor"
                        + " not found — yellow probe inert");
            }
        }

        // Diagnostic: with SSR off, LOD water is still dark at grazing angles
        // where the analytic arm should return the bright horizon sky. Force
        // magenta right AFTER GetSkyColor but leave the cloud mix and the
        // occlusion/isEyeInWater multipliers LIVE, so one run discriminates
        // three ways: pure magenta = GetSkyColor guilty (bad uniforms or ray
        // direction); dimmed/patchy magenta = cloud/multipliers guilty; no
        // magenta = the fresnel mix path never runs for these pixels.
        if (translucent && "1".equals(System.getenv("VOXY_VX_SKYREF_DEBUG"))) {
            String needle = "skyReflection = GetSkyColor(skyRefPos, true);";
            boolean found = patchText.contains(needle);
            if (found) {
                patchText = patchText.replace(needle,
                        needle + " skyReflection = vec3(1.0, 0.0, 1.0); // voxy debug: magenta discriminant");
            }
            Logger.info("[Metal-LODTEST] vx resolve skyReflection debug " + (found
                    ? "ON (magenta after GetSkyColor; cloud/occlusion multipliers still live)"
                    : "rewrite FAILED (GetSkyColor needle not found — pack text drifted)"));
        }

        // Diagnostic coverage/id visualizer (VOXY_VX_ID_DEBUG=1): rewrite the
        // pack's final colortex16 write to a flat colour keyed on the TRUE
        // blockID, bypassing lighting/fog/encode entirely. Three outcomes for
        // the dark far-water band: magenta = resolve emits there with water
        // id and deferred1 composites it (the darkness is legit pack
        // lighting/fog — hunt inside GetLighting/Fog); yellow = coverage OK
        // but the id degraded at high mips; unchanged dark = the resolve
        // never emits there or deferred1 drops it (check VOXY_VX_DUMP_OUT
        // coverage).
        if (translucent && idDebug) {
            String needle = "gbufferData0 = albedo;";
            boolean found = patchText.contains(needle);
            if (found) {
                // v3 probe encoding: the yellow/magenta test said 100% non-water over
                // 19.47% coverage while the BOUND TEXTURES read back as 1.06% coverage /
                // 100% water — the shader sees different data than the texture objects
                // hold. Encode what the shader ACTUALLY sampled: R = albedo alpha (the
                // discard-gate input), G = water-id flag, B = customId low byte.
                patchText = patchText.replace(needle,
                        "gbufferData0 = vec4(parameters.sampledColour.a,"
                        + " (blockID == 200u || blockID == 204u) ? 1.0 : 0.0,"
                        + " float(parameters.customId & 255u) / 255.0, 1.0); // voxy ID debug v3");
            }
            Logger.info("[Metal-LODTEST] vx resolve ID DEBUG " + (found
                    ? "ON at FINAL WRITE v3 (R=sampled albedo alpha, G=waterId flag, B=customId low byte)"
                    : "rewrite FAILED (gbufferData0 needle not found — pack text drifted)"));
        }

        // Kill switch for the volumetric-cloud arm of the sky reflection: the
        // cloud march runs from GetReflectedCameraPos(worldPos,...) with LOD
        // worldPos hundreds of blocks out — if it returns dense dark samples
        // there, cloud.a≈1 replaces the bright sky with near-black.
        if (translucent && "1".equals(System.getenv("VOXY_VX_NO_CLOUDREF"))) {
            String needle = "skyReflection = mix(skyReflection, cloud.rgb, cloud.a);";
            boolean found = patchText.contains(needle);
            if (found) {
                patchText = patchText.replace(needle,
                        "// voxy resolve: cloud reflection disabled (VOXY_VX_NO_CLOUDREF)");
            }
            Logger.info("[Metal-LODTEST] vx resolve cloud reflection " + (found
                    ? "DISABLED (sky reflection = pure GetSkyColor)"
                    : "rewrite FAILED (cloud-mix needle not found — pack text drifted)"));
        }

        sb.append('\n').append(appleStrictCompat(patchText)).append('\n');
        return sb.toString();
    }

    /**
     * Apple's GLSL compiler rejects implicit int→uint conversions that
     * desktop drivers accept, so pack text needs targeted rewrites. Each
     * rule is narrow on purpose — log when applied so new packs' failures
     * stay diagnosable via the dumped sources.
     */
    private static String appleStrictCompat(String src) {
        String out = src;
        // BSL voxy_opaque/translucent: `(parameters.face & 1)` — uint & int.
        String fixed = out.replaceAll("(parameters\\.face\\s*&\\s*)1(?!u)", "$11u");
        if (!fixed.equals(out)) {
            Logger.info("MetalVxResolvePass: apple-compat applied uint-literal fix (face & 1 -> & 1u)");
            out = fixed;
        }
        // General: switch cases over uints written as int literals are fine
        // (case labels convert), but bare bitwise ops with .face need uint.
        fixed = out.replaceAll("(\\.face\\s*(?:&|\\||>>|<<)\\s*)(\\d+)(?![u\\d])", "$1$2u");
        if (!fixed.equals(out)) {
            Logger.info("MetalVxResolvePass: apple-compat applied generic face-bitop uint fix");
            out = fixed;
        }
        return out;
    }

    /**
     * Phase D spike (issue #11): assemble a translucent resolve that runs the
     * pack's {@code voxy_translucent} over the LOD water using ONLY the existing
     * Phase D-lite bridges — the premultiplied water colour ({@code uVxAlbedo})
     * and the side-channel depth ({@code uVxDepth}) — with CONSTANT material
     * attributes and a host-supplied water {@code customId} ({@code uVxWaterId}).
     * The full Phase C material g-buffer is NOT required: the translucent LOD
     * layer is ~entirely water, so a constant water id lets the pack's water
     * branch (waves/reflections/depth-grade) run. This answers whether
     * albedo+depth+water-id is enough before investing in the material g-buffer.
     *
     * @return assembled GLSL, or null when the pack can't be resolved this way.
     */
    static String spike(IrisVoxyRenderPipelineData data) {
        String patchText = data.translucentFragPatch();
        if (patchText == null) {
            return null;
        }
        if (data.getSsboSet() != null && data.getSsboSet().layout() != null
                && !data.getSsboSet().layout().isBlank()) {
            Logger.warn("MetalVxResolvePass spike: pack declares SSBOs (GL 4.3) — not resolvable on Apple GL 4.1");
            return null;
        }

        StringBuilder sb = new StringBuilder();
        sb.append("#version 410 core\n\n");
        sb.append("#define texture2D texture\n");
        sb.append("#define texture3D texture\n");
        sb.append("#define texture2DLod textureLod\n");
        sb.append("#define shadow2D texture\n\n");

        if (data.getUniforms() != null) {
            // Binding-free (Apple GL 4.1) — block binding assigned post-link.
            sb.append("layout(std140) uniform ShaderUniformBindings ")
                    .append(data.getUniforms().layout())
                    .append(";\n\n");
        }

        if (data.samplerDecls != null) {
            // Binding-free sampler decls; units assigned post-link with
            // glUniform1i in declaration order, matching bindingFunction's base.
            data.samplerDecls.forEach((name, type) ->
                    sb.append("uniform ").append(type).append(' ').append(name).append(";\n"));
            sb.append('\n');
        }

        sb.append("""
                uniform sampler2DRect uVxAlbedo;
                uniform sampler2DRect uVxDepth;
                uniform int uVxDepthIsWindow;
                uniform uint uVxWaterId;

                """);

        sb.append(PARAMS_STRUCT).append('\n');

        sb.append("""
                vec4 vx_fragCoord;

                void main() {
                    ivec2 vxSz = textureSize(uVxDepth);
                    vec2 vxTexel = vec2(gl_FragCoord.x, float(vxSz.y) - gl_FragCoord.y);
                    vec3 vxDEnc = texture(uVxDepth, vxTexel).rgb;
                    float vxD = dot(vxDEnc, vec3(1.0, 1.0 / 255.0, 1.0 / 65025.0));
                    if (vxD <= 0.0 || vxD >= 0.9999999) discard;
                    vec4 vxA = texture(uVxAlbedo, vxTexel);
                    if (vxA.a <= 0.001) discard;
                    // Bridge holds premultiplied colour — un-premultiply to straight albedo.
                    vec3 vxStraight = vxA.rgb / max(vxA.a, 0.0001);
                    vec4 vxAlbedo = vec4(vxStraight, vxA.a);
                    float vxWz = (uVxDepthIsWindow == 1) ? vxD : vxD * 0.5 + 0.5;
                    gl_FragDepth = vxWz;
                    vx_fragCoord = vec4(gl_FragCoord.xy, vxWz, 1.0);
                    // Spike: constant material attrs + water customId so the pack's
                    // voxy_translucent shades the whole LOD translucent layer as water.
                    voxy_emitFragment(VoxyFragmentParameters(
                        vxAlbedo, vec2(0.0), vec2(0.0), 1u, 0u, vec2(1.0, 0.0), vec4(1.0), uVxWaterId));
                }

                #define gl_FragCoord vx_fragCoord
                """);

        sb.append('\n').append(appleStrictCompat(patchText)).append('\n');
        return sb.toString();
    }

}
