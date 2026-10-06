# Command-only architecture candidate

Baseline: `fed768e7701511bc0e118d2c0fc95f302dba102a` on
`metal-12111-stabilization`. Candidate: `architecture-fixes` in a separate Git
worktree. Minecraft 1.21.11, Sodium 0.8.11, Iris 1.10.7, the pinned upstream
adaptation, storage formats, and existing caches are preserved.

This candidate implements all six recommendations from the architecture review.
It was implemented with AI assistance and verified through commands. The accepted
baseline had human gameplay testing; this candidate has not had gameplay testing.
No game launch, computer use, screenshot inspection, or installed-mod replacement
was used for this comparison.

## Changes

| Review area | Implementation and regression evidence |
| --- | --- |
| Renderer resource ownership | `RendererLifecycle` owns acquisitions by dependency phase. Downloads finish before node shutdown; nodes, meshing, and bakery stop before upload visibility waits and resource release. Constructor rollback restores indexed GL buffer bindings. `ResourceScope` unwinds frame and MDIC resources once in reverse acquisition order and attempts independent releases after failures. Interrupted joins finish before restoring interruption. Real constructor, teardown, and worker regressions reproduce preceding failures. |
| Transactional frame replacement | `MetalFrameTargets` acquires the complete size/mode target set before `MetalFrameRenderer` publishes it. Failed acquisition releases only the new set. Every one of 13 allocation faults retained the old set; successful replacement and unchanged-size reuse also passed. Replacement briefly holds both sets, so resize requires temporary memory headroom. |
| Canonical terrain configuration | `TerrainShaderConfiguration` owns unpatched shader loading, defines, targets, and render state. Production MDIC and the native driver test use the same module. A characterization captured before extraction matches afterward for both backends, every material policy, and TAA on/off. Metal compiles/links all 16 corresponding terrain pipelines plus Hi-Z. The patched OpenGL Iris shader path remains separate. |
| Main-view frame ownership | `WorldFrameCapture` publishes copied camera inputs only on successful non-shadow capture. Preparation is shared by Iris and Sodium. Material begin/publish/consume use the same long frame identity, and traversal derives its visibility tag from it. The cleaner consumes that tag before upload clears and retains it across repeated work. `prepareTerrain` captures a mid-frame replacement before beginning material ownership. Rejected capture, shadow capture, competing Iris clock, and mid-frame replacement regressions failed before the changes. Characterization preserves repeated preparation. The visibility-clear regression reproduced the independent cleaner counter. |
| Coverage delivery | Metal subscribes only to generation resets, eliminating its unused upload-delta map and map copy. OpenGL still receives consolidated incremental updates. Existing upload/draw readiness, stale generation, removal, and replacement tests remain green. The production Metal subscription regression failed before the change. |
| Failed saves | A failed write restores dirty state and retains the queue claim and section reference. Explicit bounded recovery and shutdown retry each retained entry once; persistent failure raises an error without unloading the unsaved section. Two failing regressions cover latest-data recovery and persistent failure. Existing RocksDB reopening and serialization checks pass. |
| Geometry publication | `GeometryPublication` owns upload/scatter scratch and recording order. Geometry, metadata, and cleaner work precede readiness callbacks. Failed recording latches a generation failure, reclaims the consumed payload, and prevents continued publication. Worker assembly retains ownership until handoff. The old order and leaks were reproduced with GPU recording faults, readiness faults, and worker serialization faults. |

The cleanup modules improve locality by placing acquisition and release together.
The shader module removes a second test implementation. The publication module's
interface hides recording order and completion; its worker and render-thread
adapters share one payload. Deleting these modules would disperse that protocol
again rather than remove unnecessary indirection.

## Validation

Tests were written and run before their corresponding behavior changes.
Refactoring characterization established existing descriptors before extraction.
Fault-injection tests call production construction, frame preparation, publication,
and teardown; GPU allocation/recording faults use backend mocks. Driver tests use
real Metal and invisible GL contexts with numeric assertions, not visual testing.

| Checks | Baseline | Candidate |
| --- | --- | --- |
| Existing Gradle CPU and GL/Metal verification tasks | 30 passed | Same 30 passed |
| New focused verification tasks | Defects reproduced before fixes | 8 passed |
| Existing deployment-tool unit tests | 13 passed | 13 passed |
| Actual Complementary Unbound r5.9.3 programs | Compile/link and material checks passed | Same checks passed |
| Production configuration fingerprint | Captured before extraction | Identical |
| Clean installable build and runtime packaging | Accepted build preserved | `clean remapJar` passed; validated separately |

Production configuration SHA-256:
`576c59c67f4926bc7a29e4a02f32121bef58b6c6ae9acaeafbf2cb1c6151982b`.

Existing driver checks include bake coverage/tint and mip readback, depth bridges,
composition pixels, sampler isolation and GL state restoration, traversal,
Complementary targets `[0,6]` / `[0]`, actual Iris cached-uniform updates, TAA
injection, and preservation of zero-light material inputs. The unchanged legacy
BSL compatibility test emits its intentional source-anchor warnings; these do not
represent failed Complementary checks.

Run these commands inside either worktree, supplying the same shader-pack ZIP:

```sh
export JAVA_HOME=/opt/homebrew/opt/openjdk@21
export COMPLEMENTARY_PACK=/path/to/ComplementaryUnbound_r5.9.3.zip
./gradlew --offline verifyCpu verifyMetal \
  "-PcomplementaryPack=$COMPLEMENTARY_PACK" --console=plain
python3 -m unittest discover -s tools -p 'test*.py'
```

The candidate adds `testPublication`, `testCoverageConsumption`, `testFrameTargets`,
`testRendererOwnership`, `testTerrainConfiguration`, `testWorldFrameProtocol`,
`testMdicOwnership`, and `testVisibilityClock` to those aggregate tasks. They can also run individually.

To build without deploying:

```sh
./gradlew --offline clean remapJar -PbuildLabel=architecture-fixes-candidate1
```

The installable JAR remains in the candidate's `build/libs`. The packaging verifier
checks Fabric ID and identity, intermediary access widening, the ARM64 Metal
library, RocksDB ARM64, Shaderc/SPVC Java classes and native libraries, and other
required bundled runtimes. Test classes must not enter that JAR.

## Comparison limits

The shader descriptor fingerprint demonstrates unchanged shader choices under the
characterized inputs. Native assertions and failure injection establish the
specified protocols and error handling. They do not establish identical gameplay
images, absence of every gameplay artifact, or an FPS improvement.

No frame-rate comparison is claimed. Removing the discarded coverage map avoids
known work, but representative stationary/travel performance still needs a
controlled runtime workload. Asynchronous GPU scheduling, reverse depth, storage
migration, and Metal Hi-Z re-enablement were not introduced.

MDIC and AsyncNodeManager are now below 1,000 lines. No production file crosses
from below 1,000 lines to above it. Other pre-existing oversized modules remain
future review candidates, outside these six changes.
