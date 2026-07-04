# Voxy — Apple M-Series Support

> **Status: ALPHA** — playable on Apple Silicon, under active development.

A fork of [Voxy](https://github.com/MCRcortex/voxy) (the LOD rendering mod for
Minecraft) ported to run on **Apple M-series GPUs**. Upstream Voxy requires
OpenGL 4.6; macOS ships a frozen OpenGL 4.1, so this fork runs Voxy's entire
LOD renderer on **Metal** while Minecraft itself keeps rendering on GL, and
bridges the two worlds every frame through an IOSurface.

Verified on an Apple M4 at **~110 FPS** with 32-chunk render distance and LOD
terrain to the horizon.

## Quick start (development environment)

Requirements: macOS on Apple Silicon, JDK 21+, this repository.

```bash
VOXY_FORCE_METAL=1 ./gradlew runClient
```

`VOXY_FORCE_METAL=1` is the explicit opt-in for the Metal render path —
without it Voxy disables itself on macOS and you get plain Sodium rendering.

To use the mod in a launcher profile, build the jar and drop it in `mods/`
together with the matching Sodium version:

```bash
./gradlew build    # output: build/libs/voxy-<version>.jar
```

| Component | Version |
|---|---|
| Minecraft | 1.21.11 |
| Fabric Loader | 0.18.2+ |
| Fabric API | 0.140.0+ |
| Sodium (required) | mc1.21.11-0.8.1 |

## What works (alpha)

- LOD terrain to the horizon with **real baked block textures**, biome
  tinting, Minecraft lighting, and fog parity with Sodium's near terrain
- **Translucent, animated water** with correct underwater behavior (fog murk,
  no X-ray, stable visuals while swimming)
- Correct LOD↔terrain boundary (chunk-bound depth masking)
- Spyglass / zoomed FOV, screenshots, render-distance changes
- ~110 FPS on an M4 (synchronous GPU model — more headroom planned)

## Known issues / not yet done

- **LOD water animation phase** can be slightly offset from MC's water, and
  flowing water (rivers/waterfalls seen up close) uses a static frame —
  accepted open issue.
- **SSAO** is not ported (an interim brightness compensation matches LOD
  terrain to Sodium's ambient-occlusion look).
- **Sky at the horizon** is the fog color rather than MC's real sky, and
  white clouds can be hard to see against it at day (MC 1.21.11 renders the
  sky in a way the compositor cannot yet preserve).
- **Performance phase 2** pending: the Metal frame currently uses 3
  synchronous GPU waits; collapsing them should push FPS well past the
  current ~110.
- **Iris shader packs now drive LOD terrain on Metal** through Voxy's native
  pack contract (packs shipping `voxy.json`, e.g. BSL): LOD depth goes to the
  pack's dedicated `vxDepthTex` side-channel and the pack's own `#ifdef VOXY`
  branches apply fog, AO, shadows and clouds to LOD pixels. Full per-pixel
  pack lighting of LODs (material g-buffer resolve) is in progress
  (milestone "Native Iris shader contract on Metal").
- **BSL fog tuning for LOD view distances**: BSL applies its full fog density
  across LOD distances by default, washing the far field out white. Lower
  *Shader Pack Settings → Fog → More Fog Settings → Fog Density LOD* to
  ~0.25 (BSL added that slider specifically for LOD mods). Equivalent file
  setting: `FOG_DENSITY_LOD=0.25` in `shaderpacks/<pack>.zip.txt`.

## Documentation

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
