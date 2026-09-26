# Development Guide

How to build, run, and debug the Apple M-series port. Architecture lives in
[`METAL-MIGRATION.md`](METAL-MIGRATION.md); the project history in
[`MIGRATION-HISTORY.md`](MIGRATION-HISTORY.md).

## Build & run

```bash
./gradlew build                          # full build incl. native dylib + remapped jar
VOXY_FORCE_METAL=1 ./gradlew runClient   # dev client with the Metal path enabled
```

- The Metal native library (`native/metal/src/*.mm`) is compiled by the
  `buildMetalNative` task into
  `src/main/resources/natives/macos-arm64/libvoxy_metal.dylib` and is
  checked in — rebuild happens automatically when the `.mm` sources change.
- The release jar lands in `build/libs/`. It needs Sodium
  (`mc1.21.11-0.8.1`) in the same instance.
- Runtime shaders are compiled on first use and cached in
  `~/.voxy/shader-cache` keyed by source hash — delete the directory to force
  recompilation (any `.glsl` edit produces new cache entries automatically).

## Debugging workflow

The loop that produced every fix in this port:

1. Reproduce with the smallest scene possible; take screenshots (F2) —
   consecutive same-second screenshots are gold for frame-alternation bugs.
2. Read `run/logs/latest.log` and correlate the `[Metal-*]` markers (below)
   with the screenshot timestamps.
3. Form a hypothesis, find the **discriminating** env toggle (or add one),
   and re-run changing exactly one variable.
4. Fix with a kill switch so the change can be A/B-verified on-device.

## Log markers

| Marker | Meaning |
|---|---|
| `[Metal-DEFINES]` | Which shader-define variant compiled (one-shot) |
| `[Metal-LayerB]` | Per-window draw counts: render-list size, opaque/translucent/temporal draws, cmdgen dispatch shape |
| `[Metal-FLICKER]` | Render-list variance over ~600-frame windows — with a static camera, `min != max` means nondeterministic section selection |
| `[Metal-DIAG]` | Section/geometry totals + camera position |
| `[Metal-FRUSTUM]` | Projection/viewport dump (every 600 frames) — catches FOV/viewport anomalies |
| `[Metal-WATERBAKE]` | Per-face alpha coverage of the fluid bake (one-shot) |
| `[Metal-WATERANIM]` | Water animator registration (frames, frametime, faces) |
| `[Metal-VIEWPORT]` | Warn-once: `GL_VIEWPORT` disagreed with MC's main RT (leaked pass viewport) |
| `[Metal-TIMING]` | `VOXY_FRAME_TIMING=1`: per-frame avg of the three Metal-side CPU waits (hotWait / drawFlushWait / bridgeWait) + the per-draw JNI loop, every 600 frames |
| `[Metal-VXTIMING]` | `VOXY_FRAME_TIMING=1`: same window, GL side — per-pass `gpuAvg/gpuMax\|cpuAvg/cpuMax` ms for the vx-contract passes (scOpaque/scTrans/scAbyss, inject*, resolve*, acquire, composite, lightmap; GPU via async `GL_TIME_ELAPSED`) + `lodCoverage=` (% of framebuffer pixels that are opaque LOD, from a `GL_SAMPLES_PASSED` query). One-shot `GL-side vx pass timing ON` line at first frame |
| `IOSurfaceBridgeCompositor` | Composite mode (blit vs shader) and target FBO |

## Key environment variables

The full table is in `METAL-MIGRATION.md`. The ones used constantly during
development:

| Variable | Use |
|---|---|
| `VOXY_FORCE_METAL=1` | Required opt-in for the Metal path |
| `VOXY_BRIDGE_SOLID_TEST=1` | Solid-green bridge, no LOD draws — isolates bridge/composite/sync from content |
| `VOXY_LOD_NO_CULL=1` | Disable frustum culling (slow; separates culling from drawing bugs) |
| `VOXY_BOUND_DEBUG=1` | Tint chunk-bound-discarded fragments red (visualizes the depth mask) |
| `VOXY_LOD_WATER_DEBUG=1` | Magenta water, depth-test off (water geometry coverage) |
| `VOXY_NO_DEPTH_BOUND=1` | Kill switch: disable the chunk-bound depth mask |
| `VOXY_WATER_ANIMATE=0` | Kill switch: freeze LOD water animation |
| `VOXY_HOT_SERIALIZE=1` | Submit+wait per traversal iteration (race diagnostic, slow) |
| `VOXY_FOG_SMOOTH_MS` | Fog colour smoothing constant (0 disables) |
| `VOXY_FRAME_TIMING=1` | Per-stage frame cost probe: `[Metal-TIMING]` (Metal-side waits) + `[Metal-VXTIMING]` (GL-side vx passes, async timer queries). Zero cost when unset |
| `VOXY_VX_TIMING=0` | Kill switch: with `VOXY_FRAME_TIMING=1`, keep only `[Metal-TIMING]` (no GL queries, no VXTIMING line) — pre-probe behaviour |
| `VOXY_VX_TIMING_GPU=0` | `[Metal-VXTIMING]` CPU brackets only, no `GL_TIME_ELAPSED`/`GL_SAMPLES_PASSED` queries |

## Hard-won constraints (do not regress these)

- **No GL readbacks on the hot path.** `glGetTexImage`-class calls on Apple
  GL are the established SIGBUS source. The lightmap mirror is the one
  per-frame exception (1 KB, proven); everything else (bakery, water
  animation) works from CPU-resident or Metal-side data.
- **The GL backend must stay byte-identical.** Every Metal change is either
  inside a `backend != OPENGL` gate or provably no-op on GL.
- **Metal compute encoders don't fence indirect-dispatch argument fetches.**
  If pass N writes the dispatch args of pass N+1, they need separate
  encoders (see the HOT traversal) — `memoryBarrier(scope:)` is not enough.
- **Don't trust GL state at the Sodium SOLID hook.** The viewport can be
  polluted (16×16 lightmap pass); MC's main RT does not yet contain the sky.
  Size and target from `Minecraft.getMainRenderTarget()`.
- **Descriptor-vs-shader drift fails silently on Metal.** Threadgroup sizes
  are parsed from the shader source (`ComputeLocalSizeParser`) and PSOs pin
  `maxTotalThreadsPerThreadgroup`; keep it that way.
- **Shared GLSL must avoid UB the Metal compiler exploits**: no left-shifts
  of negative ints (use multiplication), no `(v<<L)>>R` sign-extension
  idioms (use `bitfieldExtract`), no boolean-select `mix()` overloads
  (use ternaries) in shaders that are live on the Metal path.

## Conventions

- Branches: `feature/…` for work, `docs/…` for documentation; PRs target
  `dev`. `dev` is push-protected — merge via PR.
- Commits document root causes, not just changes (see `git log` for the
  house style).
- Every behavioral fix ships with an env kill switch until the user has
  verified it on-device; falsified experiment knobs get removed or marked
  stale in the next round.
