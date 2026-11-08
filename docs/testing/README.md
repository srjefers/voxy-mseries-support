# Help test Voxy on Apple Silicon

This page is for players and developers who want to test this fork on their
Mac and report the results on GitHub.

## Why we test

Voxy draws far terrain (LODs) for Minecraft. Upstream Voxy needs OpenGL 4.6,
but macOS stops at OpenGL 4.1, so this fork runs Voxy's renderer on Metal.
It is an **alpha**.

So far only one Mac has been tested: an M4 Max with 48 GB on macOS 26.
Other chips, less memory and older macOS versions run code paths we have
never seen. Your report may be the first one for your kind of Mac.

## Who can help

Anyone with an Apple Silicon Mac (M1 or newer) and Minecraft: Java Edition.
Intel Macs cannot run the Metal path. These Macs help the most right now:

- Base chips: M1, M2, M3 and M4 (not Pro, Max or Ultra).
- 16 or 24 GB of memory. 8 GB is not supported yet, but a report still
  helps. Use the 8 GB settings under [Tiers](../../README.md#tiers).
- macOS 13 Ventura, 14 Sonoma or 15 Sequoia.

## Pick a track

| | Player track | Developer track |
|---|---|---|
| Guide | [player-track.md](player-track.md) | [Part 1](developer-track-part1.md), [part 2](developer-track-part2.md), [part 3](developer-track-part3.md) |
| You need | A launcher and the release jar | Git, JDK 21 (arm64), Terminal |
| Time | 30 to 90 minutes, no programming | 1 to 3 hours per phase |
| Tests | `PT-01` to `PT-13` | `P0-*` to `P8-*`, `UC-*`, `7.1` to `7.7` |

### Player track

Follow the [player guide](player-track.md). It shows each launcher step by
step, then lists 13 short tests. Start with `PT-01`. The basics:

1. Download the Voxy jar from
   [Releases](https://github.com/srjefers/voxy-mseries-support/releases).
   Keep its file name, for example `voxy-0.2.8-alpha-455d8cbb.jar`.
2. In your launcher (official, Prism, Modrinth App or CurseForge), create a
   Minecraft 1.21.11 profile with Fabric Loader 0.18.2 or newer. Give it its
   own empty game folder.
3. Put in its `mods` folder: Fabric API `0.140.0+1.21.11`, Sodium exactly
   `mc1.21.11-0.8.1`, and the Voxy jar. Iris 1.10.7 and a shader pack such
   as BSL are optional. Never add `voxyworldgenv2` 2.2.2, which is
   incompatible.
4. Set the profile's JVM arguments to `-Xmx6G -Dvoxy.forceMetal=true`
   (`-Xmx4G` on a 16 GB Mac). Without the flag, Voxy turns itself off.
5. Get the [testing scripts](player-track.md#5-get-the-test-scripts). They
   check your log and pack everything into one zip.

| Test ID | Test | Time |
|---|---|---|
| `PT-01` | Clean start without shaders | 20 min |
| `PT-02` | F3 lines and the opt-in | 10 min |
| `PT-03` | Iris and BSL (optional) | 15 min |
| `PT-04` | Far terrain: edge, grass, water and horizon | 25 min |
| `PT-05` | Rejoin five times: memory and threads | 20 min |
| `PT-06` | Minimize, restore and resize | 10 min |
| `PT-07` | Voxy settings and Apply | 15 min |
| `PT-08` | Render distance changes | 15 min |
| `PT-09` | Underwater | 20 min |
| `PT-10` | Caves | 15 min |
| `PT-11` | Chunky world generation (optional) | 25 min |
| `PT-12` | Performance snapshot | 20 min |
| `PT-13` | Older macOS and smaller Macs | 30 min |

A crash fails any test, and so does a failure in `log-check.sh`. To see
Voxy's lines in F3, press F3+F6 and set the row `debug` to *In Overlay*.

### Developer track

```bash
git clone https://github.com/srjefers/voxy-mseries-support.git
cd voxy-mseries-support
VOXY_FORCE_METAL=1 ./gradlew runClient -PrunXmx=6G
```

- The game folder is `run/`. The testing scripts find it on their own.
  `VOXY_QUICKPLAY="voxytest"` joins that world on launch.
- Many tests are A/B tests: you flip one `VOXY_*` environment variable
  between two runs. The variables are listed in
  [METAL-MIGRATION.md](../METAL-MIGRATION.md). Build details are in
  [DEVELOPMENT.md](../DEVELOPMENT.md).
- Prism Launcher 10.0.2 or newer (*Edit > Settings > Environment
  Variables*) and the Modrinth App (*Java and memory > Custom environment
  variables*) can also set them. The official launcher and CurseForge
  cannot.

| Test IDs | Phase | Guide |
|---|---|---|
| `P0-*` | Build, shader and Metal smoke tests, Terminal only | [Part 1](developer-track-part1.md) |
| `P1-*`, `P2-*` | Start without a shader pack, then visuals with Iris and BSL | [Part 1](developer-track-part1.md) |
| `P3-*`, `P4-*` | Lifecycle and memory (minimize, rejoin, reloads), failure paths | [Part 2](developer-track-part2.md) |
| `P5-*` | Performance | [Part 2](developer-track-part2.md) |
| `UC-00` to `UC-14` | Underwater and caves | [Part 3](developer-track-part3.md) |
| `P7-*`, `7.1` to `7.7` | Stress while new terrain generates, with Chunky | [Part 3](developer-track-part3.md) |
| `P8-*` | The release jar in a clean launcher profile | [Part 3](developer-track-part3.md) |

Before a long phase, say which one you take in the pinned *Call for
testers* issue, so two people do not repeat the same work.

## How to report

1. Quit the game and run `collect-report.sh --redact` before the next
   launch. The next launch overwrites `logs/latest.log`.
   - Player track: `bash ~/voxy-testing/collect-report.sh --redact`
   - Developer track: `bash scripts/testing/collect-report.sh --redact`
2. Open a [new issue](https://github.com/srjefers/voxy-mseries-support/issues/new/choose)
   and pick **Test report**.
3. Write **one issue per session**: one Mac, one jar, one sitting. List every
   test ID you ran with its result: Pass, Fail, Partial or Blocked.
4. Put the test IDs and your Mac in the title, for example
   `[Test] PT-01, PT-02 on M2, 16 GB, macOS 14`.
5. Attach the zip from `collect-report.sh`, the F2 screenshots that show the
   problem (fn+F2 on a MacBook), and the `java` memory in Activity Monitor.

No Terminal? Attach `logs/latest.log` from your game folder. After a crash,
also attach the newest `crash-reports/` file and any `hs_err_pid*.log`.
Attach logs as files: an issue holds at most 65,536 characters of text.

## Privacy

GitHub issues are public. Your log contains your Minecraft name, file paths
with your Mac user name, and the addresses of servers you joined.

- `--redact` replaces your home folder path, Mac user name and Minecraft name
  in the report copies. It does not hide server addresses.
- Screenshots with F3 open show your coordinates.
- Check every file before you upload it. Never attach launcher files.

## Known non-errors

These are expected. Do not report them as failures.

- `[Metal-CULL] ... back faces of water WILL rasterise`. See
  [Log messages checked during testing](../../README.md#log-messages-checked-during-testing).
- `Viewport width or height was zero`: one per minimize.
- `Voxy section saving still in progress`: saves that finish at quit.
- `[M9 TRANSITIONAL] Voxy disabled on METAL backend`: the opt-in flag is
  missing, as in the second half of `PT-02`.
- A slow first launch with a new jar: Voxy compiles shaders into
  `~/.voxy/shader-cache`.
- 30 fps after an idle minute: set *Video Settings > Sodium > Performance >
  Reduce FPS when* to *Minimized*.
- The game pauses when you click another window: press F3+P until the chat
  says "Pause on lost focus: disabled".
- The frame rate snaps to 60, 40 or 30: VSync is on.
- Activity Monitor shows far more memory than F3. See
  [unified memory](../../README.md#why-activity-monitor-shows-much-more-than-f3-unified-memory).

## Coverage

Maintainers fill this table from the reports. A cell holds the result, the
issue and the macOS version, for example `Pass #31 (macOS 14)`. Empty means
no report yet. Developer test IDs get a row with their first report.

| Test ID | Base chip, 8 GB | Base chip, 16-24 GB | Pro | Max or Ultra |
|---|---|---|---|---|
| `PT-01` | | | | |
| `PT-02` | | | | |
| `PT-03` | | | | |
| `PT-04` | | | | |
| `PT-05` | | | | |
| `PT-06` | | | | |
| `PT-07` | | | | |
| `PT-08` | | | | |
| `PT-09` | | | | |
| `PT-10` | | | | |
| `PT-11` | | | | |
| `PT-12` | | | | |
| `PT-13` | | | | |
