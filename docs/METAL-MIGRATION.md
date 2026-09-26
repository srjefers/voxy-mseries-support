# Voxy on Apple Silicon — Metal Migration & Implementation Notes

This document describes how Voxy's LOD renderer was ported to run on Apple
M-series GPUs (tested on an M4), the architecture that makes it possible, the
root causes that were found and fixed along the way, and the runtime knobs
available for debugging. It covers the work on `feature/voxy-lod-textures`
(PR #3) on top of the 88-commit base migration already merged into `dev`
(PR #2).

## Why a port is needed at all

Voxy upstream targets OpenGL 4.6 (compute shaders, multi-draw-indirect,
persistent-mapped buffers, `glMultiDrawElementsIndirectCount`). macOS ships a
frozen OpenGL 4.1 — none of that exists. Minecraft itself (with Sodium) runs
on Apple's GL 4.1; Voxy's renderer cannot. The port therefore runs **Voxy's
renderer on Metal** while Minecraft keeps rendering on GL, and bridges the
two worlds per frame.

## Architecture

```
┌────────────────────────────  Minecraft / Sodium (Apple GL 4.1) ───────────┐
│  vanilla terrain, entities, clouds, UI …                                  │
│        ▲                                                                  │
│        │ ④ compositor (glBlitFramebuffer at the head of                   │
│        │    Sodium's SOLID pass → MC's main RenderTarget)                 │
│  ┌─────┴──────┐                                                           │
│  │ IOSurface  │  shared, zero-copy CPU/GPU surface                        │
│  └─────▲──────┘                                                           │
└────────│──────────────────────────────────────────────────────────────────┘
         │ ③ Metal renders the LOD frame into the IOSurface
┌────────┴────────────────────  Voxy (Metal)  ──────────────────────────────┐
│ ① compute: hierarchical occlusion traversal (HOT) → render list           │
│ ② compute: prep → cull stub → cmdgen → prefix-sum → translucent gen       │
│ ③ render:  opaque → temporal → translucent (MDIC emulation)               │
│    model atlas bakery (block/fluid textures baked on Metal)               │
│    MC lightmap + atlas mirrored from GL each frame/bake                   │
└────────────────────────────────────────────────────────────────────────────┘
```

Key components (all under `me.cortex.voxy.client.core`):

- **`gpu/` abstraction** — cross-backend interfaces (`RenderBackend`,
  `IGpuPipeline`, `ComputeEncoder`, `RenderEncoder`, …) with GL and Metal
  implementations. Upstream call sites were migrated to these instead of raw
  GL.
- **`metal/` backend** — JNI bindings (`MetalNative` ⇄
  `native/metal/src/*.mm`) over MTLDevice/MTLCommandQueue. One lazily-opened
  command buffer per frame section; `submit()` commits + waits
  (synchronous M3-era model; async is future work).
- **Shader pipeline** — upstream GLSL 4.6 is compiled at runtime:
  `shaderc` (GLSL → SPIR-V, `-O`) then SPIRV-Cross (SPIR-V → MSL 3.0), with
  a content-hash disk cache in `~/.voxy/shader-cache`.
- **IOSurface bridge** (`interop/`) — Metal renders the LOD color into an
  IOSurface-backed texture; `IOSurfaceBridgeCompositor` re-binds it to a GL
  rectangle texture each frame (`CGLTexImageIOSurface2D`, required for
  cross-API coherence) and blits it into MC's main render target at the head
  of Sodium's SOLID terrain pass. Sodium then draws near terrain on top.
- **Model bakery on Metal** (`model/bakery/`) — upstream bakes block/fluid
  textures through GL FBO readbacks, which SIGBUS on Apple GL. The Metal
  bakery renders each model's 6 faces with a budget renderer
  (`MetalBudgetBufferRenderer` + `MetalViewCapture`) into the model atlas.
- **Mirrors** — MC's lightmap (16×16) is copied from GL to a Metal texture
  every frame (`LightMapHelper`); the block atlas is mirrored at bake time
  (`AtlasMirror`).

### Frame timeline (Metal path)

1. Sodium SOLID pass head (`MixinDefaultChunkRenderer`) → `setupViewport`
   (captures matrices + Sodium's `FogParameters`, temporally smoothed).
2. Node manager / cleaner compute + HOT traversal → **submit/wait #1** →
   CPU reads the LOD request queue.
3. `buildDrawCalls`: prep → cull-stub → cmdgen → prefix-sum → translucent
   gen → **submit/wait #2** → CPU reads the GPU-written draw counts.
4. Render pass into the bridge: opaque → temporal → translucent, draw counts
   clamped to the real GPU counts → **submit/wait #3**.
5. GL compositor blits the bridge into MC's main RT; Sodium renders near
   terrain over it.

## Root causes found and fixed (June 2026 stabilization)

| Symptom | Root cause | Fix | Commit |
|---|---|---|---|
| LODs/water flickering content↔transparent every few frames, even stationary | Compute pipeline descriptors declared threadgroup size 32 while the shaders declare 128/256; Metal dispatches the descriptor value (GL uses the shader's), so only ~25 % of sections got draw commands, in atomicAdd-scrambled order — a different subset every frame | `ComputeLocalSizeParser` parses the effective `layout(local_size_*)` from the preprocessed GLSL and Metal uses it as authoritative `threadsPerThreadgroup`; PSOs pin `maxTotalThreadsPerThreadgroup` so an under-provisioned pipeline fails loudly at creation | `abcf14b3` |
| Terrain vanishing at screen edges; spyglass made *everything* transparent | Undefined behavior in shared shaders that Metal's compiler exploits: left-shifts of negative ints, sign-extension `(v<<L)>>R` idioms, boolean-select `mix()` overloads | Rewritten as multiplication / `bitfieldExtract` / ternaries — bit-exact, verified against the Java bit-packers | `76159281` |
| GPU readbacks (e.g. node cleaner visibility) returned stale CPU data | Stream buffer copies committed their own command buffers ahead of the still-uncommitted frame work | Copies are encoded into the frame's active command buffer (request order = execution order); fences ride the same buffer, with deferred signaling while the bakery holds a render pass open | `abcf14b3` |
| Water rendered as "grey seafloor" / water faces missing entirely | The Metal bake placed 5 of 6 fluid face quads exactly on the far clip plane; float error clipped them → zero-alpha bakes → the mesher marked water faces nonexistent | Bake depth range compressed to NDC [0.25, 0.75] (depth is unused by the bake); `[Metal-WATERBAKE]` one-shot diagnostic logs per-face alpha coverage | `1117dca2` |
| "Empty squares" lattice on the ocean | A border-face meshing workaround emitted full-column water "walls" on both sides of every section border; with depth-write-off translucency they band through the surface | Workaround removed (the LOD-seam case it targeted never needed it — border culls already pass when the neighbour is air) | `9186dfb2` |
| Whole far field strobing light/dark when crossing the water surface | MC's eye-in-fluid verdict is binary per frame; Voxy painted the entire far field from that one captured fog record | Captured fog is smoothed (~200 ms) **within** a fog type and **snapped** on type changes so near and far field flip together; fog *distance* fields track fast (≤250 ms) so underwater murk applies immediately | `9186dfb2`, `0c2c1a0c` |
| All LOD faces flat single-colour ("paper") | Derivative-based atlas mip selection collapses to the smallest mip through the Metal transpile | Fixed-mip sampling (`textureLod(..,0)`) is the Metal default (`VOXY_LOD_FIXED_MIP=0` re-enables derivative mips) | `36a8ced6` |
| Visible brightness ring at the LOD boundary | GL runs an SSAO pass that darkens LOD ≈10 % to match Sodium's baked vertex AO; the pass is parked on Metal | Interim parity multiplier on opaque LOD (`VOXY_LOD_BRIGHTNESS`, default 0.92) until the SSAO port | `0c2c1a0c` |
| 18–20 FPS collapse | 150k–450k CPU-encoded no-op draws per frame (upper-bound loops), a 12 MB/frame buffer clear, ~8–12 throwaway command buffers per frame, duplicate uniform uploads | Draw encode clamps to the GPU-written counts; per-frame zero skipped (slices are compactly written); stream copies/fences batched into the frame buffer | `abcf14b3` |
| Visible LOD↔terrain ring; LOD bleeding under near terrain (underwater X-ray contributor) | The chunk-bound depth mask (`ChunkBoundRenderer`) was GL-only, so Metal shaders compiled with `VOXY_NO_DEPTH_BOUND` and never discarded LOD inside MC's loaded-chunk volume | `ChunkBoundRenderer.renderMetal` rasterizes the loaded-chunk AABBs depth-only into `depthBoundingBuffer` (uint16 cube indices + a shader-side `section.w` count guard instead of a baseInstance tail draw); quads.frag's depth-bound test is now ON by default on Metal, sharing the LOD pass's NDC convention via `MetalMvpUtil`. Also fixes a latent upstream std140 bug (the outline cull radius read stale memory). Kill switch `VOXY_NO_DEPTH_BOUND=1`; verify with `VOXY_BOUND_DEBUG=1` (red tint) | — |

Verified on-device after the fixes: stationary flicker gone, spyglass works,
real translucent biome-tinted water, full texture detail, ~111 FPS.

## Environment variables

| Variable | Default | Purpose |
|---|---|---|
| `VOXY_FORCE_METAL=1` | off | **Required** opt-in for the Metal render path |
| `VOXY_LOD_FIXED_MIP` | `1` on Metal | `0` re-enables derivative mip selection |
| `VOXY_LOD_BRIGHTNESS` | `0.92` | Opaque LOD brightness parity (1.0 disables) |
| `VOXY_FOG_SMOOTH_MS` | `200` | Fog colour smoothing time constant (0 disables) |
| `VOXY_LOD_FRUSTUM_MARGIN` | `96` | Frustum cull margin in blocks |
| `VOXY_LOD_NO_CULL=1` | off | Disable frustum culling (debug) |
| `VOXY_LOD_ZERO_DRAWBUF=1` | off | Restore the per-frame draw-buffer zero |
| `VOXY_WATER_BORDER_FACES=1` | off | Re-enable border water faces (experiment) |
| `VOXY_LOD_FLAT_WATER=1` | off | Interim flat-blue water instead of the real path |
| `VOXY_COMPOSITE_SHADER=1` | off | Experimental alpha-discard compositor (blocked: MC 1.21.11 has no sky in the RT at the composite point) |
| `VOXY_LOD_WATER_DEBUG=1` | off | Magenta water + depth-off (geometry coverage debug) |
| `VOXY_BAKERY_OFF=1` | off | Hash-colour fallback instead of the bakery |
| `VOXY_BRIDGE_SOLID_TEST=1` | off | Solid green bridge (bridge/sync isolation test) |
| `VOXY_NO_DEPTH_BOUND=1` | off | Kill switch: skip the chunk-bound depth test (restores the pre-mask Metal behaviour) |
| `VOXY_BOUND_DEBUG=1` | off | Tint bound-discarded LOD fragments red instead of discarding (mask verification) |
| `VOXY_WATER_ANIMATE=0` | off | Kill switch: freeze LOD water (disables the `water_still` model-atlas cell re-upload that animates LOD water in step with MC) |
| `VOXY_IRIS_GBUFFER_INJECT=0` | off | Kill switch: disable the Iris gbuffer injection (LODs stay hidden while a pack is active, the pre-injection behaviour) |
| `VOXY_LIGHTMAP_SYNC_EVERY_FRAME=1` | off | Kill switch: `glGetTexImage` MC's lightmap every frame (pre-throttle behaviour) instead of only on frames where `LightTexture.updateLightTexture` rewrote it (client tick, ~20 Hz). Marker `[Metal] lightmap sync throttled to <mode>`; `[Metal-LayerB] … lightmapSync=<run>/<skipped>` |
| `VOXY_BRIDGE_RESYNC=every` | `once` | Kill switch: CGLTexImageIOSurface2D re-specification on every GL acquire (the pre-lever-C behaviour; 7+ per frame). Default re-specifies each bridge once per Metal frame (marker `[Metal-RESYNC]`, counts on `[Metal-TIMING]`) |
| `VOXY_METAL_DRAW_BATCH=0` | off (batch on) | Kill switch: per-draw Java JNI indirect loop instead of the native batched loop (A/B on `[Metal-TIMING] jniDrawLoop` at equal `draws=`) |
| `VOXY_MESH_XFACE_FIX=0` | off (fix on) | Kill switch: pre-sync swapped -x/+x neighbour face-occlusion indices at section borders (upstream 1f985ce6). No-op on Metal: the Metal bakery bakes both faces of an axis with the same occlusion bits |
| `VOXY_MESH_FACE_OCCLUDE=0` | off (upstream predicate on) | Kill switch: pre-sync non-opaque face predicate. Upstream 6212d95c/514d0a0e/89b3dacc mesh MORE faces: a same-model neighbour hides a face only when the model culls-same, and a neighbour's occluding face hides this face only when it can be occluded. Marker `[Voxy-SYNC] mesher face-occlusion predicate` On Metal the only visible difference is LOD-0 plant side faces next to an occluding neighbour: the Metal bakery marks fences, panes, walls, slabs and stairs fully opaque, so they never reach this predicate (a null A/B on them is expected). Metal test scene: a flower or crop in a 1-wide slot between two full blocks on the first LOD ring. On GL: fences, panes, walls, slabs, stairs at LOD 0-2. |
| `VOXY_MIP_BLOCKLIGHT_FIX=0` | off (fix on) | Kill switch: pre-sync Mipper packing that shifted the averaged block light out of the byte (every mip ≥ 1 stored 0/noise block light — torches/lava dark at distance). Stored mips need a re-import. Marker `[Voxy-SYNC] mip block-light packing` |
| `VOXY_SHADER_DEFINE_VERSION=N` | 1 | Value of the `VOXY` define handed to Iris packs (upstream 13230c27 contract v1; packs may test `#if VOXY >= N`). `0` = pre-sync bare define. Do not emit 2 until the depth-hack (eda60134) exists on GL and Metal. Marker `[Voxy-SYNC] Iris shader define` |
| `VOXY_SYNC_UNDEFINED_SKYLIGHT=0` | off (sky 15 on) | Kill switch: pre-sync zero fill for sections that are not in storage (upstream 47053483 fills air with sky light 15 so bordering LOD faces are not cave-dark) |
| `VOXY_SECTION_FREE_ASSERT=1` | off (log) | Upstream c7166d3f/c2dca44e assertion that a section is never freed while dirty/queued. Fork default logs and refuses to free the section (it stays alive and is saved by the thread that dirtied it): the check fires inside ActiveSectionTracker's stripe write lock (no try/finally), so a throw would leak the lock and stall ~1/64 of all section keys. `1` throws like upstream |
| `VOXY_SAVE_BACKPRESSURE=0` | off (backpressure on) | Upstream/dev a15d5e2b: the lock-free unload path enqueues saves blocking, so over the 5000-entry soft cap the unloading thread steals save jobs and the queue (and the dirty sections it pins, 256 KB each) stays bounded. `0` = non-blocking, unbounded |
| `VOXY_SYNC_CHUNK_POS_CHECK=0` | off (check on) | Kill switch: pre-sync unchecked chunk-ring slot read (upstream 6189ee38 rejects a slot holding a chunk from another position after death/teleport) |
| `VOXY_NODE_UPLOAD_CAP_KB=N` | 1000 | Per-run geometry upload cap in the async node manager (upstream 36f85026); `0` restores the uncapped loop. Unified memory on Apple Silicon may tolerate a larger cap — measure section fill rate vs frame time |
| `VOXY_GEOMETRY_ALLOC_ALIGN=N` | 128 | Geometry allocation granule in elements (power of two). Upstream 72b3ade6 went 1024 → 128 for ~20% less geometry memory; `1024` restores pre-sync |
| `VOXY_RD_PROCESS_RATE=N` | 40 | Render-distance tracker add/remove budget per rendered frame (upstream 27f82dda doubled it; the fork applies it only with the 19-bit request ids); `20` restores pre-sync |
| `VOXY_WORKER_RETHROW=0` | off (rethrow on) | Pre-sync worker failure handling: Async Node Manager / Model factory threads log and exit instead of surfacing their exception on the render thread (upstream 36f85026/02e490e0 — a crash report with the real cause instead of frozen LODs) |
| `VOXY_CTOR_FAILURE_CLEANUP=0` | off (cleanup on) | Kill switch for the A11 fix: a failed VoxyRenderSystem construction releases every component it had already built (newest first). Marker: `Voxy render system construction failed, releasing N partially built components` |
| `VOXY_IRIS_PACK_PREDICATE=mode` | hybrid | Iris 'pack enabled' predicate that also selects gbuffer-inject vs composite on Metal: `hybrid` = pack loaded AND pipeline null-or-IrisRenderingPipeline, `quick` = pre-sync `Iris.isPackInUseQuick()`, `current` = pure upstream 1952d3df (drops LODs under Iris's vanilla fallback) |
| `VOXY_FRAME_TIMING=1` | off | Print `[Metal-TIMING]` (hotWait / drawFlushWait / bridgeWait / jniDrawLoop / lightmapSync / resync) and `[Metal-VXTIMING]` (GL-side vx passes, GPU via async timer queries) every 600 frames. Close F3 while sampling: vanilla's GPU-utilization timer owns `GL_TIME_ELAPSED` |
| `VOXY_VX_TIMING=0` | off | With `VOXY_FRAME_TIMING=1`: keep only `[Metal-TIMING]` (no GL queries) |
| `VOXY_VX_TIMING_GPU=0` | off | `[Metal-VXTIMING]` CPU brackets only |

## Running

```bash
VOXY_FORCE_METAL=1 ./gradlew runClient
```

Useful log markers: `[Metal-DEFINES]` (shader variant), `[Metal-WATERBAKE]`
(fluid bake alpha coverage), `[Metal-LayerB]` (per-frame draw counts),
`IOSurfaceBridgeCompositor` (composite mode and target).

## Known limitations / backlog

- **SSAO** is not ported (interim brightness multiplier instead).
- **Sky-aware composite**: MC 1.21.11 does not have the sky in the main RT
  when Voxy composites, so the bridge's fog-coloured clear acts as the far
  sky and the alpha-discard composite cannot be enabled yet. Fixing this
  requires hooking the composite after MC's sky pass.
- **Synchronous GPU model**: 3 × `waitUntilCompleted` per frame. The fence
  machinery added in `abcf14b3` is the building block for going async
  (estimated to push well past the current ~111 FPS).
- **Visible LOD↔terrain transition on water** and residual underwater
  artifacts — under active investigation.
- **LOD water animation** (`WaterAnimator`) covers the still-water sprite
  only: the source-water model's UP/DOWN atlas cells re-upload the current
  `block/water_still` frame every frametime ticks. The `water_flow` side
  faces and flowing-water states stay frozen at their baked frame, and the
  animation phase matches MC's cadence but not its exact start offset —
  both are follow-ups.
- **Iris shader packs** render the Metal LODs via **gbuffer injection**
  (`IrisGbufferInjector`, default ON, kill switch
  `VOXY_IRIS_GBUFFER_INJECT=0`): at the head of Sodium's SOLID pass the LOD
  bridge color + an R32F depth export of the LOD pass are drawn into the
  pack's SOLID terrain framebuffer (colortex0 attachment only; the FBO's
  DRAWBUFFERS list is saved/restored) with `gl_FragDepth` unprojected from
  Voxy's MVP and reprojected through MC's vanilla MVP — i.e. the pack's
  depthtex0 convention. Because the pack sky sits at depth 1.0 before SOLID,
  LOD pixels (depth < 1.0) survive the pack's deferred/composite/final
  chain, and Iris's near terrain depth-tests over them. Voxy's own env fog
  is disabled in this mode (the pack shades the injected pixels). Expected
  pack-variability caveats: LODs receive **no per-pixel shadows** (they
  never render into the shadow map); deferred-lighting packs may shade LODs
  flat (gbuffer normals/material IDs aren't written, only color + depth);
  and pack fog saturates at MC's far plane, so very distant LODs take the
  pack's maximum fog rather than a Voxy-specific curve.
