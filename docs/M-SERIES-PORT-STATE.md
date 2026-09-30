# Voxy → Mac M-Series Port — Knowledge Transfer (LLM-oriented)

**Audience:** AI/LLM agent picking up this branch.
**Purpose:** Hand off enough context to continue M9 (Voxy migration) without re-deriving prior decisions.
**Companion doc:** `docs/M-SERIES-PORT-OVERVIEW.md` (narrative, human-readable).

---

## TL;DR

Goal: make Voxy (Minecraft Java mod, requires GL 4.3+ compute) run on Mac Apple Silicon by adding Metal direct + Vulkan/MoltenVK backends.

Current state (2026-05-13): **M0–M12 closed**; **M13 chunks 2 + 5 closed**; **M13 chunks 1 + 3 implemented end-to-end but parked behind their runtime gates** because each triggered a separate regression when wired in. Set `VOXY_FORCE_METAL=1` to enable Voxy on M-series — without it `VoxyClient.java:65-82` auto-disables Voxy and the log emits `Not creating renderer due to disabled`. The default M-series experience: LOD pipeline runs, MC lightmap + environmental fog are real, but model textures fall back to the `VOXY_NO_ATLAS` hash-colour placeholder.

**Open blockers for the next session:**

1. **Sodium `glMapBufferRange` returns null mid-frame whenever the Metal-native bakery is active** (`VOXY_BAKERY_FORCE=1`). Same crash shape the GL bakery had even though the Metal path has minimal GL footprint (one-time `AtlasMirror` `nglGetTexImage` at startup, same pattern as the working `LightMapHelper.bindMetal`). Working theory: `mtlCommandBufferWaitUntilCompleted` synchronisation invalidates Apple GL's persistent-mapped buffer state on the shared GPU context. First fix to try: replace `RenderBackend.submit()` calls inside `MetalViewCapture.{clear,endBake}` with an async submit + `MTLEvent`-gated readback (avoids the synchronous wait on the main render thread's command queue).

2. **HiZ pyramid sampling returns wrong values on Metal**, causing HOT to over-reject LOD sections (chunks vanish at the horizon when `runPipelineMetal` calls `HiZBuffer.buildMipChain(IGpuTexture, ...)` instead of `ensureAllocated`). Pyramid format is `MTLPixelFormatDepth32Float_Stencil8` (from `GL_DEPTH24_STENCIL8`); MSL's `sampler2D` on a depth+stencil format may not return the depth aspect cleanly. First fix to try: allocate the Metal HiZ pyramid as `GL_DEPTH_COMPONENT32F` (pure Depth32Float) instead of D24S8.

**Two open chunks haven't been started**: chunk 3b (real depth-test raster cull replacing `force_all_visible.comp`) and chunk 4 (SSAO + depth-aware finish blit). Both unblock once chunk 3's HiZ is re-enabled.

Branch is still 81 commits ahead of `dev` and uncommitted on top — all 2026-05-13 work is local to the working tree.

---

## Repository state

| Field | Value |
|---|---|
| Branch | `claude/opengl-mac-migration-analysis-6319V` |
| Base | `dev` |
| Last commit | `32aeb455` (M13 chunk 1 attempt — auto-gate GL bakery on Metal + persistence fixes) |
| Working tree | M13 chunks 1 + 3 + 5 + foundation work staged but uncommitted (see "Working tree" section below) |
| Commits ahead of `dev` | 81 |
| Test machine | Apple M4 Max, macOS 26.4.1, JDK 24.0.2 |
| MC target | 1.21.11 (Fabric 0.18.2, Java 21+) |
| LWJGL | 3.3.3 |
| Milestone status | M0–M12 ✅ closed; M13 chunks 2 + 5 ✅ closed; M13 chunks 1 + 3 🌱 wired end-to-end but parked behind their runtime gates. M13 chunks 3b + 4 ⏳ not started. Default Metal experience (`VOXY_FORCE_METAL=1`, no other env vars): LOD chunks render with real lightmap + fog, atlas placeholder is the hash-colour pattern. |

---

## Decisions already made (do not re-litigate)

1. **Migration path**: parallel Metal direct + Vulkan/MoltenVK. User confirmed "Prototipo paralelo de ambos". Both backends now validated for clear/triangle/compute.
2. **Shader translation**: runtime via LWJGL `shaderc` + `spvc` (pivoted from build-time after dry-run showed Voxy's runtime `#define` permutations + Vulkan-strict GLSL would force a per-permutation manifest). Disk cache at `~/.voxy/shader-cache/`.
3. **Iris compatibility**: deferred to phase 2. Mac launches with Voxy's built-in shaders; Iris stays GL-only on Win/Linux. User confirmed "Deferir Iris a fase 2".
4. **First-test goal**: full LOD distance rendering as M14 acceptance. User confirmed "Renderizado de chunks LOD funcional".
5. **Timeline**: ~6 weeks accepted ("Aceptar 6 semanas, scope completo").
6. **`MSL_ARGUMENT_BUFFERS=false`** in `RuntimeShaderCompiler` — direct `[[buffer(N)]]` bindings match Voxy's per-binding pattern.
7. **`PipelineState.DEFAULT` is depth-disabled**. Use `OPAQUE_MESH` for production opaque rendering, `TRANSLUCENT_MESH` for translucent.

---

## Commit history (chronological, reverse order)

| SHA | Title | Validates |
|---|---|---|
| `75277c42` | M12 close — translucent + temporal render + full-screen composite | M12 acceptance closure. Adds `MDICSectionRenderer.renderTemporalMetal` + `renderTranslucentMetal` (refactors `renderTerrainMetal` to take a pipeline param); `AbstractRenderPipeline.runPipelineMetal` invokes all three (opaque → temporal → translucent) inside its render pass. `IOSurfaceBridgeCompositor` switches from the LEFT-25% diagnostic blit to a full-screen blit. `MixinDefaultChunkRenderer` moves the inject from `BEFORE-Sodium.end` on CUTOUT to `HEAD` of Sodium SOLID — Voxy runs its full pipeline + composite BEFORE Sodium draws so Sodium's near terrain overdraws Voxy LOD via depth. User-confirmed 2026-05-12: full-screen LOD pyramid visible behind Sodium chunks. |
| `9d31a759` | quads.frag — bias VOXY_NO_ATLAS hash colour into [0.55, 1.0] | After 89c4d947 user reported only ice blocks visible. The random hash mapped channels to `[0.0, 1.0]`; the bottom half collapsed to near-black after Lambertian shading. Bias to `[0.55, 1.0]` (mul 0.45, add 0.55) and raise the shade floor from 0.30 to 0.55. Every block now reads as a saturated bright colour with visible face-direction shading. |
| `89c4d947` | M12 polish — face-shaded LOD chunks + NO_CULL on Metal terrain | Two fixes after step 3 (chunks visible but dark / sparse). (a) The Metal terrain pipeline now uses `RasterState.NO_CULL` (GL's renderTerrain explicitly calls `glDisable(GL_CULL_FACE)` at draw-time — that override doesn't reach Metal where cull mode is baked into the pipeline state). (b) `VOXY_NO_ATLAS` adds fake Lambertian shading from face normal so the cube structure of each LOD chunk is visible (top bright, bottom dark, sides graded). |
| `fd05a9fa` | M12 chunk 6 step 3 follow-up — VOXY_NO_ATLAS debug colour on Metal | After step 3 the chunks were nearly-as-dark-as-the-clear background because `quads.frag` discards on unbound `depthTex` + unbound `blockModelAtlas` alpha. New `VOXY_NO_ATLAS` shader define (injected on non-GL backends) skips those discards entirely and emits a per-quad debug colour hashed from `interData.x`. Smoke test SPV 28/28, MSL 27/28. |
| `9ef28478` | M12 chunk 6 step 3 — renderOpaque draws LOD chunks on Metal | First real LOD draws on Metal. New `metalDepthTex` (D24S8) sized to bridge; `runPipelineMetal`'s render pass attaches bridge colour + depth, clears both each frame, then dispatches `MDICSectionRenderer.renderOpaqueMetal` via instanceof guard. New encoder-based renderTerrainMetal (6 SSBOs + SharedIndexBuffer UINT16 + drawIndexedIndirect). `buildDrawCalls` zeros `drawCallBuffer` on non-GL so beyond-count slots have instanceCount=0 (no-op). `ModelStore.bindBuffers(encoder, ...)` + `SharedIndexBuffer.getBuffer()` accessors added. Lightmap / atlas / depth-bounding texture binds skipped (untextured visuals until M13). |
| `676f0c09` | Close M9-TODO — encoder.setBuffer(IGpuPersistentBuffer, offset, size) | AsyncNodeManager + NodeCleaner had 3 remaining raw `glBindBufferRange(GL_SHADER_STORAGE_BUFFER, ..., UploadStream.INSTANCE.getRawBufferId(), ...)` calls inside compute encoder passes; on Metal those raw GL binds scribbled into MC's context. New `ComputeEncoder.setBuffer(int, IGpuPersistentBuffer, long, long)` overload + `UploadStream.getUploadBuffer()` accessor close the M9 "UploadStream raw GL id" TODO. ANM + NC fully encoder-clean. |
| `e71decf8` | Fix M12 chunk 6 step 1 crash — DownloadStream.commit through backend | `DownloadStream.commit()` was crashing in `runPipelineMetal` with "No context is current or a function not available in the current context" because it called `glMemoryBarrier` (GL 4.2) on Apple's GL 4.1 context. Fix: route through `backend.memoryBarrier(...)` + a new `RenderBackend.copyBufferSubData(IGpuBuffer src, IGpuPersistentBuffer dst, ...)` overload (mirrors the existing upload-direction signature). Unblocks chunk 6 step 1. |
| `4f066eb8` | Update M-SERIES-PORT-STATE for M12 chunk 5 + chunk 6 prep + step 1 | Doc-only — captures chunks 5 / 6-prep / 6-step-1 in the commit-history table; M12 section rewritten with per-chunk status and the three remaining open decisions (cross-context MC-depth, depth-target ownership for runPipelineMetal, ModelStore + LightMapHelper migration vs stub). |
| `dd007eae` | M12 chunk 6 step 1 — runPipelineMetal runs the migrated compute side | Replaces `runPipelineMetalStub` (clear-only) with a real `runPipelineMetal`: allocates the bridge, calls `viewport.hiZBuffer.ensureAllocated`, runs DownloadStream.tick + AsyncNodeManager.tick + NodeCleaner.tick + HOT.doTraversal + buildDrawCalls in full on Metal, then clears the bridge to cyan/teal as visual feedback. Adds `HiZBuffer.ensureAllocated(w,h)` so HOT can bind a valid (zero-initialized) HiZ texture without the GL-only mip blit. Renders no actual LOD geometry yet — that's step 3. |
| `89b35814` | M12 chunk 6 prep — close HOT's 4 raw-GL gaps for cross-backend safety | `addTLN` / `remTLN` now upload via `UploadStream` instead of raw `glBindBuffer` + `nglBufferSubData(SCRATCH)`; the doTraversal renderList-counter zero and downloadResetRequestQueue request-counter zero use `IGpuBuffer.zeroRange` (already implemented on both backends). The static `SCRATCH` direct-buffer alloc and the `GL_COPY_READ_BUFFER` / `glBindBuffer` static imports are gone. `HierarchicalOcclusionTraverser` is now fully backend-agnostic. |
| `3fa07c44` | M12 chunk 5 — Metal cull stub via force-all-visible compute | Adds `lod/gl46/force_all_visible.comp` (writes `visibilityData[sid] = (frameId & 0x7fffffff) \| (1<<31)` for every section in `indirectLookup`). MDIC's "Test occlusion" block now branches on `backend.getType()`: GL keeps the raster cull, every other backend runs the force-all-visible compute via `dispatchIndirect` (reusing `prep`'s dispatch sizing in `drawCountCallBuffer`). Smoke test grows to 27 cases — SPV 27/27, MSL 26/27. Real Metal cull (depth-test rasterized) deferred until cross-context MC-depth access lands. |
| `08d01ddb` | Update M-SERIES-PORT-STATE for M12 chunks 2-4 completion | Doc-only — adds the chunk-1–4 commit history rows, M12-section per-chunk status, decision queue trimmed to the two architectural questions (cull Metal target shape; runPipelineMetal/IGpuRenderTarget). |
| `f4d4c4ab` | M12 chunk 4 — migrate translucentGen compute prepass to ComputeEncoder | Last of the 4 simple compute prepasses. 6 SSBO bindings (SceneUniform at 0 via the post-chunk-3 SSBO flip) + `dispatchIndirect` against `drawCountCallBuffer` offset 0; `BARRIER_SHADER \| BARRIER_INDIRECT` both sides. `translucentGenProgram` cached id removed. |
| `a50e53e1` | M12 chunk 3 — flip SceneUniform to SSBO + migrate commandGen pass | Atomic refactor + migration. `lod/gl46/bindings.glsl` flips `SceneUniform` from `uniform` (UBO) to `readonly buffer` (SSBO) at binding 0 with std140 preserved (on-disk byte layout identical, matches HOT's commit `5dcbc645` pattern). All 4 raw-GL bind sites in MDIC (bindRenderingBuffers, cull block, commandGen block, translucentGen block) retargeted to `GL_SHADER_STORAGE_BUFFER`. commandGen migrated to `beginComputePass`+ 8 SSBOs + dispatchIndirect; conditional statistics SSBO at binding 8. testShaderCompiler: SPV 26/26, MSL 25/26 (hiz.comp pre-existing). |
| `f0e7a3de` | M12 chunk 2 — migrate prep compute prepass to ComputeEncoder | `prep.comp` doesn't reference SceneUniform fields so the encoder skips binding 0 entirely — that defers the UBO↔SSBO decision to chunk 3. Just `setBuffer(1, drawCountCallBuffer)` + `setBuffer(2, renderList)` + barriers + dispatch. `prepProgram` cached id removed; `ComputeEncoder` imported so chunk 1's barrier calls drop the fully-qualified prefix. |
| `2e8e302d` | M12 kickoff — plan + migrate prefixSum compute pass to ComputeEncoder | Adds the M12 plan to docs (6 chunks + decision queue). First migration: `prefixSum` (single SSBO binding 0, no UBO, 1 thread group) — proves the encoder pattern works inside MDIC's buildDrawCalls. `prefixSumProgram` cached id removed. |
| `70d94002` | M11 closeout — visually verified end-to-end on M4 inside Minecraft | Working tree consolidation: `IOSurfaceBridgeCompositor` lands as the canonical bridge→MC blit (called from `MixinDefaultChunkRenderer.render` after Sodium's `renderOpaque`); the obsolete `MixinLevelRendererVoxyMetalComposite` is removed; `AsyncNodeManager` worker is daemon so MC's close button doesn't hang the JVM; `build.gradle` bundles macOS arm64 `lwjgl-zstd` + `lwjgl-lmdb` natives for Sodium's chunk render task workers; magenta stub in `AbstractRenderPipeline.runPipelineMetalStub` is bumped to full alpha for unmistakable visual confirmation. Doc reflects M11 ✅ closed and outlines the M12 scope. |
| `290bb11f` | M11 — MetalRenderBackend handles depth-only pipelines (HiZBuffer fix) | createGraphicsPipeline now allows a 0/`MTLPixelFormatInvalid` color format when a depth attachment is present, so HiZBuffer's depth-only per-mip pipeline compiles on Metal instead of erroring at PSO link. Unblocks the second of the four M11 init crashes. |
| `bd3242d3` | M11 — fix four Metal-init crashes hit by `VOXY_FORCE_METAL` world entry | Four small fixes uncovered by world-entry: (1) `NormalRenderPipeline.setupAndBindOpaque` no longer raw-binds MC's GL FBO on Metal (no-op skip); (2) `MDICSectionRenderer.uploadUniformBuffer` is safe to call before the world is loaded; (3) `BasicSectionGeometryData` no-ops its raw GL setup on Metal; (4) `HiZBuffer` first-frame allocation path tolerates `targetTexture==null`. With these, MC reaches the game loop on Metal. |
| `c64d1801` | M11 — enable Voxy on Metal end-to-end via `VOXY_FORCE_METAL` flag | `RenderBackendFactory` honours `VOXY_FORCE_METAL=1` (also unblocked the prior "Metal disabled until M9 done" gate); Voxy bootstraps on Metal; `AbstractRenderPipeline.runPipeline` early-routes to a `runPipelineMetalStub` that prints a clear color into an `IOSurfaceBridge` so the user can verify the Metal path is wired. |
| `f1e975ab` | M10 — IOSurfaceBridge is now usable as a RenderEncoder render target | `IOSurfaceBridge.asGpuTexture()` returns the bridge's `MTLTexture` as an `IGpuTexture` (`MetalTexture.fromHandle`). `RenderPassDesc.clearColor(IGpuTexture, r, g, b, a)` now accepts a bridge-backed texture; the Metal render backend builds an `MTLRenderPassDescriptor` against it. Validated by `runPipelineMetalStub` clearing the bridge each frame. |
| `fc64badc` | Update M-SERIES-PORT-STATE for Blocker 1 + M10 IOSurface bridge | Doc-only — records ICB blocker clearance, M10 surface-side allocation, and the GL-side bind path. |
| `7db450af` | M11 — IOSurface bridge demo runs inside Minecraft | New `MixinLevelRendererBridgeDemo` (and a since-removed sibling `MixinLevelRendererVoxyMetalComposite`) gated behind `VOXY_BRIDGE_DEMO=1` allocate an IOSurfaceBridge, render a clear pass into it from Metal, and blit it via a GL `glBlitFramebuffer` over MC's main RT. Proves the IOSurface↔MC pipe works from inside MC's render thread. |
| `19f76320` | M9 Phase 2 — version-bump `quads3.vert` + `quads.frag`; add VOXY_BRIDGE_DEMO env var | Bumps the two MDIC terrain shaders to 460 core so shaderc's Vulkan profile accepts the texelFetch overloads + bit ops. Adds the `VOXY_BRIDGE_DEMO=1` env flag plumbing used by the M11 demo mixin. |
| `cc8957f3` | M9 Phase 4 — migrate MDIC terrain shaders (non-Iris path) to createGraphicsPipeline | The two `quads3.vert` + `quads.frag` pipelines (opaque + translucent) now flow through `RenderBackend.createGraphicsPipeline` when no Iris patch callbacks fire. Pipelines explicitly opt OUT of `supportIndirectCommandBuffers` because quads.frag uses gl_FragDepth + discard, both Metal-ICB-incompatible. On Metal MDIC keeps the CPU-readback fallback (`drawIndexedIndirect` host loop) for now. |
| `238457f4` | M10 — IOSurface bridge (GL side): CGLTexImageIOSurface2D binding | New `voxy_metal_iosurface_gl.mm` links the OpenGL framework + exposes `cglTexImageIOSurface2D` JNI. `IOSurfaceBridge.bindToGlTexture(glName)` pins the IOSurface to a GL_TEXTURE_RECTANGLE texture so MC's GL compositor can sample Voxy's Metal-rendered output. End-to-end validation waits for M11 (Voxy running inside MC's render thread). |
| `cab941c9` | M10 — IOSurface bridge (Metal side): allocate + wrap as MTLTexture | New `voxy_metal_iosurface.mm` + `IOSurfaceBridge` class. Smoke test (`testIOSurfaceBridge`) validates 256x256 BGRA8 IOSurface allocation and MTLTexture wrapping via `newTextureWithDescriptor:iosurface:plane:`. Storage forced to Private (IOSurface owns memory). M10 SMOKE OK. |
| `9f504be1` | Blocker 1 — Metal ICB infrastructure | New `voxy_metal_icb.mm` + `MetalIndirectCommandBuffer` class. Adds `IGpuIndirectCommandBuffer` cross-backend interface, `RenderBackend.createIndirectCommandBuffer`, `RenderEncoder.executeCommandsInBuffer`. Smoke test (`testMetalIcb`) validates the full path: 2-slot ICB with CPU-baked indexed triangle draw, executed via range buffer (location=0, length=1). All graphics pipelines now created with `supportIndirectCommandBuffers=YES`. |
| `bd89fcaf` | M9 Phase 4 — migrate MDICSectionRenderer non-Iris shader pipelines | 5 of 7 shaders (prep/cmdgen/cull/prefixSum/translucentGen) now via createComputePipeline+createGraphicsPipeline; cached glProgram per pipeline; `.bind()` → `glUseProgram(glProgramX)`. The two terrain shaders stay legacy (Iris patch callbacks; Iris is GL-gated). Smoke test grows to 24 cases — SPIRV 24/24. |
| `73549c9e` | M9 Phase 2 — patch lod/gl46/quads.frag gl_HelperInvocation gap | Add `GL_ARB_shader_helper_invocation : enable` so glslang/shaderc compiles the shader at 430. |
| `761f135b` | M9 Phase 4 — migrate BudgetBufferRenderer shader pipeline | Bakery's vert+frag pair now via createGraphicsPipeline; matrix uniform → UBO push at PUSH_BINDING; sampler in position_tex.fsh moved from location to binding. Smoke test grows to 19 cases — SPIRV 19/19. Caller ModelTextureBakery stays GL-only for now (FBO + viewport ownership). |
| `1e2a1190` | M9 Phase 4 — gate IrisVoxyRenderPipeline on GL + add drawIndexedIndirectCount API | RenderPipelineFactory refuses to construct Iris pipeline on non-GL backends; NormalRenderPipeline fallback runs. Encoder API gets drawIndexedIndirectCount(prim, drawBuf, drawOff, countBuf, countOff, maxDraw, stride) — GL lowers to glMultiDrawElementsIndirectCountARB, Metal throws pending ICB (Blocker 1). |
| `68734b78` | M9 Phase 4 — migrate AsyncNodeManager onto the compute encoder | Two compute pipelines (scatterWrite/multiMemcpy) flow through createComputePipeline + beginComputePass; scatter.comp's `count` uniform wrapped in UBO push; UploadStream raw-id glBindBufferRange persists as the existing M9-TODO. Smoke test grows to 17 cases — SPIRV 17/17. |
| `71e26460` | M9 Phase 4 — migrate ChunkBoundRenderer + outline.vsh #version bump | AABB-wireframe instanced indexed draws; pipeline created via createGraphicsPipeline (glProgram cached for raw bind); outline.vsh bumped to #version 460 core to get mix(ivec3,...) + gl_BaseInstance as built-ins (ARB extensions weren't accepted by shaderc's Vulkan profile). Smoke test grows to 15 cases. |
| `81c06456` | M9 Phase 4 Cluster A — migrate FullscreenBlit + AbstractRenderPipeline + NormalRenderPipeline | FullscreenBlit pipeline now via createGraphicsPipeline (compiles on Metal/Vulkan); raw bind/blit retained for GL-only runtime; setBytes API replaces glUniform2f/glUniform4f/nglUniformMatrix4fv across 4 call sites; 2 shader UBO push blocks (depth_copy.frag + blit_texture_depth_cutout.frag); gl_DepthRange.diff/.near gated behind VOXY_VULKAN macro to fix shaderc gap. Smoke test grows to 13 cases — SPIRV 13/13. |
| `a9a599af` | M9 Phase 4 — migrate HiZBuffer onto RenderBackend abstraction | First graphics-pipeline migration in Voxy proper; per-mip beginRenderPass + draw 4 verts as TRIANGLE_STRIP (blit.vsh re-ordered from fan); custom PipelineState DepthState(test=true, write=true, ALWAYS); raw glBindTextureUnit kept for external source depth (raw int id, no IGpuTexture); GL_TEXTURE_BASE_LEVEL/MAX_LEVEL mutation for source-mip selection (GL-only until createView lands) |
| `5dcbc645` | M9 Phase 4 — migrate HierarchicalOcclusionTraverser + split setTexture/setStorageImage | Single beginComputePass with one direct + MAX-1 indirect dispatches; SceneUniform converted to readonly SSBO; queueIdx uniform → UBO push at PUSH_BINDING. Encoder API now has setStorageImage(binding, tex, level) for image bindings; setTexture is now sampled-only |
| `0b963825` | M9 Phase 4 — migrate NodeCleaner (pilot) | tick() fully on encoder API; updateIds() still raw glBindBufferRange for UploadStream's raw GL id. Shader uniforms → UBO push blocks at PUSH_BINDING. |
| `3bb722df` | Update M-SERIES-PORT-STATE for M9 Phase 1 completion | Doc-only — Blocker 2 cleared, Phase 4 recipe + uniform-block pattern, OpenGL backend section |
| `88a2c926` | M9 Phase 1 — implement GL backend behind RenderBackend abstraction | GlGraphicsPipeline/GlComputePipeline/GlSampler/GlComputeEncoder + full GlRenderEncoder + GLSL fields on (Graphics\|Compute)PipelineDesc |
| `b50109e5` | Add IGpuSampler + setBytes to encoders (M9 prep finishing batch) | Sampler API, push-constant equivalent |
| `4e5131d0` | Add depth/blend/raster state to graphics pipeline | PipelineState (depth, blend, raster) end-to-end on Metal |
| `c47dfe82` | Fix MTLVertexFormat enum values + add M9-prep smoke test | testMetalVertexBuffer caught off-by-9 in MTLVertexFormat |
| `79dc1033` | Add VertexLayout and indirect-draw to encoder API | VertexLayout + drawIndirect + drawIndexedIndirect (single-draw) |
| `2079e43c` | Expand encoder API for M9 prep — buffers, textures, indexed/indirect | Compute setTexture/dispatchIndirect/barrier; render setBuffer/setTexture/bindVertex/bindIndex/draw/drawIndexed/setViewport/setScissor |
| `8fd586b1` | Add Vulkan triangle + compute smoke tests (M6 + M8) | Vulkan graphics pipeline + draw, compute pipeline + descriptor sets |
| `07aa7d31` | Add Vulkan clear-color render pass via MoltenVK (M4 Stages B-D) | Vulkan VkDevice + VkImage + dynamic_rendering clear, pixel-exact readback |
| `6c1e661e` | Add Metal compute pipeline + dispatch (M7) | MetalComputePipeline + ComputeEncoder + SSBO write/read 64/64 |
| `864b9a7c` | Bootstrap Vulkan/MoltenVK loader (M4 Stage A) | VulkanLoader → VkInstance → Apple M4 Max enumerated |
| `6d4f0308` | Add Metal graphics pipeline + draw chain (M5 — first triangle) | MetalGraphicsPipeline + RenderEncoder.setPipeline/draw + readback |
| `16fe5667` | Add Mac M-series rendering: shaderc/spvc + Metal clear-color pass | M0 (env), M1 (RuntimeShaderCompiler 9/9), M2 (encoder API), M3 (Metal clear) |
| `08fcae6d` | Route UploadStream.commit() through the render backend | (pre-existing — UploadStream uses RenderBackend.copyBufferSubData) |

---

## Working tree (uncommitted, 2026-05-13)

The 2026-05-13 session(s) added significant code on top of `32aeb455`
but didn't commit it. Treat the list below as a virtual single commit
when picking up work. Files are listed by deliverable.

**Deliverable 1 — M13 chunk 5 (env fog parity) — closed, runtime-verified.**
- `assets/voxy/shaders/lod/gl46/bindings.glsl` — extends `SceneUniform`
  SSBO with `vec4 voxyFogEndParams` + `vec4 voxyFogColour` (offsets
  96 / 112 within the 1024-byte buffer; std140 layout preserved).
- `assets/voxy/shaders/lod/gl46/quads3.vert` — emits
  `layout(location=2) out float voxyFogDist = length(cornerPoint -
  cameraSubPos)` when `USE_ENV_FOG` is defined.
- `assets/voxy/shaders/lod/gl46/quads.frag` — mixes `voxyFogColour`
  in at the end of the non-PATCHED_SHADER branch under
  `#ifdef USE_ENV_FOG`. The GL path keeps its post-pass fog via
  `blit_texture_depth_cutout.frag` (no double-apply).
- `AbstractRenderPipeline.useEnvFog()` accessor + `NormalRenderPipeline`
  override returning its existing config flag.
- `MDICSectionRenderer.uploadUniformBuffer` packs the env-fog params
  using the same `invEndFogDelta` / `startDelta` / max-density clamp
  formula as `NormalRenderPipeline.finish`. Constructor injects
  `USE_ENV_FOG` define only when `backend.getType() != OPENGL` AND
  `pipeline.useEnvFog()` (so Iris-patched paths and the GL pipeline
  don't inject it).
- `tools/ShaderCompilerSmokeTest` adds two variants: USE_ENV_FOG
  vertex + USE_ENV_FOG fragment. Both transpile cleanly to MSL.

**Deliverable 2 — M13 chunk 3 (MC depth import + HiZ pyramid) — parked.**
- `core/rendering/util/DepthMirror.java` — new class. Shared-storage
  `Depth32Float` mirror of MC's main-FBO depth attachment. Frame-id-
  gated CPU readback via `nglGetTexImage(GL_DEPTH_COMPONENT, GL_FLOAT)`
  using the bind-then-read legacy path (Apple GL 4.1-compatible).
  Mirrors the `LightMapHelper.bindMetal` pattern at a larger scale
  (~8 MB per readback at 1920×1080).
- `metal/MetalNative.mtlTextureNewSubresourceView` + the corresponding
  C++ JNI in `native/metal/src/voxy_metal_texture.mm`. Wraps
  `[MTLTexture newTextureViewWithPixelFormat:textureType:levels:
  slices:]`. Native dylib rebuilt + copied to
  `src/main/resources/natives/macos-arm64/libvoxy_metal.dylib`.
- `gpu/IGpuTexture.createView(int baseLevel, int levelCount)` default
  method (falls back to `createView()`); `MetalTexture` overrides to
  call the new JNI and syncs the result handle into `MetalHandleMap`
  so `encoder.setTexture(slot, view)` resolves the real native
  pointer (single-arg `createView()` had the same latent
  handle-map-sync bug; fixed in the same Edit).
- `rendering/util/HiZBuffer.buildMipChain(IGpuTexture srcDepth,
  int width, int height)` — encoder-driven mip-chain build using
  cached per-mip self-views for the source binding. The existing
  GL `buildMipChain(int, int, int)` is unchanged.
- `AbstractRenderPipeline.runPipelineMetal(viewport,
  sourceFrameBuffer)` — signature change (now takes the source FBO)
  with a `DepthMirror` lifecycle managed inside the pipeline.
- **Status:** the call site to `buildMipChain(IGpuTexture, ...)` is
  reverted to `ensureAllocated(...)` because in-game test showed LOD
  chunks vanishing at the horizon. Infrastructure stays committed.
  Suspected cause: HiZ pyramid format `Depth32Float_Stencil8`
  doesn't sample cleanly as `sampler2D` in MSL — try
  `GL_DEPTH_COMPONENT32F` as the pyramid format on Metal.

**Deliverable 2b — Horizon-chunks regression chain (root-cause + patch, late evening).**

Two-layer bug that landed together at commit `32aeb455` (chunk-1 attempt,
2026-05-12) and made the M12-stable visual completely invisible until
this session caught it. The first fix alone wasn't enough — both layers
had to be unblocked.

- **Layer 1 (shader gate).** The chunk-1 commit removed the
  `VOXY_NO_ATLAS` define injection from `MDICSectionRenderer`'s Metal
  branch, based on the (then-aspirational, now stale) assumption that
  the bakery would soon be filling `ModelStore.textures`. The bakery
  was gated to a no-op in the same commit, so the terrain shader ran
  the real-atlas path against an all-zeros atlas → alpha = 0 →
  `discard` for CUTOUT/TRANSLUCENT, black output for SOLID → invisible
  on the near-black bridge clear colour.
  - **Fix:** re-inject `VOXY_NO_ATLAS` in MDIC's Metal branch when
    `VOXY_BAKERY_FORCE` isn't set; under force, inject
    `VOXY_DEBUG_MAGENTA_MISSING` instead so empty atlas pixels render
    as bright magenta (`outColour = vec4(1,0,1,1)` in `quads.frag`
    just after the `textureGrad` sample, gated on the new define).
    Smoke-tested as a new shader variant.
  - **New diagnostic**: `MDICSectionRenderer`'s constructor now emits
    a `[Metal-DEFINES] terrain shader injections: ...` log line so the
    live define set is unambiguous from the runtime log.

- **Layer 2 (bakery output controls quad generation).** Even after
  layer 1 was patched, the user reported still-invisible chunks. The
  `Metal-GEN realQ` counter stayed at zero across thousands of frames.
  Trace: `ModelTextureBakery.renderToStream`'s Metal-default branch
  returned 0 without writing anything to `destAddr` (the CPU-mapped
  download-stream buffer `ModelFactory.addEntry` provides). Downstream
  `ModelFactory.processTextureBakeResult` reads that buffer per-face
  and calls `TextureUtils.wasPixelWritten` to decide if the face is
  visible. For SOLID blocks (most blocks!) the check is
  `WRITE_CHECK_STENCIL`:
  ```java
  return (data.depth()[index] & 0xFF) != 0;
  ```
  Reading `GlViewCapture.emitToStream`'s pack format,
  `value = (depthBits << 8) | ((meta & 1) << 7)`, the low 8 bits of
  the depth field are: bit 7 = tint flag, bits 0–6 = always zero.
  With a zero `destAddr` the low byte is 0, every pixel fails the
  check, every face is unflagged, every block has zero faces visible,
  zero quads get generated.
  - **Fix:** `ModelTextureBakery.writeDefaultBakePattern` fills
    `destAddr` with colour = `0xFFFFFFFF` (alpha = 255, passes
    `WRITE_CHECK_ALPHA` for CUTOUT/TRANSLUCENT) and depthStencilTint =
    `0x80` (bit 7 set, passes `WRITE_CHECK_STENCIL` for SOLID) for
    every pixel of every face of every block. Every block ends up
    registered as "all 6 faces drawn, tinted"; the LOD shader's
    `VOXY_NO_ATLAS` path ignores atlas colour entirely and emits
    per-quad hash colours, so the spurious tint flag has zero visual
    impact. 12 KB memset per unique block state — negligible.

  - **Misnomer captured for future cleanup:**
    `TextureUtils.WRITE_CHECK_STENCIL` is misleadingly named — it
    doesn't read the GL stencil channel (`GlViewCapture.emitToStream`
    only reads `GL_DEPTH_COMPONENT`, no `GL_STENCIL_INDEX`). It
    inspects the low byte of the depth-field uint which the bakery
    happens to pack as "bit 7 = tint, bits 0–6 = zero". A future
    refactor should rename it to `WRITE_CHECK_TINT_BIT` and document
    that the GL stencil aspect isn't read back. If that ever changes
    (e.g., we start writing a real stencil byte into low bits), the
    `writeDefaultBakePattern` low byte needs updating to match.

**Deliverable 3 — M13 chunk 1 (Metal-native bakery) — parked.**
- `core/rendering/util/AtlasMirror.java` — new class. Mirrors MC's
  `textures/atlas/blocks.png` GL texture into a Shared-storage Metal
  texture via `nglGetTexImage` (Apple GL 4.1-compatible). One-time
  sync gated by `lastSyncedGlId`; re-syncs on resource-pack reload.
- `metal/MetalTexture.storeRenderTargetUploadable(...)` — Shared +
  RenderTarget + ShaderRead storage variant for the bake target so
  we can render into it AND `getBytes` from it without an
  intermediate Metal blit-copy.
- `metal/MetalNative.mtlTextureGetBytes` + C++ JNI in
  `native/metal/src/voxy_metal_texture.mm`. CPU readback wrapping
  `[MTLTexture getBytes:bytesPerRow:fromRegion:mipmapLevel:]`.
  `MetalTexture.getBytes` guards on storage mode.
- `core/model/bakery/MetalBudgetBufferRenderer.java` — new class.
  Metal counterpart to `BudgetBufferRenderer`. Builds the
  `bakery/position_tex.vsh+.fsh` MSL-transpiled pipeline (via
  `RenderBackend.createGraphicsPipeline` with the new
  `BAKERY_SINGLE_ATTACHMENT` define), uses `MetalRenderEncoder` +
  cached vertex/index buffer for the bake. Supports `beginPass(clear)`
  with both CLEAR and LOAD load-actions so the fluid bakery path
  can render face-by-face across multiple passes while preserving
  prior faces.
- `core/model/bakery/MetalViewCapture.java` — new class. Metal
  analogue of `GlViewCapture`. Owns the 48×32 bake target, the
  `AtlasMirror`, and the `MetalBudgetBufferRenderer`. Exposes
  `clear → beginBake → renderFace × N → endBake → emitToStream` and
  packs RGBA bytes into the legacy
  `(packedRGBA, packedDepthStencilTint)` uvec2-per-pixel format
  that `ModelStore` consumers expect (single-attachment MVP — the
  metadata uvec2 component is zero).
- `assets/voxy/shaders/bakery/position_tex.fsh` — gates the
  `layout(location=1) out uvec4 metaOut` declaration + write under
  `#ifndef BAKERY_SINGLE_ATTACHMENT`. The GL path keeps both outputs
  via the existing two-attachment FBO.
- `tools/ShaderCompilerSmokeTest` adds a
  `BAKERY_SINGLE_ATTACHMENT` variant — transpiles cleanly to MSL.
- `core/model/bakery/ModelTextureBakery` —
  - Adds `MetalViewCapture metalCapture` field (lazy).
  - Adds `private int renderToStreamMetal(BlockState, long destAddr)`
    that mirrors the GL path's `bakeBlockModel`/`bakeFluidState`
    logic but routes through the Metal-side capture. Block path
    opens ONE render pass with all 6 face draws; fluid path
    opens one LOAD-action pass per face (each face has its own
    mesh from `bakeFluidState`).
  - Projection matrix on the Metal path uses `m22 = +1` (Metal NDC
    z range [0, 1] vs GL's [-1, 1]) and `m11 = -2` + `m31 = +1`
    (Y-flip so the Metal memory-row-first byte order matches GL's
    bottom-row-first layout the LOD shader was designed against).
- **Gate**: `ModelTextureBakery.renderToStream` now reads three env
  vars: `VOXY_BAKERY_OFF=1` → always 0 (kill switch);
  `VOXY_BAKERY_FORCE=1` + Metal → run `renderToStreamMetal`;
  otherwise on Metal → returns 0 (M12-stable hash-colour fallback).
  GL path unchanged.
- **Status:** end-to-end implementation lands cleanly (Java + native
  + shaders compile, smoke tests pass), but enabling via
  `VOXY_BAKERY_FORCE=1` triggers `Sodium.SharedQuadIndexBuffer.grow
  → glMapBufferRange` returning null ~30 s into chunk loading —
  same crash shape the GL bakery had. Default is OFF.

**Docs touched:** `docs/STATUS.md` rewritten to reflect the new state;
this document (`docs/M-SERIES-PORT-STATE.md`) updated; memory file
`project_m13_chunks3_5_closed.md` rewritten with late-session
corrections.

---

## Smoke test inventory (all green on Apple M4 Max)

```bash
./gradlew testShaderCompiler    # 31/31 SPIRV, 30/31 MSL — hiz.comp deferred (subgroupClusteredMax cluster > 4).
./gradlew testMetalRender       # M3: clear-color render pass commits + completes
./gradlew testMetalTriangle     # M5: gl_VertexIndex triangle, interpolated colors
./gradlew testMetalCompute      # M7: increment.comp on SSBO, 64/64 values match
./gradlew testMetalVertexBuffer # M9-prep: VertexLayout + bindVertexBuffer + drawIndirect
./gradlew testMetalIcb          # Blocker 1: MTLIndirectCommandBuffer with CPU-baked indexed-draw, executeCommandsInBuffer
./gradlew testIOSurfaceBridge   # M10: IOSurface allocation + MTLTexture wrap, BGRA8 256x256
./gradlew testVulkanLoader      # M4-A: MoltenVK + VkInstance + physical device
./gradlew testVulkanClear       # M4-D: dynamic_rendering clear, pixel-exact readback
./gradlew testVulkanTriangle    # M6: Vulkan graphics pipeline + draw
./gradlew testVulkanCompute     # M8: compute pipeline + descriptor sets, 64/64 values match
```

The shader-compiler test exits 1 on the documented `hiz.comp` MSL
deferral; count PASS lines (or the `=== SPV: X/X  MSL: Y/Z ===`
summary) to verify rather than the exit code.

Each test is a standalone `me.cortex.voxy.tools.*SmokeTest` class with `main()`. Useful as regression gates after any change to the encoder API or backend code.

---

## Critical gotchas already hit (do not repeat)

**A.** *(2026-05-13)* `MDICSectionRenderer` MUST inject `VOXY_NO_ATLAS`
on the Metal terrain shader when the bakery is gated off. Without it
the shader compiles for the real-atlas path, samples an all-zeros
atlas, alpha-discards every cutout fragment, and renders SOLID blocks
as black against the near-black bridge — totally invisible. The
chunk-1 attempt commit (`32aeb455`) removed the injection prematurely
based on an aspirational "chunk 1 closed" comment that turned out to
be wrong; took until late session 3 of 2026-05-13 to catch and patch.
See "Horizon-chunks regression chain" section below.

**B.** *(2026-05-13)* Returning 0 from `ModelTextureBakery.renderToStream`
without writing to `destAddr` is NOT semantically equivalent to "skip
this block". Downstream `RenderDataFactory` uses the bytes in
`destAddr` to determine which faces of the block are visible — a
zero buffer means "no faces visible" → zero quads generated for that
block → that block invisible at LOD distance. **BUT** writing a
synthetic "visible" pattern directly to `destAddr` is also NOT
SAFE on Apple GL — `destAddr` points into MC's GL-persistent-mapped
download stream buffer, and writing significant data there (e.g.
12 KB per bake × many bakes per second) destabilises Apple's GL
pixel processor and SIGBUSes the next `nglGetTexImage` call from
`LightMapHelper.syncFromMc`. The safe path forward is to populate
the face-visibility metadata at the HEAP level (build a
`RawBakeResult.rawData` synthetically and push it onto
`rawBakeResults` directly, bypassing `ModelFactory.addEntry`'s
`this.downstream.download(...)` GL allocation). Tracked in TaskCreate
#11 and the "Horizon-chunks regression chain" section of
`docs/STATUS.md`.

**C.** *(2026-05-13)* `TextureUtils.WRITE_CHECK_STENCIL` doesn't check
the GL stencil aspect. `GlViewCapture.emitToStream` only reads
`GL_DEPTH_COMPONENT` (depth float), packs it as
`(depthBits << 8) | ((meta & 1) << 7)`, and the "stencil check"
evaluates `(value & 0xFF) != 0` — that's just bit 7 (the tint flag)
plus six always-zero bits. The constant is misnamed; treat it as
"WRITE_CHECK_TINT_BIT" mentally. Any pattern that wants SOLID blocks
to register as drawn MUST set bit 7 of the depth field.

---

## Original critical gotchas

1. **Repo didn't compile baseline.** `GlRenderBackend` referenced `GL31C.GL_COPY_READ_BUFFER_BINDING` / `GL_COPY_WRITE_BUFFER_BINDING`, which LWJGL 3.3.3 doesn't expose. The bind-target enums share numeric values per OpenGL spec, so `GL_COPY_READ_BUFFER` works as the `glGetIntegerv` `pname`. Fixed in `16fe5667`.

2. **MTLVertexFormat values were off-by-9.** Initial `VertexFormat.metalValue` assignments assumed values from "standard order" without checking the Metal SDK header. `FLOAT` was 37 (actually `UInt2`); pipeline link failed with "MTLAttributeFormatUInt3". Caught only because `testMetalVertexBuffer` exercised it. **Rule: always verify enum values against the SDK header**, never infer from order. Fixed in `c47dfe82`.

3. **`VkWriteDescriptorSet.descriptorCount(1)` is required.** `calloc`'d struct defaults to 0, which means "write 0 descriptors" — the update is a silent no-op. Compute dispatched, but the buffer was effectively unbound, and all values came back as zero. Set `descriptorCount` + `dstArrayElement` explicitly on every Vulkan descriptor write. Fixed in `8fd586b1`.

4. **`SPVC_COMPILER_OPTION_MSL_ARGUMENT_BUFFERS=true` wraps each binding in a descriptor-set struct.** Output had `spvDescriptorSetBuffer0` with `[[id(N)]]` members, accessed via a single `[[buffer(0)]]` slot. Doesn't match Voxy's per-binding pattern; setBuffer(0) silently bound to the wrong slot. Disabled (`false`) in `RuntimeShaderCompiler`. Fixed in `6c1e661e`.

5. **Auto-injected GLSL → Vulkan macros.** `RuntimeShaderCompiler` injects `#define VOXY_VULKAN 1`, `#define gl_VertexID gl_VertexIndex`, `#define gl_InstanceID gl_InstanceIndex` so the same Voxy GLSL source compiles for both GL and Vulkan/Metal targets. Patched `hiz.comp` with `#ifdef VOXY_VULKAN` to wrap `invImSize` in a uniform block (the GL path keeps the location-based uniform).

6. **`createBuffer` returns Shared (CPU-visible) on Metal.** Means `((MetalBuffer)buf).getContentsPtr()` works for write/read from Java. M7 + M9-prep tests use this. For DEVICE_LOCAL we'd need a flag, currently not exposed.

7. **`MTLIndirectCommandBuffer` (ICB) needed for `glMultiDrawElementsIndirectCountARB`.** Metal lacks native multi-draw-indirect-count. The current `drawIndirect` impl loops on the host. For Voxy's MDIC use (potentially 100,000s of draws/frame), ICB is required for performance. **Not yet implemented.**

8. **Metal has no `TRIANGLE_FAN` primitive.** `RenderEncoder.PRIMITIVE_TRIANGLE_STRIP` is the closest. Voxy's `hiz/blit.vsh` outputs corners in fan order; needs patching for Metal use.

9. **`glBindImageTexture(unit, tex, mipLevel, ...)` — Metal needs a per-mip texture view.** `MetalTexture.createView()` exists but doesn't take a mip level. Blocks HiZBuffer2's compute pass migration (binds mips 1..6 as storage images).

10. **`hiz.comp` MSL fails — spvc-MSL only supports cluster size 4.** The shader uses `subgroupClusteredAdd` with cluster > 4. Vulkan/MoltenVK consumes the SPIRV directly (works). Metal direct via spvc-MSL needs a handwritten MSL replacement OR algorithm rewrite.

11. **LWJGL Vulkan auto-initializes** — `VK.create()` was being called before `VulkanLoader.load()` set `Configuration.VULKAN_LIBRARY_NAME`. Caught the `IllegalStateException("Vulkan has already been created.")` and continued.

12. **MoltenVK 1.4 doesn't need `VK_KHR_portability_enumeration`.** Initial Vulkan smoke test enabled the extension and it failed with `VK_ERROR_EXTENSION_NOT_PRESENT`. LWJGL bundles its own MoltenVK that loads directly; portability_enumeration is a loader-only extension.

13. **The IOSurface→MC composite must run *during* the Sodium opaque chunk pass, not at `LevelRenderer.renderLevel` RETURN.** First attempt blitted the bridge into `GL_DRAW_FRAMEBUFFER` from a `@Inject(at = RETURN)` mixin on `renderLevel`; at that point MC has already unbound to FBO 0, so the blit landed in the window backbuffer — which MC then overwrote with its own RT→window blit, hiding our output entirely. Fix: hook `DefaultChunkRenderer.render` *after* `renderer.renderOpaque(viewport)` (CUTOUT pass). At that mixin point, MC's main render target is still the active draw FBO and the bridge blit lands in pixels Sodium is about to ship. Lives in `MixinDefaultChunkRenderer` + `IOSurfaceBridgeCompositor`.

14. **`AsyncNodeManager`'s worker thread must be a daemon.** Without `setDaemon(true)`, the worker keeps the JVM alive after MC's main loop exits, so the close button hangs the process indefinitely. One-line fix on the thread before `.start()`; flagged because it only reproduces when Voxy actually runs (i.e. only matters after M11 enables the Metal path in-game).

15. **macOS arm64 LWJGL natives for `lwjgl-zstd` + `lwjgl-lmdb` must be bundled.** Voxy's chunk-serialization workers (and Sodium's chunk render task executors, which transitively touch `ZSTDCompressor` static init) fail with `UnsatisfiedLinkError: Failed to locate library: liblwjgl_zstd.dylib` if these are missing from the runtime classpath. The Win/Linux entries existed already; M11 adds the macos-arm64 pair to `build.gradle` so `runClient` works out of the box.

16. **MDIC terrain pipelines must NOT request ICB support.** `quads.frag` writes `gl_FragDepth` and uses `discard` — both are forbidden in Metal indirect command buffers ("fragment shader cannot be used with indirect command buffers"). The MDIC terrain `createGraphicsPipeline` call explicitly opts out (via the no-ICB constructor variant). The ICB infrastructure stays available (`MetalIndirectCommandBuffer` smoke-tested independently) for future simpler-shader use cases. For MDIC, the Metal path uses the CPU-readback fallback (`MetalRenderEncoder.drawIndexedIndirect` loops on the host).

---

## What's pending

### Blocker 1 — ICB for `drawIndirectCount` (~~~1 day~~ DONE — commit `9f504be1`)

✅ **Cleared 2026-05-09.** `MTLIndirectCommandBuffer` infrastructure
landed:

- Native: `voxy_metal_icb.mm` exposes
  `mtlDeviceNewIndirectCommandBuffer`,
  `mtlIndirectCommandBufferGetIndirectRenderCommand`, all the
  `mtlIndirectRenderCommand*` setters, and
  `mtlRenderEncoderExecuteCommandsInBuffer{,Indirect}`.
- Java: `IGpuIndirectCommandBuffer` interface,
  `MetalIndirectCommandBuffer` impl, `RenderBackend.createIndirectCommandBuffer`,
  `RenderEncoder.executeCommandsInBuffer(...)`.
- Smoke test `testMetalIcb`: 2-slot ICB with CPU-baked indexed
  triangle draw, executed via range buffer (location=0, length=1).
  Green on M4.
- Graphics pipelines that want ICB are now created with
  `supportIndirectCommandBuffers=YES`. **Exception**: MDIC's terrain
  pipelines (`quads.frag` uses `gl_FragDepth` + `discard`) explicitly
  opt out — see gotcha #16. For MDIC the Metal path keeps the
  CPU-readback fallback (`drawIndexedIndirect` host loop) until a
  simpler shader variant lands or we accept the perf cost.

For Vulkan: `RenderEncoder.drawIndexedIndirectCount(...)` exists in
the encoder API (commit `1e2a1190`) and will lower to
`vkCmdDrawIndexedIndirectCount` when the Vulkan backend implements
`RenderBackend` (deferred). GL path uses
`glMultiDrawElementsIndirectCountARB` directly.

### Blocker 2 — GL backend implements the abstraction (~~~1-2 days~~ DONE — commit `88a2c926`)

✅ **Cleared 2026-05-10.** All four `GlRenderBackend` methods that previously
threw `UnsupportedOperationException("see M9")` now have real
implementations, and the inline `GlRenderEncoder` was rewritten end-to-end
(every former stub is wired).

Files added under `src/main/java/me/cortex/voxy/client/core/gl/`:
- `GlGraphicsPipeline.java` — wraps a `Shader` compiled from raw GLSL via
  `Shader.Builder.addSource()`. Reads `defines` map straight from
  `GraphicsPipelineDesc`. Holds `VertexLayout` + `PipelineState` so the
  encoder can apply state on bind.
- `GlComputePipeline.java` — same pattern for compute.
- `GlSampler.java` — `glGenSamplers` + parameter mapping (filter, wrap,
  LOD clamps, compare). `CLAMP_TO_ZERO` → `GL_CLAMP_TO_BORDER` (GL's
  closest equivalent).
- `GlComputeEncoder.java` — direct lowering: SSBO base/range, image
  bind, sampler bind, UBO push (named buffer), `glDispatchCompute`,
  `glDispatchComputeIndirect`, `glMemoryBarrier` mask translation.
- `GlPipelineStateApplier.java` — depth/blend/raster GL state, called
  from `setPipeline`. (Metal/Vulkan bake this; GL has no PSO so we
  re-issue per bind.)
- `GlVertexFormatMap.java` — `VertexFormat` → (gl_type, count,
  normalized, isInteger) tuple table, used by `glVertexArrayAttrib*Format`.

Descriptor changes — `GraphicsPipelineDesc` and `ComputePipelineDesc`
now carry `vertexGlsl/fragmentGlsl/computeGlsl` + `defines` so call
sites can pass the same source they fed `Shader.Builder`. Existing
constructors delegate with `null` GLSL so the M3-M8 smoke tests stay
valid.

Build: `./gradlew compileJava` — green.

**Caveat**: legacy GL paths still call raw `org.lwjgl.opengl.*` directly.
The encoder API is *available* for the migration; nothing in Voxy uses
it yet (Phase 4 — see "M9 file migration order" below).

### Blocker 3 — Per-mip texture view JNI (~1-2 hours)

Why: HiZBuffer2 binds mip levels 1..6 as separate storage images (`glBindImageTexture(i, tex, mipLevel=i, ...)`).

To implement:
- New JNI: `mtlTextureNewViewWithMipLevel(tex, pixelFormat, mipLevel, mipLevelCount)` (uses `[texture newTextureViewWithPixelFormat:textureType:levels:slices:]`).
- `IGpuTexture.createView(int mipLevel, int mipLevelCount)` overload.
- For Vulkan, `VkImageView` already supports `subresourceRange.baseMipLevel` — ready when Vulkan backend is wired in.

### Blocker 4 — `hiz.comp` MSL workaround (~0.5-1 day)

Why: spvc-MSL fails on cluster size > 4 ClusteredReduce.

Options:
- Handwrite MSL for hiz.comp specifically. Live alongside the GLSL/SPIRV path.
- Rewrite hiz.comp algorithm using only quad subgroups (cluster=4) or threadgroup-memory reductions.

Vulkan/MoltenVK is unaffected — the extension SPIRV consumes natively.

### Blocker 5 — `GL_TRIANGLE_FAN` shader patches (~30 min/shader)

Voxy uses fan-order corners in `hiz/blit.vsh`. Metal has no fan primitive; calls map to TRIANGLE_STRIP, but the vertex order differs. Either:
- Patch shaders to emit strip-order corners always. Test on GL side (most cards still accept either).
- Branch via a `#define` injected by `RuntimeShaderCompiler`.

### Source patches still pending (from M1 sweep)

Five shaders flagged in the M1 sweep. 4 of 5 patched, 1 remains:

| Shader | Issue | Fix | Status |
|---|---|---|---|
| `chunkoutline/outline.vsh` | `mix(int, int)` requires extension | bump `#version` to 460 (gets it + `gl_BaseInstance` as core built-ins) | ✅ done — `71e26460` |
| `lod/gl46/quads.frag` | `gl_HelperInvocation` undeclared | `#extension GL_ARB_shader_helper_invocation : enable` | ✅ done — `73549c9e` |
| `post/depth_copy.frag` | `binding=` not supported in this version | bump `#version` | ✅ done — `81c06456` bumped to 430 core |
| `post/blit_texture_depth_cutout.frag` | `gl_DepthRange` undeclared in newer profile | replace with uniform OR version bump | ✅ done — `81c06456` gates on `VOXY_VULKAN` macro |
| `lod/gl46/test/raw.vert` | syntax error around line 190 | likely needs runtime define injection | ⚠️ orphan — `grep -rn raw.vert src/main/java` returns nothing; this file is a test/experiment Voxy never compiles at runtime, so the M1 report's "fail" is non-blocking. Leave as-is. |

### M9 Phase 4 — file migration order (Blocker 2 now cleared)

Each migration follows the same pattern (validated against the GL backend
in Phase 1):

1. Replace `Shader.makeAuto(...).compile()` /
   `Shader.make(...).compile()` with `RenderBackend.createComputePipeline(
   new ComputePipelineDesc(glsl, defines, null, null, lx, ly, lz, label))`
   (or `createGraphicsPipeline` for v+f stages).
2. Replace direct `glBindBufferBase / glBindBufferRange` with
   `ComputeEncoder.setBuffer(binding, buf, offset)`.
3. Replace `glBindImageTexture(binding, tex, 0, ...)` with
   `setTexture(binding, tex)` — note: the encoder always binds level 0.
   Multi-mip storage images (HiZBuffer2) need an API extension
   (`setStorageImage(binding, texture, level)`) — file under "API gaps"
   below.
4. Replace `glBindSampler` with `setSampler(binding, sampler)`; sampler
   must be created via `RenderBackend.createSampler(SamplerDesc)`.
5. Replace `glDispatchCompute(x, y, z)` with `dispatch(x, y, z)`.
   `glDispatchComputeIndirect` → `dispatchIndirect`.
6. Replace `glMemoryBarrier(GL_SHADER_STORAGE_BARRIER_BIT)` with
   `barrier(BARRIER_SHADER, BARRIER_SHADER)`.
7. **Edit the shader** to convert `layout(location=N) uniform X;` into
   a UBO block (see "Uniform-block pattern" below). Then push the data
   from Java with `setBytes(binding, dataAddr, dataSize)`.

#### Uniform-block pattern (mandatory for every shader during migration)

GL allows `layout(location=N) uniform uint count;` set via
`glUniform1ui(N, value)`. Vulkan and Metal don't have location-based
uniforms — they need a UBO block (or push constants) the host writes
into. To stay single-source across backends, every migrated shader
wraps these:

```glsl
// Before
layout(location=0) uniform uint count;
layout(location=1) uniform uint setTo;

// After
layout(binding = PUSH_CONSTANTS_BINDING, std140) uniform Push {
    uint count;
    uint setTo;
};
```

Java side replaces the `glUniform*` calls:

```java
// Before
glUniform1ui(0, count); glUniform1ui(1, setTo);

// After (push 8 bytes via setBytes)
try (var stack = MemoryStack.stackPush()) {
    long addr = stack.nmalloc(8);
    MemoryUtil.memPutInt(addr,     count);
    MemoryUtil.memPutInt(addr + 4, setTo);
    encoder.setBytes(PUSH_CONSTANTS_BINDING, addr, 8);
}
```

`PUSH_CONSTANTS_BINDING` should be a stable binding index per shader
(the migration convention: pick a high binding that doesn't collide
with SSBO bindings — e.g. 14). Defines map carries it so the same
GLSL works for GL (UBO at that binding) and the Metal/Vulkan transpile
path.

#### API gaps to fix mid-Phase-4

These extensions weren't needed to validate Phase 1 but are required by
specific Voxy callers:

- ✅ `ComputeEncoder.setStorageImage(binding, texture, level)` — added
  in commit `5dcbc645`. GL impl uses `glBindImageTexture` with the
  requested level; Metal currently throws on level != 0 until the
  per-mip texture-view JNI lands; Vulkan path will allocate a per-mip
  `VkImageView` when the backend is wired up.
- `IGpuTexture.createView(int level, int levelCount)` — needed for HiZ
  blit pass (source mip selection). GL: `glTextureView` (immutable
  view of a subset). Metal:
  `newTextureViewWithPixelFormat:textureType:levels:slices:`.
  Vulkan: `VkImageView` with subresource range.
- `RenderEncoder.drawIndexedIndirectCount(...)` — for
  `MDICSectionRenderer`. GL: `glMultiDrawElementsIndirectCountARB`.
  Vulkan: `vkCmdDrawIndexedIndirectCount` (core 1.2). Metal: emulated
  via ICB (Blocker 1 above).
- `RenderEncoder` extension for `GL_TRIANGLE_FAN` — used by HiZ blit
  and a few full-quad shaders. Easiest fix is to rewrite affected
  shaders to emit strip-order vertices and drop fan support; that path
  avoids needing fan emulation on Metal.
- `RenderBackend.copyToBuffer(buf, offset, dataAddr, size)` —
  CPU→GPU single-int upload used by
  `HierarchicalOcclusionTraverser.addTLN/remTLN` and renderList-counter
  zero. Both are left as raw `glBindBuffer` + `nglBufferSubData` in
  the migrated version; the helper would close that gap.

Recommended migration order (simplest first):
1. ✅ **`NodeCleaner.java`** — DONE in commit `0b963825`. Pilot for the
   uniform-block pattern; pure compute, three small shaders.
2. ✅ **`HierarchicalOcclusionTraverser.java`** — DONE in commit
   `5dcbc645`. Compute + indirect dispatch + barrier translation +
   sampled-texture binding. Also drove the
   `setTexture`/`setStorageImage` split on `ComputeEncoder`.
3. ✅ **`HiZBuffer.java`** — DONE in commit `a9a599af`. First graphics
   migration. blit.vsh re-ordered fan→strip. Source-mip selection
   left as raw GL_TEXTURE_BASE_LEVEL/MAX_LEVEL mutation (GL-only)
   pending `IGpuTexture.createView(level, count)` API for
   Metal/Vulkan. `HiZBuffer2.java` (the unused variant) is similar
   shape — migrate when the compute mip-chain path actually gets used.
4. ✅ **Cluster A — `FullscreenBlit` + `AbstractRenderPipeline` +
   `NormalRenderPipeline`** — DONE in commit `81c06456`. Pipeline
   creation now backend-agnostic (createGraphicsPipeline); bind/blit
   stay raw GL because the whole runPipeline path early-returns on
   non-GL until IOSurface bridge lands. setBytes(binding, addr, size)
   replaced all four glUniform* call sites (depth_copy scaleFactor,
   transformBlitDepth invProj/proj, finalBlit fog endParams/colour).
   Shaders: depth_copy.frag and blit_texture_depth_cutout.frag grew
   UBO push blocks; the latter also patches gl_DepthRange via the
   `VOXY_VULKAN` macro. `IrisVoxyRenderPipeline` still uses
   FullscreenBlit but only via the no-arg constructor path (still
   works); its full migration waits on the GL-gate sweep.
5. ✅ **`ChunkBoundRenderer.java`** — DONE in commit `71e26460`. AABB
   wireframe instanced draws; pipeline now via createGraphicsPipeline;
   raw bind/draw retained for GL-only runtime. Required #version 460
   bump on outline.vsh because shaderc's Vulkan profile only exposes
   `mix(ivec3, ivec3, bvec3)` + `gl_BaseInstance` at 4.6.
6. ✅ **`AsyncNodeManager.java`** (compute paths) — DONE in commit
   `68734b78`. Two compute pipelines now flow through the encoder;
   scatter.comp's `count` uniform wrapped in UBO push. Worker thread
   later marked daemon in M11 closeout so MC closes cleanly on Metal.
7. **`VoxyRenderSystem.java`** — global GL state reads
   (`glGetIntegerv(GL_VIEWPORT, ...)`, `glGetIntegeri(GL_SHADER_STORAGE_BUFFER_BINDING)`)
   used for save/restore around Voxy's run. The whole method already
   early-returns on non-GL backends, so this stays GL-only until the
   IOSurface bridge changes the cross-context model. Migration would
   mostly mean replacing query+restore with the encoder/pass model
   on day Voxy starts running on Metal.
8. ⚠️ **`MDICSectionRenderer.java`** — partial. Pipeline creation
   (5 compute + 1 graphics + 2 terrain) DONE through `createComputePipeline` /
   `createGraphicsPipeline` (commits `bd89fcaf`, `cc8957f3`). The actual
   dispatch + draw codepaths (`buildDrawCalls`, `renderTerrain`,
   `renderTranslucent`, `renderTemporal`) STILL call raw `glUseProgram`
   /`glBindBufferBase` / `glDispatchCompute*` /
   `glMultiDrawElementsIndirectCount*` — they no-op on Metal because
   `mdicProgramId(p)` returns 0 for non-GL pipelines. **This is the M12
   scope** (new section below). Will need:
   - 5 compute prepasses (`prep`, `cull`-as-graphics, `commandGen`,
     `prefixSum`, `translucentGen`) routed through `beginComputePass` /
     `beginRenderPass` + `setBuffer` + `dispatch[Indirect]`.
   - `renderTerrain` / `renderTranslucent` / `renderTemporal` invoked
     against a `RenderEncoder` whose target is the IOSurface bridge,
     with `drawIndexedIndirect` (Metal CPU loop) or
     `drawIndexedIndirectCount` (GL path).
   - `AbstractRenderPipeline.runPipelineMetalStub` replaced with a real
     `runPipelineMetal` that opens the render pass against the bridge,
     invokes the section renderer, then submits.
9. **Cluster B — ✅ `BudgetBufferRenderer` (commit `761f135b`) +
   ⚠️ `ModelTextureBakery`** — pipeline side done; the caller
   `ModelTextureBakery` still owns the FBO + viewport setup with ~150
   lines of raw GL state management. Must migrate together for Mac
   functionality. Large. GL-only at the moment.
10. **`GlViewCapture`** — explicitly GL-only by design (class name
    documents it). Replacement is a parallel `MetalViewCapture` once
    IOSurface/MTLBlitCommandEncoder JNI lands; until then the
    M9-transitional `if (backend != GL) return` keeps the bakery
    silent on Mac without crashing.
11. ✅ **Iris* paths** — DONE in commit `1e2a1190`. `RenderPipelineFactory`
    refuses to construct `IrisVoxyRenderPipeline` on non-GL backends;
    NormalRenderPipeline fallback handles Metal/Vulkan. The two MDIC
    Iris-patched terrain shaders stay on the legacy `Shader.Builder`
    by design (Iris is GL-gated).

---

## M12 — LOD distance migration ✅ CLOSED (2026-05-12)

**Goal:** Voxy's actual LOD content (terrain quads from
`MDICSectionRenderer`) renders into the IOSurface bridge on Metal,
replacing the magenta clear stub in
`AbstractRenderPipeline.runPipelineMetalStub`. **Acceptance** = on
Apple M4 with `VOXY_FORCE_METAL=1`, loading a world shows Voxy's
distance-LOD chunks behind Sodium's chunk terrain (not just the
diagnostic strip).

**Why M11's closeout left this open:** every `MDICSectionRenderer`
*pipeline* now goes through `RenderBackend.create{Graphics,Compute}Pipeline`
(commits `bd89fcaf` for the 5 non-Iris pipelines, `cc8957f3` for the
2 terrain pipelines). But the *dispatch / draw* code in
`MDICSectionRenderer.buildDrawCalls` + `renderTerrain` +
`renderTranslucent` + `renderTemporal` still uses raw GL:

```java
if (this.prepProgram != 0) org.lwjgl.opengl.GL20C.glUseProgram(this.prepProgram);
glBindBufferBase(GL_UNIFORM_BUFFER, 0, this.uniform.id());
glBindBufferBase(GL_SHADER_STORAGE_BUFFER, 1, viewport.drawCountCallBuffer.id());
glBindBufferBase(GL_SHADER_STORAGE_BUFFER, 2, viewport.getRenderList().id());
glMemoryBarrier(GL_SHADER_STORAGE_BARRIER_BIT);
glDispatchCompute(1,1,1);
glMemoryBarrier(GL_SHADER_STORAGE_BARRIER_BIT);
```

`mdicProgramId(pipeline)` returns 0 for `MetalComputePipeline` /
`MetalGraphicsPipeline` (those don't have a GL program id), so
`glUseProgram(0)` runs with no program bound → the surrounding
`glBindBufferBase` + `glDispatch*` calls execute against a null
program and have no effect. Hence: nothing renders.

### Migration scope (six chunks)

1. ✅ **`prefixSum`** (commit `2e8e302d`) — single SSBO at binding 0,
   no UBO, 1 thread group. Pattern: `beginComputePass` →
   `setPipeline` → `setBuffer(0, distanceCountBuffer, 0)` →
   `barrier(SHADER, SHADER)` → `dispatch(1, 1, 1)` →
   `barrier(SHADER, SHADER)` → close. Pilot migration; pattern now
   proven inside MDIC's buildDrawCalls.
2. ✅ **`prep`** (commit `f0e7a3de`) — does not actually reference
   SceneUniform fields, so the encoder skips binding 0 entirely.
   Just SSBO bindings 1 (DrawCommandCountBuffer) and 2
   (IndirectSectionLookupBuffer). Defers the SceneUniform
   decision to chunk 3.
3. ✅ **SceneUniform flip + `commandGen`** (commit `a50e53e1`) —
   atomic two-part change:
   - `bindings.glsl` flips `SceneUniform` from `uniform` to
     `readonly buffer` (SSBO) at binding 0, std140 preserved.
     Matches HOT pattern (commit `5dcbc645`). Affects every shader
     importing bindings.glsl; all 26 smoke-test cases stayed green
     (SPV 26/26, MSL 25/26 — hiz.comp pre-existing).
   - The 3 still-raw-GL bind sites (bindRenderingBuffers, cull
     block, translucentGen block) retargeted to
     `GL_SHADER_STORAGE_BUFFER`.
   - `commandGen` migrated to `beginComputePass` + 8 SSBOs (incl.
     SceneUniform at 0 + optional statistics at
     `STATISTICS_BUFFER_BINDING=8`) + `dispatchIndirect` against
     `drawCountCallBuffer` offset 0 + barriers.
4. ✅ **`translucentGen`** (commit `f4d4c4ab`) — SceneUniform now
   already SSBO. 6 SSBO bindings + dispatchIndirect from the same
   `drawCountCallBuffer` (which doubles as the indirect arg AND
   one of the shader's SSBO inputs at binding 2 — pre-existing
   read-then-dispatch pattern preserved).
5. ✅ **`cull`** (commit `3fa07c44`, partial — Metal substitute) —
   GL keeps its depth-test rasterized cull. Metal runs a tiny
   `force_all_visible.comp` compute pass that writes
   `visibilityData[sid]` for every frustum-visible section
   (bit 31 set so cmdgen's temporal path is suppressed).
   No real occlusion on Metal yet — that's perf optimization
   deferred until cross-context MC-depth access lands (see
   chunk 6 step 2 below).
6. ✅ **Render passes + `runPipelineMetal`** — landed in 3 steps:
   - ✅ **step 1** (commit `dd007eae`) — `runPipelineMetal`
     replaces the clear-only stub and runs the full migrated
     compute pipeline on Metal: `viewport.hiZBuffer.ensureAllocated`
     (new helper; allocates HiZ tex without per-mip blit so HOT
     can bind it) + `DownloadStream.tick` + `AsyncNodeManager.tick`
     + `NodeCleaner.tick` + `HOT.doTraversal` + `buildDrawCalls`.
     Plus a teal clear of the bridge as visual feedback. Two
     follow-up fixes (`e71decf8` `DownloadStream.commit` raw GL,
     `676f0c09` AsyncNodeManager/NodeCleaner raw `glBindBufferRange`)
     made the compute path actually run on Apple's GL 4.1 context
     without crashing.
   - ✅ **step 3** (commit `9ef28478`, polished by `fd05a9fa`,
     `89c4d947`, `9d31a759`, and closed by `75277c42`) — full
     render-side migration. `metalDepthTex` (D24S8, lazy, bridge-
     sized) attached as depth in `runPipelineMetal`'s render pass.
     `MDICSectionRenderer.renderOpaqueMetal` / `renderTemporalMetal`
     / `renderTranslucentMetal` issue `drawIndexedIndirect` against
     `drawCallBuffer` at the respective slot offsets, with all 6
     SSBO bindings flowing through `encoder.setBuffer`. Texture
     bindings (modelAtlas / lightmap / depthBoundingBuffer)
     remain unbound (M13 territory); `VOXY_NO_ATLAS` shader
     define + a per-quad hash-colour + sky-direction face
     Lambertian shade gives a clearly visible cube-shaded debug
     output. `NO_CULL` rasterizer state on Metal so the GL path's
     draw-time `glDisable(GL_CULL_FACE)` semantic is preserved.
     `buildDrawCalls` zeros `drawCallBuffer` on non-GL backends
     so beyond-count slots no-op.
   - **step 2 deferred to M13** — cross-context MC-depth (real
     HiZ + real cull on Metal). M12 ships with the
     `force_all_visible` stub instead, which is functionally
     correct (slower than depth occlusion, no observable issue
     for the M4 test runs).

### Composite (M12 close)

After step 3 the LOD draws landed in the bridge but
`IOSurfaceBridgeCompositor` was still in its M11 dev-time mode of
blitting only the **left 25%** of the bridge into MC's main RT.
Commit `75277c42` flips this to a **full-screen** blit AND moves
the Sodium-render mixin from `BEFORE-Sodium.end CUTOUT` to `HEAD
Sodium.SOLID` so Voxy's full pipeline + composite runs *before*
Sodium starts drawing. Result: Sodium's near terrain overdraws
Voxy LOD via depth-test on top of the bridge's blit, exactly the
"LOD behind near terrain" stacking M12 acceptance requires.

### M12 acceptance (2026-05-12)

User-confirmed visually on Apple M4 with `VOXY_FORCE_METAL=1`: no
diagnostic strip, full LOD pyramid visible behind Sodium's near
chunks, all blocks with distinct face-shaded debug colours.
M12 ✅ closed.

---

## M13 — texture / lighting / depth-import polish

**Goal:** the Metal LOD samples real model textures + MC lightmap
instead of `VOXY_NO_ATLAS`'s hash colours; per-fragment occlusion
against MC's depth attachment (replacing the M12
`force_all_visible` cull stub); SSAO + final-blit on Metal so the
visuals match the GL path.

**Acceptance:** Voxy LOD on Metal is visually indistinguishable from
the GL backend at the same distance settings (modulo Iris features,
which stay GL-gated).

### Chunk-by-chunk status (2026-05-13)

1. 🌱 **Metal-native model texture bakery** — code complete,
   parked behind `VOXY_BAKERY_FORCE=1`. End-to-end implementation
   lives in the working tree:
   - `AtlasMirror` clones MC's GL atlas into a Shared Metal texture
     (~16 MB readback at startup; gated by `lastSyncedGlId`).
   - `MetalTexture.storeRenderTargetUploadable` allocates the 48×32
     RGBA8 bake target as Shared + RenderTarget + ShaderRead so we
     can render into it AND `getBytes` from it without a Metal blit.
   - `MetalBudgetBufferRenderer` drives the bakery's
     `position_tex.vsh+.fsh` MSL pipeline (gated by the new
     `BAKERY_SINGLE_ATTACHMENT` shader define) via
     `MetalRenderEncoder`. Supports CLEAR + LOAD load-actions so
     the fluid path can rebuild meshes between faces.
   - `MetalViewCapture` is the Metal counterpart of `GlViewCapture`
     — owns the bake target, exposes `clear → beginBake →
     renderFace → endBake → emitToStream`, and packs RGBA bytes
     into the legacy uvec2-per-pixel format the GL path produced
     (metadata uvec2 component is zero — single-attachment MVP).
   - `MetalNative.mtlTextureGetBytes` + JNI in
     `voxy_metal_texture.mm` is the underlying CPU readback.
   - `ModelTextureBakery.renderToStreamMetal` orchestrates per-block
     and per-fluid bakes. Projection matrix flips Y (`m11 = -2`,
     `m31 = +1`) and uses Metal NDC z range
     (`m22 = +1` so world z [0, 1] → NDC z [0, 1]).

   **Blocker:** enabling the bakery via `VOXY_BAKERY_FORCE=1`
   reproduces the same Sodium crash the GL bakery had —
   `SharedQuadIndexBuffer.grow → glMapBufferRange` returns null
   mid-frame, ~30 s into chunk loading. The Metal bakery's GL
   footprint is minimal (one-time `AtlasMirror` `nglGetTexImage`
   at startup — same pattern as the working `LightMapHelper.bindMetal`),
   so the failure isn't about a specific GL-state side effect.
   Working theory: `mtlCommandBufferWaitUntilCompleted` (which
   `RenderBackend.submit()` calls inside
   `MetalViewCapture.{clear,endBake}`) invalidates Apple GL's
   persistent-mapped buffer state on the shared GPU context.

   **Next session: try first** — replace `RenderBackend.submit()`
   inside `MetalViewCapture` with an async submit + `MTLEvent`-gated
   `getBytes` so the bakery never synchronously stalls the
   main render thread's command queue. If that doesn't help, move
   the bakery onto a dedicated `MTLCommandQueue` separate from
   the main Voxy queue.

2. ✅ **MC lightmap on Metal** — closed 2026-05-12. Shared-storage
   16×16 mirror; `LightMapHelper.bindMetal` CPU-uploads MC's GL
   lightmap once per frame via `IGpuTexture.uploadSubImage2D`
   (gated by `viewport.frameId`).

3. 🌱 **MC depth import (real HiZ pyramid)** — code complete,
   parked. `DepthMirror` lifts MC's GL depth into a
   Shared-storage `Depth32Float` Metal texture each frame;
   `MetalNative.mtlTextureNewSubresourceView` + per-mip
   `IGpuTexture.createView(level, count)` support let
   `HiZBuffer.buildMipChain(IGpuTexture, ...)` rotate the source
   binding across pyramid mips without the GL `BASE/MAX_LEVEL`
   state hack.

   **Blocker:** when wired at the call site (replacing
   `ensureAllocated(...)` in `runPipelineMetal`), LOD chunks
   vanished at the horizon. Suspected cause: the pyramid format
   is `MTLPixelFormatDepth32Float_Stencil8` (mapped from
   `GL_DEPTH24_STENCIL8`); sampling that as `sampler2D` in MSL
   may not return the depth aspect cleanly. The GL path's
   `initDepthStencil` also pre-processes MC's depth (stencil-mask
   dance, sky=1.0 / MC-drawn=MC-depth) which the Metal path
   skips entirely.

   **Next session: try first** — allocate the Metal HiZ pyramid
   as `GL_DEPTH_COMPONENT32F` (pure Depth32Float, no stencil)
   to remove the depth+stencil sampling ambiguity. If chunks
   still vanish, add a `voxyDepthForHiZ` preprocessing pass on
   Metal that mirrors `initDepthStencil`'s semantics.

3b. ⏳ **Depth-test raster cull (chunk 3 follow-up)** — not started.
   Replaces `force_all_visible.comp` with the real
   `cull/raster.vert`+`.frag` pass. Needs MC's depth as a
   Metal depth ATTACHMENT (not just a sampleable source). Two
   options: (a) extend `MetalTexture.storeUploadable` to also
   accept the RenderTarget usage bit so the existing
   `DepthMirror` doubles as an attachment; (b) blit-copy
   `DepthMirror` into a Private + RenderTarget depth target
   each frame via `MTLBlitCommandEncoder`.

4. ⏳ **`finish()` + SSAO on Metal** — not started. Unblocked
   once chunk 3 is re-enabled (SSAO compute reads MC depth +
   Voxy color; finish blit reads MC depth + Voxy color +
   sky fog). The fog math in
   `blit_texture_depth_cutout.frag` already compiles to MSL —
   only the call site is missing.

5. ✅ **Fog + atmosphere parity** — closed 2026-05-13. Per-fragment
   fog inside `quads.frag` gated by `USE_ENV_FOG`; params packed
   into the SceneUniform SSBO so the existing compute prepasses
   that ignore the trailing fields don't need to change. Runtime
   verified in-game (LOD chunks now fade into MC's near-terrain
   fog).

### Open decisions (M13)

- **Sodium `glMapBufferRange` interaction** (chunk 1 blocker). See
  the "Next session: try first" note in chunk 1 above. The default
  hypothesis is `mtlCommandBufferWaitUntilCompleted` interference;
  if disproven, the next candidates are (a) dedicated MTLCommandQueue
  for the bakery, (b) IOSurface-backed bake target so the readback
  reads the IOSurface's CPU base address directly (no `getBytes`),
  (c) batch multiple block bakes into one render pass so the
  submit rate drops by ~10× (currently 2 submits per block).
- **HiZ pyramid format on Metal** (chunk 3 blocker). Recommended:
  try `GL_DEPTH_COMPONENT32F` first; if that doesn't fix it, add
  a `voxyDepthForHiZ` preprocessing pass.
- **Depth attachment for raster cull** (chunk 3b). Recommended:
  extend `MetalTexture.storeUploadable` with a variant that takes
  the RenderTarget usage flag — Apple Silicon supports
  Shared+RenderTarget on unified memory, the only cost is a driver
  "eviction overhead" warning that doesn't matter for a per-frame-
  refreshed depth mirror.

### How to verify the runtime experience after each step

```bash
# Stable baseline — must work after any change in tree.
VOXY_FORCE_METAL=1 ./gradlew runClient
#   Expected: LOD chunks render in hash colours behind Sodium's
#   near terrain, fading into MC's environmental fog at distance.
#   No crashes.

# Re-enable a chunk for testing:
VOXY_FORCE_METAL=1 VOXY_BAKERY_FORCE=1 ./gradlew runClient
#   Expected: real block textures on LOD chunks (currently crashes
#   on Sodium glMapBufferRange ~30 s into chunk load — known).
```

If `VOXY_FORCE_METAL=1` is missing, the log emits
`Not creating renderer due to disabled` from `MixinLevelRenderer.
createRenderer` and Voxy never initialises (the LOD pipeline is
gated off at `VoxyClient.java:65-82` on non-OpenGL backends). The
visible symptom is "LOD chunks don't appear at all" — common
confusion when a fresh shell forgets the env var.

---

## File map

### New abstraction (`src/main/java/me/cortex/voxy/client/core/gpu/`)

| File | Purpose |
|---|---|
| `RenderBackend.java` | Central interface — buffers, textures, framebuffers, fences, render/compute pipelines, encoders, samplers, copy/barrier |
| `RenderBackendFactory.java` | Auto-detects Mac+aarch64 → MetalRenderBackend, else GlRenderBackend |
| `BackendType.java` | enum: OPENGL, METAL (VULKAN deferred) |
| `RenderEncoder.java` | Encoder for inside a render pass (setPipeline, setBuffer, setTexture, setSampler, setBytes, draw/drawIndexed/drawIndirect/drawIndexedIndirect, setViewport, setScissor, bindVertex/IndexBuffer) |
| `ComputeEncoder.java` | Encoder for compute pass (setPipeline, setBuffer, setTexture, setSampler, setBytes, dispatch, dispatchIndirect, barrier) |
| `RenderPassDesc.java` | Render pass description (color/depth attachments, load/store, clear values, viewport size). Has Builder. |
| `GraphicsPipelineDesc.java` | Graphics pipeline desc. Carries vertex/fragment GLSL (used by GL), MSL (Metal), SPIRV (Vulkan) — backends pick whichever they need. Plus `defines` map (forwarded to all three compile paths), color format, `VertexLayout`, `PipelineState`, label. Multiple constructors for backwards compat. |
| `ComputePipelineDesc.java` | Compute pipeline desc — same tri-source pattern (GLSL/MSL/SPIRV) + `defines` map, local thread-group size, label. |
| `IGpuPipeline.java` | Marker for a graphics or compute pipeline state object. AutoCloseable. |
| `IGpuIndirectCommandBuffer.java` | Cross-backend handle for a pre-baked indirect-draw command buffer (Metal MTLIndirectCommandBuffer / Vulkan secondary cmd buf). Consumed by `RenderEncoder.executeCommandsInBuffer(...)`. AutoCloseable. |
| `IGpuSampler.java` | Marker for a sampler state object. AutoCloseable. |
| `SamplerDesc.java` | Sampler config (filters, wrap modes, LOD clamps, comparison). Has Builder. |
| `VertexLayout.java` | Vertex inputs (attributes + buffer bindings). VertexFormat enum carries `metalValue` (raw MTLVertexFormat int). |
| `PipelineState.java` | Static state: DepthState, BlendState, RasterState. Presets: DEFAULT, OPAQUE_MESH, TRANSLUCENT_MESH. |
| `IGpuBuffer.java`, `IGpuTexture.java`, `IGpuFramebuffer.java`, `IGpuRenderBuffer.java`, `IGpuVertexArray.java`, `IGpuFence.java`, `IGpuPersistentBuffer.java`, `IGpuShader.java`, `IGpuResource.java` | Pre-existing resource interfaces |
| `shader/RuntimeShaderCompiler.java` | Runtime GLSL→SPIRV (LWJGL `Shaderc`) → MSL (LWJGL `Spvc`). Disk cache. Auto-injects `gl_VertexID`→`gl_VertexIndex` etc. |

### Metal backend (`src/main/java/me/cortex/voxy/client/core/metal/`)

| File | Purpose |
|---|---|
| `MetalRenderBackend.java` | Backend impl. Holds device, command queue, shared event, active command buffer. Implements all `RenderBackend` methods incl. `createGraphicsPipeline` / `createComputePipeline` / `createSampler`. Has `readPixelsRGBA8` for test smoke verification. |
| `MetalRenderEncoder.java` | Render encoder impl. Tracks bound index buffer for drawIndexed/drawIndexedIndirect. Implements all `RenderEncoder` methods. |
| `MetalComputeEncoder.java` | Compute encoder impl. Tracks bound pipeline for dispatch threadsPerThreadgroup. |
| `MetalGraphicsPipeline.java` | Wraps MTLRenderPipelineState + library + functions + MTLDepthStencilState + cull/winding/fill ints. |
| `MetalComputePipeline.java` | Wraps MTLComputePipelineState + library + function + local thread-group size. |
| `MetalSampler.java` | Wraps MTLSamplerState. |
| `MetalNative.java` | All JNI declarations (~100 methods now, including the ICB + IOSurface entry points). Plus Metal enum constants pinned to SDK header values. |
| `MetalHandleMap.java` | int-id ↔ long-handle bridge (legacy of pre-existing pattern). Has `setHandle(id, handle)` to update after lazy alloc. |
| `MetalBuffer.java`, `MetalTexture.java`, `MetalFramebuffer.java`, `MetalFence.java`, `MetalPersistentBuffer.java` | Pre-existing resource wrappers (with M3 fixes) |
| `MetalIndirectCommandBuffer.java` | `IGpuIndirectCommandBuffer` impl — wraps `MTLIndirectCommandBuffer`. Supports `setIndirectRenderCommand(slot, pipeline, ...)` and is consumed by `RenderEncoder.executeCommandsInBuffer`. Smoke-tested by `testMetalIcb`. |

### Cross-API interop (`src/main/java/me/cortex/voxy/client/core/interop/`)

| File | Purpose |
|---|---|
| `IOSurfaceBridge.java` | Wraps an `IOSurfaceRef` as both an `MTLTexture` (Metal-side render target via `MetalNative.mtlDeviceNewTextureWithIOSurface`) and — through `bindToGlTexture(glName)` → `CGLTexImageIOSurface2D` — a `GL_TEXTURE_RECTANGLE` texture in MC's GL context. `asGpuTexture()` exposes the Metal side as `IGpuTexture` so `RenderPassDesc.clearColor` accepts it. AutoCloseable; releases both surface and texture handles. |
| `IOSurfaceBridgeCompositor.java` | Composites a bridge's contents into the currently bound `GL_DRAW_FRAMEBUFFER`. Lazy-binds the IOSurface to a `GL_TEXTURE_RECTANGLE` once (via `bindToGlTexture`) and reuses a transient source FBO with that texture as `COLOR_ATTACHMENT0`. Caller responsibility: must invoke from a code path where MC's main RT is the active draw FBO — currently `MixinDefaultChunkRenderer.render` after `renderOpaque(viewport)`. M11 demo blits only the LEFT QUARTER of the bridge so Sodium chunk output remains visible side-by-side; flip back to full-width when MDIC migration replaces the clear stub. |

### OpenGL backend (`src/main/java/me/cortex/voxy/client/core/gl/`)

| File | Purpose |
|---|---|
| `GlRenderBackend.java` | Backend impl. Resource creation + framebuffer ops + `createGraphicsPipeline` / `createComputePipeline` / `createSampler` / `beginComputePass`. Owns the inline `GlRenderEncoder` (transient FBO + transient VAO + transient UBO for push constants). |
| `GlGraphicsPipeline.java` | Wraps a `Shader` compiled from raw GLSL via `Shader.Builder.addSource()`. Holds `VertexLayout` + `PipelineState` so the encoder can apply state on bind. |
| `GlComputePipeline.java` | Same pattern for compute. |
| `GlSampler.java` | Wraps `glGenSamplers` + parameter mapping (filter / wrap / LOD clamps / compare). |
| `GlComputeEncoder.java` | Compute encoder — SSBO bind, image bind, sampler bind, push UBO, dispatch[Indirect], `glMemoryBarrier` mask translation. |
| `GlPipelineStateApplier.java` | Re-issues depth/blend/raster GL state when encoder binds a pipeline. (Metal/Vulkan bake this into the PSO.) |
| `GlVertexFormatMap.java` | `VertexFormat` → (gl_type, count, normalized, isInteger) tuple table for `glVertexArrayAttrib*Format`. |
| `GlBuffer.java`, `GlTexture.java`, `GlFramebuffer.java`, `GlFence.java`, `GlPersistentMappedBuffer.java`, `GlVertexArray.java`, `GlRenderBuffer.java` | Pre-existing resource wrappers |
| `GLCompat.java` | Pre-existing GL helper layer (DSA fallback, framebuffer ops). |
| `Capabilities.java` | Pre-existing feature/extension detection. |
| `GlDebug.java` | Pre-existing `glObjectLabel` wrapper. |
| `shader/Shader.java`, `shader/ShaderType.java`, `shader/ShaderLoader.java`, `shader/AutoBindingShader.java` | Pre-existing GLSL compile + binding-by-reflection. M9 Phase 4 starts replacing these call sites with the abstraction. |

### Vulkan backend (`src/main/java/me/cortex/voxy/client/core/vulkan/`)

| File | Purpose |
|---|---|
| `VulkanLoader.java` | Bootstrap — extracts MoltenVK from jar OR reads from java.library.path / /opt/homebrew, calls `VK.create()`, swallows "already created" exception |

**Note: no `VulkanRenderBackend` class yet.** Vulkan path is exercised via standalone smoke tests (`tools/Vulkan*SmokeTest.java`). Wrapping into `RenderBackend` happens after M9 prep is fully done.

### Native (`native/metal/src/`)

| File | Purpose |
|---|---|
| `voxy_metal.h` | Shared header — type-id ranges, helper macros |
| `voxy_metal_jni.mm` | Generic retain/release/setLabel/getLastCompileError |
| `voxy_metal_device.mm` | MTLDevice creation, command queue, device props |
| `voxy_metal_buffer.mm` | MTLBuffer creation, contents, didModifyRange |
| `voxy_metal_texture.mm` | MTLTexture creation, view, replaceRegion + render pass descriptor + color/depth/stencil attachments + setColorClearColor (M3) |
| `voxy_metal_render.mm` | Render encoder + pipeline state object + vertex descriptor + sampler + blend + depth-stencil + indirect draw |
| `voxy_metal_compute.mm` | Compute encoder + sampler bind + setBytes + dispatch + dispatchIndirect + memoryBarrier |
| `voxy_metal_memutil.mm` | memset helpers |
| `voxy_metal_icb.mm` | `MTLIndirectCommandBuffer` allocation + per-slot indirect-render-command setters + `executeCommandsInBuffer` JNI (Blocker 1) |
| `voxy_metal_iosurface.mm` | IOSurface allocation + wrap-as-MTLTexture via `[device newTextureWithDescriptor:iosurface:plane:]` (M10) |
| `voxy_metal_iosurface_gl.mm` | Links the OpenGL framework + exposes `cglGetCurrentContext` and `cglTexImageIOSurface2D` so the IOSurface can be bound to a `GL_TEXTURE_RECTANGLE` from MC's GL context (M10) |
| `CMakeLists.txt` | Lists all sources; output to `src/main/resources/natives/macos-arm64/libvoxy_metal.dylib` |
| `build.sh` | Convenience build script (CMake Release) |

### Shaders (`src/main/resources/assets/voxy/shaders/tools/`)

| File | Purpose |
|---|---|
| `triangle.vert` | M5 — gl_VertexIndex-driven, hardcoded positions |
| `triangle.frag` | M5/M6 — passthrough vertex color |
| `triangle_vbuf.vert` | M9-prep — explicit `in vec2 inPos; in vec3 inColor;` |
| `increment.comp` | M7/M8 — writes `i*2+1` to SSBO |

### Smoke tests (`src/main/java/me/cortex/voxy/tools/`)

All have a `main()` and a corresponding `./gradlew test*` task in `build.gradle`.

| File | Validates |
|---|---|
| `ShaderCompilerSmokeTest.java` | M1 `RuntimeShaderCompiler` against representative shaders |
| `MetalRenderBackendSmokeTest.java` | M3 — clear-color render pass |
| `MetalTriangleSmokeTest.java` | M5 — graphics pipeline + draw |
| `MetalComputeSmokeTest.java` | M7 — compute pipeline + dispatch + SSBO readback |
| `MetalVertexBufferSmokeTest.java` | M9-prep — VertexLayout + bindVertexBuffer + drawIndirect |
| `MetalIcbSmokeTest.java` | Blocker 1 — MTLIndirectCommandBuffer + executeCommandsInBuffer (CPU-baked 2-slot indexed draw) |
| `IOSurfaceBridgeSmokeTest.java` | M10 — IOSurface allocation + MTLTexture wrap (256x256 BGRA8) |
| `VulkanLoaderSmokeTest.java` | M4-A — MoltenVK + VkInstance + physical device |
| `VulkanClearSmokeTest.java` | M4-D — full clear-color render pass |
| `VulkanTriangleSmokeTest.java` | M6 — graphics pipeline + draw |
| `VulkanComputeSmokeTest.java` | M8 — compute pipeline + descriptor sets |

---

## Build / verification commands

### Standard

```bash
./gradlew compileJava                  # Compile only
./gradlew build                        # Compile + jar (~5 min cold)
./gradlew runClient                    # Launch sandbox MC + Voxy. Will boot, log "Metal backend selected", crash on first chunk render (M9 not done).
```

### Smoke tests (each ~5-10s after first build)

See "Smoke test inventory" section above.

### Native rebuild

```bash
native/metal/build.sh Release          # Rebuild libvoxy_metal.dylib
nm -gU src/main/resources/natives/macos-arm64/libvoxy_metal.dylib | grep mtl | wc -l   # Should print ~85
```

---

## Environment

| Requirement | Where |
|---|---|
| macOS Apple Silicon (M1+) | required for Metal+Vulkan |
| JDK 21+ (Java 24 also works) | `java -version` |
| Xcode CLT | `xcode-select -p` |
| CMake 3.20+ | `cmake --version` |
| Homebrew | for tooling installs |
| `glslang`, `spirv-cross` | `brew install glslang spirv-cross` (used for dev shader inspection; runtime uses LWJGL natives) |
| `molten-vk`, `vulkan-loader`, `vulkan-headers`, `vulkan-tools` | `brew install ...` |
| LWJGL natives (auto-resolved by Gradle) | `lwjgl-shaderc:natives-macos-arm64`, `lwjgl-spvc:natives-macos-arm64`, `lwjgl-vulkan:natives-macos-arm64`, `lwjgl:natives-macos-arm64` |
| `VK_ICD_FILENAMES` env (dev only) | Gradle JavaExec sets it for testVulkan* tasks if `/opt/homebrew/etc/vulkan/icd.d/MoltenVK_icd.json` exists |

---

## Untracked / build-artifact files

These are intentionally not in git and will appear in `git status`:

- `src/main/resources/natives/macos-arm64/libvoxy_metal.dylib` — auto-rebuilt by the `buildMetalNative` Gradle task on macOS aarch64 hosts
- `src/main/resources/natives/macos-arm64/libMoltenVK.dylib` — copied manually from `/opt/homebrew/Cellar/molten-vk/1.4.1/lib/`. Should eventually be replaced with a `downloadMoltenVK` Gradle task that fetches the official KhronosGroup release.
- `.DS_Store`

---


## Next-session playbook

1. Read `docs/STATUS.md` end-to-end — it has the TL;DR with all gated
   chunks + their re-enable recipes. Then skim this doc's "Working
   tree (uncommitted, 2026-05-13)" section for the precise file
   inventory of what's staged.
2. Sanity-check the baseline:
   ```bash
   ./gradlew --continue testShaderCompiler testMetalRender testMetalTriangle \
       testMetalCompute testMetalVertexBuffer testMetalIcb testIOSurfaceBridge
   ```
   Expected: 31/31 SPV, 30/31 MSL (`hiz.comp` deferred — that's the
   baseline, not a regression). All 6 Metal smoke tests `SMOKE OK`.
   Then runtime-verify `VOXY_FORCE_METAL=1 ./gradlew runClient`
   gets you LOD chunks with hash colours + real lightmap + fog,
   no crash.
3. Recommended order to clear the M13 backlog:
   - **(a) Re-enable chunk 3 HiZ (~2-3 hours, low risk).** In
     `HiZBuffer.java`, change the parent texture's format on Metal
     from `GL_DEPTH24_STENCIL8` to `GL_DEPTH_COMPONENT32F`. The
     simplest patch is at construction — pass `GL_DEPTH_COMPONENT32F`
     to `new HiZBuffer(type)` on the Metal path (Viewport currently
     uses the default constructor → D24S8). Then re-enable
     `runPipelineMetal`'s `buildMipChain(mcDepth, ...)` call. If the
     horizon-vanishing still reproduces, add a `voxyDepthForHiZ`
     preprocessing pass that mirrors `initDepthStencil`'s
     stencil-mask dance.
   - **(b) Re-enable chunk 1 bakery (~3-5 hours, higher risk).**
     Replace `backend.submit()` calls inside
     `MetalViewCapture.{clear, endBake}` with an async submit +
     `MTLEvent`-gated read. The hypothesis is that
     `mtlCommandBufferWaitUntilCompleted` is what perturbs Sodium's
     persistent-mapped buffer. If async submit doesn't help, move
     the bakery onto a dedicated `MTLCommandQueue` separate from
     the main Voxy queue. Last resort: switch the bake target to an
     IOSurface-backed Metal texture and read it via
     `IOSurfaceGetBaseAddress` (no `getBytes` → no driver-side
     readback synchronisation).
   - **(c) Implement chunk 3b (~1 day).** Replace
     `force_all_visible.comp` dispatch with the real
     `cull/raster.vert+.frag` pass — needs MC's depth as a Metal
     depth attachment (currently `DepthMirror` is ShaderRead-only).
     Easiest path: add a `storeRenderTargetMirror` variant to
     `MetalTexture` (Shared + RenderTarget + ShaderRead) and let
     `DepthMirror` allocate via it.
   - **(d) Implement chunk 4 (~2-3 hours, depends on 3).** SSAO
     compute migrates straightforwardly to `beginComputePass`. The
     depth-aware finish blit is a new render pass that samples
     Voxy color + MC depth (via `DepthMirror`) + fog params and
     writes to the bridge with depth-test against MC's foreground.
4. After each chunk lands runtime-clean, commit it as its own
   focused commit with a descriptive title that names the chunk
   (matches the existing commit-table format). Update the "Working
   tree" section of this doc to drop the deliverable from the
   uncommitted list.
5. Each commit should be small, focused, and gated by a smoke test
   or regression check. Smoke tests + in-game runtime check on
   `VOXY_FORCE_METAL=1` are the two acceptance gates for M-series
   work.

---

## Quick API reference for new agent

### Creating a graphics pipeline

```java
RenderBackend backend = RenderBackendFactory.get();

// Compile shaders
RuntimeShaderCompiler.Result vert = RuntimeShaderCompiler.compile(
    glslSource, RuntimeShaderCompiler.Stage.VERTEX, defines,
    RuntimeShaderCompiler.Target.METAL_MSL);
RuntimeShaderCompiler.Result frag = RuntimeShaderCompiler.compile(...);

// Optional: declare vertex inputs
VertexLayout layout = VertexLayout.builder()
    .buffer(0, /*stride*/ 20, VertexLayout.StepRate.PER_VERTEX)
    .attribute(0, VertexLayout.VertexFormat.FLOAT2, /*offset*/ 0, /*bufSlot*/ 0)
    .attribute(1, VertexLayout.VertexFormat.FLOAT3, 8, 0)
    .build();

IGpuPipeline pipeline = backend.createGraphicsPipeline(new GraphicsPipelineDesc(
    vert.mslSource(), frag.mslSource(),
    vert.spirv(), frag.spirv(),
    /*GL color format*/ 0x8058 /*GL_RGBA8*/,
    layout,
    PipelineState.OPAQUE_MESH,
    "voxy:my-shader"));
```

### Render pass

```java
IGpuTexture target = backend.createTexture(0x0DE1 /*GL_TEXTURE_2D*/);
target.store(0x8058 /*GL_RGBA8*/, 1, 256, 256);

RenderPassDesc pass = RenderPassDesc.builder(256, 256)
    .clearColor(target, 0.1f, 0.1f, 0.15f, 1.0f)
    .build();

try (RenderEncoder enc = backend.beginRenderPass(pass)) {
    enc.setPipeline(pipeline);
    enc.setViewport(0, 0, 256, 256, 0, 1);
    enc.setBuffer(/*binding*/ 0, ssbo, /*offset*/ 0);
    enc.bindVertexBuffer(0, vbo, 0);
    enc.bindIndexBuffer(ibo, RenderEncoder.INDEX_TYPE_UINT32, 0);
    enc.drawIndexed(RenderEncoder.PRIMITIVE_TRIANGLES, indexCount, 1, 0, 0, 0);
}
backend.submit();
```

### Compute pass

```java
IGpuPipeline computePipeline = backend.createComputePipeline(new ComputePipelineDesc(
    msl, spirv, /*localSize*/ 64, 1, 1, "voxy:my-compute"));

try (ComputeEncoder enc = backend.beginComputePass()) {
    enc.setPipeline(computePipeline);
    enc.setBuffer(0, ssbo, 0);
    enc.setTexture(1, storageImage);
    enc.setSampler(2, sampler);
    enc.setBytes(3, /*addr*/ stack.ints(42).address(), 4);
    enc.dispatch(/*groups*/ 1, 1, 1);
    enc.barrier(ComputeEncoder.BARRIER_SHADER, ComputeEncoder.BARRIER_SHADER);
    enc.dispatch(...);
}
backend.submit();
```

### Reading pixels back (Metal-only, smoke-test-grade)

```java
byte[] rgba = ((MetalRenderBackend) backend).readPixelsRGBA8(texture, 0, 0, 256, 256);
// rgba is 256*256*4 bytes, RGBA8 little-endian
```
