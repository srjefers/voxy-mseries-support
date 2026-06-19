# LOD Water BSL-Shading Fix — Session Handoff (2026-06-19)

Handoff for continuing the **LOD water under BSL** fix (GitHub issue #11 / "Phase D").
Read this top-to-bottom before touching code — it records what's done, what's **objectively
ruled out** (don't re-chase these), the leading hypotheses, the diagnostic tooling, and the
exact next steps.

---

## 1. Goal & scope (locked with the user)

- **Make LOD water render BSL's water shading** (waves / reflection / transparency / depth
  grade) so distant LOD water matches the near (Sodium-rendered) water.
- **Do NOT touch the opaque LOD path.** Opaque LODs already render correctly on the base
  path; the user wants them byte-identical so this can **merge into `dev`** without regressing
  the other LOD blocks.
- Mac/Metal only. `VOXY_FORCE_METAL=1` is the required opt-in. The GL/Windows path is upstream
  Voxy and must stay unchanged.

## 2. Branch / commits

- Branch: **`bugfix/water-shaders-textures`** (off `dev` lineage; the whole vx-contract lives
  here). Push uses the `srjefers` gh account.
- Key commits (newest first):
  - `03c86a69` **vx material: make VOXY_VX_MATERIAL translucent-only** ← the current shape.
  - `de3e0136` objective dump proves resolve OK; dark is in BSL deferred consumption.
  - `f4a16b77` diagnose dark LODs (sky-light) + resolve sky-light floor.
  - `302378e6` Phase C: sampler order + depth space fixes.
  - `60bd3e8b` Phase C (5/5): GL resolve runtime.
- **UNCOMMITTED diagnostics in the working tree** (default-off, no rendering change; commit
  them first thing): `dumpVxTransStats` water face + sky/block-light histograms
  (`AbstractRenderPipeline.java`), trans-depth probe + `dumpDepthStats(label,fbo,…)` +
  `fboTransId()` (`VxIrisSideChannel.java`), trans-depth dump call (`MetalVxResolvePass.java`).
  (Bash tooling was flaky at end of session — compile + commit them.)

## 3. Current state — what WORKS

`VOXY_VX_MATERIAL=1` is now **translucent-only**:
- **Opaque LODs** render on the proven base path (base lit-colour shader → bridge →
  `VxContractInjector` Phase-B inject of colour + `vxDepthTexOpaque`). **Verified on-device:**
  far opaque is lit to the horizon, no dark band. This is the mergeable, untouched-opaque shape.
- **Water** renders a 3-plane material g-buffer (albedo/tint/customId) + a separate depth, and
  the GL resolve runs the pack's real `voxy_translucent` over it → `colortex16` → BSL composite.
  **Near/mid lake water DOES get BSL shading** (waves + reflection visible).
- The old opaque+translucent material path is parked behind `VOXY_VX_MATERIAL_OPAQUE=1` (A/B only).
- Default (no `VOXY_VX_MATERIAL`) is unchanged — everything is gated on
  `vxMaterialMode()` / `vxOpaqueMaterialMode()`.

## 4. Remaining bugs (the actual task)

From the user's latest screenshots (spyglass on water):

1. **Chunk-aligned "square" artifacts** — LIGHTER / gray flat rectangular patches overlaid on
   the LOD water, with sharp chunk-edge boundaries. **View-angle dependent**: appear mainly when
   looking toward the **horizon or sky**, and through the spyglass. Affect **near AND mid** water
   chunks. Look like flat sky-reflection panels where the surrounding water has wave texture.
2. **Far / horizon water is flat solid dark-blue** — no wave texture at distance.
3. **Water is not transparent enough** — reads as a deep opaque dark-blue instead of the
   semi-transparent water that shows the bottom. (You can see seagrass through it in places, so
   it's partial, but the overall alpha is too high / too dark.)

All three are about the **water SURFACE shading** (reflection / waves / transparency), which is
produced INSIDE BSL's `voxy_translucent`, fed by clean Voxy inputs (see §5).

## 5. RULED OUT — objective evidence (do NOT re-investigate these)

Measured via CPU read-back of the actual g-buffer planes & depth (flags in §7). The squares are
**NOT** any of these:

| Hypothesis | Verdict | Evidence |
|---|---|---|
| LOD water **depth** chunk-stepped / quantized | ❌ refuted | trans depth dense (7.4–7.6/8 covered neighbours) + **smooth**, `localRoughness ≈ 0.000003` window-Z |
| Wrong **depth remap** (`d*0.5+0.5`) | ❌ refuted | depth is smooth & round-trips; for far LOD (NDC-z∈[0,1]) the GL-convention remap is correct |
| Per-quad **normal/face** inconsistency | ❌ refuted | water face histogram = **all UP (face=1)**, uniform |
| Water **not detected** (wrong customId) | ❌ refuted | ~95% of covered translucent px read `blockID 200` (water); only stray `202` |
| **Sky-light starvation** as opaque-dark cause | ❌ (opaque) | opaque is fixed via trans-only; far opaque now lit |
| AO / LOD-shadows / fog darkening (opaque) | ❌ refuted | toggled each off at frozen noon — far opaque stayed lit/dark independently; was the directional-shading detour, now avoided by trans-only |

A **multi-agent workflow** (run this session) "confirmed" depth-remap / depth-quantization as
the cause — that was **WRONG** (the verify pass rubber-stamped a plausible chain). The CPU
measurement of the actual depth refutes it. **Lesson: measure the real buffer, don't trust the
agents' depth reasoning.**

## 6. Leading hypotheses (where to look next)

The inputs `voxy_translucent` receives are clean & uniform, so the artifacts are generated
inside BSL's water shading. Two concrete, code-grounded leads:

### (A) Squares = per-chunk **sky-light scaling the sky reflection** — TOP SUSPECT, not yet confirmed
`voxy_translucent.glsl:357-362`: the water **sky reflection** is multiplied by
`waterSkyOcclusion = lightmap.y` (the SKY light), squared. The water albedo is a flat
`WATER_MODE` colour, so the *visible* "waves/texture" come entirely from reflecting the
wave-perturbed normal — i.e. **reflection brightness IS the texture**. If the LOD water's
per-pixel **sky-light varies per chunk** (mip/bake variation), the sky-reflection brightness
varies per chunk → the lighter/darker **squares**; uniform low/high sky far away → flat.
- **Confirm:** `VOXY_VX_DUMP_PLANES=1`, read `[Metal-VXTRANS] water skyLight hist[0..15]`
  (added this session, not yet run). Multi-modal / per-chunk spread ⇒ confirmed.
- **Likely fix (Voxy-side, doesn't touch opaque):** stabilise the LOD water sky-light — e.g.
  mip water sky-light to **max** instead of average (`Mipper.java`), and/or floor the sky-light
  the emitter writes for water so the reflection is uniform. (See also the pre-existing
  `VOXY_VX_SKY_FLOOR` knob in `MetalVxResolvePass.assembleFragment`.)

### (B) Transparency / darkness = water **alpha** too high + flat far reflection
Water not transparent: `voxy_translucent.glsl:277-295` sets the water albedo from `WATER_MODE`
and the alpha from `WATER_ALPHA_MODE` / `WATER_VA` / `waterAlpha`. The composite
(`deferred1.glsl:592-610`) blends `colortex16` over the scene using `voxyTransparentColor.a` and
a depth-test gate `*= step(-vxViewPos0.z, -viewPos.z)`. If the water alpha reaching the composite
is ~opaque, the water won't show the bottom. Check what alpha the resolve writes to `colortex16`
and whether the composite's depth-test/blend is correct for LOD water.
- Far-flat is **partly inherent** (grazing far water reflects sky flatly + sub-pixel waves), but
  verify the reflection isn't reading an incomplete screen — the resolve runs at the **Sodium
  SOLID-pass head** (pre-composite), so any screen-space reflection (`raytrace.glsl` /
  `simpleReflections.glsl`, included by `voxy_translucent`) samples an unfinished frame.

### (C) Quick user A/B to bisect A vs reflection
In BSL's in-game shader options, turn **water/translucent reflections OFF** (or lower Reflection
quality). If the squares disappear → they ride on the sky-reflection (⇒ pursue A). If they
persist → it's the water colour/alpha path (⇒ pursue B).

## 7. Diagnostic tooling (all default-off, Metal+vx-material only)

Run: `VOXY_FORCE_METAL=1 VOXY_VX_MATERIAL=1 VOXY_VX_DUMP_PLANES=1 VOXY_QUICKPLAY="New World (40)" ./gradlew runClient`
then grep `run/logs/latest.log`:

- `[Metal-VXTRANS]` — translucent water plane stats (one shot ~frame 200, repeats every 600):
  - `covered / waterDetected / notWater / topNonWaterIds` — water customId detection.
  - `water face hist[0..7]` — per-quad normal face (1=UP expected).
  - `water skyLight hist[0..15]` / `water blockLight hist[0..15]` — **the key one for hypothesis A.**
- `VOXY_VX_DUMP_OUT=1` → `[VX-OUT]`:
  - `trans depth grid` + `trans coverage density` (roughness/neighbours) — depth smoothness.
  - `colortex0 meanRGBA` / UBO scratch dump (opaque-path; the AO/colortex4 readback is timing-walled — ignore the 0s).
- `VOXY_VX_DEBUG_PLANE=albedo|tint|misc|depth` — writes a raw plane to the pack's first colortex
  (bypasses BSL) to see the material g-buffer directly.
- `VOXY_VX_SKY_FLOOR=0..15` — floors the resolve's decoded sky-light (test/knob for hyp. A).
- `VOXY_VX_MATERIAL_OPAQUE=1` — re-enables the old opaque+water material path (A/B only; darkens far opaque).
- **Screenshot caveat:** the assistant's own auto-screenshots repeatedly misled (view drift
  between quickplay launches, grazing-horizon bands, BSL tonemap). **Trust the user's eyes /
  spyglass and the CPU read-backs, not generated screenshots.** The squares are best seen via the
  in-game **spyglass** (zoom) — could not be reproduced headlessly.

## 8. Key files & line references

**Voxy side (our code):**
- `src/main/java/me/cortex/voxy/client/core/util/MetalVxResolvePass.java` — `assembleFragment`
  (resolve GLSL: depth decode `vxD`→`vxWz`, `uVxDepthIsWindow`, sampler/UBO bindings),
  `resolveTranslucentOnly` (the water-only resolve), `resolve` (full A/B path), `VOXY_VX_SKY_FLOOR`.
- `src/main/java/me/cortex/voxy/client/core/util/VxIrisSideChannel.java` — decodes the 24-bit
  packed depth bridge → D32F `vxDepthTexTrans` (`resolveInto`, `gl_FragDepth=(depthIsWindow?d:d*0.5+0.5)`);
  `dumpDepthStats`, `fboTransId`.
- `src/main/java/me/cortex/voxy/client/core/util/MetalVxGbufferEmitter.java` — the
  `voxy_emitFragment` body appended to quads.frag: writes albedo (P0), tint (P1), and the
  misc plane (P2 = light nibbles + face + 16-bit customId). **This is where the water's
  light/face/customId are packed** — relevant for hypothesis A (sky-light) fixes.
- `src/main/java/me/cortex/voxy/client/core/AbstractRenderPipeline.java` — `runPipelineMetal`
  (opaque pass → bridge unless `vxOpaqueMaterialMode()`; translucent pass ~L855-916 → `metalVxTrans`
  planes + `metalDepthTransBridge`); `vxMaterialMode()` / `vxOpaqueMaterialMode()`;
  `dumpVxStats` / `dumpVxTransStats` (the trans light histograms).
- `src/main/java/me/cortex/voxy/client/mixin/sodium/MixinDefaultChunkRenderer.java` — the
  SOLID-head hook: trans-only branch = `VxContractInjector.inject(opaque, null, null)` +
  `MetalVxResolvePass.resolveTranslucentOnly(...)`.
- `src/main/java/me/cortex/voxy/common/world/other/Mipper.java` — LOD downsample. NOTE: its
  all-air-cell light combine does `(blockLight<<4)|skyLight` where `blockLight` is already in
  the high nibble (double-shift drops block light on mips — minor). Solid cells take the
  representative voxel's light (top voxel I111). **Sky-light mip behaviour is the fix site for hyp. A.**
- LOD water shaders: `src/main/resources/assets/voxy/shaders/lod/gl46/quads.frag` (PATCHED path
  builds `VoxyFragmentParameters`), `quad_util.glsl`, `quad_format.glsl`.

**BSL pack side (read-only, in `run/shaderpacks/BSL_v10.1.3.zip`; extracted copies in `/tmp/bslwater/`):**
- `shaders/program/voxy_translucent.glsl` — water shading: `GetWaterNormal` :104-131,
  `GetWaterHeightMap` (samples `noisetex` at `worldPos.xz/256` & `/48`) :68-90, worldPos/viewPos
  reconstruction :195-201, **sky reflection scaled by sky-light :357-362**, water colour/alpha
  :277-295, reflection includes `raytrace.glsl`/`simpleReflections.glsl` :149-150, water detect
  `blockID = customId/100; water = blockID==200||204` :165/181.
- `shaders/program/deferred1.glsl` — **water composite** :592-610 (`colortex16`,
  `vxDepthTexTrans`, `voxyTransparentColor.a *= step(-vxViewPos0.z, -viewPos.z)`, blend).
- `shaders/program/composite.glsl` — more LOD water/colortex16 handling.
- `shaders/program/voxy.json` — `translucentDrawBuffers: [16, 1]` (gbufferData0→colortex16),
  `excludeLodsFromVanillaDepth: true`, uniform & sampler lists.
- `shaders/lib/settings.glsl` — `WATER_NORMALS 1`, `WATER_PIXEL 0` (off), `WATER_PARALLAX` on,
  `WATER_DETAIL/BUMP/SHARPNESS`, `REFLECTION`/`REFLECTION_TRANSLUCENT`, `WATER_MODE`/`WATER_ALPHA_MODE`/`WATER_VA`/`WATER_VI`.
- Assembled resolve sources dumped to `run/voxy/debug/vx_resolve_opaque.frag` /
  `vx_resolve_translucent.frag` (the full inlined `voxy_translucent` that actually compiles).

## 9. Concrete next steps (recommended order)

1. **Compile + commit** the uncommitted default-off diagnostics (§2).
2. **Run the sky-light histogram** (`VOXY_VX_DUMP_PLANES=1`, read `water skyLight hist`).
   - If multi-modal / per-chunk → **hypothesis A confirmed.** Fix the LOD water sky-light
     (mip-to-max in `Mipper.java` and/or a water sky-light floor in `MetalVxGbufferEmitter`),
     re-test, get the user's spyglass verdict.
   - If uniform → move to **hypothesis B** (water alpha/transparency + composite blend, and/or
     reflection reading an incomplete screen).
3. **Water transparency:** read what alpha the resolve writes to `colortex16` (add a small
   readback like the existing ones) and trace the composite blend (`deferred1.glsl:592-610`);
   adjust the water alpha the emitter/resolve produces so water shows the bottom.
4. **Far-flat:** accept the grazing component as partly inherent; focus on getting the
   reflection right (sky-light uniform + verify SSR isn't reading garbage at SOLID-head).
5. Verify with the **user's spyglass** at each step (the reliable judge), then push + open/refresh
   the PR to `dev`.

## 10. Environment / gotchas

- Build/run: `VOXY_FORCE_METAL=1 VOXY_VX_MATERIAL=1 VOXY_QUICKPLAY="New World (40)" ./gradlew runClient`.
  Gradle daemon is off; cold builds. Runtime shaders cache in `~/.voxy/shader-cache` (delete to force recompile).
- **`New World (40)` is frozen at noon** (`level.dat` Data.DayTime=6000, game_rules
  `minecraft:advance_time=0`, `minecraft:advance_weather=0`) for controlled testing. Restore the
  cycle with in-game `/gamerule doDaylightCycle true` or revert `level.dat`.
- BSL settings file: `run/shaderpacks/BSL_v10.1.3.zip.txt` (currently `FOG_DENSITY_LOD=0.25`).
  A pristine pack backup is at `/tmp/BSL_backup.zip` (may not persist across reboots — re-copy
  from `run/shaderpacks/` if needed). Earlier A/B testing edited `deferred1.glsl` inside the zip
  then restored it; **confirm the pack has no leftover `// [VX-…]` edit markers** before trusting visuals.
- Per repo rules: every behavioural fix ships behind an env kill switch; opaque/GL path stays
  byte-identical; commit messages document root causes.

## 11. One-paragraph summary for the new session

LOD water is now BSL-shaded on a translucent-only path (opaque untouched, mergeable). Three
water artifacts remain: chunk-aligned lighter "squares" (view/horizon-dependent), flat dark far
water, and insufficient transparency. The Voxy-side inputs to BSL's `voxy_translucent` are
**objectively clean** (depth dense+smooth, normal uniform-UP, customId=water — all measured), so
the artifacts are produced inside BSL's water shading. **Top suspect:** the water sky-reflection
is scaled by per-chunk sky-light (`voxy_translucent.glsl:357-362`), so per-chunk sky-light
variation → the squares; confirm with the `water skyLight hist` diagnostic and fix the LOD water
sky-light (mip-to-max / floor). Also fix water alpha/transparency (`WATER_MODE`/alpha + the
`deferred1` composite blend). Don't re-chase depth — it's been measured smooth.
