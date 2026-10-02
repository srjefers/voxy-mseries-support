# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## What this is

A fork of [Voxy](https://github.com/MCRcortex/voxy) (a Minecraft LOD / far-distance
rendering mod) ported to run on **Apple M-series GPUs**. The problem it solves:
upstream Voxy needs OpenGL 4.6 (compute shaders, multi-draw-indirect, persistent
mapping); macOS is frozen at OpenGL 4.1. So this fork runs **Voxy's entire LOD
renderer on Metal** while Minecraft + Sodium keep rendering on GL, and bridges the
two every frame through a shared **IOSurface**. Fabric mod, Minecraft 1.21.11,
requires Sodium `mc1.21.11-0.8.1`. Status: alpha, ~110 FPS on an M4.

The Windows/Linux GL path is upstream Voxy unchanged — all porting work lives
behind `backend != OPENGL` gates or is provably no-op on GL.

## Commands

```bash
VOXY_FORCE_METAL=1 ./gradlew runClient   # dev client with the Metal path (the everyday command)
./gradlew runClient                      # without the opt-in, Voxy disables itself → plain Sodium
./gradlew build                          # full build incl. native dylib + remapped jar → build/libs/
cd native/metal && ./build.sh [Debug|Release]   # rebuild libvoxy_metal.dylib standalone (gradle does this automatically)
```

- **`VOXY_FORCE_METAL=1` is the required opt-in.** Without it `VoxyClient` force-disables
  Voxy on non-GL backends (so the user sees normal Sodium rendering instead of a crash).
- **Headless-ish debug loop:** `VOXY_QUICKPLAY="<world folder name>" VOXY_FORCE_METAL=1 ./gradlew runClient`
  auto-joins a singleplayer world on launch so diagnostics land in logs without menu interaction.
- **Logs:** `run/logs/latest.log`. Grep the `[Metal-*]` markers (full table in `docs/DEVELOPMENT.md`).
- **Runtime shaders** are GLSL→SPIR-V→MSL compiled on first use and cached in
  `~/.voxy/shader-cache` keyed by source hash. Delete the directory to force recompilation.
- **Gradle daemon is disabled** (`org.gradle.daemon=false`); builds are cold each time.

### Tests

There is **no JUnit suite**. Verification is on-device + a set of standalone JavaExec
smoke tests (one per Metal/Vulkan milestone) in `me.cortex.voxy.tools`. Run one with:

```bash
./gradlew testShaderCompiler     # GLSL→SPIRV→MSL over representative Voxy shaders
./gradlew testMetalTriangle      # graphics pipeline + draw + readback
./gradlew testMetalCompute       # compute dispatch + SSBO writeback
./gradlew testMetalIcb           # MTLIndirectCommandBuffer (the MDIC primitive)
./gradlew testIOSurfaceBridge    # IOSurface-backed MTLTexture allocation
# also: testMetalRender, testMetalVertexBuffer, testVulkan{Loader,Compute,Triangle,Clear}
```

Most require a macOS aarch64 host with `libvoxy_metal.dylib` built. Vulkan ones use
MoltenVK and auto-set `VK_ICD_FILENAMES` if the ICD is present.

## Architecture

Per-frame, Metal renders the LOD frame into an IOSurface; a GL compositor blits it
into MC's main render target at the head of Sodium's SOLID pass; Sodium then draws
near terrain on top. The Metal frame is currently **synchronous** — 3 ×
`waitUntilCompleted` per frame (async is the main pending perf work).

**Source layout** (`src/main/java/me/cortex/voxy/`):
- `client/` (~211 files) — all rendering. Key subtrees under `client/core/`:
  - `gpu/` — **cross-backend abstraction** (`RenderBackend`, `IGpuPipeline`,
    `ComputeEncoder`, `RenderEncoder`, `IGpuTexture`, …). Upstream raw-GL call
    sites were migrated onto these interfaces. `RenderBackendFactory.get()` picks
    Metal on mac+aarch64 (if the dylib loads) else GL.
  - `gl/`, `metal/`, `vulkan/` — backend implementations. `metal/MetalNative` is
    the JNI surface over `native/metal/src/*.mm`. (Vulkan/MoltenVK was an earlier
    exploration; Metal is the shipping path.)
  - `rendering/` — the render pipeline: `hierachical/` (HOT occlusion traversal),
    `section/backend/mdic/` (MDIC = the `glMultiDrawElementsIndirectCount`
    emulation, central terrain draw path), `building/`, `post/`.
  - `model/bakery/` — bakes block/fluid textures into the model atlas. Upstream
    uses GL FBO readbacks (SIGBUS on Apple GL); the Metal bakery renders each
    model's 6 faces with a budget renderer instead.
  - `interop/` — the **IOSurface bridge** (`IOSurfaceBridge`,
    `IOSurfaceBridgeCompositor`) plus the Iris depth side-channel
    (`MetalDepthExport`/`MetalDepthRestore`).
  - `iris/` + `mixin/iris/` — Iris shader-pack integration (the "vx contract": LOD
    pixels injected into the pack's gbuffer/colortexes; see `docs/VX-CONTRACT-METAL-DESIGN.md`).
  - `mixin/` — hooks into `sodium/` (compositor hook is `MixinDefaultChunkRenderer`
    at the SOLID-pass head), `minecraft/`, `iris/`, `nvidium/`, `flashback/`.
- `common/` (~64 files) — platform-agnostic world storage & voxelization:
  `config/storage/` has lmdb / rocksdb / redis / sqlite / inmemory backends,
  plus `voxelization/`, `thread/`, `world/`.
- `commonImpl/` — common/server-side init (`VoxyCommon` main entrypoint), world
  importers, chunky mixins.
- `tools/` — the smoke tests above.

**Entry points** (`fabric.mod.json`): client `VoxyClient`, main `VoxyCommon`,
modmenu `ModMenuIntegration`, sodium config `VoxyConfigMenu`. Mixin configs:
`client.voxy.mixins.json` (package `client.mixin`), `common.voxy.mixins.json`
(package `commonImpl.mixin`). Access widener: `voxy.accesswidener`.

**Native** (`native/metal/`): Objective-C++ JNI (`*.mm`) implementing `MetalNative`,
compiled by the `buildMetalNative` Gradle task into
`src/main/resources/natives/macos-arm64/libvoxy_metal.dylib` (checked in; rebuilds
when `.mm` sources change). ARC enabled; handles are `jlong` +1-retained ObjC
pointers — Java must `mtlRelease()`. `libMoltenVK.dylib` ships alongside for the
Vulkan path.

## Hard-won constraints — DO NOT regress these

These are non-obvious and were each paid for with a crash or invisible-render bug.

- **No GL readbacks on the hot path.** `glGetTexImage`-class calls on Apple GL are
  the established SIGBUS source. The 16×16 lightmap mirror is the one proven
  per-frame exception; everything else works from CPU-resident or Metal-side data.
- **The GL backend stays byte-identical.** Every Metal change is either inside a
  `backend != OPENGL` gate or provably no-op on GL.
- **Metal compute encoders don't fence indirect-dispatch argument fetches.** If
  pass N writes the dispatch args of pass N+1, they need *separate encoders* —
  `memoryBarrier(scope:)` is not enough (this caused the HOT traversal staleness).
- **Threadgroup size comes from the shader, not the descriptor.** Descriptor-vs-shader
  drift fails *silently* on Metal (dispatches the descriptor value; GL uses the
  shader's). `ComputeLocalSizeParser` parses the effective `layout(local_size_*)`
  and PSOs pin `maxTotalThreadsPerThreadgroup`. A 32-vs-128 mismatch here caused
  the long-standing flicker bug.
- **Shared GLSL must avoid UB the Metal compiler exploits:** no left-shifts of
  negative ints (use multiplication), no `(v<<L)>>R` sign-extension (use
  `bitfieldExtract`), no boolean-select `mix()` overloads (use ternaries) in any
  shader live on the Metal path.
- **Don't trust GL state at the Sodium SOLID hook.** The viewport can be polluted
  (16×16 lightmap pass) and MC's main RT does not contain the sky at composite
  time. Size/target from `Minecraft.getMainRenderTarget()`.

## Conventions

- Branches: `feature/…` for work, `docs/…` for docs. PRs target `dev`, which is
  **push-protected** — merge via PR. Pushing uses the `srjefers` gh account.
- Commit messages document **root causes**, not just diffs (see `git log` for the house style).
- **Every behavioral fix ships with an env kill switch** until verified on-device,
  so changes can be A/B'd by flipping one variable; falsified knobs get removed.
- Common env toggles (full table in `docs/METAL-MIGRATION.md` / `docs/DEVELOPMENT.md`):
  `VOXY_BRIDGE_SOLID_TEST=1` (green bridge, isolates bridge/sync from content),
  `VOXY_BOUND_DEBUG=1` (red-tint the chunk-bound depth mask),
  `VOXY_NO_DEPTH_BOUND=1` / `VOXY_WATER_ANIMATE=0` / `VOXY_BAKERY_OFF=1` (kill switches),
  `VOXY_LOD_NO_CULL=1`, `VOXY_HOT_SERIALIZE=1` (race diagnostic, slow),
  `VOXY_AUTO_SCREENSHOT=<seconds>`.

## Docs

- `docs/METAL-MIGRATION.md` — architecture, root-cause/fix table, full env-var reference, backlog.
- `docs/DEVELOPMENT.md` — build/run/debug workflow, log-marker table, the constraints above.
- `docs/MIGRATION-HISTORY.md` — every milestone, bug, and fix that got this working.
- `docs/VX-CONTRACT-METAL-DESIGN.md` — the in-progress native Iris shader-pack contract on Metal.
- `docs/LOD-FLICKER-INVESTIGATION.md` — historical flicker investigation notes.
