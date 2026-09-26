# Backlog — Metal fork (updated 2026-09-25)

Status legend: **open** = not started · **in progress** · **pending verify** = code shipped, needs on-device confirmation · **blocked**.
Every behavioural change ships an env kill switch (see `docs/DEVELOPMENT.md`). PRs target `dev` (push-protected).

## A. Performance and memory (case 2)

| # | Item | Status | Notes |
|---|---|---|---|
| A1 | GL-side timing for the vx-contract passes (`[Metal-VXTIMING]`) | in progress | prerequisite for every other lever; same gate as `VOXY_FRAME_TIMING=1` |
| A2 | Lightmap readback once per client tick instead of once per frame | in progress | the one sanctioned readback; forces a GL drain each frame today |
| A3 | One IOSurface re-specification per bridge per frame | in progress | today several per frame (duplicate acquires) |
| A4 | Native batched indirect draw loop (one JNI call per frame) | in progress | ~40k JNI crossings/frame today; ICB path stays for later |
| A5 | Async HOT request-queue readback (remove wait #1) | open | double-buffer `requestBuffer`; issue #12 |
| A6 | Async double-buffered IOSurface bridge, frame N-1 consume (remove wait #3) | open | the main perf milestone; issue #12 |
| A7 | HiZ occlusion pyramid on Metal (no occlusion culling today) | open | 2026-05-13 attempt regressed horizon chunks; build from the previous frame's exported depth |
| A8 | Trim the vx-contract fullscreen passes (coverage stencil, fewer march taps) | open | material path is the "heat" source: BSL shades every LOD pixel |
| A9 | Depth-export / bound-mask blits to R32F colour attachments (no blit, valid sampler reads) | open | the "reads zeros" depth-format class |
| A10 | Rain fps: run stress scenario S3 with `[Metal-TIMING]` to rank the hypotheses | open | Voxy has no rain code path; suspects: pack weather cost x vsync, world churn, lightmap drain |
| A11 | Constructor-failure cleanup in `VoxyRenderSystem` (half-built renderer never freed) | open | up to ~4.6 GB + 2 threads per failed construction |
| A12 | `VxContractInjector.rsUboScratch` never freed; `WorldSection.ARRAY_REUSE_CACHE` bound | open | small, bounded |
| A13 | GL state hygiene: read-FBO tracker desync (`ensureColorFbo`, `VxIrisSideChannel`), raw `glDeleteTextures` on resize, `runOne` sampler restore without `finally`, opt-in trans-resolve spike blend leak | open | latent today; same class as the cull leak |
| A14 | Measure with vsync off (fps is quantized to 120/n) | open | measurement hygiene, no code |

## B. Far-LOD quality (case 1)

| # | Item | Status | Notes |
|---|---|---|---|
| B1 | Pack-shaded opaque LOD (`voxy_opaque`) as default — the lighting seam | pending verify (user OK'd on-device) | `5481ed12`, `VOXY_VX_MATERIAL_OPAQUE=0` reverts |
| B2 | Plant tufts on the first ring at parity (bake, sampler, AO parity, mip-aware discard, edge-on fade) | done (measured) | 9 commits; knobs `VOXY_LOD_PLANT_*`, `VOXY_VX_LOD_AO_*`, `VOXY_LOD_MIP_DISCARD*` |
| B3 | Leaves baked solid (dilation) vs vanilla cutout leaves | open (optional) | skip dilation for `LeavesBlock` behind a switch; risk: see-through holes at far distance |
| B4 | No cast shadows beyond BSL `shadowDistance` (256) on the LOD ring | open (structural) | in-game slider experiment to 512/1024; sun-space LOD shadow map is the real fix (L) |
| B5 | Plants beyond lvl 0: keep to lvl 1 with distance fade, ground-cover tint for culled plants, Mipper plant rule | open | plan steps S2/S4/S7 in memory; S7 changes persisted mips (storage version bump) |
| B6 | Green ring line during the first minute of a session | closed (user: transient world-gen, not bothersome) | |

## C. Robustness and stress tests (case 3)

| # | Item | Status | Notes |
|---|---|---|---|
| C1 | Run stress scenarios S1-S3 (baseline soak, leave+rejoin, weather/time matrix) | open | `docs/STRESS-TEST-PLAN.md` |
| C2 | Run S4-S15 (spyglass thrash, resize storm, Iris recreation matrix, RD thrash, F3+T, teleports, long flight, dimension hops, world hop, Sodium index buffer growth, session-2 build stall A/B) | open | |
| C3 | Aux rect-texture leak fix (`f829b6dc`) verified across rejoins with the RSS sampler | pending verify | marker `session GL objects released (aux drained=0)` |
| C4 | Once-per-process log guards hide second-session state | open | reset the latches on renderer shutdown |
| C5 | Sodium shared-index-buffer grow: uncaught `IllegalArgumentException` at the max-element check | open | |
| C6 | Metal command-buffer failure = fatal `RuntimeException` (no device-lost recovery) | open | |

## D. Release and repo hygiene

| # | Item | Status | Notes |
|---|---|---|---|
| D1 | Metal enabled by default on macOS arm64 via config, replacing the `VOXY_FORCE_METAL` env opt-in | open | required for launcher users |
| D2 | Release jar must bundle the runtime natives (RocksDB osx-arm64 jnilib, LWJGL shaderc/spvc) | open | issue #18 |
| D3 | Version bump (`0.3.0-alpha`), release notes, tag, GitHub Release with the jar | open | after merging PR #17 and this branch into `dev` |
| D4 | Merge PR #17 into `dev`; open the PR for `feature/lod-shadows-perf-stress` | open | |
| D5 | Docs: env table entries for every knob added since July (`VOXY_LOD_PLANT_*`, `VOXY_VX_LOD_AO_*`, `VOXY_GL_*`, `VOXY_LOD_MIP_DISCARD*`) | open | `docs/DEVELOPMENT.md`, `docs/METAL-MIGRATION.md` |
| D6 | Remove falsified diagnostic knobs (house rule) once the release is cut | open | e.g. `VOXY_OPAQUE_RING_DEBUG`, water probe siblings |
| D7 | Rewrite the falsified flip comment in `IrisVoxyRenderPipelineData.java:85-93`; apply the two identified one-liners (shadow-pass face-culling preflight, stale projection on reload) | open | |
| D8 | Upstream sync: classify and cherry-pick applicable MCRcortex/voxy commits (266 on the 1.21.11 line, 165 on newer dev) | plan done | `docs/UPSTREAM-SYNC.md`: 431 classified (APPLY 99, ADAPT 133, SKIP 190); batches B0-B5 before alpha (mesher correctness, storage/lifecycle, HOT robustness, session hardening, GL parity, `#define VOXY 1` contract), B6-B11 after |
| D9 | Issues: #12 #13 #18 kept open with status; #11 closed; #19 (Luster shader collaboration) and #20 (1.21.1 support) need the owner's decision | open | |
