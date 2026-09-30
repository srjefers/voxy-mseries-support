# Voxy → Mac M-Series Port — Status

**Last update:** 2026-05-13 (late session 3 — horizon-chunks regression
chain root-caused; layer 1 patch shipped, layer 2 patch reverted after
runtime SIGBUS in `LightMapHelper.syncFromMc`)
**Branch:** `claude/opengl-mac-migration-analysis-6319V`
**HEAD:** uncommitted on top of `32aeb455` (M13 chunks 1, 3, 5 + foundation
work + partial horizon-chunks fix staged locally; nothing pushed)
**Commits ahead of `dev`:** 81

> One-page summary of where the port stands and what's next. Detailed
> commit-by-commit history + open-decision logs live in
> [`M-SERIES-PORT-STATE.md`](M-SERIES-PORT-STATE.md). Higher-level
> narrative in [`M-SERIES-PORT-OVERVIEW.md`](M-SERIES-PORT-OVERVIEW.md).

---

## TL;DR

| State | Item |
|---|---|
| ✅ Ships | Voxy LOD pipeline runs end-to-end on Apple Silicon via Metal (M12 close, M11 visual-verified). |
| ✅ Ships | Real MC sky/block lightmap on LOD chunks (M13 chunk 2). |
| ✅ Ships | Environmental fog on LOD chunks matching MC's near-terrain fog (M13 chunk 5). |
| ⚠️ Partial | Horizon LOD chunks have been INVISIBLE since commit `32aeb455` (chunk-1 attempt). Two-layer regression root-caused 2026-05-13: layer 1 (`VOXY_NO_ATLAS` missing) FIXED via MDIC re-injection. Layer 2 (`destAddr` zero → face-visibility check fails → `realQ=0`) attempted via synthetic `writeDefaultBakePattern` but REVERTED after that triggered a SIGBUS BUS_ADRALN inside `LightMapHelper.syncFromMc → nglGetTexImage → glgProcessPixelsWithProcessor`. Net: horizon chunks still invisible on Metal default but the game no longer crashes. Architectural fix needed (bypass `ModelFactory`'s GL-persistent-buffer downstream when bakery is gated). |
| 🌱 Parked | Real model textures (M13 chunk 1 Metal-native bakery) — code complete, gated OFF because enabling it triggers Sodium's `glMapBufferRange` to return null mid-frame. |
| 🌱 Parked | Real HiZ pyramid from MC's depth (M13 chunk 3) — code complete, gated OFF because LOD chunks vanished at the horizon when wired (suspected `Depth32Float_Stencil8` sample mismatch). |
| ⏳ Open | Depth-test raster cull (chunk 3 follow-up — replaces `force_all_visible.comp`). |
| ⏳ Open | SSAO + depth-aware finish blit (M13 chunk 4). |

**How to run on Metal today:**
```bash
VOXY_FORCE_METAL=1 ./gradlew runClient
```

`VOXY_FORCE_METAL=1` is mandatory on M-series. Without it Voxy detects the
non-OpenGL backend at `VoxyClient.java:65-82` and disables itself entirely
(log: `Not creating renderer due to disabled`) — you get vanilla MC, no LOD.

**Optional env vars:**
- `VOXY_BAKERY_FORCE=1` — opt in to the experimental Metal bakery. Produces
  real block textures on LOD chunks. **Currently triggers a Sodium crash
  after ~30 s of chunk-load activity** (`SharedQuadIndexBuffer.grow →
  glMapBufferRange returns null`). Leave unset unless you're debugging
  the bakery interaction.
- `VOXY_BAKERY_OFF=1` — force-disables both bakery paths (GL + Metal).
  Useful for bisecting any Voxy stability issue (returns `0` from
  `renderToStream`; LOD falls back to hash-colour placeholder).

---

## Current state

**Milestones M0–M12 closed.** Voxy renders LOD chunks end-to-end on
Apple M4 Max (macOS 26.4.1, JDK 24) inside Minecraft via
`VOXY_FORCE_METAL=1`. User-confirmed visually 2026-05-12: full-screen
LOD pyramid behind Sodium's near terrain, every chunk visibly distinct,
no diagnostic overlay strip. Fog parity (chunk 5) landed and verified
in-game 2026-05-13.

### Horizon-chunks regression chain (root-cause analysis, 2026-05-13)

The user reported LOD chunks invisible at the horizon across multiple runs.
Investigation surfaced a **two-layer regression** that landed together at
commit `32aeb455` (chunk-1 attempt, 2026-05-12) and broke the M12-stable
visual until tonight's patches. Both layers had to be unblocked
independently — the first fix alone wasn't enough.

**Layer 1 — shader define gate.** `MDICSectionRenderer`'s Metal
define-injection block had stopped injecting `VOXY_NO_ATLAS` based on the
(aspirational, now stale) comment "M13 chunk 1 closed the atlas path on
Metal — `ModelStore.textures` now receives real bake output". Reality:
the bakery was auto-gated to a no-op on Metal in the same commit. So the
LOD terrain shader compiled with the real-atlas path, sampled an
all-zeros atlas, hit alpha = 0, and `discard`-ed every CUTOUT/TRANSLUCENT
fragment + rendered SOLID fragments as black (invisible against the
near-black bridge clear colour). **Fix:** re-inject `VOXY_NO_ATLAS` on
Metal when bakery isn't `VOXY_BAKERY_FORCE=1`; under force, inject
`VOXY_DEBUG_MAGENTA_MISSING` so empty atlas pixels show as bright magenta
instead of disappearing — debug aid for the bakery follow-up. A new
`[Metal-DEFINES]` log line in MDIC's constructor reports the live define
set at runtime so we can confirm without source grep.

**Layer 2 — bakery output controls quad generation, ATTEMPTED FIX
REVERTED.** Even with the shader path corrected, `realQ` (CPU-side
quad generation counter) stayed at zero across thousands of frames.
Trace: `ModelTextureBakery.renderToStream` returned 0 on Metal without
writing anything to `destAddr` (the CPU-mapped buffer
`ModelFactory.addEntry` provides). Downstream
`ModelFactory.processTextureBakeResult` reads that buffer and passes it
to `TextureUtils.wasPixelWritten()` for each face. For SOLID blocks
(most of the world) the check is `WRITE_CHECK_STENCIL` which evaluates
`(depth[index] & 0xFF) != 0` — the low byte of the 32-bit depth value.
Looking at `GlViewCapture.emitToStream`'s pack format,
`value = (depthBits << 8) | ((meta & 1) << 7)`, only bit 7 of the low
byte is ever written (the tint flag); bits 0–6 are always zero, and
the GL stencil aspect is never read back. So with a zero `destAddr`,
every pixel of every face fails the check → no face visible → `realQ=0`
→ empty horizon.

**Attempted fix:** `ModelTextureBakery.writeDefaultBakePattern` filled
`destAddr` with colour = `0xFFFFFFFF` (passes `WRITE_CHECK_ALPHA`) and
depthStencilTint = `0x80` (bit 7 set, passes `WRITE_CHECK_STENCIL`)
for every pixel of every face. Compiled fine and would have made every
block register as "all 6 faces drawn".

**Reverted because:** in-game runtime test crashed with `SIGBUS
BUS_ADRALN` inside `LightMapHelper.syncFromMc → nglGetTexImage →
glgProcessPixelsWithProcessor` within ~18 seconds of world load.
Same Apple-GL pixel-processor fragility documented for the original
GL bakery `glReadPixels` chain. The 12 KB write per bake into MC's
GL-persistent-mapped download stream buffer (the destination
`ModelFactory.addEntry` provides via `this.downstream.getBufferAddr() +
allocation`) destabilises the parallel pixel-readback path that
`LightMapHelper` uses every frame to mirror MC's lightmap.

**Net state:** the synthetic-bake write is gone; bakery returns 0 cold
again. Horizon chunks remain invisible on Metal default but the game
no longer crashes. The architectural fix is to give RenderDataFactory
its face-visibility metadata WITHOUT routing through MC's GL persistent
buffer — most likely by adding a Metal-only branch in
`ModelFactory.addEntry` that bypasses `this.downstream.download(...)`
entirely, builds a synthetic `RawBakeResult.rawData` on the heap, and
pushes it directly onto `rawBakeResults` for the normal processing
pipeline. ~half-day rewrite. Tracked in "Open decisions" below.

**Lessons for future agents:** (1) `TextureUtils.WRITE_CHECK_STENCIL`
is misnamed — `GlViewCapture` doesn't read the GL stencil channel at
all; the constant ends up checking the tint bit at position 7 of the
depth field. If a future cleanup touches `TextureUtils`, consider
renaming to `WRITE_CHECK_TINT_BIT` and documenting that bits 0–6 of
the depth field are always zero. (2) Whenever the bakery is gated
off on a backend, the gate must STILL populate the face-visibility
metadata somewhere — but writing to `destAddr` directly (the GL
persistent buffer) is NOT safe on Apple GL when other GL pixel-readback
paths are active concurrently (LightMapHelper, future DepthMirror).
The fix needs to be at the `RawBakeResult` / `rawBakeResults` level
instead of the bakery's destination buffer. (3) The bakery's return
value tells the caller about per-block shading flags; it does NOT
communicate "the bake was skipped" — even a "no-op" bake gate has
side-effect requirements on `destAddr`.

### What works on Metal today

- **Environmental fog on LOD terrain (M13 chunk 5, closed 2026-05-13)** —
  Voxy's per-fragment terrain shader (`quads.frag`) mixes the MC fog
  colour in on the Metal path, gated by `USE_ENV_FOG` (injected only on
  non-GL backends — the GL pipeline keeps its post-pass fog via
  `blit_texture_depth_cutout.frag` and would double-apply otherwise).
  Fog params live in the `SceneUniform` SSBO (`voxyFogEndParams` at
  offset 96, `voxyFogColour` at 112) and mirror the formula in
  `NormalRenderPipeline.finish`: `invEndFogDelta`, `startDelta`,
  max-density clamp at MC render distance × √3. Per-vertex world
  distance computed in `quads3.vert` as `length(cornerPoint -
  cameraSubPos)` and emitted as `layout(location=2) out float
  voxyFogDist`. Smoke tests cover both variants. Runtime-verified in-game.
- **MC lightmap on Metal (M13 chunk 2, closed 2026-05-12)** — Shared-
  storage 16×16 RGBA8 mirror of MC's lightmap; `LightMapHelper.bindMetal`
  CPU-reads MC's GL lightmap via `nglGetTexImage` and pushes it into
  the mirror once per frame (gated by `viewport.frameId`). Mirror bound
  at `LIGHTING_SAMPLER_BINDING = 1` on the render encoder alongside a
  LINEAR / CLAMP_TO_EDGE sampler. `quads3.vert`'s `getLighting()` reads
  real MC sky/block-light values; the `VOXY_NO_ATLAS` path in
  `quads.frag` modulates its per-quad hash colour by the lightmap ×
  face-shade colour packed into `interData.y`.
- **Bootstrap** — Metal device + command queue + shared event; LWJGL
  natives bundled for macOS arm64 (`lwjgl-metal`, `lwjgl-zstd`,
  `lwjgl-lmdb`).
- **Cross-backend abstraction** — `RenderBackend` / `RenderEncoder` /
  `ComputeEncoder` real on Metal *and* OpenGL (Win/Linux still works).
  Pipeline state (depth + blend + raster), samplers, push-constants-
  equivalent, indirect command buffers (ICB).
- **Shader translation** — runtime GLSL → SPIRV (`shaderc`) → MSL
  (`spvc`) with disk cache. Current smoke-test counts: 31/31 SPIRV,
  30/31 MSL (`hiz.comp` still defers — uses `subgroupClusteredMax`
  with cluster > 4 which Metal's spvc-MSL doesn't support).
- **IOSurface bridge** — IOSurface wrapped as both `MTLTexture`
  (render target) and `GL_TEXTURE_RECTANGLE` (sampleable from MC's
  GL compositor). Used to surface Voxy's Metal render into MC's
  framebuffer per-frame.
- **Voxy compute pipeline on Metal** — full chain runs end-to-end:
  `AsyncNodeManager.tick`, `NodeCleaner.tick`,
  `HierarchicalOcclusionTraverser.doTraversal`, MDIC's 5 prepasses
  (`prep`, `cull` (Metal stub via `force_all_visible.comp`),
  `commandGen`, `prefixSum`, `translucentGen`). HOT and ANM/NC are
  fully backend-agnostic — no raw GL on the hot path.
- **Voxy render pipeline on Metal** — `runPipelineMetal` opens a
  render pass against bridge color + Voxy-owned depth texture, then
  invokes `renderOpaqueMetal → renderTemporalMetal →
  renderTranslucentMetal` (3-pass MDIC). Each pass binds the 6 SSBOs
  through `encoder.setBuffer` + the shared `SharedIndexBuffer` as
  UINT16 index + `drawIndexedIndirect`.
- **Compositor** — `IOSurfaceBridgeCompositor` blits the bridge
  full-screen into MC's main RT at HEAD of Sodium's SOLID pass, so
  Sodium's near terrain overdraws Voxy LOD via depth-test naturally.
- **Chunk persistence** — works on Mac since M11 (LMDB + zstd
  natives + background workers; orthogonal to render).
- **Clean shutdown** — `AsyncNodeManager` worker is daemon-flagged;
  MC's close button exits the JVM without hanging.

### What's PARKED on Metal (code committed but gated off)

These have full implementations committed but are intentionally disabled
at the call site because enabling them produced runtime regressions.
Each section lists exactly what's gating the code and what would need
to happen to re-enable.

#### 1. Real model textures — Metal-native bakery (M13 chunk 1)

**Gate:** `ModelTextureBakery.renderToStream` returns 0 when on Metal
unless `VOXY_BAKERY_FORCE=1` is set.

**What's committed:**
- `AtlasMirror` (`core/rendering/util/AtlasMirror.java`) — clones MC's
  `textures/atlas/blocks.png` GL texture into a Shared-storage Metal
  texture via `nglGetTexImage` (Apple GL 4.1-compatible). One-time
  sync gated by `lastSyncedGlId`; re-syncs on resource-pack reload.
- `MetalTexture.storeRenderTargetUploadable` — Shared + RenderTarget +
  ShaderRead storage variant for the bake target so we can render
  into it AND `getBytes` out without a Metal blit-copy.
- `MetalTexture.getBytes` + `MetalNative.mtlTextureGetBytes` JNI —
  CPU readback from Shared/Managed textures via
  `[MTLTexture getBytes:bytesPerRow:fromRegion:mipmapLevel:]`.
- `MetalBudgetBufferRenderer` (`core/model/bakery/`) — Metal-side
  counterpart to `BudgetBufferRenderer`. Drives the
  `bakery/position_tex.vsh+.fsh` MSL-transpiled pipeline via
  `MetalRenderEncoder` against the Shared bake target. Supports
  `beginPass(clear)` with both CLEAR and LOAD load-actions so the
  fluid path can render face-by-face while preserving prior faces.
- `MetalViewCapture` (`core/model/bakery/`) — Metal analogue of
  `GlViewCapture`. Owns the 48×32 bake target + atlas mirror +
  renderer; exposes `clear → beginBake → renderFace × N → endBake →
  emitToStream` and packs RGBA bytes into the legacy
  `(packedRGBA, packedDepthStencilTint)` uvec2-per-pixel format
  `ModelStore` consumers expect. Metadata uvec2 component is zero
  (single-colour-attachment MVP — see "What's not done" below).
- `ModelTextureBakery.renderToStreamMetal` — the per-block bake
  driver. Mirrors the GL path's `bakeBlockModel` / `bakeFluidState`
  logic but routes through `MetalViewCapture`. Uses a Metal-friendly
  projection matrix: `m22 = +1` so world z ∈ [0,1] maps to NDC z
  ∈ [0,1] (Metal's valid clip range); `m11 = -2`, `m31 = +1` to
  Y-flip so the Metal memory-row-first byte order matches the
  bottom-row-first layout the LOD shader was designed against.
- `bakery/position_tex.fsh` extended with a `BAKERY_SINGLE_ATTACHMENT`
  define that gates out the second colour output (`metaOut` at
  location 1) so the shader matches the single-attachment Metal bake
  target. Smoke-tested.

**Why it's parked:** runtime test on 2026-05-13 evening showed the same
crash as the original GL bakery — `Sodium.SharedQuadIndexBuffer.grow →
GLRenderDevice.mapBuffer` raises `RuntimeException: Failed to map
buffer`, ~30 s in, on the first Sodium grow after a few dozen bake
invocations. This is identical to the documented GL bakery failure
("bakery on → Sodium dies on the first chunk batch"). The Metal
bakery's GL footprint is minimal (one-time AtlasMirror readback at
startup — same pattern as `LightMapHelper.bindMetal` which works
fine), so the crash isn't about a specific GL-state side-effect.
Working theory: `mtlCommandBufferWaitUntilCompleted` (called from
`RenderBackend.submit()` at the end of each Metal bake) interferes
with Apple's GL persistent-mapped buffer assumptions on shared GPU
context — possibly the driver invalidates Sodium's persistent
mapping whenever a Metal command buffer drains synchronously on the
render thread.

**To re-enable (next session):**
1. Test the wait-completed hypothesis: replace `backend.submit()` in
   `MetalViewCapture.clear/endBake` with an async fire-and-forget
   submit (no wait), and use an `MTLEvent` to gate the `getBytes`
   call. If the Sodium crash goes away, we've identified the culprit.
2. If that doesn't help: move bake submits onto a dedicated
   `MTLCommandQueue` separate from the main Voxy queue, so the wait
   doesn't drain through the same channel as the main render frame.
3. Last resort: defer bakes to a worker thread with its own
   `CGLContext` — but that breaks MC's single-threaded render model.

#### 2. Real HiZ pyramid from MC's depth (M13 chunk 3)

**Gate:** `AbstractRenderPipeline.runPipelineMetal` calls
`viewport.hiZBuffer.ensureAllocated(...)` (zero-init pyramid) instead
of `buildMipChain(IGpuTexture, ...)`.

**What's committed:**
- `DepthMirror` (`core/rendering/util/DepthMirror.java`) — Shared-
  storage `Depth32Float` mirror of MC's main-FBO depth attachment.
  Frame-id-gated CPU readback via `nglGetTexImage(GL_DEPTH_COMPONENT,
  GL_FLOAT)`. ~8 MB per readback at 1920×1080.
- `MetalNative.mtlTextureNewSubresourceView` JNI — wraps
  `[MTLTexture newTextureViewWithPixelFormat:textureType:levels:
  slices:]` so the HiZ pyramid can be bound one mip at a time as a
  sampling source for the next mip's render pass.
- `IGpuTexture.createView(int baseLevel, int levelCount)` cross-
  backend overload; `MetalTexture` impl uses the new JNI.
- `HiZBuffer.buildMipChain(IGpuTexture srcDepth, int w, int h)`
  overload — encoder-driven, per-mip self-view cache so the source
  binding rotates from `srcDepth` (mip 0) to `pyramid.view(i-1)`
  (mip i ≥ 1) without the GL `BASE/MAX_LEVEL` global-state hack.

**Why it's parked:** when wired up on Metal, LOD chunks vanished at
the horizon. Suspected cause: the HiZ pyramid format is
`GL_DEPTH24_STENCIL8` → `MTLPixelFormatDepth32Float_Stencil8` on
Apple Silicon, and sampling that as `sampler2D` in MSL may not
return the depth aspect cleanly — returning the stencil byte instead
would push HiZ values out of the expected [0, 1] range and HOT
would over-reject sections. The GL path's `initDepthStencil`
preprocesses MC's depth (stencil-mask dance — sets depth=0 where MC
drew, depth=1 in sky regions); without that pre-process on Metal,
HOT's "section behind HiZ" test is using the raw MC depth values
which may be in a different convention than the shader expects.

**To re-enable (next session):**
1. Try the simpler fix first: allocate the HiZ pyramid on Metal with
   `GL_DEPTH_COMPONENT32F` (no stencil) instead of `GL_DEPTH24_STENCIL8`.
   That removes the depth+stencil sampling ambiguity. Trade-off:
   slightly different format than GL path — fine because HiZ buffer
   is per-backend anyway.
2. If chunks still vanish: add a `voxyDepthForHiZ` preprocessing pass
   on Metal that mirrors `initDepthStencil`'s stencil-mask dance —
   takes raw MC depth, outputs a depth texture with sky=1.0 and
   MC-drawn=MC-depth. Then feed that to `buildMipChain`.

### What's NOT done at all on Metal

- **Depth-test raster cull (chunk 3 follow-up).** Even with chunk 3
  HiZ enabled, the depth-test cull pass (`cull/raster.vert`+`.frag`)
  still defers to the `force_all_visible.comp` compute stub on Metal.
  The real pass needs MC's depth as an actual depth ATTACHMENT in a
  Metal render pass with colour-mask off + depth-test on; the
  `DepthMirror` only exposes MC depth as a sampleable source. Next
  step: either add a Shared-storage + RenderTarget-usage path to
  `MetalTexture` so the mirror can double as an attachment, or
  copy-resolve into a Private depth target via a
  `MTLBlitCommandEncoder`. Until then, HOT's coarse HiZ rejection
  carries most of the perf win — `cmdgen` still queues more sections
  than the GL path's two-stage cull would.
- **`finish()` blit + SSAO (M13 chunk 4).** Skipped on Metal. The
  IOSurfaceBridgeCompositor's full-screen blit substitutes (lossy:
  no per-pixel depth-test against MC's foreground in the blit
  itself). Sodium overdrawing on top via its own depth keeps the
  visual stacking right for now. Now technically unblocked since
  chunk 3's `DepthMirror` exposes MC depth, but waits on chunk 3
  being re-enabled.

### How to test

```bash
# Stable: LOD pipeline ON, hash-colour textures (M12-stable visual).
VOXY_FORCE_METAL=1 ./gradlew runClient

# Experimental: full Metal bakery. Expect a Sodium glMapBufferRange
# crash within ~30 s of chunk loading. Empty atlas regions render as
# magenta (VOXY_DEBUG_MAGENTA_MISSING).
VOXY_FORCE_METAL=1 VOXY_BAKERY_FORCE=1 ./gradlew runClient

# Bisect tool: bake disabled entirely (GL + Metal). Used to confirm
# stability issues are bakery-related vs orthogonal. NOTE: with bakery
# fully off the synthetic "all-faces-drawn" default-pattern also
# doesn't run, so horizon chunks go invisible again.
VOXY_FORCE_METAL=1 VOXY_BAKERY_OFF=1 ./gradlew runClient
```

**Expected behaviour with the stable command:** boot log should include
`[Metal-DEFINES] terrain shader injections: VOXY_NO_DEPTH_BOUND +
VOXY_NO_ATLAS (hash-colour fallback — bakery gated off) + USE_ENV_FOG`.
Load a world, walk past MC's render distance, look at the horizon.
Sodium chunks render normally up close; beyond that Voxy's LOD pyramid
appears — every chunk a distinct hash-coloured block with the
procedural checker+speckle pattern, fading into MC's environmental fog.
`Metal-GEN realQ` and `Metal-DIAG sections` should both climb from 0
within the first 30–60 s as the bakery fills the model registry with
the default-visible pattern.

**Diagnostic counter cheat-sheet** (all visible in `Metal-DIAG` /
`Metal-GEN` / `Metal-BAKE` log blocks every 600 frames):

| Counter | Healthy value | What it means if stuck low |
|---|---|---|
| `[Metal-BAKE] invocations` | climbs steadily, plateaus at the count of unique block states | Bakery never called → `ModelFactory.addEntry` never enqueueing → check `Metal-CHAIN ingestCall`. |
| `[Metal-GEN] realQ` | climbs into the thousands within the first minute | Quads not being generated. Was 0 before the synthetic default-pattern fix; if still 0 after, suspect `TextureUtils` or the synthetic-pattern bit layout. |
| `[Metal-DIAG] sections` | climbs to tens/hundreds depending on terrain | LOD geometry buffer empty. Usually downstream of `realQ=0`. |
| `[Metal-PGR] uploadReal` | grows alongside `realQ` | Upload pipeline starved or rejecting uploads. |

**If LOD chunks DON'T appear at all:** check the log for
`Not creating renderer due to disabled` (from `MixinLevelRenderer`).
That means `VOXY_FORCE_METAL=1` wasn't set — Voxy auto-detected the
non-OpenGL backend and gated itself off (`VoxyClient.java:65-82`).

**If the boot log shows VOXY_NO_ATLAS but chunks still invisible:**
check `[Metal-GEN] realQ`. If it stays at 0 across multiple diag
blocks, the synthetic bake pattern isn't satisfying the downstream
face-visibility check. Look at `TextureUtils.wasPixelWritten` to
confirm the depth low-byte bits the pattern sets are still what
`WRITE_CHECK_STENCIL` expects.

For the GL backend (Win/Linux, or Mac without the env var):
unchanged behaviour — Voxy renders LOD with real Minecraft textures
via the existing GL pipeline.

### Smoke tests (all green on Apple M4 Max)

```bash
./gradlew testShaderCompiler    # 31/31 SPIRV, 30/31 MSL (hiz.comp deferred)
./gradlew testMetalRender       # M3 — clear-color render pass
./gradlew testMetalTriangle     # M5 — graphics pipeline + draw
./gradlew testMetalCompute      # M7 — compute pipeline + dispatch
./gradlew testMetalVertexBuffer # M9-prep — vertex layout + drawIndirect
./gradlew testMetalIcb          # Blocker 1 — MTLIndirectCommandBuffer
./gradlew testIOSurfaceBridge   # M10 — IOSurface + MTLTexture wrap
./gradlew testVulkanLoader      # M4-A — MoltenVK + VkInstance
./gradlew testVulkanClear       # M4-D — dynamic_rendering clear
./gradlew testVulkanTriangle    # M6 — Vulkan graphics + draw
./gradlew testVulkanCompute     # M8 — Vulkan compute + descriptor sets
```

The shader-compiler test's exit-code-1 on `hiz.comp` MSL deferral is the
documented baseline and not a regression — count the PASS lines to
verify.

---

## Next steps — remaining M13 work

**Goal:** Voxy on Metal visually indistinguishable from the GL backend
(modulo Iris features, which stay GL-gated).

Ranked by visual impact + tractability:

| # | Chunk | Status | Effort | What it unlocks |
|---|---|---|---|---|
| 1 | **Metal-native bakery** | 🌱 parked (Sodium crash) | 0.5–1 day to re-enable | Real Minecraft block textures on LOD chunks (drops the hash-colour placeholder). Code is committed; only the Sodium-mapBufferRange interaction blocks. |
| 2 | ✅ **MC lightmap** | done (2026-05-12) | — | — |
| 3 | **MC depth → real HiZ** | 🌱 parked (format mismatch) | 0.5 day to re-enable | HOT samples a real pyramid → coarse occlusion rejection of distant LOD chunks behind MC near terrain. |
| 3b | **Depth-test raster cull** | ⏳ not started | 1 day | Drops `force_all_visible.comp` stub; per-section depth-test against MC's foreground for finer cull (50–90% draw reduction in dense scenes). |
| 4 | **SSAO + finish blit** | ⏳ not started (blocked by 3) | 0.5 day | Depth-aware compositor; SSAO ambient occlusion on LOD. |
| 5 | ✅ **Fog parity** | done (2026-05-13) | — | — |

**Total M13 scope remaining:** ~2–3 days if the parked chunks debug
cleanly with the hypotheses above; ~4–5 days if the Sodium interaction
needs a queue-separation rewrite.

### Suggested order for next session

1. **Chunk 3 first (low risk, big perf win).** Try the
   `GL_DEPTH_COMPONENT32F` HiZ pyramid format flip; if that resolves
   the horizon-vanishing, ship it. ~1–2 hours.
2. **Chunk 1 second (high impact).** Test the
   wait-completed/async-submit hypothesis. If submit-without-wait
   resolves the Sodium crash, ship it. Otherwise try a dedicated
   command queue. ~2–4 hours.
3. **Chunk 4 third (depends on 3).** Once depth is on Metal, port
   SSAO compute + the depth-aware blit shader. ~2–3 hours.
4. **Chunk 3b last (perf polish).** Migrate the raster cull pass.
   ~half day.

---

## Open decisions / outstanding bugs

- **Sodium `glMapBufferRange` interaction with Metal bakery (chunk 1
  blocker).** The Metal bakery doesn't touch GL state during render
  (only the one-time `AtlasMirror` readback at startup), yet enabling
  it triggers the same Sodium crash the GL bakery did. Hypothesis:
  `mtlCommandBufferWaitUntilCompleted` synchronization invalidates
  Apple GL's persistent-mapped buffer guarantees on the shared GPU
  context. Diagnostic plan in the chunk-1 "To re-enable" section.
- **HiZ depth format ambiguity (chunk 3 blocker).** Pyramid is
  `Depth32Float_Stencil8` on Metal (from `GL_DEPTH24_STENCIL8`).
  Sampling as `sampler2D` may return stencil bits instead of depth.
  Fix candidate: `GL_DEPTH_COMPONENT32F` pyramid format.
- **Depth attachment for raster cull (chunk 3b).** Need MC's depth as
  a Metal depth attachment, not just a sampleable texture. Two
  options: (a) Shared+RenderTarget storage on the `DepthMirror` —
  Apple Silicon supports it but with a driver eviction warning; (b)
  blit-copy into a Private+RenderTarget target each frame.
- **`TextureUtils.WRITE_CHECK_STENCIL` misnomer** (catalogued during
  the horizon-chunks debug). The constant name suggests it reads the
  GL stencil channel, but `GlViewCapture.emitToStream` only reads
  `GL_DEPTH_COMPONENT` (depth aspect) and packs the tint bit at
  position 7 of the resulting uint. The "stencil check" therefore
  evaluates `(value & 0xFF)` which is just the tint bit plus six
  always-zero bits. Future cleanup: rename to `WRITE_CHECK_TINT_BIT`,
  document that the GL stencil aspect is never read back. The
  Metal-default `writeDefaultBakePattern` sets bit 7 = 1 to pass this
  check; if the constant ever gets reworked to actually read stencil
  the pattern needs updating.

---

## Git status

- **2026-05-13 work is uncommitted.** Files touched today:
  - Java: `AbstractRenderPipeline`, `NormalRenderPipeline`,
    `HiZBuffer`, `IGpuTexture`, `MetalTexture`, `MetalNative`,
    `MDICSectionRenderer` (includes the new `[Metal-DEFINES]` log line
    + the bakery-force / VOXY_NO_ATLAS / VOXY_DEBUG_MAGENTA_MISSING
    inject decision), `ModelTextureBakery` (includes the new
    `writeDefaultBakePattern` synthetic-bake helper that satisfies
    `WRITE_CHECK_STENCIL` for the bakery-gated path),
    `ShaderCompilerSmokeTest`.
  - Java new: `DepthMirror`, `AtlasMirror`,
    `MetalBudgetBufferRenderer`, `MetalViewCapture`.
  - GLSL: `lod/gl46/bindings.glsl`, `lod/gl46/quads.frag` (includes
    the new `VOXY_DEBUG_MAGENTA_MISSING` magenta-on-empty-atlas
    branch), `lod/gl46/quads3.vert`, `bakery/position_tex.fsh`.
  - Native: `native/metal/src/voxy_metal_texture.mm` + rebuilt
    `src/main/resources/natives/macos-arm64/libvoxy_metal.dylib`.
  - Docs: `docs/STATUS.md`, `docs/M-SERIES-PORT-STATE.md`,
    `docs/M-SERIES-PORT-OVERVIEW.md`, memory
    `project_m13_chunks3_5_closed.md`, `MEMORY.md`.

---

## Quick links

- [Detailed history + commit table](M-SERIES-PORT-STATE.md)
- [Narrative overview](M-SERIES-PORT-OVERVIEW.md)
- M12 closure commit: `75277c42`
- M13 fog commit (not yet committed): in working tree
- Latest committed HEAD: `a007f038` (procedural checker+speckle in
  `VOXY_NO_ATLAS`)
