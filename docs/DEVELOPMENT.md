# Development Guide

How to build, run, and debug the Apple M-series port. Architecture lives in
[`METAL-MIGRATION.md`](METAL-MIGRATION.md); the project history in
[`MIGRATION-HISTORY.md`](MIGRATION-HISTORY.md).

## Build & run

```bash
./gradlew build                          # full build incl. native dylib + remapped jar
./gradlew runClient   # dev client with the Metal path enabled
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

### Which Java runs what

**Gradle itself: Java 17 to 25.** Gradle 9.2.1 compiles `build.gradle` with a
Groovy that cannot read Java 26+ class files. On JDK 27 every build that has
to compile the script (a fresh clone, or any change to `build.gradle`) fails
with `Unsupported class file major version 71`. Gradle only runs on Java 26
from 9.4 and on Java 27 from 9.8. Until the wrapper moves, if `java -version`
says 26 or newer, run Gradle on a Java 21. The official launcher's runtime
works and includes `javac`:

```bash
J21="$HOME/Library/Application Support/minecraft/runtime/java-runtime-delta/mac-os-arm64/java-runtime-delta/jre.bundle/Contents/Home"
./gradlew -Dorg.gradle.java.home="$J21" build
```

To make that permanent, put `org.gradle.java.home=<that path>` in
`~/.gradle/gradle.properties` (it applies to every Gradle build of your user),
or install a JDK 21 (`brew install openjdk@21`) and point `org.gradle.java.home`
at it. Do not set `JAVA_HOME` to the launcher's runtime: `native/metal/build.sh`
takes `jni.h` from `JAVA_HOME`, and that runtime has no `include/`. With
`JAVA_HOME` unset, the native build finds a full JDK through
`/usr/libexec/java_home`.

**The dev client: the launcher's Java 21.** `runClient` runs Minecraft on
the official launcher's Java 21 (Microsoft OpenJDK 21.0.7), the JVM players
use. Before, it ran on whatever JDK ran Gradle (JDK 27 on the reference Mac),
which hid H6: JOML's `getToAddress` throws on macOS Java 21 only. Gradle
prints the choice as `>>> runClient JVM: ...`; Fabric logs `- java 21`.

| Option | Dev client JVM |
|---|---|
| (none) | The launcher's Java 21; a JDK 21 toolchain if the launcher is not installed |
| `-PrunJava=27` | An installed JDK 27 found by Gradle toolchains (the JDK used before) |
| `-PrunJava=<path to bin/java>` | Any java binary |

**Without Iris: `-PnoIris=true`.** The dev client loads Iris by default, so
it never showed H5 (a mixin that referenced Iris crashed every world join
without it). `-PnoIris=true` leaves Iris and its nested libraries out of the
dev runtime only. Compiling and the built jar are unchanged.

**The closest the dev client gets to a player:**

```bash
VOXY_QUICKPLAY="voxytest" \
  ./gradlew runClient -PnoIris=true -PrunXmx=6G
```

One difference stays: names. `runClient` runs on Mojang's names, and the
release jar runs on intermediary names. Code that looks up a Minecraft class,
field or method by its name works in the dev client and fails in the release
jar (H7: `getDeclaredField("firstFboId")`). Use the access widener or a mixin
accessor instead.

**The release jar itself: `runProductionClient`.** Loom's production run
task starts the vanilla client on intermediary names through Fabric Loader,
like a launcher. It loads the jar from `remapJar`, Fabric API and Sodium 0.8.1
(`productionRuntimeMods`), and no Iris. It runs on Java 21 with no Voxy JVM
arguments, as a player's profile does, in `run/`:

```bash
VOXY_QUICKPLAY="voxytest" ./gradlew runProductionClient -PrunXmx=6G
```

`-PforceMetal=false` adds `-Dvoxy.forceMetal=false`, the kill switch: Voxy
turns itself off and the game runs as plain Sodium.

It is the only local run that shows H7-class bugs. Before a release, still
test the built jar in the launcher (`P8-LAUNCHER-CLEAN` in the developer
track).

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
| `[Metal-LODTEST] bound-mask radius=` | Radius the chunk-bound mask culls sections at, and where it came from (`Sodium search distance`, `render distance (no Sodium build yet)`, or `legacy max(RD, 320)` under `VOXY_BOUND_SODIUM_RADIUS=0`). One line when it changes, at most every ~2 s |
| `[Metal-LODTEST] bound-mask new sections join` / `bound-mask deferred adds:` | One line per mask renderer with the add order (`when Sodium lists them` by default, `in their upload frame` under `VOXY_BOUND_ADD_DEFER=0`); then every ~10 s while sections build, how many deferred adds Sodium presented at once and how many joined at its next list build. Both must be non-zero while flying, or a Sodium hook did not apply |
| `[Metal-LODTEST] bound-mask split counts:` / `bound-mask reset by a new Sodium section manager` | Mask size every ~10 s (now also without a pack: `transOnly=0`); one line when a Sodium reload drops queued adds/removals (H1) |
| `[Metal-FRUSTUM]` | Projection/viewport dump (every 600 frames) — catches FOV/viewport anomalies |
| `[Metal-WATERBAKE]` | Per-face alpha coverage of the fluid bake (one-shot) |
| `[Metal-WATERANIM]` | Water animator registration (frames, frametime, faces) |
| `[Metal-VIEWPORT]` | Warn-once: `GL_VIEWPORT` disagreed with MC's main RT (leaked pass viewport) |
| `[Metal-TIMING]` | `VOXY_FRAME_TIMING=1`: per-frame avg of the three Metal-side CPU waits (hotWait / drawFlushWait / bridgeWait) + the per-draw JNI loop, every 600 frames |
| `[Metal-VXTIMING]` | `VOXY_FRAME_TIMING=1` (close F3 while sampling — vanilla's GPU-utilization timer owns `GL_TIME_ELAPSED`; skipped frames print as `gpuSkippedForeign=`): same window, GL side — per-pass `gpuAvg/gpuMax\|cpuAvg/cpuMax` ms for the vx-contract passes (scOpaque/scTrans/scAbyss, inject*, resolve*, acquire, composite, lightmap; GPU via async `GL_TIME_ELAPSED`) + `lodCoverage=` (% of framebuffer pixels that are opaque LOD, from a `GL_SAMPLES_PASSED` query). One-shot `GL-side vx pass timing ON` line at first frame |
| `[Metal] lightmap sync throttled to <mode>` | (`lightmapSync` ms on `[Metal-TIMING]` is CPU wall time *including* the GL drain the readback forces; under the throttle `ms each` rises while `ms/frame` falls — judge the win by `[Metal-LayerB] fps`, A vs B at the same spot.) One-shot: whether the MC-lightmap readback runs only on lightmap rewrites (`lightmap-version`, default) or every frame (`VOXY_LIGHTMAP_SYNC_EVERY_FRAME=1`); `[Metal-LayerB]` carries `lightmapSync=<run>/<skipped>` per window, `[Metal-TIMING]` its ms |
| `[Metal-RESYNC]` | One-shot: IOSurface GL re-specification mode (`ONCE` per bridge per Metal frame, or `EVERY` acquire under `VOXY_BRIDGE_RESYNC=every`); `[Metal-TIMING]` carries `resync=N/frame (skipped M/frame) resyncCost=` |
| `[Metal-CLOUDS]` | LOD cloud layer: one line each time it turns on or off (`on (mesh N chunks, LOD radius M blocks)` or `off: <reason>`, e.g. `fog shortens the clouds`, `camera inside the cloud layer`, `shader pack or non-blit composite`) and one `layer drawn:` line the first time it draws (mode, vanilla mesh radius, fade range, cloud texture), plus one `clouds only over LOD terrain:` line with the coverage grid size and how many top-level sections had terrain at the first upload. With BSL: one `pack cloud distance ON:` line when the pack text is patched (or `... FAILED` if BSL's text drifted) one `pack clouds only over LOD terrain:` line at the first coverage texture upload, and one `vxLodCoverageInfo first valid read by the pack:` line when the pack first reads the coverage uniform (no line = the pack never samples the coverage). `VOXY_LOD_CLOUDS=0` turns all of it off |
| `[Voxy-MIP]` | One-shot at client init on the Metal backend: surface mip ON/OFF (`VOXY_MIP_SURFACE`, `VOXY_MIP_SNOW`) |
| `[Voxy-SYNC]` | One-shot at class load: state of each upstream-sync kill switch (`mesher face-occlusion predicate`, `mip block-light packing`, `Iris shader define`) — see `docs/UPSTREAM-SYNC.md` |
| `IOSurfaceBridgeCompositor` | Composite mode (blit vs shader) and target FBO |

## Key environment variables

The full table is in `METAL-MIGRATION.md`. The ones used constantly during
development:

| Variable | Use |
|---|---|
| `VOXY_FORCE_METAL=0` | Kill switch: turns the Metal path off (plain Sodium). Same as `-Dvoxy.forceMetal=false`. The path is on by default; `=1` from older notes is harmless |
| `VOXY_BRIDGE_SOLID_TEST=1` | Solid-green bridge, no LOD draws — isolates bridge/composite/sync from content |
| `VOXY_LOD_NO_CULL=1` | Disable frustum culling (slow; separates culling from drawing bugs) |
| `VOXY_BOUND_DEBUG=1` | Tint chunk-bound-discarded fragments red (visualizes the depth mask) |
| `VOXY_LOD_MAGENTA=1` | Paint every LOD fragment that draws solid magenta: texture = Sodium, magenta = LOD, anything else = nobody draws. With `VOXY_BOUND_DEBUG=1`, red = LOD the mask removed |
| `VOXY_LOD_LEVEL_TINT=1` / `=all` | Colour LOD water (`=1`) or every LOD fragment (`=all`) by LOD level: 0 cyan, 1 yellow, 2 green, 3 orange, 4 blue, 5+ white (plain path, alpha kept) |
| `VOXY_LOD_WATER_DEBUG=1` | Magenta water, depth-test off (water geometry coverage) |
| `VOXY_NO_DEPTH_BOUND=1` | Kill switch: disable the chunk-bound depth mask |
| `VOXY_BOUND_SODIUM_RADIUS=0` | Kill switch: the chunk-bound mask culls sections at max(render distance, 320 blocks) again instead of Sodium's own search distance (the old radius masked a ring Sodium does not draw at Render Distance < 20: holes at the vanilla/LOD border) |
| `VOXY_BOUND_ADD_DEFER=0` | Kill switch: a newly built section joins the chunk-bound mask in its upload frame again (Round 23). Default: when Sodium lists it — at once if Sodium presents it in that frame (within 64 blocks or a blocking job), else at Sodium's next render-list build. The old order masked far new sections one frame before Sodium drew them: grey squares on water and ice along the loading front |
| `VOXY_BOUND_RESET_QUEUES=0` | Kill switch (H1): when Sodium builds a new RenderSectionManager (renderer reload, F3+A, shader toggle, RD change, world join) the chunk-bound mask clears only its resident set again, keeping the mirror seed queued by Voxy's earlier reload. Default: the queues are dropped too, so Sodium's re-announcements do not hit `Chunk already in map` and a stale wider ring cannot linger |
| `VOXY_BOUND_MIRROR_ALWAYS=0` | Kill switch (H2): the static bound-mask mirror stops following Sodium while no Voxy renderer exists ('Enable Voxy' OFF), so turning Voxy back on seeds a frozen, stale set. Default: it keeps following |
| `VOXY_DEBUG_RELOAD=<s>` / `VOXY_DEBUG_TOGGLE="<s>:off;<s>:on"` | Diagnostic: `LevelRenderer.allChanged()` (the Apply / F3+A renderer reload) once, `<s>` seconds after joining; the 'Enable Voxy' switch at fixed seconds after joining. Nothing is saved to the config. Marker `[VOXY_DEBUG_ACTION]` |
| `VOXY_LOD_WATER_STEP=0` | Kill switch: LOD water of levels >= 1 back at its cell top (63.89 for the sea instead of 62.89). Its 1-block step up at the level-0/level-1 ring showed the dark seafloor as a thin zigzag line on far water (H10) |
| `VOXY_LOD_STEP=water` / `=0` | LOD step rule. Default `all`: every face of LOD levels >= 1 (terrain, ice, snow and water) one block lower, so at the level-0/level-1 ring and the vanilla/level-1 edge the farther side is never higher and no unmeshed step riser shows what lies behind (the "stair" line on snow and ice; the grey sunken patches on ice). `water` keeps only the H10 water rule, `0` (or `VOXY_LOD_WATER_STEP=0`) disables both |
| `VOXY_LOD_ICE_OPAQUE=0` | Kill switch: LOD ice and frosted ice back in the translucent layer. Default: baked solid, so they occlude the water top beneath; otherwise frozen water stacks three translucent LOD layers that the no-pack translucent pass blends unsorted (grey rectangle mosaic on far ice). Takes effect at model bake (restart) |
| `VOXY_LOD_FLAT_HEIGHT=0` | Kill switch (H16): snow layers, carpets, bottom slabs and other full-footprint blocks lower than a block are full cubes in the LODs again. Default: their top face at its real height and short sides, so LOD snow meets vanilla snow without the dark stair line at the seam. Takes effect at model bake (restart) |
| `VOXY_LOD_BAMBOO=0` | Kill switch (H18): bamboo is a block-wide column with a flat top in the LODs again. Default: a thin stalk with its leaves, centred in the cell at every LOD level and under the spyglass. Takes effect at model bake (restart). Marker `[Metal-LODTEST] H18 bamboo` |
| `VOXY_LOD_LEAVES_DARK=0` | Kill switch (H19): LOD leaves back to the bright fill (7–36 % brighter and greener than vanilla's far leaves). Default: leaves keep vanilla's dark hole pixels, so their far mips darken like vanilla's `dark_cutout` mips and the LOD canopy matches the Sodium canopy next to it. Takes effect at model bake (restart). Marker `[Metal-LODTEST] H19 leaves` |
| `VOXY_LOD_BAMBOO_UNSHADED=0` | Kill switch (H19): leafy bamboo gets the stalk's side-face shade again, so LOD bamboo leaves read olive and about 17 % darker than Sodium's. Default: unshaded, like vanilla's bamboo leaves. Takes effect at model bake (restart). Marker `[Metal-LODTEST] H19 bamboo leaves unshaded` |
| `VOXY_LOD_BRIGHTNESS_AO=0` | Kill switch (H19): the 0.92 AO stand-in (`VOXY_LOD_BRIGHTNESS`) darkens every opaque LOD quad again. Default: only quads whose model has AO, as in vanilla; plants and bamboo leaves keep Sodium's brightness. Marker: the `LOD brightness compensation` log line says `AO models only` |
| `VOXY_LOD_MSAA=1` | Kill switch (H17): the LOD pass back to one sample per pixel. Default 4 samples (`=2` for 2x) on the plain path, no Iris pack: far snowy slopes stop crawling when the camera turns. Marker `[Metal-LODTEST] H17 LOD MSAA` |
| `VOXY_LOD_UV_CENTROID=0` | Kill switch (H20): no `centroid` on the LOD atlas `uv` under MSAA. With it off, ice and water seen from above show a grid of 1 px lines along quad edges (the edge pixels read the neighbouring atlas tile). Marker `[Metal-LODTEST] uv centroid ON` |
| `VOXY_WATER_ANIMATE=0` | Kill switch: freeze LOD water animation |
| `VOXY_LOD_CLOUDS=0` | Kill switch: vanilla clouds only (no clouds over the LODs); with BSL, the pack's clouds as the pack ships them |
| `VOXY_LOD_CLOUDS_DEBUG=1` | Magenta LOD cloud layer (shows where the vanilla cloud mesh hands over); with BSL, a solid cloud slab over the LOD terrain coverage |
| `VOXY_HOT_SERIALIZE=1` | Submit+wait per traversal iteration (race diagnostic, slow) |
| `VOXY_LIGHTMAP_SYNC_EVERY_FRAME=1` | Kill switch: readback MC's lightmap every frame instead of only when MC rewrote it (client tick) |
| `VOXY_BRIDGE_RESYNC=every` | Kill switch: re-specify IOSurfaces into GL on every acquire (pre-lever-C); default `once` per bridge per Metal frame |
| `VOXY_FOG_SMOOTH_MS` | Fog colour smoothing constant (0 disables) |
| `VOXY_METAL_DRAW_BATCH=0` | Kill switch: per-draw Java JNI indirect loop instead of the native batch (A/B on `[Metal-TIMING] jniDrawLoop`) |
| `VOXY_MESH_XFACE_FIX=0` | Kill switch: pre-sync swapped -x/+x face-occlusion indices at section borders (upstream 1f985ce6). A no-op on Metal (the Metal bakery's occlusion bits are symmetric per axis); visible on GL only |
| `VOXY_MESH_FACE_OCCLUDE=0` | Kill switch: pre-sync non-opaque face predicate (before upstream 6212d95c/514d0a0e/89b3dacc). On Metal the only visible difference is LOD-0 plant side faces next to an occluding neighbour: the Metal bakery marks fences, panes, walls, slabs and stairs fully opaque, so they never reach this predicate (a null A/B on them is expected). Metal test scene: a flower or crop in a 1-wide slot between two full blocks on the first LOD ring. On GL: fences, panes, walls, slabs, stairs at LOD 0-2; marker `[Voxy-SYNC] mesher face-occlusion predicate` |
| `VOXY_MIP_BLOCKLIGHT_FIX=0` | Kill switch: pre-sync Mipper block-light packing (upstream 0eb618d1 fixed block light lost at every mip level ≥ 1). Stored mips only refresh on re-import; marker `[Voxy-SYNC] mip block-light packing` |
| `VOXY_MIP_SURFACE=0` | Kill switch (H13/H20): upstream opacity mip instead of the Metal surface mip (water, ice and snow stay the top of coarse LOD cells; ties go to the top layer). It changes what is stored: run `/voxy import current` after flipping it (on Metal, importers rewrite every mip level with whichever rule is active). Marker `[Voxy-MIP] surface mip` |
| `VOXY_MIP_SNOW=0` / `=2` | Snow part of the surface mip (H13): `0` drops it, `2` also turns snowy leaf cells into snow blocks (default: only snow on the ground, not on leaves). Re-import after flipping it |
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
| `VOXY_METAL_ARENA_RAM_SCALE=0` | Restore the fixed 4 GB Metal geometry arena. Default sizes it at RAM/16 clamped to 1-4 GB, because the whole arena becomes resident and wired on first GPU use. Marker `[Metal-MEM] geometry arena`; `-Dvoxy.geometryBufferSizeOverrideMB=N` still overrides |
| `VOXY_VX_SKY_FLOOR_UNDERWATER=1` / `=skip` | Opaque LOD pixels seen through LOD water. Default: the sky-light floor shrinks with the water column above them (`15 - column` levels, capped at the 12/15 floor), like vanilla water. `=skip` gives them no floor at all; `=1` floors them like dry land (pre-2026-09-26: far seafloors lit as if at the surface). Marker `[Metal-LODTEST] vx sky-light floor runtime` must show locations >= 0. `VOXY_VX_SAMPLER_FIX=0` also switches this off |
| `VOXY_VX_SKY_FLOOR_WATER_ATTEN=<f>` | Sky levels lost per block of LOD water for the floor above (default 1.0 = vanilla water) |
| `VOXY_GEOMETRY_FIT_GATE=0` | Metal: restore the `Geometry OOM` throw (worker death, crash) when the geometry arena has no contiguous block for a section. Default defers that section until the cleaner frees room. Marker `[Metal-MEM] geometry arena fragmented` |
| `VOXY_METAL_MSL_PIN=0` | Compile runtime MSL with the newest language version the OS offers instead of pinning MSL 3.0 (the macOS 13 floor). The `Metal device:` log line shows the mode |
| `VOXY_CTOR_FAIL_ONCE=1` | Diagnostic (remove once A11 is verified): the first renderer construction of the process throws after the worker threads start, to exercise the constructor-failure cleanup. Use with a shader pack active: the pack turns off for that session only and the renderer is rebuilt. Expect `releasing 8 partially built components` |
| `VOXY_IRIS_DISABLE_PERSIST=1` | Restore upstream's handling of a renderer failure with a pack active: Iris saves enableShaders=false. Default on Metal: the disable is not saved (iris.properties restored); reloading shaders (R) tries the pack again |
| `VOXY_BOUND_TRANS_SPLIT=all` / `=0` | Bound-mask coverage-epsilon split. Default: only under a vx-contract pack with the near-cull on (marker `bound-mask trans split ON`/`OFF`). `all` forces it without a pack (pre-2026-09-26), `0` disables it |
| `VOXY_POOL_SHUTDOWN_FINALLY=0` / `VOXY_SHUTDOWN_PER_STEP=0` | Restore the old teardown: the service pool only released its workers if every service had shut down, and renderer teardown stopped at the first throwing step |
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
- **No optional-mod types in a mixin outside that mod's mixin package.**
  Mixin looks up every class a mixin body references when it applies the
  mixin, so a single Iris reference in a Sodium mixin crashed every world
  join without Iris (H5). Put that code in a plain class
  (`MetalVxResolveHooks`); `scripts/testing/check-mixin-optional-refs.sh`
  checks the built jar.
- **No JOML `getToAddress`; use `JomlCompat.put`.** On macOS Java 21 JOML's
  `MemUtilUnsafe` cannot initialise and `getToAddress` throws (H6).
  `./gradlew testJomlCompat -PtestJava=launcher` checks the replacement.
- **No reflection by name on Minecraft classes.** The release jar runs on
  intermediary names (H7). Use `voxy.accesswidener` or a mixin accessor.

## Conventions

- Branches: `feature/…` for work, `docs/…` for documentation; PRs target
  `dev`. `dev` is push-protected — merge via PR.
- Commits document root causes, not just changes (see `git log` for the
  house style).
- Every behavioral fix ships with an env kill switch until the user has
  verified it on-device; falsified experiment knobs get removed or marked
  stale in the next round.
