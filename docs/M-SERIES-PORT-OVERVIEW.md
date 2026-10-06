# Voxy → Mac M-Series Port — Overview

Audience: human developer (or PM) wanting a quick read on where this branch stands.
For the full technical handoff including file paths, gotchas, and APIs, see `M-SERIES-PORT-STATE.md`.

---

## The problem

[Voxy](https://github.com/MCRcortex/voxy) is a Minecraft Java mod that adds far-distance ("LOD") chunk rendering driven by GPU compute shaders. It needs OpenGL 4.3+ features (compute shaders, SSBO, indirect draw with count, persistent mapped buffers, image load/store). Apple deprecated OpenGL on macOS at version 4.1 — none of those features are available — so Voxy currently doesn't run on any Mac, including Apple Silicon (M1+).

This branch is the work-in-progress port to make Voxy run on Mac M-series Macs by adding two new render backends:
- **Metal direct** — uses Apple's native graphics API.
- **Vulkan via MoltenVK** — uses Vulkan 1.2+ (translated to Metal under the hood by MoltenVK).

Both are being prototyped in parallel so we can benchmark them and pick the long-term winner.

---

## The approach

Voxy already had a partial render-backend abstraction (`RenderBackend` interface + `MetalRenderBackend` skeleton) before this branch picked up. We've extended that abstraction so that Voxy's render code can talk to Metal, Vulkan, or OpenGL without per-backend `if` checks at every call site.

The plan, in milestones:

1. **M0–M2:** Get the dev environment working, validate shader translation, expand the abstraction.
2. **M3 / M4:** First render — clear the screen to a color on each backend.
3. **M5 / M6:** Render a triangle on each backend.
4. **M7 / M8:** Run a compute shader on each backend.
5. **M9:** Migrate Voxy's code from raw OpenGL calls to the abstraction (file by file).
6. **M10 / M11:** Bridge Voxy's GPU output back into Minecraft's existing OpenGL pipeline (via Apple `IOSurface`).
7. **M12 / M13:** Validate full LOD distance rendering inside Minecraft.
8. **M14:** Benchmark Metal vs Vulkan and decide.

**M0 through M12 are closed.** **M13 chunks 2 + 5 are closed** (MC lightmap on Metal, fog parity); **M13 chunks 1 + 3 are wired end-to-end but parked behind runtime gates** because each triggered a separate regression when enabled. M13 chunks 3b + 4 haven't started. Status as of 2026-05-13.

---

## Where we are now

**~80 commits on the branch. 12 smoke tests pass on this Apple M4 Max. Voxy renders LOD chunks end-to-end on Metal inside Minecraft, behind Sodium's near terrain, full-screen — user-confirmed 2026-05-12.**

- ✅ Dev env (JDK 24, Xcode CLT, CMake) verified.
- ✅ Runtime shader compiler — translates Voxy's GLSL to SPIRV (for Vulkan) and to MSL (for Metal) on the fly. Disk cache. 28 representative shaders compile to SPIRV (27 to MSL — `hiz.comp` deferred).
- ✅ Encoder API — Voxy describes render or compute passes through a backend-agnostic API instead of raw GL, with real implementations on Metal **and** GL (so Win/Linux users keep working).
- ✅ **Metal**: clear color, triangle, compute dispatch + SSBO write, vertex layout + indirect draw, indirect command buffers (ICB), render passes against IOSurface bridges with depth attachments, encoder-based drawIndexedIndirect — all working end-to-end.
- ✅ **Vulkan via MoltenVK**: clear color, triangle, compute dispatch with descriptor sets all working.
- ✅ Pipeline state config (depth, blend, cull, polygon mode), samplers, push-constant-equivalent uniforms — all wired up.
- ✅ **M9 file migration**: every Voxy file that builds a pipeline now goes through the cross-backend abstraction. `AsyncNodeManager`, `NodeCleaner`, `HierarchicalOcclusionTraverser`, `MDICSectionRenderer` (5 compute prepasses + 2 terrain graphics pipelines) are all fully encoder-clean — no raw GL on the hot path. Iris pipelines gated to GL-only by design.
- ✅ **M10 IOSurface bridge**: an IOSurface is wrapped as both an `MTLTexture` (Metal render target) and a `GL_TEXTURE_RECTANGLE` (sampleable from MC's GL compositor), with the GL-side bind path through `CGLTexImageIOSurface2D`.
- ✅ **M11 end-to-end visual verification (run 2026-05-10/11)**: with `VOXY_FORCE_METAL=1`, MC boots, Voxy initializes on Metal, Sodium chunk workers run, the IOSurface bridge composites cleanly. MC closes without hanging.
- ✅ **M12 LOD distance migration (closed 2026-05-12)**: `runPipelineMetal` runs the full pipeline (DownloadStream + AsyncNodeManager + NodeCleaner + HOT + MDIC's 5 compute prepasses + 3 render passes — opaque, temporal, translucent). `IOSurfaceBridgeCompositor` blits the bridge full-screen at HEAD of Sodium SOLID so MC's near terrain overdraws Voxy LOD via depth. User-confirmed: no diagnostic strip, LOD pyramid visible across the horizon, all chunks distinct face-shaded blocks.

The native library (`libvoxy_metal.dylib`) exposes ~100 JNI entry points.

**Each smoke test has caught at least one real bug.** The `VertexLayout` test caught an off-by-9 in our `MTLVertexFormat` enum mapping that the SDK header would have flagged. The ICB test caught a per-slot pipeline binding requirement. The runtime tested catches included GL 4.2 `glMemoryBarrier` slipping into `DownloadStream.commit` (caught on Apple's GL 4.1 context) and `MetalBuffer.id()` masquerading as a GL buffer name in `AsyncNodeManager`'s `glBindBufferRange` — both fixed via encoder-routing.

---

## What's still missing

Two chunks of M13 closed cleanly (lightmap, fog). Two more were
implemented end-to-end but **each enable triggered a separate
runtime regression**, so we landed the infrastructure and reverted
the call sites back to M12-stable. The remaining work is debugging
those gates rather than building new code from scratch.

1. **Model texture atlas on Metal — parked.** Metal-native bakery is
   implemented (`AtlasMirror`, `MetalBudgetBufferRenderer`,
   `MetalViewCapture`, `ModelTextureBakery.renderToStreamMetal`,
   `mtlTextureGetBytes` JNI). Projection-matrix Z-clip + Y-axis-flip
   fixes verified in shader smoke tests. **Blocker:** enabling via
   `VOXY_BAKERY_FORCE=1` triggers the same Sodium crash the GL bakery
   had — `SharedQuadIndexBuffer.grow → glMapBufferRange` returns null
   mid-frame, ~30 s after chunk load starts. Working theory: the
   bakery's `mtlCommandBufferWaitUntilCompleted` calls interfere with
   Apple GL's persistent-mapped buffer state on the shared GPU
   context. Default is OFF — LOD chunks fall back to the
   `VOXY_NO_ATLAS` hash-colour placeholder.

2. **MC lightmap on Metal** — ✅ closed 2026-05-12.

3. **MC depth import — parked.** `DepthMirror` lifts MC's GL depth
   into a Shared Metal texture, `mtlTextureNewSubresourceView` +
   `IGpuTexture.createView(level, count)` support per-mip view
   creation, `HiZBuffer.buildMipChain(IGpuTexture, ...)` runs the
   encoder-driven mip-chain build. **Blocker:** when wired, LOD
   chunks vanished at the horizon — the HiZ pyramid format
   (`Depth32Float_Stencil8` from `GL_DEPTH24_STENCIL8`) probably
   doesn't sample cleanly as `sampler2D` in MSL. Default is the M12
   zero-init pyramid.

3b. **Depth-test raster cull** — not started. Replaces
    `force_all_visible.comp`; needs MC's depth as a Metal depth
    *attachment* (not just sampleable). Unblocked once chunk 3 is
    re-enabled.

4. **`finish()` blit + SSAO on Metal** — not started. Unblocked once
   chunk 3 is re-enabled.

5. **Fog + atmosphere parity** — ✅ closed 2026-05-13.

Total estimate for the rest: **~2-3 days** if the parked chunks
debug cleanly with the first-attempt hypotheses, ~4-5 days if the
Sodium interaction needs a queue-separation rewrite.

---

## How to test it today

```bash
VOXY_FORCE_METAL=1 ./gradlew runClient
```

The `VOXY_FORCE_METAL=1` env var is **mandatory** on M-series. Without
it Voxy detects the non-OpenGL backend at `VoxyClient.java:65-82` and
disables itself entirely (log emits `Not creating renderer due to
disabled` from `MixinLevelRenderer.createRenderer`). The visible
symptom of forgetting the env var is "LOD chunks don't appear" —
because Voxy is silently off, not because of any rendering bug.

With the env var set, you should see LOD chunks in hash colours
fading into MC's environmental fog at the horizon. No crashes.

---

## What this looks like as a release

The end-state Mac user experience:

1. Drop the mod JAR into a Modrinth profile (Fabric loader, Sodium 0.8.1, MC 1.21.11).
2. Launch.
3. Voxy auto-selects Metal (no `VOXY_FORCE_METAL=1` needed once auto-detect is wired — currently the user must opt in).
4. World renders with full LOD distance, same as Win/Linux, with real block textures, MC lightmap, fog, and SSAO.
5. Benchmark numbers compare Metal vs Vulkan to inform whether we keep both backends or consolidate.

**What's left between "today" and "release":** clear the M13 chunk 1 + 3 runtime blockers (the staged code is already there — see the previous section), finish chunks 3b + 4 (raster cull + SSAO/finish), drop the `VOXY_FORCE_METAL` opt-in gate, and run the M14 Metal-vs-Vulkan benchmarks. Realistically **~1-2 weeks of focused work** for a beta-quality release given how much is staged; longer if either parked chunk needs a deeper architectural fix.

---

## Where to look for details

- **Full technical state**: `docs/M-SERIES-PORT-STATE.md` (this folder)
- **Smoke tests**: `src/main/java/me/cortex/voxy/tools/*SmokeTest.java`
- **Encoder API**: `src/main/java/me/cortex/voxy/client/core/gpu/`
- **Metal backend**: `src/main/java/me/cortex/voxy/client/core/metal/`
- **Native bridge**: `native/metal/src/`
- **Branch**: `claude/opengl-mac-migration-analysis-6319V`
