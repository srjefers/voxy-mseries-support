# Backlog — Metal fork (updated 2026-09-26)

Status legend: **open** = not started · **in progress** · **pending verify** = code shipped, needs on-device confirmation · **blocked**.
Every behavioural change ships an env kill switch (see `docs/DEVELOPMENT.md`). PRs target `dev` (push-protected).

## A. Performance and memory (case 2)

| # | Item | Status | Notes |
|---|---|---|---|
| A1 | GL-side timing for the vx-contract passes (`[Metal-VXTIMING]`) | pending verify | prerequisite for every other lever; same gate as `VOXY_FRAME_TIMING=1` |
| A2 | Lightmap readback once per client tick instead of once per frame | pending verify | the one sanctioned readback; forces a GL drain each frame today |
| A3 | One IOSurface re-specification per bridge per frame | pending verify | today several per frame (duplicate acquires) |
| A4 | Native batched indirect draw loop (one JNI call per frame) | pending verify | ~40k JNI crossings/frame today; ICB path stays for later |
| A5 | Async HOT request-queue readback (remove wait #1) | open | double-buffer `requestBuffer`; issue #12 |
| A6 | Async double-buffered IOSurface bridge, frame N-1 consume (remove wait #3) | open | the main perf milestone; issue #12 |
| A7 | HiZ occlusion pyramid on Metal (no occlusion culling today) | open | 2026-05-13 attempt regressed horizon chunks; build from the previous frame's exported depth |
| A8 | Trim the vx-contract fullscreen passes (coverage stencil, fewer march taps) | open | material path is the "heat" source: BSL shades every LOD pixel |
| A9 | Depth-export / bound-mask blits to R32F colour attachments (no blit, valid sampler reads) | open | the "reads zeros" depth-format class |
| A10 | Rain fps: run stress scenario S3 with `[Metal-TIMING]` to rank the hypotheses | open | Voxy has no rain code path; suspects: pack weather cost x vsync, world churn, lightmap drain |
| A11 | Constructor-failure cleanup in `VoxyRenderSystem` (half-built renderer never freed) | pending verify | fixed 2026-09-26: components register their release as they are built and the catch unwinds them; `VOXY_CTOR_FAILURE_CLEANUP=0` reverts. Trigger to test: a pack whose Voxy pipeline fails to build (Iris then gets disabled and a second renderer is built) |
| A12 | `VxContractInjector.rsUboScratch` never freed; `WorldSection.ARRAY_REUSE_CACHE` bound | open (low) | reviewed 2026-09-26: the rs* objects belong to the opt-in Phase-D spike, built once per process (a few KB + 3 programs); the array cache is a bounded 100 MB heap pool. Neither grows across sessions |
| A13 | GL state hygiene: read-FBO tracker desync (`ensureColorFbo`, `VxIrisSideChannel`), raw `glDeleteTextures` on resize, `runOne` sampler restore without `finally`, opt-in trans-resolve spike blend leak | open | latent today; same class as the cull leak |
| A14 | Measure with vsync off (fps is quantized to 120/n) | open | measurement hygiene, no code |
| A15 | Upstream memory fixes: RocksDB iterator leak (352da265), 128-element geometry granule (~20% less geometry memory, 72b3ade6), save-queue self-deadlock (136381a7) | pending verify | integrated with the B0-B3 sync; F3 `UC/GC,#N` shows geometry use; A/B with `VOXY_GEOMETRY_ALLOC_ALIGN=1024` |

## B. Far-LOD quality (case 1)

| # | Item | Status | Notes |
|---|---|---|---|
| B1 | Pack-shaded opaque LOD (`voxy_opaque`) as default — the lighting seam | pending verify (user OK'd on-device) | `5481ed12`, `VOXY_VX_MATERIAL_OPAQUE=0` reverts |
| B2 | Plant tufts on the first ring at parity (bake, sampler, AO parity, mip-aware discard, edge-on fade) | done (measured) | 9 commits; knobs `VOXY_LOD_PLANT_*`, `VOXY_VX_LOD_AO_*`, `VOXY_LOD_MIP_DISCARD*` |
| B3 | Leaves baked solid (dilation) vs vanilla cutout leaves | open (optional) | skip dilation for `LeavesBlock` behind a switch; risk: see-through holes at far distance |
| B4 | No cast shadows beyond BSL `shadowDistance` (256) on the LOD ring | open (structural) | in-game slider experiment to 512/1024; sun-space LOD shadow map is the real fix (L) |
| B5 | Plants beyond lvl 0: keep to lvl 1 with distance fade, ground-cover tint for culled plants, Mipper plant rule | open | plan steps S2/S4/S7 in memory; S7 changes persisted mips (storage version bump) Before keeping plants past lvl 0: `quad_util.glsl` divides the plant indent (0.5) by lodScale, which turns crosses into inset hollow boxes at lvl >= 1 (the 'box' look); skip that division for plant-cross models first |
| B6 | Green ring line during the first minute of a session | closed (user: transient world-gen, not bothersome) | |

## C. Robustness and stress tests (case 3)

| # | Item | Status | Notes |
|---|---|---|---|
| C1 | Run stress scenarios S1-S3 (baseline soak, leave+rejoin, weather/time matrix) | open | `docs/STRESS-TEST-PLAN.md` |
| C2 | Run S4-S15 (spyglass thrash, resize storm, Iris recreation matrix, RD thrash, F3+T, teleports, long flight, dimension hops, world hop, Sodium index buffer growth, session-2 build stall A/B) | open | |
| C3 | Aux rect-texture leak fix (`f829b6dc`) verified across rejoins with the RSS sampler | pending verify | marker `session GL objects released (aux drained=0)` |
| C4 | Once-per-process log guards hide second-session state | open | reset the latches on renderer shutdown; the new sync warnings are already per instance (child-existence budget per NodeManager, zero-viewport latch per transition) |
| C5 | Sodium shared-index-buffer grow: uncaught `IllegalArgumentException` at the max-element check | open | |
| C6 | Metal command-buffer failure = fatal `RuntimeException` (no device-lost recovery) | open | |

## D. Release and repo hygiene

| # | Item | Status | Notes |
|---|---|---|---|
| D1 | Metal enabled by default on macOS arm64 via config, replacing the `VOXY_FORCE_METAL` env opt-in | open | required for launcher users |
| D2 | Release jar must bundle the runtime natives (RocksDB osx-arm64 jnilib, LWJGL shaderc/spvc) | open | issue #18 |
| D3 | Version bump (`0.3.0-alpha`), release notes, tag, GitHub Release with the jar | open | after merging PR #17 and this branch into `dev` |
| D4 | Merge PR #17 into `dev`; open the PR for `feature/lod-shadows-perf-stress` | in progress | PR #17 merged; this branch has no PR yet (open it after the B0-B3 on-device check). Note: `gh` defaults to MCRcortex/voxy here, always pass `-R srjefers/voxy-mseries-support` |
| D5 | Docs: env table entries for every knob added since July (`VOXY_LOD_PLANT_*`, `VOXY_VX_LOD_AO_*`, `VOXY_GL_*`, `VOXY_LOD_MIP_DISCARD*`) | open | 2026-09-26 scan: 106 of 142 `VOXY_*` env reads have no row in either table; do it together with D6 so removed diagnostics are not documented |
| D6 | Remove falsified diagnostic knobs (house rule) once the release is cut | open | e.g. `VOXY_OPAQUE_RING_DEBUG`, water probe siblings |
| D7 | Rewrite the falsified flip comment in `IrisVoxyRenderPipelineData.java:85-93`; apply the two identified one-liners (shadow-pass face-culling preflight, stale projection on reload) | open | |
| D8 | Upstream sync: classify and cherry-pick applicable MCRcortex/voxy commits | in progress | `docs/UPSTREAM-SYNC.md`. B0-B3 integrated (57 commits, adversarially reviewed per commit; two boot/crash blockers caught and fixed before shipping) and pending on-device verify; next B4 (GL parity), rest of B5 (samplers a7dc2112/d0e879b6, Iris reload df90323f), then after-alpha batches |
| D9 | Issues: #12 #13 #18 kept open with status; #11 closed; #19 (Luster shader collaboration) and #20 (1.21.1 support) need the owner's decision | open | |
