# Developer track, part 1: build checks, no-pack start, BSL visuals

This part covers phases 0, 1 and 2 of the test plan. It is for developers who
clone the repository and test with the dev client (`./gradlew runClient`).

| Phase | Test IDs | Topic |
|---|---|---|
| 0 | `P0-*` | Build, shader and Metal checks in Terminal, plus two helper checks |
| 1 | `P1-*` | First start on the Metal path without a shader pack |
| 2 | `P2-*` | Far LOD visuals with the BSL shader pack |

Phases 3 to 5 are in [part 2](developer-track-part2.md). Phases 6 to 8 are in
[part 3](developer-track-part3.md). How to report is in the
[testing overview](README.md#how-to-report).

Every test keeps its ID from the test plan. Use that ID in your report.

## Setup

### Requirements

- A Mac with Apple Silicon (M1 or newer) and macOS 13 or newer.
- 16 GB of memory or more. 8 GB Macs are not supported.
- Xcode Command Line Tools: `xcode-select --install`. The build uses
  `clang` and `otool`.
- CMake 3.20 or newer: `brew install cmake`. Gradle calls it to rebuild the
  Metal library, `libvoxy_metal.dylib`.
- A JDK 21 or newer for arm64. A JRE is not enough, because the native build
  needs `jni.h`.
- Git, and internet access. The first build downloads Minecraft and the dev
  mods.

Check Java:

```bash
java -version
java -XshowSettings:properties -version 2>&1 | grep os.arch
ls "$(/usr/libexec/java_home)/include/jni.h"
```

The version must be 21 or newer, `os.arch` must be `aarch64`, and `ls` must
find `jni.h`.

### Get the code

```bash
git clone https://github.com/srjefers/voxy-mseries-support.git
cd voxy-mseries-support
git checkout dev
```

To test an open pull request instead, check out its branch. With the GitHub
CLI: `gh pr checkout <number>`.

The log lines in this guide come from the code of pull request #21 (branch
`feature/lod-shadows-perf-stress`). Older commits lack many of them. If #21
is not merged into `dev` yet, test that branch, or report which lines are
missing on yours.

Write down the commit you test. The report form asks for it.

```bash
git rev-parse --short HEAD
git status --short
```

### Rules for every command

- Run every command from the repository root, the folder with `gradlew`. No
  command in this guide uses an absolute path.
- The dev game folder is `./run`. Gradle creates it on the first
  `runClient`, and git ignores it. Logs, settings, worlds, screenshots and
  shader packs all live there.
- Put environment variables on the same line, before `./gradlew`. Do not
  `export` them, so they do not leak into the next run. This works in zsh,
  the macOS default shell, and in bash.
- The Gradle daemon is off, so every Gradle command starts cold.
- Run one game at a time.

### The dev client

The dev client already includes Sodium `mc1.21.11-0.8.1`, Iris 1.10.7,
Fabric API, Lithium, Mod Menu, Chunky and spark. You do not install mods by
hand. Iris is always loaded, so "no pack" means shaders turned off with K.

| What | Command |
|---|---|
| Start the game | `VOXY_FORCE_METAL=1 ./gradlew runClient -PrunXmx=6G` |
| Join the test world on launch | `VOXY_QUICKPLAY="voxytest" VOXY_FORCE_METAL=1 ./gradlew runClient -PrunXmx=6G` |

- `VOXY_FORCE_METAL=1` is required. Without it, Voxy turns itself off and you
  see plain Sodium.
- `-PrunXmx=6G` caps the Java heap like `-Xmx6G` in a launcher profile. On a
  16 GB Mac, use `-PrunXmx=4G` and say so in your report.
- `VOXY_QUICKPLAY` takes the world's save folder name.
- `VOXY_DEBUG_CMD` runs chat commands a fixed number of seconds after you
  join. Separate commands with `;` and write them without the leading `/`.
  Use `@p`, not `@s`. Example: `VOXY_DEBUG_CMD='5:time set noon;8:tp @p X Y Z YAW PITCH'`.
  It runs again on every join. The log shows `[VOXY_DEBUG_CMD] running:` for
  each command.
- Other `VOXY_*` variables are A/B switches. Each test lists the ones it uses.
  The full list is in [METAL-MIGRATION.md](../METAL-MIGRATION.md).

**A fixed viewpoint.** Open F3. Write down the three numbers after `XYZ:` and
the two numbers in brackets after `Facing:`, which are yaw and pitch. Put
them in `VOXY_DEBUG_CMD='8:tp @p X Y Z YAW PITCH'`, or type
`/tp @s X Y Z YAW PITCH` in the chat.

### Keys

| Key | What it does |
|---|---|
| F3 | Debug screen. Voxy adds lines such as `I/S/AWSC:` and `UC/GC,#N:`. |
| F3+F6 | Debug Options. Voxy's rows are `debug` and `version`. Set `debug` to *In Overlay*. `version` always shows. Searching `voxy` finds nothing. |
| F3+P | Turns *Pause on lost focus* on or off. |
| F2 | Screenshot, saved to `run/screenshots/`. |
| F1 | Hides the hand and the HUD. |
| K | Iris: shaders on or off. The chat says "Toggled shaders to ...". The choice is saved in `run/config/iris.properties`. |
| R | Iris: reload shaders. |
| O | Iris: shader pack list. |

On a MacBook, press fn with the F keys (fn+F3), unless macOS uses them as
standard function keys (*System Settings > Keyboard*).

### Settings that skew results

`P1-SETUP` sets these once:

- *Options > Video Settings > Sodium > Performance > Reduce FPS when*:
  *Minimized*. With *AFK*, Minecraft drops to 30 fps after 60 s without input.
- *Pause on lost focus*: off. Press F3+P in a world until the chat says
  "Pause on lost focus: disabled". Otherwise the game pauses when you click
  Terminal.
- For fps measurements only: *VSync* OFF and *Max Framerate* *Unlimited* on
  the Sodium *General* page. With VSync on, fps snap to 60, 40 or 30.

Settings named in a test are part of the test. The tests use the reference
Mac's values, for example Minecraft *Render Distance* 32. If your Mac cannot
run them, use lower values and write them in the report.

### Shader pack

The end of phase 1 and all of phase 2 need BSL Shaders v10.1.3. Download it
from its official page and put the zip, unopened, in `run/shaderpacks/`:

```bash
mkdir -p run/shaderpacks
cp ~/Downloads/BSL_v10.1.3.zip run/shaderpacks/
```

If your file has another name, use that name wherever this guide says
`BSL_v10.1.3.zip`.

### Testing scripts

The scripts in `scripts/testing/` find `./run` on their own when you run
them inside the repository. They print the folder they chose and never
change it. Details are in the [scripts README](../../scripts/testing/README.md).

| Script | When | Command |
|---|---|---|
| `settings-dump.sh` | Before a phase, game closed | `bash scripts/testing/settings-dump.sh` |
| `sampler.sh` | During a test, in a second Terminal tab | `bash scripts/testing/sampler.sh` |
| `log-check.sh` | After a test, game closed or on the title screen | `bash scripts/testing/log-check.sh` |
| `collect-report.sh` | At the end of a session, before the next launch | `bash scripts/testing/collect-report.sh --redact` |

Results go to `~/voxy-test-results/`.

### Back up your settings

If `run/` already exists from earlier work, back up your settings before you
change anything. Close the game first:

```bash
mkdir -p ~/voxy-test-results/settings-backup
for f in run/options.txt run/config/voxy-config.json run/config/iris.properties run/shaderpacks/BSL_v10.1.3.zip.txt; do [ -f "$f" ] && cp "$f" ~/voxy-test-results/settings-backup/; done
ls ~/voxy-test-results/settings-backup
```

On a fresh clone there is nothing to back up yet.

When you finish testing, restore them with the game closed:

```bash
B="$HOME/voxy-test-results/settings-backup"
[ -f "$B/options.txt" ] && cp "$B/options.txt" run/
[ -f "$B/voxy-config.json" ] && cp "$B/voxy-config.json" run/config/
[ -f "$B/iris.properties" ] && cp "$B/iris.properties" run/config/
[ -f "$B/BSL_v10.1.3.zip.txt" ] && cp "$B/BSL_v10.1.3.zip.txt" run/shaderpacks/
```

Then check the result with `bash scripts/testing/settings-dump.sh`.

### After every in-game test

1. Quit with *Esc > Save and Quit to Title > Quit Game*.
2. Run `P0-LOG-HEALTH`: `bash scripts/testing/log-check.sh`.
3. Keep a copy of the log under the test ID. The next launch overwrites it.

   ```bash
   mkdir -p ~/voxy-test-results && cp run/logs/latest.log ~/voxy-test-results/P1-NOPACK-START.log
   ```

   Change `P1-NOPACK-START` to the test you just ran.
4. Keep your F2 screenshots from `run/screenshots/`.
5. At the end of the session, run
   `bash scripts/testing/collect-report.sh --redact` and file one
   [test report](https://github.com/srjefers/voxy-mseries-support/issues/new?template=test-report.yml)
   for the session. List each test ID with its result: Pass, Fail, Partial or
   Blocked. Put the commit in *Voxy jar or commit*.

### How to read a test

- **Owner status** is the result on the reference Mac, an M4 Max with 48 GB
  on macOS 26. *Verified* passed there. *Partial* passed in part. *Pending*
  is written but not run yet. *New* was never run. Pending and New tests are
  where your help counts most.
- **Markers** are log lines. Search for them in `run/logs/latest.log` with
  `grep -F`.
- **A/B switch** is an environment variable that turns a fix off, so you can
  compare. Add it in front of the test's command for a second run.
- Numbers such as fps come from the reference Mac. Your numbers will differ.
  Report them anyway.

---

## Phase 0: Automatic checks

Terminal only, with Minecraft closed. No world is needed, and the shader pack
does not matter. `P0-SETTINGS` and `P0-LOG-HEALTH` are helpers you repeat in
the other phases: `P0-SETTINGS` before each phase, `P0-LOG-HEALTH` after each
test in the game.

| ID | Test | Priority | Min |
|---|---|---|---|
| `P0-BUILD` | Full build of the jar and the Metal library | Essential | 6 |
| `P0-SHADERS` | Shader compilation and pipeline linking | Essential | 2 |
| `P0-METAL-SMOKE` | Metal smoke tests | Recommended | 3 |
| `P0-SETTINGS` | Print the current settings | Optional | 1 |
| `P0-LOG-HEALTH` | Log check after each session | Essential | 1 |

### P0-BUILD: Full build of the jar and the Metal library

**Priority:** Essential. **Time:** 6 min. **Owner status:** Verified.
**Depends on:** nothing.

**Goal.** Check that everything compiles and that the build makes the jar
used in phase 8. Also check that the Metal library still targets macOS 13.
If it does not, Voxy turns itself off on macOS 13 to 15.

**Needs.**

- Xcode Command Line Tools, CMake 3.20 or newer, and a JDK 21 or newer. See
  [Requirements](#requirements).
- The game closed.
- No uncommitted changes to tracked files. Otherwise the jar name ends in
  `-dirty`.

**Settings.** None.

**Command.**

```bash
mkdir -p ~/voxy-test-results
./gradlew build 2>&1 | tee ~/voxy-test-results/P0-BUILD.txt
grep -E 'buildMetalNative|dylib NOT found|BUILD (SUCCESSFUL|FAILED)' ~/voxy-test-results/P0-BUILD.txt
ls build/libs
otool -l src/main/resources/natives/macos-arm64/libvoxy_metal.dylib | awk '/LC_BUILD_VERSION/{f=1} f&&/minos/{print "minos", $2; exit}'
```

**Steps.**

1. Run the commands and wait 3 to 6 minutes. The first build on a new clone
   also downloads Minecraft and the mods, so it takes longer.
2. Read the `buildMetalNative` lines. `>>> buildMetalNative: RUNNING on mac os
   x/aarch64` followed by `SUCCESS` or by `UP-TO-DATE` is good.
3. `>>> buildMetalNative: FAILED` means the native build broke. Gradle still
   ends with BUILD SUCCESSFUL, and the jar carries the old library from the
   repository. Count it as a fail.
4. Check the jar name from `ls`. It must be `voxy-<version>-<commit>.jar`,
   for example `voxy-0.2.8-alpha-f52c7ff0.jar` at commit `f52c7ff0`.
5. Check that the last line says `minos 13.0`.
6. Run `git status --short`. If `libvoxy_metal.dylib` shows as modified, your
   Xcode built a different binary. That is not a fail of this test, but your
   next build's jar will end in `-dirty`. Add the output of `clang --version`
   to your report.

**Markers.**

```
>>> buildMetalNative: RUNNING
>>> buildMetalNative: SUCCESS
>>> buildMetalNative: FAILED
dylib NOT found
BUILD SUCCESSFUL
minos 13.0
```

**Pass if.** BUILD SUCCESSFUL, with no `>>> buildMetalNative: FAILED` and no
`dylib NOT found`. There is one `voxy-<version>-<commit>.jar` without
`-dirty`, and the last line says `minos 13.0`.

**Fail if.** BUILD FAILED, `>>> buildMetalNative: FAILED`, a jar that ends in
`-dirty`, or a minos above 13.0. To see the native error, run
`bash native/metal/build.sh Release`. For `-dirty`, commit or stash your
changes. For the minos, run `rm -rf native/metal/build` and build again.

**A/B switch.** None.

**Notes.** Write down the jar size and the build time. The jar from commit
`e69c2a35` is 21.7 MB.

### P0-SHADERS: Shader compilation and pipeline linking

**Priority:** Essential. **Time:** 2 min. **Owner status:** Verified.
**Depends on:** `P0-BUILD`.

**Goal.** Run 59 shaders through the whole chain: GLSL to SPIR-V, then to
MSL, then to a Metal library. Then build the 10 MDIC terrain pipelines for
real, once for each state: no pack, pack without contract, pack with
contract, and the debug settings.

**Needs.** `libvoxy_metal.dylib` from `P0-BUILD`. Without it, the link step
prints `SKIP link` and tests nothing.

**Settings.** None.

**Command.**

```bash
mkdir -p ~/voxy-test-results
./gradlew testShaderCompiler 2>&1 | tee ~/voxy-test-results/P0-SHADERS.txt
grep -E '=== SPV:|FAIL (spv|msl|lib|link)|SKIP link|Failures:|BUILD (SUCCESSFUL|FAILED)' ~/voxy-test-results/P0-SHADERS.txt
grep -c 'PASS link' ~/voxy-test-results/P0-SHADERS.txt
```

**Steps.**

1. Run the commands and wait about 2 minutes.
2. Read the summary line that starts with `=== SPV:`.
3. The last command counts the `PASS link` lines. It must print 10.
4. There must be no `SKIP link` line and no line that starts with `FAIL`.
5. Gradle must end with BUILD SUCCESSFUL.

**Markers.**

```
=== SPV:
PASS link
FAIL spv
FAIL msl
FAIL lib
FAIL link
SKIP link (no Metal backend
Failures:
```

**Pass if.** The summary says `=== SPV: 59/59  MSL: 59/59  METAL LIB: 59/59`,
there are 10 `PASS link` lines, no `FAIL` lines, and BUILD SUCCESSFUL. These
counts match the code of PR #21. If the shader list changes, each pair must
still be equal (N/N).

**Fail if.** Any `FAIL spv`, `FAIL msl`, `FAIL lib` or `FAIL link` line, a
`Failures:` block, `SKIP link (no Metal backend`, or BUILD FAILED. A failure
here predicts a crash on world join in that pack state. Do not start the game
until it is fixed. Report it instead.

**A/B switch.** None.

**Notes.** Write down the total time from the summary line.

### P0-METAL-SMOKE: Metal smoke tests

**Priority:** Recommended. **Time:** 3 min. **Owner status:** Partial.
**Depends on:** `P0-BUILD`.

**Goal.** Test the native Metal layer alone, without Minecraft: a clear, the
graphics pipeline, compute, the indirect command buffer (ICB) that MDIC uses,
an IOSurface texture, and a vertex buffer with `drawIndirect`.

**Needs.** An Apple Silicon Mac and `libvoxy_metal.dylib` from `P0-BUILD`.

**Settings.** None.

**Command.**

```bash
mkdir -p ~/voxy-test-results
./gradlew --continue testMetalRender testMetalTriangle testMetalCompute testMetalIcb testIOSurfaceBridge testMetalVertexBuffer 2>&1 | tee ~/voxy-test-results/P0-METAL-SMOKE.txt
grep -E 'SMOKE OK|Metal device:|requires macOS aarch64|failed to load|Exception|BUILD (SUCCESSFUL|FAILED)' ~/voxy-test-results/P0-METAL-SMOKE.txt
```

`--continue` runs the other tests even if one fails.

**Steps.**

1. Run the commands and wait about 3 minutes.
2. Look for one OK line per test, six in total:

   | Task | OK line |
   |---|---|
   | `testMetalRender` | `M3 SMOKE OK` |
   | `testMetalTriangle` | `M5 SMOKE OK` |
   | `testMetalCompute` | `M7 SMOKE OK` |
   | `testMetalIcb` | `Blocker 1 SMOKE OK` |
   | `testIOSurfaceBridge` | `M10 SMOKE OK` |
   | `testMetalVertexBuffer` | `M9-prep SMOKE OK` |

3. Ignore `[main/ERROR] ... Running voxy without minecraft`. It is normal in
   these standalone tests.
4. The `M9-prep` line always ends in "on Apple M4 Max". That text is fixed in
   the test. Your real chip is in the `Metal device:` lines.

**Markers.**

```
M3 SMOKE OK
M5 SMOKE OK
M7 SMOKE OK
Blocker 1 SMOKE OK
M10 SMOKE OK
M9-prep SMOKE OK
Metal device:
Metal native library failed to load
```

**Pass if.** All six OK lines appear and Gradle ends with BUILD SUCCESSFUL.

**Fail if.** `... requires macOS aarch64`, `Metal native library failed to
load`, BUILD FAILED, or any RuntimeException. Examples: `Triangle interior
unchanged`, `M7 FAILED: N values mismatched`, `IOSurface render pass produced
wrong clear color`, `Background corner pixels changed`.

**A/B switch.** None.

**Notes.** `testMetalVertexBuffer` has not been confirmed on the reference
Mac yet. It is the reason this test is Partial, so your result helps.

### P0-SETTINGS: Print the current settings

**Priority:** Optional. **Time:** 1 min. **Owner status:** Pending.
**Depends on:** nothing.

**Goal.** Print, without changing anything, the settings that matter for the
tests: VSync, the fps limit, the AFK limiter, pause on lost focus, render
distances, Voxy's settings, the shader pack and the shader cache. Use it to
confirm that each phase starts as it should. After a crash, the menu and the
files can disagree.

**Needs.** The game closed, so the files show the saved values.

**Settings.** None.

**Command.**

```bash
bash scripts/testing/settings-dump.sh
```

**Steps.**

1. Quit Minecraft.
2. Run the command.
3. Compare each value with what the next phase asks for.
4. If a value is wrong, change it in the game menu and press Apply. Do not
   edit the files by hand.
5. Quit the game and run the command again to confirm the change was saved.

**Markers.** None. The script prints the values with notes:

- `enableVsync:true` is VSync ON. Fps then stay near 60.
- `maxFps:260` is *Unlimited*.
- `inactivityFpsLimit:"afk"` drops to 30 fps after 60 s without input and to
  10 fps after 10 minutes. For measurements it must be `"minimized"`.
- `pauseOnLostFocus:true` pauses the game when you click Terminal. Toggle it
  with F3+P in a world.
- `renderDistance`: Minecraft's render distance, in chunks.
- `section_render_distance`: multiply by 32 to get Voxy's *Render distance*
  in the menu. 63 shows as 2016. The default, 16, shows as 512.
- `sub_division_size`: the menu shows one less. 96 shows as 95.
- `use_environmental_fog`: *Enable environmental fog*. The default is true.
  The reference Mac's dev config has false.
- `dont_use_sodium_builder_threads: false` means *Use sodium threads* is ON.
- `service_threads` and `ingest_enabled`: Voxy's thread count and *Chunk
  Ingest*.
- `enableShaders` and `shaderPack`: whether the Iris pack is on, and which.
- The shader cache file count. It is 0 right after you delete
  `~/.voxy/shader-cache`.

**Pass if.** Each value matches what the next phase asks for. For example,
the performance phase needs `enableVsync:false` and
`inactivityFpsLimit:"minimized"`, and phase 1 needs `enableShaders=false`.

**Fail if.** A value does not match. Fix it in the menu before you start, or
the phase measures a different setup.

**A/B switch.** None.

### P0-LOG-HEALTH: Log check after each session

**Priority:** Essential. **Time:** 1 min. **Owner status:** Pending.
**Depends on:** nothing.

**Goal.** One command counts known errors in `run/logs/latest.log`, compares
how many model atlases were created and freed, and looks for crash files from
the last hour. It catches failures you cannot see on screen, such as a shader
pack that Iris turned off by itself, or a Voxy thread that died.

**Needs.** Run it right after you quit the game, or with the game on the
title screen. Old crash files are ignored. Only files from the last 60
minutes count.

**Settings.** None.

**Command.**

```bash
bash scripts/testing/log-check.sh
```

**Steps.**

1. Quit the game, or go back to the title screen.
2. Run the command.
3. Read the result line at the end.
4. Before you launch again, copy the log under the test ID, as in
   [After every in-game test](#after-every-in-game-test). Keep your F2
   screenshots from `run/screenshots/`.

**Markers.** The script searches for these failure messages. Each count must
be 0:

```
disabling shaders
was not freed
Cannot have multiple renderers
composite disabled
inject failed
FBO incomplete
bindToGlTexture failed
Metal command buffer ended with status
Not all worlds shutdown
World still in use after
Error shutting down renderer
Error releasing
thread died
Geometry OOM
Section freed while marked as dirty
had an exception
was not alive
Executing had an error
Tried executing on a dead service
Critical error occurred in async processor
OutOfMemoryError
```

It also counts `[Metal-ATLASLIFE] model atlas CREATED` and `model atlas
FREED`.

**Pass if.** The last line says `== Result: PASS` and the exit status is 0.
That means:

- Every count under "Failure messages" is 0.
- After you quit, the atlas line shows CREATED equal to FREED. Inside a
  world, CREATED = FREED + 1 is fine.
- No `crash-*` or `hs_err_pid*` file from the last 60 minutes.

A result of "PASS, with N warning(s)" also passes, but read the warnings. If
one says Voxy disabled itself, the launch was missing `VOXY_FORCE_METAL=1`.
Repeat the test.

**Fail if.** The last line says `== Result: FAIL` and the exit status is 1:
a failure count above 0, CREATED above FREED after you quit, or a new crash
file. Attach the saved log, this output and your screenshots to the report.
Exit status 2 means the script could not run, for example because there is
no `run/logs/latest.log` yet.

**A/B switch.** None.

**Notes.**

- The script lists these under "Info only". They are never failures:
  - `[Metal-CULL] ... back faces of water WILL rasterise`. A diagnostic
    probe. Without a shader pack it can appear a few times per session,
    mostly while you fly past mobs, and water renders correctly. See
    [Log messages checked during testing](../../README.md#log-messages-checked-during-testing).
    An older version of this check counted it as a failure. It is not one.
  - `Viewport width or height was zero`: one ERROR each time you minimize.
  - `Voxy section saving still in progress`: saves that finished at quit. It
    can be above 0 if you quit while terrain is loading.
  - `Voxy render system construction failed`: expected exactly once in
    `P4-CTOR-FAIL-WITH-PACK` (part 2), and nowhere else.
- What some failure messages mean:
  - `disabling shaders`: Iris could not build the pack's pipeline and turned
    shaders off.
  - `thread died`, `had an exception`, `Tried executing on a dead service` or
    `Critical error occurred in async processor`: a Voxy thread or service
    failed.
  - `was not alive`: chunks arrived for a world that was already closed.
  - `Section freed while marked as dirty`: a problem saving or freeing
    sections.
- Every world join builds the renderer twice (backlog item A20 in
  [BACKLOG.md](../BACKLOG.md)), so CREATED and FREED go up by 2 per join.
- Reference: in the log of 2026-09-26 19:12 every count was 0, with
  `CREATED=14 FREED=14`.

---

## Phase 1: Start without a shader pack

This phase starts the game on the Metal path without a shader pack, in the
test world `voxytest`. `P1-SETUP` sets stable settings and creates the world.
The other tests check the start, Voxy's default environmental fog and double
water. Shaders stay off (K) for the whole phase. The last test turns BSL back
on for phase 2.

| ID | Test | Priority | Min | Pack |
|---|---|---|---|---|
| `P1-SETUP` | One-time setup: settings, test world, frozen clock | Essential | 15 | None |
| `P1-NOPACK-START` | Start without a pack, start-up log lines, F3 lines, pack toggle | Essential | 12 | None, then BSL briefly |
| `P1-ENV-FOG-DEFAULT` | *Enable environmental fog* ON, the default for new users | Recommended | 15 | None |
| `P1-DOUBLE-WATER-AB` | A/B of double water from the chunk-bound mask | Recommended | 20 | None |

### P1-SETUP: One-time setup: settings, test world, frozen clock

**Priority:** Essential. **Time:** 15 min. **Owner status:** New.
**Pack:** none. **Depends on:** `P0-SHADERS`.

**Goal.** Put the game in a known state before any other test. Fix the
settings that skew results without any error, create the `voxytest` world,
select BSL, and turn shaders off for phase 1.

**Needs.**

- `P0-SHADERS` passed.
- The game closed.
- `BSL_v10.1.3.zip` in `run/shaderpacks/`. See [Shader pack](#shader-pack).
- If `run/` already exists, a backup of your settings. See
  [Back up your settings](#back-up-your-settings).

**Settings.**

- *Video Settings > Sodium > Performance > Reduce FPS when*: *Minimized*.
- *Pause on lost focus* off, with F3+P. If it stays on, clicking Terminal
  pauses the world, the clock and Chunky.
- F3+F6 *Debug Options*: the row `debug` set to *In Overlay*.
- Minecraft *Render Distance* 32 (*Video Settings > Sodium > General*) and
  Voxy *Render distance* 2016 (*Video Settings > Voxy > Rendering*). These are
  the reference values. A fresh `run/` starts at 12 and 512. On a 16 GB Mac,
  use lower values and note them.
- World `voxytest`: *Game Mode* Creative, *Allow Cheats* ON, and the
  gamerules `advance_time` and `advance_weather` set to false.
- BSL selected, shaders turned off with K.

**Command.**

```bash
VOXY_FORCE_METAL=1 ./gradlew runClient -PrunXmx=6G
```

**Steps.**

1. Run the command. The game opens on the title screen.
2. Open *Options > Video Settings*. On the Sodium *Performance* page, set
   *Reduce FPS when* to *Minimized*. On the *General* page, set *Render
   Distance* to 32. On *Voxy > Rendering*, set *Render distance* to 2016.
   Press Apply, then Done.
3. Press *Singleplayer > Create New World* and type `voxytest` in *World
   Name*. The screen must show "Save folder: voxytest".
4. On the *Game* tab, set *Game Mode* to Creative and *Allow Cheats* to ON.
   Press *Create New World*.
5. In the world, press F3+P until the chat says "Pause on lost focus:
   disabled".
6. Press F3+F6 to open *Debug Options*. Type `debug` in *Search...* and set
   the row `debug` to *In Overlay*. The row `version` shows by itself when F3
   is open.
7. Press Esc, then F3. Check that you see the lines `I/S/AWSC:` and
   `UC/GC,#N:`. Press F3 again to close it.
8. Press T and type these one at a time: `/gamerule advance_time false`,
   `/gamerule advance_weather false`, `/time set noon`, `/weather clear`.
   Each gamerule must answer "Gamerule ... is now set to: false".
9. Press O. Select `BSL_v10.1.3.zip`, press Apply, then Done.
10. If the world now looks like BSL, press K once so it looks vanilla. The
    chat says "Toggled shaders to ...". K saves the choice.
11. Press Space twice to fly. Fly for 2 minutes so Voxy stores some LODs.
12. Press *Esc > Save and Quit to Title > Quit Game*.
13. In Terminal:

    ```bash
    grep -E 'inactivityFpsLimit|pauseOnLostFocus' run/options.txt
    grep -E 'enableShaders|shaderPack' run/config/iris.properties
    grep use_environmental_fog run/config/voxy-config.json
    ls run/saves
    ```

14. Write down the `use_environmental_fog` value. A fresh `run/` has true.
    If the line is missing, the value is the default, true.
    `P1-ENV-FOG-DEFAULT` returns the setting to this value.
15. Run `P0-LOG-HEALTH`.

**Markers.**

```
Pause on lost focus: disabled
is now set to: false
I/S/AWSC:
UC/GC,#N:
[VOXY_FORCE_METAL] Voxy enabled on METAL backend
Toggled shaders to
```

The first two and the last are chat messages. The F3 lines are on screen.
Only `[VOXY_FORCE_METAL]` is in the log.

**Pass if.** `run/options.txt` shows `inactivityFpsLimit:"minimized"` and
`pauseOnLostFocus:false`. `iris.properties` shows `enableShaders=false` and
`shaderPack=BSL_v10.1.3.zip`. `run/saves` lists `voxytest`. You saw Voxy's
F3 lines and both gamerule answers.

**Fail if.** Voxy's lines are missing from F3 even with the row `debug` set
to *In Overlay*. A gamerule answers "Unknown or incomplete command". The two
settings are not saved in `run/options.txt`.

**A/B switch.** None.

**Notes.**

- `run/options.txt` keeps VSync as it was. Only the performance phase turns
  VSync off.
- If Voxy's F3 lines are missing, run
  `grep -F '[VOXY_FORCE_METAL] Voxy enabled on METAL backend' run/logs/latest.log`.
  If it finds nothing, the launch was missing `VOXY_FORCE_METAL=1`.

### P1-NOPACK-START: Start without a pack, start-up log lines, F3 lines, pack toggle

**Priority:** Essential. **Time:** 12 min. **Owner status:** Verified.
**Pack:** none, then BSL briefly. **Depends on:** `P1-SETUP`.

**Goal.** A regression test. The Metal path starts without a shader pack and
the world loads without a crash. It also checks that turning the pack on and
off with K does not break the LODs.

**Needs.** `P1-SETUP` done: the `voxytest` world exists, shaders are off, and
the row `debug` is set in F3+F6.

**Settings.**

- Shaders off, saved by K in `P1-SETUP`.
- The render distances from `P1-SETUP`: Minecraft 32, Voxy 2016.

**Command.**

```bash
VOXY_QUICKPLAY="voxytest" VOXY_FORCE_METAL=1 ./gradlew runClient -PrunXmx=6G
```

**Steps.**

1. Run the command. The world opens directly.
2. Optional: when the game window appears, start
   `bash scripts/testing/sampler.sh` in a second Terminal tab. It records
   memory and threads until the game exits.
3. Check that the world looks vanilla, without shaders. Terrain must continue
   past the vanilla distance to the horizon, with textured LODs and water, and
   without white or pale squares.
4. Stay still for 60 s without touching the mouse.
5. Press F3 and check Voxy's lines, among them `I/S/AWSC:` and `UC/GC,#N:`.
   If they are missing, press F3+F6, type `debug` in *Search...* and set the
   row `debug` to *In Overlay*. Then close F3.
6. Press K to turn BSL on, and wait 20 s. The LODs must come back with the
   pack's shading and without a crash.
7. Press K again to turn shaders off, and wait 20 s. Keep them off for the
   rest of phase 1.
8. Press *Esc > Save and Quit to Title > Quit Game*. Run `P0-LOG-HEALTH`.
9. Check the markers:

   ```bash
   L=run/logs/latest.log
   grep -F 'NormalRenderPipeline: shaders are off' "$L"
   grep -F 'trans split' "$L"
   grep -F -e 'Iris pack state changed' -e 'using pipeline' "$L"
   grep -cF '[Voxy-SYNC]' "$L"
   grep -F 'Metal device:' "$L"
   grep -F '[Metal-MEM] geometry arena' "$L"
   grep -F '[Metal-FLICKER' "$L" | tail -4
   ```

**Markers.**

```
[VOXY_FORCE_METAL] Voxy enabled on METAL backend
Metal native library loaded from
Using Metal render backend (Apple Silicon)
Metal device:
MSL 3.0 pinned (the macOS 13 floor)
[Metal-MEM] geometry arena
[Voxy-SYNC]
Voxy renders LODs with its NormalRenderPipeline: shaders are off.
[Metal-LODTEST] bound-mask trans split OFF
Iris pack state changed (gbufferInject
using pipeline 'MetalVxRenderPipeline'
[Metal-LODTEST] bound-mask trans split ON
[Metal-FLICKER f=
```

**Pass if.**

- The world loads without a crash and with textured LODs, and
  `P0-LOG-HEALTH` passes.
- Before K, the log has `shaders are off` and `bound-mask trans split OFF`.
- After K, it has `Iris pack state changed`,
  `using pipeline 'MetalVxRenderPipeline'` and `bound-mask trans split ON`.
- There are exactly 7 `[Voxy-SYNC]` lines. They print once per launch. The
  last one, `Iris shader define`, prints when K loads the pack.
- The `Metal device:` line ends in `MSL 3.0 pinned (the macOS 13 floor)`.
- The arena line reads `geometry arena <N> MB for <RAM> GB of unified
  memory`. N is RAM / 16, kept between 1024 and 4096 MB: 16 GB gives 1024,
  24 GB 1536, 32 GB 2048, 48 GB 3072, and 64 GB or more 4096.
- The `[Metal-FLICKER]` windows from the still minute show `min` equal to
  `max` and `changes=0`.

**Fail if.** A crash or a crash report on join, an empty horizon, or no
`using pipeline` line. Fewer than 7 `[Voxy-SYNC]` lines. `[Metal-FLICKER]`
with `min` different from `max` while the camera was still. K crashes the
game or leaves the world without LODs.

**A/B switch.** None.

**Notes.**

- Write down the time from join until the LOD horizon is complete.
- In F3, `UC/GC,#N:` shows the MB used against the capacity.
- Two "Voxy render system created" lines per join are normal (backlog item
  A20).
- Each `[Metal-FLICKER]` line covers about 600 frames. Windows from loading
  or moving can show `min` different from `max`. Only the still windows
  count.
- If the game crashes on join, run `P0-SHADERS` again.

### P1-ENV-FOG-DEFAULT: Enable environmental fog ON, the default for new users

**Priority:** Recommended. **Time:** 15 min. **Owner status:** New.
**Pack:** none. **Depends on:** `P1-NOPACK-START`.

**Goal.** Voxy's *Enable environmental fog* is ON by default for every new
user. The reference Mac's dev config has it OFF, so the team never tested ON.
This test turns it ON without a pack and checks the fog underwater, with
blindness and in rain. Then it returns the setting to the value you wrote
down in `P1-SETUP`.

**Needs.**

- `P1-NOPACK-START` passed.
- Shaders off.
- The `use_environmental_fog` value from `P1-SETUP`.

**Settings.** *Video Settings > Voxy > Rendering > Enable environmental fog*:
ON during the test, with Apply. At the end, back to the `P1-SETUP` value.

**Command.**

```bash
VOXY_QUICKPLAY="voxytest" VOXY_FORCE_METAL=1 VOXY_FRAME_TIMING=1 VOXY_DEBUG_CMD='5:time set noon;6:weather clear;7:effect give @p minecraft:water_breathing infinite' ./gradlew runClient -PrunXmx=6G
```

**Steps.**

1. Run the command and wait 30 s. The world opens without shaders.
2. Open *Video Settings > Voxy > Rendering*. Set *Enable environmental fog*
   to ON and press Apply, then Done. If it was already ON, set it OFF and
   press Apply first, then ON and Apply, so the rebuild runs. The LODs vanish
   and come back in about 20 s while the renderer rebuilds.
3. Look at the horizon and press F2.
4. Type `/locate biome minecraft:ocean`, click the coordinates in the answer
   and press Enter.
5. Dive 5 to 10 blocks and stay still for 20 s. The near water and the far
   field must fade into blue fog within a few dozen blocks. Press F2.
6. Go back up and stay out of the water for 20 s.
7. Type `/effect give @s minecraft:blindness 10`. Near terrain and far LODs
   must both darken within a few blocks, and come back when the effect ends.
8. Type `/weather rain`, wait 20 s, look at the horizon and press F2. Then
   type `/weather clear`.
9. Set *Enable environmental fog* to OFF. Press Apply, then Done.
10. Dive again for 20 s and press F2. With OFF the water looks clear, without
    fog near or far. That is expected.
11. Set *Enable environmental fog* back to your `P1-SETUP` value. If that
    value was ON (true), set it ON and press Apply.
12. Press *Esc > Save and Quit to Title > Quit Game*. Run `P0-LOG-HEALTH`.
13. In Terminal:

    ```bash
    grep -cF 'Voxy render system created' run/logs/latest.log
    grep -F '[Metal-TIMING' run/logs/latest.log | sed -E 's/^(.{10}).*(draws=[0-9]+).*/\1 \2/'
    grep use_environmental_fog run/config/voxy-config.json
    ```

**Markers.**

```
Voxy render system created with
Voxy renders LODs with its NormalRenderPipeline: shaders are off.
[Metal-TIMING f=
[VOXY_DEBUG_CMD] running:
```

**Pass if.** No Apply crashes the game, and each Apply adds a "Voxy render
system created with" line that names `NormalRenderPipeline`. With ON, there
is fog near and far underwater, blindness also darkens the LODs, and
`[Metal-TIMING]` shows `draws=` near 0 underwater and normal again after you
surface. With OFF, `draws=` stays normal underwater. `P0-LOG-HEALTH` passes
and `voxy-config.json` ends with your `P1-SETUP` value.

**Fail if.** A crash, or missing LODs after Apply with ON. In that case, run
`P0-SHADERS` again. LODs stay clear underwater or under blindness while near
terrain has fog. `draws=` stays high underwater with ON.

**A/B switch.** The menu setting itself, ON or OFF. `VOXY_UNDERWATER_LOD=1`
forces LODs to draw underwater.

**Notes.**

- Each `[Metal-TIMING]` line is an average over 600 frames. Match its time
  stamp to when you were underwater.
- Compare the horizon in clear weather and in rain, with ON and with OFF.
- Tell us whether ON looks better for daily play.

### P1-DOUBLE-WATER-AB: A/B of double water from the chunk-bound mask

**Priority:** Recommended. **Time:** 20 min. **Owner status:** Pending.
**Pack:** none. **Depends on:** `P1-NOPACK-START`.

**Goal.** Without a pack, an older change blended LOD water a second time
under vanilla water, and far LOD plants showed through leaves inside the
vanilla ring. The fix is in. This test compares it with the old behavior to
close backlog item B10.

**Needs.**

- Shaders off with K.
- A spot on solid ground, such as a hill or a cliff, that looks at a large
  body of water crossing the vanilla border, with trees or tall grass inside
  the ring. To find one, type `/locate biome minecraft:ocean`, click the
  coordinates and press Enter.
- The spot's `XYZ` and `Facing` (yaw and pitch) from F3. See
  [A fixed viewpoint](#the-dev-client).

**Settings.**

- Minecraft *Render Distance* 12 (*Video Settings > Sodium > General*, then
  Apply). The border between vanilla and LOD is then about 190 blocks away.
- When you finish, set *Render Distance* back to 32.
- Shaders off.

**Command.** In both commands, replace `X Y Z YAW PITCH` with your numbers.

Run A, the fix (default):

```bash
VOXY_QUICKPLAY="voxytest" VOXY_FORCE_METAL=1 VOXY_DEBUG_CMD='5:time set noon;6:weather clear;8:tp @p X Y Z YAW PITCH' ./gradlew runClient -PrunXmx=6G
```

Run B, the old behavior:

```bash
VOXY_BOUND_TRANS_SPLIT=all VOXY_QUICKPLAY="voxytest" VOXY_FORCE_METAL=1 VOXY_DEBUG_CMD='5:time set noon;6:weather clear;8:tp @p X Y Z YAW PITCH' ./gradlew runClient -PrunXmx=6G
```

**Steps.**

1. Run A: run the first command. If *Render Distance* is not 12, set it to 12
   and press Apply.
2. After the teleport, wait 60 s so Sodium finishes building. In F3,
   `Chunk Builder: Schd=` should be near 0. Then close F3.
3. Look at the water just inside the vanilla border. It must look like one
   layer of normal water.
4. Look at the leaves and grass inside the border. Through their gaps you
   must see only near terrain, never far LOD plants.
5. Press F2 three times.
6. Press *Esc > Save and Quit to Title > Quit Game*. Then:

   ```bash
   grep -F 'trans split' run/logs/latest.log
   mkdir -p ~/voxy-test-results && cp run/logs/latest.log ~/voxy-test-results/P1-DOUBLE-WATER-AB-A.log
   ```

7. Run B: run the second command. Use the same spot and the same 60 s wait,
   and press F2 three times.
8. Without closing the game, run in Terminal:
   `grep -F 'trans split' run/logs/latest.log`
9. Back in the game, press K to turn BSL on for phase 2. Set *Render
   Distance* back to 32 and press Apply. Then press *Esc > Save and Quit to
   Title > Quit Game*.
10. Run `P0-LOG-HEALTH`, then
    `cp run/logs/latest.log ~/voxy-test-results/P1-DOUBLE-WATER-AB-B.log`.
11. Compare the A and B screenshots in `run/screenshots/`. Check that
    `grep enableShaders run/config/iris.properties` prints
    `enableShaders=true`.

**Markers.**

```
[Metal-LODTEST] bound-mask trans split OFF
[Metal-LODTEST] bound-mask trans split ON
```

**Pass if.** Run A shows one layer of water and no LOD plants through the
gaps inside the ring, and its log says `bound-mask trans split OFF`. The
log of run B says `bound-mask trans split ON`. If B shows darker double water
or LOD plants inside the ring and A does not, the fix is confirmed and B10
can close.

**Fail if.** Run A shows double water or LOD plants inside the ring. If A and
B look the same, the spot is not conclusive. Try shallow water with seagrass,
or a shore with leaves, and note it.

**A/B switch.** `VOXY_BOUND_TRANS_SPLIT=all` brings back the old behavior.
`VOXY_BOUND_TRANS_SPLIT=0` turns the split off.

**Notes.** Compare the water color just inside and just outside the vanilla
border, and the gaps between leaves and grass near the border.

---

## Phase 2: Visuals with BSL

This phase checks far LODs with the BSL shader pack: the light at the border,
plants, water, fog, lights at night, zoom and shadows. All tests use the
`voxytest` world with BSL on. Only `P2-PACK-NO-CONTRACT` changes the pack or
the render path on purpose.

| ID | Test | Priority | Min |
|---|---|---|---|
| `P2-BSL-VISUALS` | Light seam, grass tufts, water depth and fog at LOD distance | Essential | 25 |
| `P2-NIGHT-BLOCKLIGHT` | Torch and lava light at LOD distance at night | Recommended | 15 |
| `P2-SPYGLASS-THRASH` | Repeated spyglass zoom and FOV changes | Recommended | 12 |
| `P2-PACK-NO-CONTRACT` | Shader pack without `voxy.json`: the g-buffer inject path | Recommended | 25 |
| `P2-PANES-TRIAD` | Pale water panes at the border: item, empty hand, F1 | Recommended | 15 |
| `P2-SHADOW-DISTANCE` | BSL Shadow Distance on the first LOD ring | Optional | 10 |
| `P2-FACE-OCCLUDE-AB` | A/B of the upstream mesher face rule on LOD-0 plants | Optional | 15 |

### P2-BSL-VISUALS: Light seam, grass tufts, water depth and fog at LOD distance

**Priority:** Essential. **Time:** 25 min. **Owner status:** Verified.
**Pack:** BSL. **Depends on:** `P1-NOPACK-START`.

**Goal.** Confirm that verified fixes still hold. No light seam at the
border, LOD grass tufts that look like vanilla ones, far water that darkens
with depth, and fog that does not look washed out.

**Needs.**

- BSL on. `grep -E 'enableShaders|shaderPack' run/config/iris.properties`
  must print `enableShaders=true` and `shaderPack=BSL_v10.1.3.zip`.
- BSL's LOD fog density at 0.25. In the game: O > *Shader Pack Settings... >
  Environment > Fog > More Fog Density Config > LOD Density*. Check it with
  the game closed: `grep FOG_DENSITY_LOD run/shaderpacks/BSL_v10.1.3.zip.txt`
  must print `FOG_DENSITY_LOD=0.25`.
- The `voxytest` world from `P1-SETUP`.

**Settings.**

- BSL with its default values, except *LOD Density* 0.25.
- Minecraft *Render Distance* 32.
- Leave Voxy's *Enable environmental fog* as it is.

**Command.**

```bash
VOXY_QUICKPLAY="voxytest" VOXY_FORCE_METAL=1 VOXY_DEBUG_CMD='5:time set noon;6:weather clear' ./gradlew runClient -PrunXmx=6G
```

**Steps.**

1. Seam: fly to about Y 150 over high ground. Look across the border between
   vanilla and LOD at noon and press F2.
2. Type `/time set 12500` for sunset. Look at the same border and press F2.
3. Tufts: type `/locate biome minecraft:plains`, click the coordinates and
   press Enter.
4. Stand on the ground and look at the grass at the vanilla border without
   zoom. Press F2.
5. Type `/give @s minecraft:spyglass`, hold right click and look at the same
   grass. Press F2.
6. LOD tufts past the border must look like the vanilla tufts inside: low,
   pale and sparse.
7. Water: type `/locate biome minecraft:deep_ocean` and go there. Climb to
   about Y 120 and look at the far water. It must darken with depth, with no
   bright seabed.
8. Look at a beach or a river in the distance. Shallow water must look
   lighter. Press F2.
9. Fog: type `/time set noon` and look at the horizon. Far LODs must blend
   into the sky, without a washed-out white band. Press F2.
10. Quit the game and run `P0-LOG-HEALTH`.
11. Check the markers:

    ```bash
    grep -F -e 'AO parity ON' -e 'plant cross bake ON' -e 'bake sampler' -e 'mip-aware cutout' -e 'edge-on plane fade' -e 'sky-light floor runtime' -e 'vx fog cap' -e 'using pipeline' run/logs/latest.log
    grep -cF 'disabling shaders' run/logs/latest.log
    ```

    The second command must print 0.

**Markers.**

```
using pipeline 'MetalVxRenderPipeline'
[Metal-LODTEST] vx LOD AO parity ON
[Metal-LODTEST] plant cross bake ON
[Metal-BAKE] block-atlas bake sampler NEAREST
[Metal-LODTEST] mip-aware cutout discard ON
[Metal-LODTEST] plant edge-on plane fade ON
[Metal-LODTEST] vx sky-light floor runtime
[Metal-LODTEST] vx sky-light floor follows the LOD water column
[Metal-LODTEST] vx fog cap ON
Failed to create shader rendering pipeline, disabling shaders!
```

**Pass if.** No light step at the border, tufts that look like vanilla, dark
far deep water, and a horizon that is not washed out. The log has
`using pipeline 'MetalVxRenderPipeline'`, `bake sampler NEAREST`, and the ON
lines for AO parity, plant cross bake, mip-aware cutout discard, plant
edge-on plane fade and vx fog cap. Every `loc=` on the
`vx sky-light floor runtime` lines is 0 or more. `disabling shaders` appears
0 times.

**Fail if.** The seam comes back, tufts look like dark bushes, or the seabed
shines under far deep water. The log has `Failed to create shader rendering
pipeline, disabling shaders!`, `loc=-1` or `vx fog cap FAILED`.

**A/B switch.** One per run, added in front of the command:

| Switch | Brings back |
|---|---|
| `VOXY_VX_MATERIAL_OPAQUE=0` | The light seam |
| `VOXY_VX_LOD_AO_PARITY=0` | Dark tufts |
| `VOXY_LOD_MIP_DISCARD=0` | Turns off mip-aware cutout discard |
| `VOXY_LOD_PLANT_EDGEFADE=0` | Turns off the plant edge-on fade |
| `VOXY_LOD_PLANT_CROSS=0` | Turns off the plant cross bake |
| `VOXY_BAKE_SAMPLER_LINEAR=1` | The linear bake sampler |
| `VOXY_VX_SKY_FLOOR_UNDERWATER=skip` or `=1` | The old water floor |
| `VOXY_VX_FOG_CAP=0` | Turns off the fog cap |

**Notes.** Known, not failures: LOD leaves look solid, and the LOD ring gets
no shadows past BSL's *Shadow Distance*.

### P2-NIGHT-BLOCKLIGHT: Torch and lava light at LOD distance at night

**Priority:** Recommended. **Time:** 15 min. **Owner status:** Verified.
**Pack:** BSL. **Depends on:** `P2-BSL-VISUALS`.

**Goal.** Confirm that torches and lava still glow in far LODs at night.
Areas that older code saved only update after `/voxy import current`.

**Needs.**

- BSL on.
- A lit area: a village, lava pools, or a grid of torches you build near
  spawn. To find a village, type `/locate structure minecraft:village_plains`.

**Settings.** Night. The command sets midnight.

**Command.**

```bash
VOXY_QUICKPLAY="voxytest" VOXY_FORCE_METAL=1 VOXY_DEBUG_CMD='5:time set midnight;6:weather clear' ./gradlew runClient -PrunXmx=6G
```

**Steps.**

1. Go to the lit area.
2. Fly 600 blocks or more away, climb to about Y 150 and look back.
3. The lights must show as warm, bright spots in the LOD field. Press F2.
4. If older code saved the area, type `/voxy import current`. Wait until the
   bar "Voxy import: N/M chunks" ends and the chat says "Voxy world import
   finished in". A world created for this track does not need it, but the
   import is worth testing once.
5. Look again and press F2.
6. Quit the game and run `P0-LOG-HEALTH`.
7. Run:
   `grep -F -e 'mip block-light packing' -e 'Voxy world import finished in' run/logs/latest.log`

**Markers.**

```
[Voxy-SYNC] mip block-light packing: upstream 0eb618d1 ON
Voxy world import finished in
```

**Pass if.** Torch and lava light shows at LOD distance, and the import
finishes if you ran it. The log has
`[Voxy-SYNC] mip block-light packing: upstream 0eb618d1 ON`.

**Fail if.** Lights stay dark at LOD distance, even after a reimport. The
import never finishes. The log says
`[Voxy-SYNC] mip block-light packing: PRE-SYNC`.

**A/B switch.** `VOXY_MIP_BLOCKLIGHT_FIX=0`. It writes the old dark mips into
the world's saved LODs. Afterwards, launch without it and run
`/voxy import current` to repair them.

**Notes.** Compare the brightness of the LOD lights with the vanilla lights
just inside the ring.

### P2-SPYGLASS-THRASH: Repeated spyglass zoom and FOV changes

**Priority:** Recommended. **Time:** 12 min. **Owner status:** Pending.
**Pack:** BSL. **Depends on:** `P2-BSL-VISUALS`.

**Goal.** Stress the zoom compensation and the FOV stability hold with very
fast spyglass use. Look for dark panels over water, giant blocks, or a hold
that never releases. This is stress item S4 in
[STRESS-TEST-PLAN.md](../STRESS-TEST-PLAN.md).

**Needs.**

- BSL on.
- An ocean horizon. Type `/locate biome minecraft:ocean` to find one.

**Settings.**

- FOV in *Options > FOV*. It goes from 30 to 110, and 70 is *Normal*.
- *Reduce FPS when* on *Minimized* (*Video Settings > Sodium > Performance*).

**Command.**

```bash
VOXY_QUICKPLAY="voxytest" VOXY_FORCE_METAL=1 VOXY_DEBUG_CMD='5:give @p minecraft:spyglass;6:time set noon;7:weather clear' ./gradlew runClient -PrunXmx=6G
```

**Steps.**

1. Stand facing the ocean horizon with the spyglass in your hand.
2. Zoom in and out 40 times in 2 minutes. Hold right click for about 1.5 s
   each time.
3. Then tap right click quickly 20 times.
4. Without zoom, open *Options > FOV*. Set it to 30, then 110, then 70.
5. Zoom in and stay still for 10 s. Look for dark, section-shaped panels over
   the water, or giant blocks.
6. Zoom out, wait 5 s and press F2.
7. Quit the game and run `P0-LOG-HEALTH`.
8. Run:

   ```bash
   grep -E 'FRUSTUM-DELTA|zoom compensation|Metal-FLICKER' run/logs/latest.log | tail -60
   grep -F '[Metal-FRUSTUM-DELTA]' run/logs/latest.log | tail -1
   grep -F 'LOD zoom compensation' run/logs/latest.log | tail -1
   ```

   The second command must show `fov stable`, and the third
   `LOD zoom compensation off`.

**Markers.**

```
[Metal-FRUSTUM-DELTA] fov UNSTABLE
[Metal-FRUSTUM-DELTA] fov stable
[Metal-LODTEST] LOD zoom compensation ON (zoomComp=
[Metal-LODTEST] LOD zoom compensation off (zoomComp=
[Metal-FLICKER f=
```

**Pass if.** No panels or giant blocks remain once the zoom settles, and fps
return to normal about 5 s after you zoom out. In the log, every
`fov UNSTABLE` is followed by a `fov stable`, every
`LOD zoom compensation ON` has its `LOD zoom compensation off`, and
`[Metal-FLICKER]` shows `min` equal to `max` in the still, zoomed windows.

**Fail if.** Panels or giant blocks that stay, fps that stay low after zoom
out, or a crash. A `fov UNSTABLE` that is never followed by `fov stable`.

**A/B switch.** `VOXY_VX_FOV_HOLD=0` turns the FOV hold off.
`VOXY_LOD_ZOOM_REFINE=1` uses upstream's zoom selection.

**Notes.** Known, not a failure (backlog item B7): with a pack, an FOV change
without the spyglass can print `fov UNSTABLE` and `fov stable` pairs every
frame. Note it if you see it.

### P2-PACK-NO-CONTRACT: Shader pack without voxy.json: the g-buffer inject path

**Priority:** Recommended. **Time:** 25 min. **Owner status:** New.
**Pack:** BSL, and a copy of BSL without Voxy files. **Depends on:**
`P2-BSL-VISUALS`.

**Goal.** Most packs do not ship `voxy.json`. With them, Voxy uses
`NormalRenderPipeline` and `IrisGbufferInjector` instead of the vx contract.
The team never tested this path, because BSL, Complementary and Sildurs all
ship `voxy.json`.

**Needs.**

- `P2-BSL-VISUALS` passed, with BSL on.
- For run B only, once: a copy of BSL without Voxy files. With the game
  closed, run:

  ```bash
  cp run/shaderpacks/BSL_v10.1.3.zip run/shaderpacks/BSL-novoxy.zip
  zip -d run/shaderpacks/BSL-novoxy.zip 'shaders/program/voxy*' 'shaders/world-1/voxy*' 'shaders/world0/voxy*' 'shaders/world1/voxy*'
  unzip -Z1 run/shaderpacks/BSL-novoxy.zip | grep -c voxy
  ```

  The last command lists only file names and must print 0. On the original
  `BSL_v10.1.3.zip` it prints 12.

**Settings.**

- BSL on.
- Run B: press O to open the pack list, pick the pack, press Apply, then
  Done.

**Command.**

Run A forces the inject path with BSL:

```bash
VOXY_METAL_VX_CONTRACT=0 VOXY_QUICKPLAY="voxytest" VOXY_FORCE_METAL=1 VOXY_DEBUG_CMD='5:time set noon;6:weather clear' ./gradlew runClient -PrunXmx=6G
```

Run B, optional, is a normal launch:

```bash
VOXY_QUICKPLAY="voxytest" VOXY_FORCE_METAL=1 VOXY_DEBUG_CMD='5:time set noon;6:weather clear' ./gradlew runClient -PrunXmx=6G
```

**Steps.**

1. Run A: run the first command.
2. From a hill, look across the border between vanilla and LOD. The LODs
   must show under the pack. Press F2.
3. Go to a cave entrance or under a large overhang near the vanilla border.
   You can also type `/locate biome minecraft:dripstone_caves` and fly in.
4. Wait until `Chunk Builder: Schd=` in F3 is near 0. Look around: no far
   terrain or sky may show through the rock. Press F2.
5. Over the sea near the vanilla border, tilt the view slowly up and down.
   There must be no pale panels and no LOD seabed through the near water.
   Press F2.
6. Press K, wait 20 s, press K again and wait 20 s. The LODs must come back
   each time.
7. Quit the game and run `P0-LOG-HEALTH`. Then:

   ```bash
   grep -F -e 'NormalRenderPipeline' -e 'using pipeline' -e 'trans split' -e 'Iris pack state changed' -e 'IrisGbufferInjector' run/logs/latest.log
   cp run/logs/latest.log ~/voxy-test-results/P2-PACK-NO-CONTRACT-A.log
   ```

8. Run B, optional: run the second command. BSL starts on the contract path.
9. Press O, pick `BSL-novoxy.zip`, press Apply and Done. Wait 30 s, repeat
   steps 2 to 5 quickly, and press F2.
10. Press O, pick `BSL_v10.1.3.zip`, press Apply and Done. Wait 30 s. The
    pack-shaded LOD look must come back. Press F2.
11. Quit and run the same `grep`. Then check that
    `grep -E 'enableShaders|shaderPack' run/config/iris.properties` ends
    with `enableShaders=true` and `shaderPack=BSL_v10.1.3.zip`.

**Markers.**

```
Voxy renders LODs with its NormalRenderPipeline: the active pack has no voxy.json, or its Iris pipeline is not built yet (g-buffer inject path).
[Metal-LODTEST] bound-mask trans split OFF
Iris pack state changed (gbufferInject
IrisGbufferInjector: inject failed
using pipeline 'MetalVxRenderPipeline'
Failed to create shader rendering pipeline, disabling shaders!
```

**Pass if.** Run A: the LODs show under BSL, nothing shows through rock, and
the log has the `NormalRenderPipeline` line, `bound-mask trans split OFF`
and one `Iris pack state changed` per K press, with no `inject failed` and
no `disabling shaders`. Run B: the pipeline follows the pack.
`NormalRenderPipeline` under `BSL-novoxy.zip`, and
`using pipeline 'MetalVxRenderPipeline'` back on BSL. `iris.properties` ends
on BSL with shaders on.

**Fail if.** LODs hidden under the pack, `IrisGbufferInjector: inject
failed`, LOD terrain through near rock after Sodium finished building, or a
crash. In run B, a pipeline that does not change with the pack.

**A/B switch.** `VOXY_METAL_VX_CONTRACT=0` forces this path with a pack that
has the contract. `VOXY_IRIS_GBUFFER_INJECT=0` turns the inject off, and the
LODs stay hidden under the pack.

**Notes.**

- Normal on this path: flatter LODs than with the contract, vanilla blue LOD
  water, and a light step at the border.
- Information only: the look of the LOD water and the border, and the fps in
  `[Metal-LayerB]`, compared with the contract path.
- If Iris prints `disabling shaders` with `BSL-novoxy.zip`, that copy is not
  a valid pack. Note it and keep the result of run A.

### P2-PANES-TRIAD: Pale water panes at the border: item, empty hand, F1

**Priority:** Recommended. **Time:** 15 min. **Owner status:** New.
**Pack:** BSL. **Depends on:** `P2-BSL-VISUALS`.

**Goal.** Check that pale, section-shaped panes over the sea near the
vanilla border, fixed on 2026-09-25, do not come back. They used to show
within 10 s when you swept the same view with an item in your hand, with an
empty hand, and with F1.

**Needs.** BSL on.

**Settings.** Minecraft *Render Distance* 32. The vanilla border is then
about 512 blocks away.

**Command.**

Run A:

```bash
VOXY_QUICKPLAY="voxytest" VOXY_FORCE_METAL=1 VOXY_DEBUG_CMD='5:time set noon;6:weather clear;7:give @p minecraft:dirt' ./gradlew runClient -PrunXmx=6G
```

Run B, the older inject path where the original fix lived:

```bash
VOXY_VX_MATERIAL_OPAQUE=0 VOXY_QUICKPLAY="voxytest" VOXY_FORCE_METAL=1 VOXY_DEBUG_CMD='5:time set noon;6:weather clear;7:give @p minecraft:dirt' ./gradlew runClient -PrunXmx=6G
```

**Steps.**

1. Run A: run the first command.
2. Find a coast: type `/locate biome minecraft:ocean`, click the coordinates
   and press Enter.
3. Fly to a beach where open sea runs straight past the vanilla border, and
   stand at the shore. Write down `XYZ` and `Facing` from F3, then close F3.
4. Item: select the dirt block with the scroll wheel or a number key. Look at
   the sea and tilt the view slowly from about 10 degrees down to 20 degrees
   up and back, over about 10 s. Press F2 at the top.
5. Empty hand: select an empty slot so the arm shows. Do the same sweep and
   press F2.
6. No hand: press F1 to hide the hand and the HUD. Do the same sweep, press
   F2, then press F1 again.
7. Type `/time set midnight` and repeat steps 4 to 6.
8. Quit the game and run `P0-LOG-HEALTH`. Then:

   ```bash
   grep -F '[Metal-CULL]' run/logs/latest.log
   cp run/logs/latest.log ~/voxy-test-results/P2-PANES-TRIAD-A.log
   ```

9. Run B: run the second command.
10. Go back to the same spot with `/tp @s X Y Z YAW PITCH`. Repeat steps 4 to
    6, at noon only.
11. Quit the game and run the same `grep`.

**Markers.**

```
[Metal-CULL] translucent-pass head: GL cull=
<- back faces of water WILL rasterise this frame
[Metal-CULL] inject cull restore ON
```

**Pass if.** In all three hand states, by day and by night, the sea across
the border is continuous, with no pale rectangles. The log of run B has
`[Metal-CULL] inject cull restore ON`.

**Fail if.** Panes in any hand state. Keep the three triad screenshots of
the state that fails.

**A/B switch.** `VOXY_VX_MATERIAL_OPAQUE=0 VOXY_GL_CULL_RESTORE=0` brings the
old state leak back, for diagnosis only. `VOXY_CULL_PROBE=0` turns the probe
off.

**Notes.**

- The `[Metal-CULL] translucent-pass head: GL cull=` lines are information
  only. The probe logs each change of the face-culling state, with the held
  item (`mainHand=`) and `hideGui=`. With BSL on, the lines are expected to
  say `GL cull=ON`.
- A `GL cull=OFF ... <- back faces of water WILL rasterise this frame` line
  is not a failure by itself. The README section
  [Log messages checked during testing](../../README.md#log-messages-checked-during-testing)
  explains why it shows without a pack. If you see one during a BSL run, add
  the line and the screenshot taken at that time to your report.
- Note it if one hand state looks different from the others, even slightly.

### P2-SHADOW-DISTANCE: BSL Shadow Distance on the first LOD ring

**Priority:** Optional. **Time:** 10 min. **Owner status:** Pending.
**Pack:** BSL. **Depends on:** `P2-BSL-VISUALS`.

**Goal.** LODs are not in the pack's shadow map, so past BSL's *Shadow
Distance* the LOD ring gets no shadows. Raise the value and record whether
the first LOD ring gains shadows and what it costs in fps. This test records
results. It does not pass or fail on looks.

**Needs.**

- BSL on.
- A spot with hills or forest at the border between vanilla and LOD, at 3 pm
  game time. The command sets the time to 9000.

**Settings.**

- *VSync* OFF and *Max Framerate* *Unlimited* (*Video Settings > Sodium >
  General*).
- *Reduce FPS when* on *Minimized* (Sodium *Performance* page).
- *Pause on lost focus* off. If it is on, press F3+P once.
- *Shadow Distance* in O > *Shader Pack Settings... > Lighting > Shadow
  Distance*. Start with the default value.
- Iris *Max Shadow Distance* (*Video Settings > Iris > Settings*) is 32,
  which is 512 blocks, on the reference Mac. Do not change it. It can cap
  BSL's *Shadow Distance*, which goes up to 1024. Write down your value.

**Command.**

```bash
VOXY_QUICKPLAY="voxytest" VOXY_FORCE_METAL=1 VOXY_FRAME_TIMING=1 VOXY_DEBUG_CMD='5:time set 9000;6:weather clear' ./gradlew runClient -PrunXmx=6G
```

**Steps.**

1. Close F3. Do not click Terminal while you measure.
2. With the default *Shadow Distance*, stay still for 20 s and press F2.
3. Set *Shadow Distance* to its highest value and press Apply. Stay still for
   20 s and press F2.
4. Set *Shadow Distance* back to the default.
5. Quit the game. Compare the shadows just past the vanilla border in the two
   screenshots.
6. Compare the fps of the two still windows:

   ```bash
   grep -F '[Metal-LayerB f=' run/logs/latest.log | sed -E 's/^(\[[0-9:]+\]).*(fps=[-0-9.]+).*/\1 \2/'
   ```

7. Write down Iris *Max Shadow Distance* with the result.

**Markers.**

```
[Metal-LayerB f=
[Metal-TIMING f=
Failed to create shader rendering pipeline, disabling shaders!
```

**Pass if.** You recorded whether shadows reach the first LOD ring, and the
fps difference. If the slider stops early or has no effect, record that too.

**Fail if.** Only a crash or `disabling shaders`.

**A/B switch.** None.

**Notes.** Watch the shadow coverage past the vanilla border, the
`[Metal-LayerB]` fps before and after, and whether Iris's 512-block *Max
Shadow Distance* limits BSL's slider.

### P2-FACE-OCCLUDE-AB: A/B of the upstream mesher face rule on LOD-0 plants

**Priority:** Optional. **Time:** 15 min. **Owner status:** Pending.
**Pack:** BSL. **Depends on:** `P2-BSL-VISUALS`.

**Goal.** An upstream change makes the mesher emit more faces. On Metal the
only visible difference is on the side faces of LOD-0 plants next to a
neighbor block that covers them.

**Needs.**

- A test scene near spawn: a flower or a crop in a slot 1 block wide between
  two full blocks.
- A spyglass: type `/give @s minecraft:spyglass`.

**Settings.**

- Minecraft *Render Distance* 8 (*Video Settings > Sodium > General*). The
  first LOD ring then starts at about 128 blocks.
- Set *Render Distance* back to 32 when you finish.

**Command.**

Run A:

```bash
VOXY_QUICKPLAY="voxytest" VOXY_FORCE_METAL=1 VOXY_DEBUG_CMD='5:time set noon;6:weather clear' ./gradlew runClient -PrunXmx=6G
```

Run B:

```bash
VOXY_MESH_FACE_OCCLUDE=0 VOXY_QUICKPLAY="voxytest" VOXY_FORCE_METAL=1 VOXY_DEBUG_CMD='5:time set noon;6:weather clear' ./gradlew runClient -PrunXmx=6G
```

**Steps.**

1. Run A: run the first command.
2. Stand 150 to 200 blocks from the scene, so it is in the first LOD ring.
   Write down `XYZ` and `Facing` from F3.
3. Look at the plant through the spyglass and press F2.
4. Quit the game and run
   `grep -F '[Voxy-SYNC] mesher face-occlusion predicate' run/logs/latest.log`.
5. Run B: run the second command. Go back to the same spot with
   `/tp @s X Y Z YAW PITCH`.
6. Look at the plant through the spyglass and press F2. Quit and run the same
   `grep`.
7. Compare the A and B screenshots.

**Markers.**

```
[Voxy-SYNC] mesher face-occlusion predicate
```

**Pass if.** In A the plant has no holes and no missing side faces. A and B
may look the same on Metal, and that is fine. The log says
`[Voxy-SYNC] mesher face-occlusion predicate: upstream 6212d95c/514d0a0e/89b3dacc ON`
in A and `PRE-SYNC` in B.

**Fail if.** A has holes or missing faces that B does not have.

**A/B switch.** `VOXY_MESH_FACE_OCCLUDE=0`. `VOXY_MESH_XFACE_FIX=0` has no
effect on Metal.

**Notes.** Look at the plant's side faces against the neighbor blocks.
