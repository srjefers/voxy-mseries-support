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
| `[Metal-VXTIMING]` | `VOXY_FRAME_TIMING=1` (close F3 while sampling — vanilla's GPU-utilization timer owns `GL_TIME_ELAPSED`; skipped frames print as `gpuSkippedForeign=`): same window, GL side — per-pass `gpuAvg/gpuMax\|cpuAvg/cpuMax` ms for the vx-contract passes (scOpaque/scTrans/scAbyss, inject*, resolve*, acquire, composite, lightmap; GPU via async `GL_TIME_ELAPSED`) + `lodCoverage=` (% of framebuffer pixels that are opaque LOD, from a `GL_SAMPLES_PASSED` query). One-shot `GL-side vx pass timing ON` line at first frame |
| `[Metal] lightmap sync throttled to <mode>` | (`lightmapSync` ms on `[Metal-TIMING]` is CPU wall time *including* the GL drain the readback forces; under the throttle `ms each` rises while `ms/frame` falls — judge the win by `[Metal-LayerB] fps`, A vs B at the same spot.) One-shot: whether the MC-lightmap readback runs only on lightmap rewrites (`lightmap-version`, default) or every frame (`VOXY_LIGHTMAP_SYNC_EVERY_FRAME=1`); `[Metal-LayerB]` carries `lightmapSync=<run>/<skipped>` per window, `[Metal-TIMING]` its ms |
| `[Metal-RESYNC]` | One-shot: IOSurface GL re-specification mode (`ONCE` per bridge per Metal frame, or `EVERY` acquire under `VOXY_BRIDGE_RESYNC=every`); `[Metal-TIMING]` carries `resync=N/frame (skipped M/frame) resyncCost=` |
| `[Voxy-SYNC]` | One-shot at class load: state of each upstream-sync kill switch (`mesher face-occlusion predicate`, `mip block-light packing`, `Iris shader define`) — see `docs/UPSTREAM-SYNC.md` |
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
| `VOXY_LIGHTMAP_SYNC_EVERY_FRAME=1` | Kill switch: readback MC's lightmap every frame instead of only when MC rewrote it (client tick) |
| `VOXY_BRIDGE_RESYNC=every` | Kill switch: re-specify IOSurfaces into GL on every acquire (pre-lever-C); default `once` per bridge per Metal frame |
| `VOXY_FOG_SMOOTH_MS` | Fog colour smoothing constant (0 disables) |
| `VOXY_METAL_DRAW_BATCH=0` | Kill switch: per-draw Java JNI indirect loop instead of the native batch (A/B on `[Metal-TIMING] jniDrawLoop`) |
| `VOXY_MESH_XFACE_FIX=0` | Kill switch: pre-sync swapped -x/+x face-occlusion indices at section borders (upstream 1f985ce6). A no-op on Metal (the Metal bakery's occlusion bits are symmetric per axis); visible on GL only |
| `VOXY_MESH_FACE_OCCLUDE=0` | Kill switch: pre-sync non-opaque face predicate (before upstream 6212d95c/514d0a0e/89b3dacc). On Metal the only visible difference is LOD-0 plant side faces next to an occluding neighbour: the Metal bakery marks fences, panes, walls, slabs and stairs fully opaque, so they never reach this predicate (a null A/B on them is expected). Metal test scene: a flower or crop in a 1-wide slot between two full blocks on the first LOD ring. On GL: fences, panes, walls, slabs, stairs at LOD 0-2; marker `[Voxy-SYNC] mesher face-occlusion predicate` |
| `VOXY_MIP_BLOCKLIGHT_FIX=0` | Kill switch: pre-sync Mipper block-light packing (upstream 0eb618d1 fixed block light lost at every mip level ≥ 1). Stored mips only refresh on re-import; marker `[Voxy-SYNC] mip block-light packing` |
| `VOXY_SHADER_DEFINE_VERSION=N` | Iris define emitted to packs: `#define VOXY N` (default 1, upstream 13230c27 contract v1); `0` restores the bare `#define VOXY`. Marker `[Voxy-SYNC] Iris shader define` |
| `VOXY_SYNC_UNDEFINED_SKYLIGHT=0` | Kill switch: fill not-in-storage sections with sky light 0 again (pre upstream 47053483; dark patches next to unstored sections) |
| `VOXY_SECTION_FREE_ASSERT=1` | Make the "Section freed while marked as dirty or in the save queue" check (upstream c7166d3f/c2dca44e) throw like upstream; fork default logs and keeps the section alive so its write is saved (the check runs inside the tracker stripe lock) |
| `VOXY_SAVE_BACKPRESSURE=0` | Keep the unload path's save enqueue non-blocking (the B1 port as first applied): the save queue then has no bound and dirty sections stay resident under fast travel |
| `VOXY_SHUTDOWN_WAIT_MS=N` | How long instance shutdown waits (after the save queue is drained) for a world whose sections are still referenced before leaving it open; default 30000, `0` = wait forever (upstream) |
| `VOXY_SYNC_CHUNK_POS_CHECK=0` | Kill switch: skip the chunk-ring slot position check (upstream 6189ee38) in `voxy$cheekyGetChunk` |
| `VOXY_NODE_UPLOAD_CAP_KB=N` | Geometry uploaded per async-node-manager run (default 1000 KB, upstream 36f85026 frame-spike smoothing); `0` = uncapped (pre-sync). Marker `[Voxy-SYNC] async node upload cap` |
| `VOXY_GEOMETRY_ALLOC_ALIGN=1024` | Restore the 1024-element geometry allocation granule (upstream 72b3ade6 uses 128, ~20% less geometry memory). Marker `[Voxy-SYNC] geometry allocation granule` |
| `VOXY_RD_PROCESS_RATE=20` | Restore the pre-sync render-distance tracker rate (upstream 27f82dda: 40 top-level node changes per rendered frame). Marker `[Voxy-SYNC] render-distance process rate` |
| `VOXY_WORKER_RETHROW=0` | Pre-sync worker failure handling: the Async Node Manager / Model factory threads log and exit instead of rethrowing their exception on the render thread (upstream 36f85026/02e490e0) |
| `VOXY_CTOR_FAILURE_CLEANUP=0` | Pre-fix behaviour when the Voxy renderer constructor throws: keep the partially built components alive (geometry arena, model atlas, worker threads) instead of releasing them |
| `VOXY_CTOR_FAIL_ONCE=1` | Diagnostic (remove once A11 is verified): the first renderer construction of the process throws after the worker threads start, to exercise the constructor-failure cleanup. Use with a shader pack active |
| `VOXY_IRIS_PACK_PREDICATE=quick\|current` | Iris 'pack enabled' predicate: default `hybrid` on Metal (pack loaded AND pipeline null-or-Iris) and `current` (pure upstream) on GL, `quick` = pre-sync `isPackInUseQuick`, `current` = pure upstream 1952d3df. Marker `[Voxy-SYNC] Iris pack predicate` |
| `-Dvoxy.verify.verifyNodeManager=true` | Run NodeManager.verifyIntegrity after every async result publish (upstream 8187d2fd; slow) |
| `-Dvoxy.exclusiveLock=true` | Hold an exclusive lock on `.voxy/voxy.lock` so a second game instance cannot open the same store (upstream 36964ee4/c6b30e51; off by default) |
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
