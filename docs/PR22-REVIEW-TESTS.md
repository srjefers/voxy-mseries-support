# PR #22 teleport and snow/ice investigation

## Scope

Review discussion: https://github.com/srjefers/voxy-mseries-support/pull/22#issuecomment-5976339562

The reviewer reports that previously generated LODs change appearance after
walking to generate terrain and then teleporting nearby. The report applies with
and without `-Dvoxy.bslCompatibility=true`. The supplied screenshots show snow,
ice, and spyglass magnification. Their displayed seed is
`-4124916493478848877`, and one displayed destination is
`-1335.5, 165, -1788.5`. The original position, facing, settings, shader pack,
and exact teleport sequence are not established by those images.

This investigation used commands and numeric CPU/GL/Metal assertions. Minecraft
was not launched and the seed was not generated. The command-based tests do not reproduce the exact gameplay
sequence; subsequent user testing reproduces the visible ice artifact. The follow-up
repair below addresses controlled failures; gameplay acceptance remains pending.
No PR update is included.

## Revision comparison

The public PR head was `fed768e7701511bc0e118d2c0fc95f302dba102a`.
The locally merged architecture revision was
`b10ecdbc505d107d0729253b532f9e3bc8802881`.

| Check | PR head | Local architecture revision |
| --- | --- | --- |
| Existing aggregate CPU and native verification | 30 tasks passed | 38 tasks passed |
| Camera-jump traversal fixture | Passed | Passed |
| Camera-jump fixture with explicit BSL compatibility property | Passed | Passed |
| Projection including 1 and 7 degree FOV, resize and camera transforms | 20 cases passed | 20 cases passed |
| Partial-height bake inset | Failed for 1/8 and 1/2 height | Same failures |
| Overlapping bake planes in reverse submission order | Failed | Same failure |

Aggregate verification included the supplied Complementary Unbound r5.9.3
programs, not an unidentified pack from the screenshots. BSL-flag traversal
checks do not establish correctness of an actual BSL shader pack. The retained
ARM64 native library was used; the build reports unavailable CMake and does not
rebuild native source.

## Reproduced bakery defects

`MetalBakeDepthRegressionTest` supplies known horizontal planes to the real
Metal bake renderer, using the production UP-face view matrix and the production
Metal projection. The resulting pixels are consumed through
`TextureUtils.computeDepth`, as production classification consumes them.

- Height 0.125: expected inset 0.875, observed 0.0.
- Height 0.5: expected inset 0.5, observed 0.0.
- Height 1.0: expected and observed inset 0.0 (control passes).
- Two overlapping planes: the nearer white plane should remain visible. When
  the lower dark plane is submitted last, its color wins instead. Reversing
  submission order gives the correct white result.

Both cases also validate that the fixture actually paints covered pixels. These
are failures of the production bakery, not compilation or context setup failures.

`MetalViewCapture.emitToStream` supplies coverage/tint but no depth bits to the
existing packed metadata. `MetalBudgetBufferRenderer` disables depth testing
and culling. Consequently, non-full-block alignment is lost and overlapping
quads are sensitive to submission order. The existing fluid-height workaround
covers water, not every partial-height block.

These defects plausibly affect snow layers and other partial models. They do
not prove that teleporting corrupts caches, explain every translucent-ice
artifact, or establish the complete cause of the screenshots. Normal coarse
LOD approximation must be separated from erroneous model reconstruction.

## What the passing tests establish

The added camera-jump fixture reuses GPU node tables over 960 frames and 1,120
real Metal dispatches. It alternates visible and culled camera positions, coarse
and fine selection, and available/unavailable descendants. It checks retained
mesh and position identity, correct child selection, parent fallback, request
counts, and current visibility stamps. Cases include negative and large signed
world coordinates. Camera inputs are controlled scenes, not world generation.

The existing lifecycle tests exercise worker-failure propagation and termination,
constructor rollback, publication ordering, cleanup faults, and resource
replacement. They are not a long-running gameplay memory or process profile;
no claim of zero leaks or improved performance follows from them.

## Reproduction commands

Inside this isolated test checkout:

```sh
export JAVA_HOME=/opt/homebrew/opt/openjdk@21
./gradlew --offline testMetalTraversal testProjection --console=plain
JAVA_TOOL_OPTIONS=-Dvoxy.bslCompatibility=true \
  ./gradlew --offline testMetalTraversal testProjection --console=plain
./gradlew --offline testMetalBakeDepth --console=plain
```

The last command failed against the preceding implementation. It now passes
and is included in `verifyMetal`, together with `testIceOpacity`. Test code stays in the test
source set. Existing native and full-pack checks can be run with:

```sh
./gradlew --offline verifyCpu verifyMetal \
  -PcomplementaryPack=/path/to/ComplementaryUnbound_r5.9.3.zip --continue --console=plain
```

## Follow-up gameplay evidence

The tester reproduced the visible ice artifact in the duplicated instance with
`architecture-fixes-20261001-candidate2`. The supplied probe contains 51 matched
samples from 15:28:03 through 15:28:29, ending by an explicit stop command.

- Main-view and viewport frame IDs agree in all samples; coverage generation
  remains 6.
- Selected LOD lists are nonempty in all samples. Translucent draw-command counts
  range from 224 to 1,986. This does not prove that every affected ice face exists.
- The center coverage-bound value is zero in 41 of 51 samples. The boundary mask
  alone therefore cannot account for all of the observed scene; this is not a
  per-pixel identification of ice.
- The same center RGB survives the reported Sodium stages through world output.
  There are no material-program stages or per-ice alpha/ID readbacks in this
  capture. Final opaque framebuffer alpha and main-target depth of 1 are not
  independently evidence of incorrect ice alpha or missing LOD geometry.

The screenshot shows the irregular ice surface being investigated. Runtime
confirmation narrows the target, but does not establish a unique cause.

An additional source-level suspect is the shaders-off `VOXY_WATER_FAR_ALPHA`
branch in `quads.frag`: it applies to every translucent fragment without checking
whether the model is fluid. The draw pipeline includes ice as translucent terrain,
so a water-specific opacity policy can also alter ice. Its contribution to this
artifact requires controlled pixel tests, including ice/glass versus water at the
same distances. Translucent bake layering, front/back visibility, premultiplied
blending, and draw ordering also need explicit ice fixtures. Do not treat the
already-proven opaque bakery plane tests as sufficient translucent acceptance.

## Next correction and acceptance

1. Keep the failing plane-depth and submission-order tests as the regression
   criteria before implementing changes.
2. Adapt the bakery to capture actual nearest-visible geometry depth and encode
   it into the existing metadata contract, including inversion of the Metal
   projection's compressed Z mapping. Preserve genuine coverage and tint bits.
3. Reconcile face culling, opaque depth selection, and translucent layering with
   the GL bakery's semantics. Do not replace them with opaque fills or assume
   last-submitted surfaces are visible. Test all six views, empty faces, partial
   heights, two-sided plants, ice/glass, and water.
4. Verify existing cutout/tint/mip, terrain, water and Complementary regressions.
   Keep saved-world formats and caches unchanged.
5. Test model mip reconstruction and ordinary/spyglass selection separately from
   teleport lifecycle. If the gameplay defect remains, capture matched data,
   mesh publication, LOD selection, model depth, and bridge pixels around the
   exact teleport sequence in a disposable world.
6. Run representative reload/join/teleport memory and thread measurements before
   claiming long-running resource correctness.

No render-distance changes, arbitrary waits, forced colors, or cache deletion
are part of this repair.


## Ice bakery repair candidate

The added regressions were run against the preceding production implementation
before making the repair. The original partial-height and opaque visibility
checks failed as described above. Additional ice-like planes reproduced a
front-surface alpha/color failure: a later back surface replaced `0x80ffffff`
with `0x40202020`. A numeric Metal fixture of the production far-water block
also raised non-fluid alpha from 0.5 to 0.95; the fluid control correctly
received the existing water ramp. Expanded six-face depth fixtures failed
against the preceding implementation before the repair was reapplied.

The repair:

- Adds a depth attachment to the Metal bakery and selects the nearest painted
  surface. Culling remains disabled for the existing two-sided vegetation
  approximation. Translucent albedo and alpha are captured from that surface,
  without blending its opposite surface into the atlas.
- Encodes actual face depth in the existing 24-bit-depth/8-bit-flags word.
  Coverage and tint remain independent of color alpha, and empty faces remain
  zero. The Metal compressed projection is converted to the legacy depth
  convention before classification.
- Removes the synthetic fluid-height workaround because the bakery now
  captures real fluid geometry depth.
- Adds an unused runtime model flag and varying bit for fluid identity, so
  water-only opacity/shading controls no longer affect ice or glass. Saved
  section and geometry formats are unchanged; no cache rebuild is requested.

Validation includes all six production face matrices at depths 1/8, 1/2,
8/9 and 1, empty faces, overlapping opaque and ice-like planes in both
submission orders, existing plant/tint/four-mip checks, and the production
attribute producer feeding the far-opacity block. The deliberately changed
terrain shader source fingerprint was reviewed and updated; target, blend,
raster and camera policies are unchanged.

The full CPU and numeric GL/Metal suites include actual Complementary Unbound
r5.9.3 programs, storage/database reopening, traversal camera jumps, water
depth ownership, GL-state restoration, and resource ownership tests. They do
not prove that the user's frozen-ocean scene is repaired. Recheck that scene
with shaders off and with Complementary, ordinary FOV and spyglass, then
travel away and return/teleport after generating LODs. Keep the preview label
until these gameplay checks pass. No performance improvement is claimed.


## Shaders-off surface ownership follow-up

Gameplay feedback for `ice-bakery1` reports unchanged ice artifacts with shaders
off, and correct ice with Complementary. The runtime log confirms that candidate
was loaded, so this is not an old-JAR comparison. The first repair did not
establish shaders-off gameplay acceptance.

A new numeric fixture, `IceSurfaceOwnershipRegressionTest`, executes the actual
production translucent fragment and its production state for shaders-off and
contract modes. It supplies controlled surface depths, transparent and ice-like
albedo, fully lit inputs, zero Sodium coverage, and a water-opacity ramp. The
vertex fixture supplies known screen positions; it does not generate Minecraft
ice meshes or recreate the complete scene. Six face IDs and four sequences
exercise ice alone, farther water submitted after ice, water before ice, and
transparent pixels followed by water. Frame attachments are cleared each time.

Before correction, the 24 shaders-off cases failed surface-depth ownership;
the six farther-water-after-ice cases also replaced the visible ice color. All
24 contract controls passed. Shaders-off disabled translucent depth writes,
allowing later farther surfaces inside a section to blend over nearer ice.
Contract rendering already wrote depth and rejected those surfaces.

The follow-up changes the Metal translucent pipeline to retain nearest surface
depth in shaders-off mode. Existing blending and section command ordering are
retained. The contract, opaque, OpenGL, camera, bakery, coverage, and lighting
policies are unchanged. Alpha-zero texels still discard before writing depth.
The shader-configuration fingerprint was updated only for this reviewed state
change; it is not a replacement for numeric pixel checks.

After correction, all 48 fixture cases pass. Existing plant/tint and water-depth
checks also pass. Run `./gradlew testIceSurfaceOwnership` for the focused check;
it is included in `verifyMetal`. Validate the new candidate in the frozen ocean
with shaders off, normal FOV and spyglass, and check water/glass, underwater
views, moving boundaries and reloads. Section-level translucent ordering remains
a LOD approximation; this is not an order-independent transparency renderer.

## Follow-up: one shaders-off translucent blend

Gameplay feedback confirmed that retaining nearest depth reduced the ice artifact
but did not eliminate it. A stricter regression was written and run before this
follow-up: ice over farther water must produce the same pixel whether the water
is submitted before or after the ice. Against `ice-depth2`, all six shaders-off
water-first cases failed this independent color expectation. Empty texels,
opaque occluders, and contract controls passed. The earlier fixture checked
water-first depth but did not require its color to equal the ice-only reference.

The cause reproduced in the fixture is retained destination color: depth rejects
farther surfaces submitted later, but cannot undo a farther surface that has
already been alpha-blended. The shaders-off Metal path now captures the nearest
translucent RGBA surface without blending, then blends that layer once over the
opaque bridge. It shares the opaque depth attachment, so hidden translucent
surfaces remain rejected. The layer is cleared on each frame, including zero-work
or submerged frames. Generic Complementary, BSL and OpenGL rendering retain their
existing paths. This remains the accepted nearest-surface LOD approximation;
it does not reproduce every layer of Minecraft's translucent geometry.

The extra texture stays entirely on Metal; it does not allocate another
IOSurface or introduce a CPU readback or submission wait. It adds one RGBA8
texture (four bytes per framebuffer pixel) and a fullscreen GPU blend. No
performance improvement is claimed. Existing `ResourceScope` ownership handles
constructor rollback, failed resize, replacement and shutdown. Failure-injection
tests for shaders-off resources were established before changing acquisition.

After the fix, all 72 numeric surface cases pass. Additional readbacks through
an actual BGRA8 IOSurface verify channel order, row coordinates, transparent and
opaque pixels, destination alpha, and changing dimensions. The shader-state
fingerprint changed only for the reviewed shaders-off capture blend state.
These tests use production fragment/state and resolve code, with controlled
screen-space geometry; they do not establish gameplay appearance or cache-wide
mesh correctness. The candidate still needs frozen-ocean validation with shaders
off, both normal FOV and spyglass, and water/glass, underwater views, changing
boundaries, resize and reload.

## Lighting seam investigation

`MetalMaterialInputsRegressionTest` uses the actual production vertex and
fragment shaders, encoded cube quads and production index ordering. Numeric
readbacks cover all six exterior normals, all 256 block/sky light combinations,
and tinted/untinted faces: 3,072 material input pixels. The expected face IDs and
original independent light nibbles survive intact. A separate actual GL resolve
fixture verifies all 256 combinations decode to Minecraft lightmap texel centers.
Neither test reproduced a channel swap, tint corruption or face-normal inversion.
They do not compare lighting on real Sodium geometry, partial models or actual
Minecraft biome providers.

Inspection of Complementary Unbound r5.9.3 found explicit contract differences:
its Voxy opaque shader uses reprojected screen-space shadow information instead
of the regular terrain shadow-sampling path; the LOD mesh also does not provide
Sodium's per-vertex ambient occlusion. The pack disables generated normals in its
Voxy translucent path. These are possible contributors to the ice/snow seam,
not a demonstrated explanation of the supplied screenshots. No brightness floor,
hue multiplier, forced tint, enlarged coverage or distance blend is added here.
The existing matched frame probe can report material face/light/tint, resolved
color and final output at the same frame. Boundary samples with Complementary
are needed before changing its accepted lighting path.

Focused commands: `./gradlew testIceSurfaceOwnership testFrameTargets
 testMetalMaterialInputs testMaterialContract testTerrainConfiguration`.
The pack-dependent task requires `-PcomplementaryPack=<shaderpack.zip>`.
All are also part of `verifyCpu verifyMetal`; these are command-only numeric
checks using an invisible driver context, without launching Minecraft.

## Follow-up: shaders-off lightmap parity

A further test was added and run against `ice-layer3` before changing its
brightness policy. The actual production vertex/fragment pipeline renders six
unoccluded cube faces with known white, intermediate, dim and black lightmaps,
using Minecraft's directional shade values. The expected RGB is independently
computed from lightmap color and face shade, with only the existing RGBA8
quantization tolerance. The test found 53 channel mismatches: the historical
0.92 multiplier dimmed even uniformly lit, unoccluded faces. All 3,072 material
contract input checks continued to pass.

The automatic shaders-off opaque darkening is removed. Default and invalid
`VOXY_LOD_BRIGHTNESS` values now preserve the original lightmap; the existing
explicit diagnostic override remains available. The shader's obsolete claim
that blanket darkening reproduces local ambient occlusion was removed. No new
brightness or hue compensation is introduced. Directional shade, lightmap
sampling, fog, pack lighting and the nearest-surface resolve stay intact.

After correction, the 24 flat-face lightmap fixtures pass, including dim and
black inputs. The source/configuration fingerprint is updated for this reviewed
change. This targets a proven shaders-off boundary mismatch; it does not claim
that near terrain and LOD geometry have identical local ambient occlusion,
shadows, reflections or detail, nor that it fixes the remaining Complementary
lighting seam. Compare the ice/snow boundary in both modes before acceptance.
The next candidate includes the prior ice-layer repair and these lighting
changes, under a distinct `ice-light4` build label.

## Ice background and intermediate-light parity (`ice-transition5`)

The next transition probe was recorded with shaders off. Its early samples show
only Voxy's composited ice; the later samples show Sodium's translucent ice
blending over the underlying bridge. The screenshot was taken with Complementary
enabled earlier in the session. These are separate observations, and neither
alone establishes the cause of the pack's visible seam.

Two command-based regressions identified concrete shaders-off defects:

- The nearest-only repair loses water beneath ice. A new independent ordered
  alpha-blend fixture failed with **84 channel mismatches** against `ice-light4`.
  Ice is not opaque: the vanilla texture has alpha 190/255 throughout. Ignoring
  the water underneath changes its final appearance even when its own texture
  and lighting are correct.
- Lightmap coordinates used `n/15` with endpoint clamping instead of Sodium's
  compressed-vertex `(n*16+8)/256` centers. A real, linearly sampled 16x16 gradient
  failed with **352 channel mismatches** before the correction. Constant-color
  fixtures could not expose this. The installed Sodium vertex encoder and its
  vertex shader confirm the expected coordinates. This correction does not
  affect fully lit endpoints, and is not evidence that it caused every seam.

Shaders-off Metal now captures the nearest non-fluid and fluid surface separately,
with each depth-tested against the same opaque scene. Their per-pixel depths
select back-to-front blending over the opaque bridge. Each category contributes
once, regardless of draw submission order; farther ice back faces cannot
accumulate into another opacity layer. The implementation keeps one nearest
surface per category, not arbitrary multi-layer transparency. Complementary's
material rendering, its lighting, and its resolve schedule are unchanged.

The new `testIceBackground` passes **120** real Metal pixel fixtures across all
six face IDs, both depth orders and submission orders, absent layers, nearer
opaque occlusion, farther back faces, and changing draw counts. A follow-up depth
assertion caught 96 unpublished nearest-depth cases before the resolve was
changed to publish the visible layer's depth; empty layers preserve opaque depth.
All 256 block/sky
pairs now pass the lightmap gradient fixture. Existing ice surface, actual cube
material-input, IOSurface channel/alpha, GL-state, actual Complementary-program,
reload/resize and resource failure checks also pass. The existing target fault
injection automatically exercises all **12** shaders-off acquisitions and **13**
contract acquisitions. No game or visible computer-use test was run.

The layer resources belong to `MetalPrelitLayers` and roll back as a complete
set. The normal path adds a GPU depth copy, two depth seeding passes, and a second
translucent draw slice; it adds no CPU readback or synchronization wait. Its extra
full-size textures and buffer have a memory/submission cost. No performance
improvement or gameplay acceptance is claimed.

An active `/voxy debug probe view` also reports `[Metal-Prelit-Probe]` for the
matched frame, before composition: opaque background RGB, each layer's RGBA and
depth. It uses the existing twice-per-second, 60-second stage gate; readback
buffers are lazy, replaced transactionally on resize, and freed with the
renderer. Outside selected probe frames there are no diagnostic copies or waits.
The terrain source fingerprint was updated for the reviewed layer capture guard
and Metal-only lightmap coordinates; OpenGL sampling and pack material light
nibbles are preserved.

This candidate requires a repeated ice boundary check with shaders off. A
remaining Complementary seam needs a separate shaders-enabled matched probe;
the shaders-off log cannot identify a difference in pack reflections or shadows.
No saved caches or world formats are changed.
