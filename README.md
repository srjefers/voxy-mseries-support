# Voxy — Apple M-Series Support

> **Status: ALPHA** — playable on Apple Silicon, under active development.

A fork of [Voxy](https://github.com/MCRcortex/voxy) (the LOD rendering mod for
Minecraft) ported to run on **Apple M-series GPUs**. Upstream Voxy requires
OpenGL 4.6; macOS ships a frozen OpenGL 4.1, so this fork runs Voxy's entire
LOD renderer on **Metal** while Minecraft itself keeps rendering on GL, and
bridges the two worlds every frame through an IOSurface.

This branch adapts compatible changes from official Voxy **0.2.16** for
Minecraft **1.21.11**, while retaining the Metal backend and **Sodium 0.8.11**.
The version remains `0.2.16-metal.preview`; see the
[adaptation ledger](docs/UPSTREAM-0.2.16-LEDGER.md) for imported changes and exclusions.

## Build and install

Requirements: Apple Silicon macOS, JDK 21, and this repository. The wrapper uses
Gradle 9.4.1 and Fabric Loom 1.16.1.

```bash
./gradlew clean remapJar
```

Install the remapped JAR from `build/libs/` in your instance's `mods/` folder;
exclude sources/dev JARs. Set `VOXY_FORCE_METAL=1` in the launcher's environment
configuration. Without this opt-in, Voxy disables itself on macOS.

For a verified build and transactional installation, specify the destination explicitly:

```bash
python3 tools/build_and_deploy.py --phase local-test --mods "/path/to/instance/.minecraft/mods"
```

The helper checks the Fabric ID, remapping, build identity and ARM64 runtime
libraries/classes, backs up only prior Voxy JARs, compares source/installed SHA-256,
and verifies unrelated mod files are unchanged. Set the destination with `--mods`,
the `VOXY_MODS_DIR` environment variable, or a `.voxy-deploy-mods` file in the
repository root containing only your mods directory path. That local file is ignored
by Git, so launcher details stay private.

| Component | Target / tested setup |
|---|---|
| Minecraft | 1.21.11 |
| Java | 21 |
| Fabric Loader | 0.18.2+; tested with 0.19.3 |
| Fabric API | 0.140.0+; tested with 0.141.6+1.21.11 |
| Sodium | 0.8.11+mc1.21.11 |
| Iris | 1.10.7+mc1.21.11 |
| Shader pack tested | Complementary Unbound r5.9.3 |
| Hardware tested for this adaptation | Apple M5 Pro |

The repository retains an ARM64 Metal native library. A native rebuild requires
CMake and the Apple toolchain; a missing build tool retains that binary with an
explicit warning. The current adaptation changes Java/shaders, not native source.

## Rendering and upstream changes

- Correct plant cutout coverage and captured biome-tint metadata preserve terrain
  behind grass and avoid opaque flower backgrounds.
- Per-frame drawable Sodium coverage and stable camera history fix demonstrated
  transition holes and repeated-read reprojection errors.
- Complementary uses the full opaque/translucent contract-1 material path, its own
  lighting/blending and live textures. Pack samplers and early fragment coordinates
  are initialized explicitly, fixing the reproduced water-opacity defects.
- Compatible upstream lighting, meshing, traversal/allocation, storage/lifecycle,
  configuration and command changes include `/voxy import current`.
- ARM64 RocksDB, Shaderc/SPVC Java/native packaging and explicit JOML uniform writes
  allow initialization and rendering on the tested launcher setup.

This adaptation was implemented with AI (Codex), guided by human input and repeated
gameplay testing. The tester confirms Complementary works well, terrain/LOD stitching
works, shader water is stable, and chunk holes are gone. This validates the tested setup,
not general shader-pack compatibility or a measured performance claim.

## Verification

Run the CPU regressions and real GL/Metal pipeline, bake and pixel checks on Apple Silicon:

```bash
./gradlew verifyCpu verifyMetal -PcomplementaryPack="/path/to/ComplementaryUnbound_r5.9.3.zip"
python3 -m unittest discover -s tools -p 'test_*.py'
```

Regression drivers use explicit verification tasks, not a framework-discovered
JUnit suite. The standard `build` task compiles them; run `verifyCpu` and
`verifyMetal` to execute the checks.

The shader-pack ZIP is supplied by the tester and is not committed. Alternatively,
place it in the ignored `test-shaderpacks/` directory. Tests live in `src/test` and
are excluded from the installable mod. See
[validation and limitations](docs/METAL-0.2.16-STABILIZATION.md).

Opt-in `/voxy debug probe <x> <y> <z>` and `/voxy debug probe view` collect matched
rendering observations at most twice per second for 60 seconds. Use
`/voxy debug probe status` or `/voxy debug probe off`; pixel readbacks are disabled
outside an active probe.

## Remaining scope

- Software bakery/model-atlas replacement, reverse depth, SSAO/occupancy and shader
  contracts 2/3 are deferred; this is not complete 0.2.16 feature parity.
- BSL-specific rewrites are isolated behind `-Dvoxy.bslCompatibility=true`; only the
  generic Complementary path has the reported gameplay acceptance here.
- LOD plant/fluid geometry remains an approximation. Existing world formats,
  configuration and caches are preserved; corrected mip lighting applies to newly
  generated data, without automatic cache deletion/rebuilding.
- Native source rebuilds, broader failure-path/resource ownership checks, wider
  shader-pack testing and repeatable performance comparisons remain future work.
- Required GPU/interop waits remain intact. Asynchronous scheduling and Metal Hi-Z
  occlusion are not enabled by this adaptation.

## Documentation

- [`docs/METAL-0.2.16-STABILIZATION.md`](docs/METAL-0.2.16-STABILIZATION.md) — this adaptation, regressions, gameplay feedback and limitations.
- [`docs/UPSTREAM-0.2.16-LEDGER.md`](docs/UPSTREAM-0.2.16-LEDGER.md) — every changed upstream file and its import/adaptation/defer status.

- [`docs/METAL-MIGRATION.md`](docs/METAL-MIGRATION.md) — architecture, the
  root-cause/fix table, the full environment-variable reference, backlog.
- [`docs/MIGRATION-HISTORY.md`](docs/MIGRATION-HISTORY.md) — the complete
  journey: every milestone, bug, root cause, and fix that got this working.
- [`docs/DEVELOPMENT.md`](docs/DEVELOPMENT.md) — building, running,
  debugging workflow, log markers, and contribution conventions.
- [`docs/LOD-FLICKER-INVESTIGATION.md`](docs/LOD-FLICKER-INVESTIGATION.md) —
  historical investigation notes (May 2026).

## Credits

- [MCRcortex](https://github.com/MCRcortex) — Voxy, the upstream mod this
  fork builds on.
- Port and stabilization work: see `docs/MIGRATION-HISTORY.md`.
