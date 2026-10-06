# Minecraft 1.21.11 Metal stabilization and upstream adaptation

## Scope

This change keeps the existing Apple-Silicon Metal renderer, Minecraft 1.21.11,
Sodium 0.8.11 and Iris 1.10.7. It adapts compatible subsystems from official Voxy
0.2.16 at `59b62bee821518e06612e1d5c2c58c487bda761d` instead of replacing Metal with
upstream's OpenGL rendering contracts. Version remains `0.2.16-metal.preview`.

All implementation/adaptation changes in this PR were created using AI (Codex),
with human input guiding the work and repeated human gameplay tests providing
screenshots, probe logs and acceptance feedback. Existing Voxy/Metal-port authors
retain their original credit. The tester confirms Complementary works well,
terrain/LOD stitching works, shader water is stable, and chunk holes are gone
in the tested setup.

The [ledger](UPSTREAM-0.2.16-LEDGER.md) accounts for all 174 upstream-changed files:
43 imported exactly, 34 adapted, two already present, and 95 deferred. The JSON
companion records the source snapshot and current publication hashes. Deferred
files can still contain local Metal repairs; the status describes upstream import
scope, not whether a file has any local changes.

## Rendering corrections

- Use Minecraft's public framebuffer APIs, explicit rectangle/pack sampler binding,
  and scoped restoration of all touched GL state on successful and failed interop.
- Write Metal-reachable matrix/vector uniforms explicitly through native memory;
  avoid JOML direct-address initialization failures without a global JVM workaround.
- Preserve Minecraft's additional camera transforms when retargeting projection.
  Advance current/previous transforms once per main-view frame, before Iris updates;
  repeated getters and shadow reentry cannot mutate history. Reset on generation/
  viewport/world changes and reuse one uniform snapshot across material stages.
- Track uploaded section lifecycle separately from drawable prepared Sodium commands.
  Current-frame, current-generation pass membership drives suppression; duplicate,
  disposed, empty, obsolete and block-entity-only sections cannot retain ownership.
  Culled leading faces and unsigned allocation ranges are handled explicitly. Keep
  available parents while descendants are unavailable and permit traversal through
  empty parents to nonempty descendants.
- Capture actual bakery coverage and quad tint separately from color alpha. Preserve
  transparent backgrounds with RGB-only dilation, derive metadata from coverage,
  and upload captured face tint weights instead of grayscale guessing. Retain the
  six-face layout and distant-plant approximation; animated water preserves alpha.
- Use the full Complementary contract-1 material path consistently for a renderer
  generation, preserving targets [0,6]/[0], blending, TAA, live texture suppliers,
  previous reflection history, and `excludeLodsFromVanillaDepth`. Convert both current
  depth layers before either material program, reject stale bridge/frame resources,
  and keep the intended pre-deferred resolve timing. Never clear shared target 0
  merely to remove stale water. BSL-only overrides remain in explicit compatibility.
- Initialize fragment XY/W before pack-global initializers run. Complementary's
  global `texelCoord` previously captured an uninitialized value, so changing only
  an unrelated corner seafloor depth changed center water opacity from .893 to .254.
  The actual-pack regression now proves corner independence and both depth conventions.

The final water correction is in `MetalMaterialShader`: builtin pixel coordinates
are valid during pack-global initialization; decoded LOD depth is supplied before
calling the pack's fragment callback. It adds no depth fetch, lighting constant,
alpha floor, forced history side, timing delay, or disabled synchronization.

## Shared subsystem changes

Compatible upstream work includes bounds/face occlusion, block-light mip packing,
emission metadata, request limits/reuse, smaller geometry granules, bounded uploads,
worker-failure propagation, save/unload synchronization, native iterator ownership,
mapper publication, shutdown/session lifecycle, ingestion position/layout fixes,
configuration ownership, renderer reloads for builder-thread changes, and
`/voxy import current`. RocksDB 10.9.1 reopens existing 10.2.1 data on ARM64.

Existing world identifiers, storage formats, custom configuration and caches remain.
Corrected lighting applies to newly generated mip data; caches are not deleted or
silently rebuilt. ARM64 RocksDB and LWJGL classes/natives remain packaged; Loom
1.16.1 and Gradle 9.4.1 support the Sodium 0.8.11 build.

## Structure and diagnostics

Metal frame/resource orchestration is separated from the shared renderer pipeline;
coverage, frame history, material policy/uniforms, tint data and GL restoration have
explicit owners. Retired unused save/queue implementations are removed. Generic
material assembly is separate from BSL compatibility policy. No newly added
production Java file exceeds 1,000 lines; remaining large legacy mesher/model/MDIC
files still need independently verified extractions.

Coordinate/view probes correlate lifecycle, draw membership, readiness, bridge
color/depth and matched material/Sodium/Iris/final-output stages. They are opt-in,
limited to two samples per second for 60 seconds, report completion/sample counts,
and support status/off. Diagnostic GPU readbacks are disabled outside active probes.

## Verification and gameplay feedback

New automated regressions were written/run before implementing the behavior they
cover; extraction fixtures first established preceding behavior. Failed-before
examples include inherited samplers, failed bridge/state paths, history mutation,
cutout/tint errors, and wrong global coordinates/unrelated-depth water opacity.
The last clean publication build runs all 30 tasks below successfully:

```
testAllocation
testAsync
testBslAssembly
testBslSourcePatchScope
testClientFeatures
testClientSession
testCompression
testCoverage
testDiagnosticWindow
testDrawCoverage
testFrameHistory
testIngestionLayout
testIrisUniforms
testMaterialUniforms
testMeshing
testNativeOwnership
testProjection
testSectionProbe
testShaderLoading
testStorage
testStorageConfig
testUpstreamCpu
testWaterFrames
testGlInterop
testMaterialContract
testMetalBake
testMetalResolve
testMetalTerrainPipelines
testMetalTraversal
testWaterDepthBridge
```

Run with `./gradlew clean remapJar verifyCpu verifyMetal
-PcomplementaryPack="/path/to/ComplementaryUnbound_r5.9.3.zip"` (one command).
The standard `build` task also passes with `GITHUB_ACTIONS=true`, including CI-mode
dependency setup. Standalone JavaExec regressions run through the explicit verification
tasks; the default Test task does not require discovery of an undeclared JUnit suite.
The shader ZIP is user-supplied, not committed; test-only sources and helpers are
not installed. Python deployment-helper regressions pass nine tests, including
packaging rejection, Voxy-only replacement, checksum verification and rollback.
Required native/class packaging, ARM64 Metal binary, remapped access widener and
build identity are verified in the installable JAR.

Actual on-device checks cover shader compilation/linking, all bakery mips/coverage/
tint, material pixels, GL restoration and failure paths, Metal-to-GL depth export/
orientation/current-frame freshness, and traversal readiness. They use production
shader definitions and actual Iris-preprocessed Complementary programs.

The tester reported plants/gaps improved, then confirmed transition holes/chunk
flicker fixed, shader terrain lighting consistent, and finally confirmed shader
water fixed after `water-pixel8`. That accepted build was
`water-pixel8-20260930T035000.240244Z`, SHA-256
`dc3ab6504cf13d65adb5b475acfb097feb59f15fd61f510119a4da5dabc1d217`.
Each prior candidate was clean-built and installed with Voxy-only backup and
matching source/installed checksums; 62 unrelated mod-folder files stayed unchanged.
Raw game logs, crash dumps and machine-specific deployment manifests remain local.

## Limits

These results establish the reported fixes in one Apple M5 Pro setup with
Complementary Unbound r5.9.3. They do not establish general shader-pack support,
complete 0.2.16 feature parity or a measured performance improvement. Software
bakery/model-atlas replacement, reverse depth, SSAO/occupancy, contracts 2/3,
newer-Minecraft APIs and incompatible OpenGL assumptions are deferred.

Native source/binary were not changed. CMake is unavailable on the test machine:
the native build reports retaining the repository's ARM64 binary, whose packaging
and actual GPU behavior are checked. A fresh native-source rebuild is not claimed.
The legacy generic compiler sweep's subgroup clustered reduction limitation is
not the actual Metal Hi-Z pipeline, which compiles/links; Metal Hi-Z occlusion is
not re-enabled. Required indirect-command and Metal-to-GL waits remain intact.

Broader shader/reload/underwater failure-path coverage, native constructor/encoder
ownership audits and three comparable stationary/travel performance runs remain
future work. No asynchronous GPU scheduling or unmeasured optimization is claimed.
