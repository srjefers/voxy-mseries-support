# Player test track

This guide is for players who want to help test Voxy for Apple Silicon.
You use a normal launcher and the release jar. You do not need Gradle, a
JDK or a copy of the source code.

Each test has an ID that starts with `PT-`. Start with PT-01. After that, do
as many tests as you like, in any order. Most take 10 to 30 minutes.

Only one Mac has run the full test plan so far: an M4 Max with 48 GB on
macOS 26. Results from other chips, from 16 or 24 GB Macs and from macOS 13,
14 and 15 help the most. A pass on a new kind of Mac is as useful as a
failure.

Each test has a "Maps to" line. It names the matching tests in the
maintainers' full test plan, so your result lands in the right place.

## Contents

1. [What you need](#1-what-you-need)
2. [Install Fabric and the mods](#2-install-fabric-and-the-mods)
3. [Java arguments and memory](#3-java-arguments-and-memory)
4. [Find your game folder](#4-find-your-game-folder)
5. [Get the test scripts](#5-get-the-test-scripts)
6. [Game settings for testing](#6-game-settings-for-testing)
7. [After every session: report](#7-after-every-session-report)
8. [The tests](#8-the-tests)
9. [Troubleshooting](#9-troubleshooting)

## 1. What you need

- A Mac with Apple Silicon: M1 or newer. On Intel Macs, Voxy turns itself
  off.
- macOS 13 Ventura or newer.
- 16 GB of memory or more. 8 GB Macs are not supported (see PT-13).
- Minecraft: Java Edition 1.21.11.
- A launcher: the official Minecraft Launcher, Prism Launcher or the
  Modrinth App. CurseForge also works (see [Other launchers](#other-launchers)).
- Terminal, in *Applications > Utilities*. The scripts use the `bash` that
  ships with macOS. You do not need Homebrew.
- A GitHub account, to post reports.
- About 2 GB of free disk space.

### Mods

| Mod | Version | Needed for | Download |
|---|---|---|---|
| Fabric Loader | 0.18.2 | All tests | The Fabric Installer or your launcher |
| Fabric API | `0.140.0+1.21.11` | All tests | <https://modrinth.com/mod/fabric-api> |
| Sodium | `mc1.21.11-0.8.1`, file `sodium-fabric-0.8.1+mc1.21.11.jar` | All tests | <https://modrinth.com/mod/sodium> |
| Voxy for Apple Silicon | The newest pre-release, file `voxy-0.2.8-alpha-<commit>.jar` | All tests | <https://github.com/srjefers/voxy-mseries-support/releases> |
| Iris | `1.10.7+1.21.11-fabric`, file `iris-fabric-1.10.7+mc1.21.11.jar` | Optional: PT-03 and the shader steps | <https://modrinth.com/mod/iris> |
| BSL Shaders | 10.1.3, file `BSL_v10.1.3.zip` | Optional, with Iris | <https://modrinth.com/shader/bsl-shaders> |
| Chunky | 1.4.55, file `Chunky-Fabric-1.4.55.jar` | Optional: PT-11 | <https://modrinth.com/plugin/chunky> |

Read these before you download:

- **Sodium must be exactly 0.8.1.** Modrinth lists newer Sodium builds for
  1.21.11 first. Open the *Versions* tab and pick `mc1.21.11-0.8.1-fabric`.
  With any other Sodium, Fabric stops at launch with a dependency error.
- Do not update Sodium when your launcher offers it.
- If you add Iris from the mod browser inside Prism or the Modrinth App,
  the launcher may also install a newer Sodium for it. Check the mods list
  afterwards. Keep only Sodium `0.8.1` and remove the other one.
- Use the Fabric API and Iris versions above. They are the tested ones.
  A newer Iris may need a newer Sodium.
- BSL 10.1.3 is the tested shader pack. Other packs are welcome too: say
  which one and its version in your report.
- Do not install voxyworldgenv2 2.2.2. It is incompatible.
- Keep the test profile small: only the mods in this table. Other mods make
  results hard to compare.
- Use the jar from the Releases page only, not a patched or rebuilt one.
  Keep its file name. The part after `alpha-` is the commit, and every
  report needs it.

Use a separate profile and game folder for testing. The tests change
settings and create test worlds. Keep your normal worlds out of it.

## 2. Install Fabric and the mods

Pick your launcher. All launchers need the same mods and the same two Java
arguments from [section 3](#3-java-arguments-and-memory).

### Official Minecraft Launcher

1. Quit the Minecraft Launcher.
2. Download the Fabric Installer from <https://fabricmc.net/use/installer/>.
   Click *Download installer (Universal/.JAR)*.
3. Open the `.jar`. If macOS says it cannot verify the file, open
   *System Settings > Privacy & Security* and click *Open Anyway*.
   The installer needs Java. If it does not open, follow Fabric's macOS
   guide: <https://docs.fabricmc.net/players/installing-fabric/macos>.
   It also shows a Homebrew option.
4. In the installer, pick Minecraft Version `1.21.11` and Loader Version
   `0.18.2`. Keep *Create Profile* checked. Click *Install*.
5. Create a test game folder. In Terminal:

   ```bash
   mkdir -p "$HOME/voxy-test/mods"
   ```

6. Open the Minecraft Launcher. Go to *Installations > New installation*:
   - *Version*: `fabric-loader-0.18.2-1.21.11`.
   - *Game directory*: click *Browse* and pick the `voxy-test` folder in
     your home folder. In the file dialog, Cmd+Shift+H opens your home
     folder.
   - Click *More options*. In *JVM arguments*, change `-Xmx2G` to the value
     for your Mac from section 3. Add `-Dvoxy.forceMetal=true` at the end.
     Leave the other arguments as they are.
   - Click *Create*.
7. Copy the mod jars into the mods folder. This command opens it in Finder:

   ```bash
   open "$HOME/voxy-test/mods"
   ```

8. Play the new installation once, then quit. This creates
   `logs/latest.log`, which the scripts need.

Your game folder is `$HOME/voxy-test`. Keep the launcher's own Java. It is
Java 21 for Apple Silicon and includes `jcmd`, which `sampler.sh` uses for
thread names.

The official launcher has no setting for environment variables. Skip every
step marked "Prism or Modrinth App only".

### Prism Launcher

Use Prism 10.0.2 or newer if you want environment variables. Older versions
on macOS ignore them.

1. Click *Add Instance*. Pick Minecraft `1.21.11`. Under *Mod loader*, pick
   *Fabric* and version `0.18.2`. Name the instance `voxy-test`. Click *OK*.
2. Select the instance and click *Edit > Settings > Java*. If a field is
   greyed out, tick the checkbox of its group first.
   - *Maximum memory allocation*: the `-Xmx` value from section 3, in MiB.
     Use 4096 for 4 GB and 6144 for 6 GB.
   - *Java arguments*: `-Dvoxy.forceMetal=true`
   - Java: use Java 21 for Apple Silicon (arm64). If Prism offers to
     download Java, the Mojang build includes `jcmd`.
3. Add the mod jars: *Edit > Mods > Add file*. Or open the instance folder
   with the *Folder* button and copy the jars into `mods`.
4. Optional: *Edit > Settings > General > Enable Auto-join*, with the world
   name `voxytest`. The game then opens that world at launch.
5. Launch once, then quit.

Your game folder is
`$HOME/Library/Application Support/PrismLauncher/instances/voxy-test/minecraft`.
Older instances use `.minecraft` instead of `minecraft`.

Environment variables: *Edit > Settings > Environment Variables*. Add the
name and the value as two fields, for example `VOXY_FRAME_TIMING` and `1`.

### Modrinth App

1. Click *+* to create an instance. Name it `voxy-test`. Pick *Fabric* and
   game version `1.21.11`. If the dialog shows advanced options, pick loader
   version `0.18.2`.
2. Open the instance settings, then *Java and memory*:
   - *Custom memory allocation*: the `-Xmx` value from section 3. Use
     4096 MB for 4 GB and 6144 MB for 6 GB.
   - *Custom Java arguments*: `-Dvoxy.forceMetal=true`
   - *Custom environment variables*: optional. Write `KEY=VALUE` pairs
     separated by spaces, for example `VOXY_FRAME_TIMING=1`.
3. Open the instance folder with *Open folder* and copy the mod jars into
   `mods`. If you add Sodium from inside the app, pick
   `mc1.21.11-0.8.1-fabric`, not the newest version.
4. Launch once, then quit.

Your game folder is
`$HOME/Library/Application Support/ModrinthApp/profiles/voxy-test`.

The Modrinth App's Java has no `jcmd`. `sampler.sh` then records memory and
CPU but not thread names. That is fine. Report what it gives you.

### Other launchers

CurseForge and other launchers work too. Set the same memory and Java
arguments. In CurseForge they are in *Profile Options*: *Custom RAM
Allocation* and *Additional Arguments*. Find the instance folder with the
launcher's *Open Folder* button. CurseForge has no setting for environment
variables, so skip the steps marked "Prism or Modrinth App only".

## 3. Java arguments and memory

Every test profile needs these two Java (JVM) arguments:

```text
-Xmx6G -Dvoxy.forceMetal=true
```

- `-Dvoxy.forceMetal=true` turns on Voxy's Metal renderer. Without it, Voxy
  turns itself off and you only get plain Sodium. In Prism and the Modrinth
  App, the environment variable `VOXY_FORCE_METAL=1` does the same thing.
  The Java argument works in every launcher.
- `-Xmx` sets the Java memory limit. Pick it from your Mac's memory:

| Mac memory | `-Xmx` |
|---|---|
| 8 GB | Not supported. See PT-13 for an untested experiment. |
| 16 GB | `-Xmx4G` |
| 24 GB or more | `-Xmx6G` |

Do not go higher. On Apple Silicon the GPU uses the same memory as Java.
Every extra GB of Java heap is a GB the GPU cannot use.

Pick the graphics settings from your chip. To see your GPU core count, run
`system_profiler SPDisplaysDataType | grep -i cores` in Terminal.

| Chip | Minecraft render distance | Voxy render distance | Shader pack |
|---|---|---|---|
| M1 to M4, base (7 to 10 GPU cores) | 8 to 12 | 256 to 512 | None |
| M1 to M4 Pro (14 to 20 GPU cores) | 12 to 16 | 512 to 1024 | BSL with *Shadow Distance* 128 and *Shadowmap Quality* Low (1K) |
| M4 Max (the tested Mac, 40 GPU cores) | 32 | 2016 | BSL defaults |

Only the last row was measured. The others are estimates. Start from the
row closest to your GPU core count.

- Minecraft render distance: *Options... > Video Settings... > Sodium >
  General > Render Distance*.
- Voxy render distance: *Options... > Video Settings... > Voxy > Rendering >
  Render distance*. It is in chunks, in steps of 32. The default is 512.

F3 shows only the Java heap. Activity Monitor shows the whole `java`
process, which is much larger, because Voxy's GPU data lives in the same
memory. On the tested Mac the process uses about 12 to 13 GB in a world
with `-Xmx6G`. See
[unified memory](../../README.md#why-activity-monitor-shows-much-more-than-f3-unified-memory)
in the README.

## 4. Find your game folder

The scripts and the commands in this guide read your game folder from the
`VOXY_GAME_DIR` variable. Set it in each new Terminal window or tab:

```bash
export VOXY_GAME_DIR="$HOME/voxy-test"
```

Replace `$HOME/voxy-test` with your own folder:

| Launcher | Game folder |
|---|---|
| Official launcher, with the test installation from section 2 | `$HOME/voxy-test` |
| Official launcher, default folder | `$HOME/Library/Application Support/minecraft` |
| Prism Launcher | `$HOME/Library/Application Support/PrismLauncher/instances/<instance name>/minecraft` |
| Modrinth App | `$HOME/Library/Application Support/ModrinthApp/profiles/<instance name>` |
| CurseForge | `$HOME/Documents/curseforge/minecraft/Instances/<profile name>` |

Tip: type `export VOXY_GAME_DIR=` with no space after the `=`. Then drag
the folder from Finder into the Terminal window and press Return. Terminal
types the full path for you.

Check it:

```bash
ls "$VOXY_GAME_DIR/logs/latest.log"
```

If you see "No such file or directory", the folder is wrong, or the game
has not run in it yet.

What the game folder holds:

- `logs/latest.log`: the log of the last launch. The next launch overwrites
  it. The one before is kept as `logs/<date>-<n>.log.gz`.
- `mods/`, `options.txt`, `config/voxy-config.json`,
  `config/iris.properties`.
- `screenshots/`: your F2 screenshots.
- `saves/<world>/voxy/`: Voxy's far terrain data for that world.
- `crash-reports/` and `hs_err_pid*.log` files after a crash.

Voxy's shader cache is in `~/.voxy/shader-cache`, outside the game folder.
All profiles share it.

To open the game folder in Finder: `open "$VOXY_GAME_DIR"`.

## 5. Get the test scripts

Four scripts check your results and build the report. They read your game
folder. They never change it.

Download them into `~/voxy-testing`. Paste these lines into Terminal:

```bash
mkdir -p ~/voxy-testing && cd ~/voxy-testing
curl -fsSLO https://raw.githubusercontent.com/srjefers/voxy-mseries-support/dev/scripts/testing/lib.sh
curl -fsSLO https://raw.githubusercontent.com/srjefers/voxy-mseries-support/dev/scripts/testing/log-check.sh
curl -fsSLO https://raw.githubusercontent.com/srjefers/voxy-mseries-support/dev/scripts/testing/settings-dump.sh
curl -fsSLO https://raw.githubusercontent.com/srjefers/voxy-mseries-support/dev/scripts/testing/sampler.sh
curl -fsSLO https://raw.githubusercontent.com/srjefers/voxy-mseries-support/dev/scripts/testing/collect-report.sh
chmod +x *.sh
```

- `lib.sh` holds code the other four share. Keep all five files in the same
  folder.
- If `curl` stops with "The requested URL returned error: 404", check
  that you pasted each line whole. Or use the ZIP download below.
- Without `curl`: on the repository page, click *Code > Download ZIP*.
  Unzip it and copy the `scripts/testing` folder to `~/voxy-testing`.
- To update the scripts later, run the same lines again.

Run each script with `bash`:

| Command | When | What it does |
|---|---|---|
| `bash ~/voxy-testing/settings-dump.sh` | Game closed, before a test | Prints the saved settings that change results |
| `bash ~/voxy-testing/sampler.sh` | Game open, in a second Terminal tab | Records memory, CPU and threads every 30 s until the game exits |
| `bash ~/voxy-testing/log-check.sh` | After you quit | Checks the log for known Voxy failures. The last line says `Result: PASS` or `Result: FAIL` |
| `bash ~/voxy-testing/collect-report.sh --redact` | After `log-check.sh` | Builds one zip with the log, settings, system info and sampler data |

- All scripts use `$VOXY_GAME_DIR`. You can also pass the folder as the
  first argument.
- Results go to `~/voxy-test-results`. Open it with
  `open ~/voxy-test-results`.
- Each script has `--help`. The full description is in
  [scripts/testing/README.md](../../scripts/testing/README.md).

## 6. Game settings for testing

Set these once in your test profile.

1. *Options... > Video Settings... > Sodium > Performance > Reduce FPS
   when*: **Minimized**. Click *Apply*. With *AFK*, Minecraft drops to
   30 fps after 60 seconds without input, and to 10 fps after 10 minutes.
2. In a world, press F3+P until the chat says "Pause on lost focus:
   disabled". Otherwise the game pauses each time you click the Terminal.
3. VSync can stay on for the visual tests. For PT-12, set *Options... >
   Video Settings... > Sodium > General > VSync* to OFF and *Max Framerate*
   to *Unlimited*. With VSync on, the frame rate snaps to steps such as 60,
   40 and 30, which hides the real cost.
4. Voxy's F3 lines: press F3+F6 to open *Debug Options*. Type `debug` in
   *Search...* and set the `debug` row to *In Overlay*. Press Esc. Voxy's
   rows are named `debug` and `version`, so searching for "voxy" finds
   nothing.
5. On a MacBook keyboard, press fn with the F keys: fn+F3, fn+F3+P, fn+F2.
   You do not need fn if your Mac uses F1, F2 and so on as standard
   function keys (*System Settings > Keyboard*).
6. F2 saves a screenshot to the `screenshots` folder of your game folder.
   Press F1 first to hide the HUD.
7. With Iris: K turns shaders on and off, O opens the shader pack list and
   R reloads shaders.

### The test world

PT-01 creates the test world. Most other tests reuse it.

1. *Singleplayer > Create New World*. *World Name*: `voxytest`.
2. *Game* tab: *Game Mode* Creative, *Allow Cheats* ON. Click *Create New
   World*.
3. Press T and run these commands one at a time:

   ```text
   /gamerule advance_time false
   /gamerule advance_weather false
   /time set noon
   /weather clear
   ```

   Each gamerule answers "Gamerule ... is now set to: false". Time and
   weather then stay fixed, so screenshots compare well.

Double-tap Space to fly. `/locate biome minecraft:<name>` prints green
coordinates in the chat. Click them, then press Return to teleport there.

## 7. After every session: report

A session is one sitting on one Mac with one jar. It can include several
tests and several launches.

After **every launch**, before you start the game again:

1. Quit the game: *Esc > Save and Quit to Title > Quit Game*.
2. Check the log:

   ```bash
   bash ~/voxy-testing/log-check.sh
   ```

3. Build the report zip. It prints the zip's path.

   ```bash
   bash ~/voxy-testing/collect-report.sh --redact
   ```

At the end of the session, post one report:

1. Open the [test report form](https://github.com/srjefers/voxy-mseries-support/issues/new?template=test-report.yml).
2. Title: the test IDs and your Mac, for example
   `[Test] PT-01, PT-02 on M2, 16 GB, macOS 14`.
3. List every test ID you ran with its result:
   - **Pass**: every "Pass if" point held.
   - **Fail**: at least one "Fail if" point happened.
   - **Partial**: some steps passed and some could not be checked.
   - **Blocked**: you could not run the test. Say why.
4. Fill in your launcher, the Voxy jar name and, if used, Iris and the
   shader pack with versions.
5. Describe anything that differed from "Pass if": what you saw, where (F3
   coordinates) and when.
6. Attach every zip from the session and the screenshots that show the
   problem. Drag them into the form. Images can be up to 10 MB and zip
   files up to 25 MB. For flicker, a short screen recording made with
   Cmd+Shift+5 helps.

If the game crashed, run `collect-report.sh` right away. It adds the newest
crash report and `hs_err_pid*.log` file if they are less than 60 minutes
old.

**Privacy.** GitHub issues are public. The log holds your Minecraft name,
file paths with your Mac user name, and the addresses of servers you
joined. `--redact` replaces your home folder path, Mac user name and
Minecraft name in the report copies. It does not hide server addresses.
Screenshots with F3 open show your coordinates. Check each file before you
upload it.

### Not failures

These lines and effects are expected. Do not report them as failures.

- `[Metal-CULL] ... back faces of water WILL rasterise`: an info line
  without a shader pack. See
  [Log messages checked during testing](../../README.md#log-messages-checked-during-testing).
- `Viewport width or height was zero`: one per minimize.
- `Voxy section saving still in progress`: saves that finish at quit.
- Two `Voxy render system created` lines for each world join.
- A slow first launch with a new jar: Voxy compiles its shaders into
  `~/.voxy/shader-cache`.
- LOD leaves look solid from far away.
- With BSL, LODs get no shadows past BSL's *Shadow Distance*.
- The sky at the horizon has the fog color.
- LOD water can animate slightly out of step with nearby water.

## 8. The tests

| ID | Test | Time | Needs | Maps to |
|---|---|---|---|---|
| [PT-01](#pt-01-clean-start-without-shaders) | Clean start without shaders | 20 min | | P8-LAUNCHER-CLEAN, P1-NOPACK-START |
| [PT-02](#pt-02-f3-lines-and-the-opt-in) | F3 lines and the opt-in | 10 min | PT-01 | P1-SETUP, P8-LAUNCHER-CLEAN, P4-NO-OPTIN-FALLBACK |
| [PT-03](#pt-03-iris-and-bsl-optional) | Iris and BSL (optional) | 15 min | Iris, BSL | P8-LAUNCHER-WITH-PACK, P2-BSL-VISUALS |
| [PT-04](#pt-04-far-terrain-edge-grass-water-and-horizon) | Far terrain: edge, grass, water and horizon | 25 min | | P2-BSL-VISUALS, UC-06, UC-07 |
| [PT-05](#pt-05-rejoin-five-times-memory-and-threads) | Rejoin five times: memory and threads | 20 min | `sampler.sh` | P3-REJOIN-MEMORY |
| [PT-06](#pt-06-minimize-restore-and-resize) | Minimize, restore and resize | 10 min | | P3-MINIMISE, P3-RESIZE-STORM |
| [PT-07](#pt-07-voxy-settings-and-apply) | Voxy settings and Apply | 15 min | `sampler.sh` | P3-SODIUM-APPLY |
| [PT-08](#pt-08-render-distance-changes) | Render distance changes | 15 min | `sampler.sh` | P3-RENDER-DISTANCE |
| [PT-09](#pt-09-underwater) | Underwater | 20 min | | UC-01, UC-02, UC-03, P1-ENV-FOG-DEFAULT |
| [PT-10](#pt-10-caves) | Caves | 15 min | | UC-09, UC-10 |
| [PT-11](#pt-11-chunky-world-generation-optional) | Chunky world generation (optional) | 25 min | Chunky, a new world | 7.1, 7.5 |
| [PT-12](#pt-12-performance-snapshot) | Performance snapshot | 20 min | `sampler.sh`, VSync off | P5-AFK-CHECK, P5-BASELINE-SOAK |
| [PT-13](#pt-13-older-macos-and-smaller-macs) | Older macOS and smaller Macs | 30 min | macOS 13 to 15, or 16 to 24 GB | P8-OLDER-MACOS |

PT-02 to PT-10 use the `voxytest` world from PT-01. Run them without a
shader pack unless the test says otherwise. If you have Iris and BSL, you
can repeat any of them with shaders on. Say which in your report.

---

### PT-01: Clean start without shaders

- **Maps to:** P8-LAUNCHER-CLEAN, P1-NOPACK-START
- **Time:** about 20 minutes
- **Goal:** The release jar loads its own native libraries and starts Voxy
  on Metal in a normal launcher, with no shader pack.

**Before you start**

- Install everything as in section 2. For this test, leave Iris out of
  `mods`, or turn shaders off with K once in game.
- Optional: clear Voxy's shader cache, so the jar compiles every shader
  itself. The first launch then takes longer.

  ```bash
  rm -rf ~/.voxy/shader-cache
  ```

**Steps**

1. Launch the game. Note how long it takes to reach the title screen.
2. Create the test world `voxytest` and run its four commands (section 6).
3. Fly around for 2 to 3 minutes. Far terrain should reach the horizon,
   well past the normal render distance.
4. Stop. Look at the horizon for 60 seconds without touching anything.
   Watch for flicker. Press F1, then F2.
5. Press *Esc > Save and Quit to Title*, then *Play Selected World*. Do this
   twice. Then quit the game.
6. In Terminal:

   ```bash
   bash ~/voxy-testing/log-check.sh
   grep -E 'Metal native library|Using Metal render backend|VOXY_FORCE_METAL|Metal device:|geometry arena|using pipeline' "$VOXY_GAME_DIR/logs/latest.log"
   grep -n -A3 -E 'UnsatisfiedLinkError|NoClassDefFoundError|Failed to locate library' "$VOXY_GAME_DIR/logs/latest.log"
   ls "$VOXY_GAME_DIR/saves/voxytest/voxy"
   ls ~/.voxy/shader-cache | wc -l
   ```

**Pass if**

- Far terrain reaches the horizon, with textures and water, and without
  white or pale squares. Nothing flickers while you stand still.
- Both rejoins work.
- `log-check.sh` ends with `Result: PASS`, and its *Environment* part shows
  a `Metal device:` line.
- The first `grep` shows all of these:
  - `Metal native library loaded from`
  - `Using Metal render backend (Apple Silicon)`
  - `[VOXY_FORCE_METAL] Voxy enabled on METAL backend`
  - `Metal device: ... | MSL 3.0 pinned (the macOS 13 floor)`
  - `[Metal-MEM] geometry arena`
  - `using pipeline 'NormalRenderPipeline'`
- The second `grep` prints nothing.
- The world's `voxy` folder has files, and the shader cache count is above 0.

**Fail if**

- The game crashes, or far terrain never appears.
- The log has `UnsatisfiedLinkError`, a `NoClassDefFoundError` from Voxy,
  `Failed to locate library`, `Metal native library not bundled in jar` or
  `Metal native library not available`. Each means the jar is missing a
  library.
- `log-check.sh` ends with `Result: FAIL`.

An error from another mod in the second `grep` is not a Voxy failure. Name
that mod in your report.

**Attach**

- The report zip and 1 or 2 screenshots.
- The time to the title screen, and how long far terrain took to fill the
  horizon.
- The memory of `java` in Activity Monitor while you are in the world.

---

### PT-02: F3 lines and the opt-in

- **Maps to:** P1-SETUP (F3 part), P8-LAUNCHER-CLEAN (F3 and opt-in steps),
  P4-NO-OPTIN-FALLBACK
- **Time:** about 10 minutes
- **Goal:** Voxy shows its F3 lines and the right version. Without the
  opt-in, Voxy turns itself off and the game still works.

**Steps**

1. Launch and enter `voxytest`.
2. Press F3+F6. Type `debug` in *Search...*. Set the `debug` row to *In
   Overlay*. Press Esc.
3. Press F3 and find these lines:
   - `I/S/AWSC: ...`: Voxy's ingest queue, save queue and active sections.
   - `UC/GC,#N: ...`: far terrain memory used and capacity in MB, then the
     section count.
   - `voxy-0.2.8-alpha-<commit>` in green. The commit must match your jar's
     file name.
4. Press F2 with F3 open. Quit the game.
5. Remove `-Dvoxy.forceMetal=true` from the Java arguments. Also remove
   `VOXY_FORCE_METAL` if you set it as an environment variable.
6. Launch and enter `voxytest`. Check three things:
   - Terrain ends at the normal render distance. There is no far terrain.
   - *Options... > Video Settings...* has no Voxy entry.
   - In F3, the `voxy-...` version line is red.
7. Quit the game. Run:

   ```bash
   grep -F -e 'Voxy disabled on METAL backend' -e 'Voxy render system created' "$VOXY_GAME_DIR/logs/latest.log"
   ```

8. Put `-Dvoxy.forceMetal=true` back. All other tests need it.

**Pass if**

- With the opt-in, both Voxy lines show, and the version line is green with
  your jar's commit.
- Without it, the game runs normally with no far terrain and no Voxy menu.
  The `grep` shows `[M9 TRANSITIONAL] Voxy disabled on METAL backend` and no
  `Voxy render system created`.

**Fail if**

- The Voxy lines are missing while the `debug` row is set to *In Overlay*.
- The commit in F3 differs from the jar name.
- Without the opt-in, the game crashes, far terrain appears or the Voxy
  menu appears.

Version line colors: red means installed but off. Yellow means on, with no
world loaded. Dark green means a world is loaded but the renderer is not
running. Green means Voxy is rendering.

**Attach**

- The F3 screenshot and the `grep` output.

---

### PT-03: Iris and BSL (optional)

- **Maps to:** P8-LAUNCHER-WITH-PACK, P2-BSL-VISUALS (log checks)
- **Time:** about 15 minutes
- **Goal:** Voxy works with Iris and the BSL shader pack outside the
  developer setup.

**Before you start**

- PT-01 passed.
- Iris 1.10.7 is in `mods`. Launch once with it, so Iris creates the
  `shaderpacks` folder.
- `BSL_v10.1.3.zip` is in the `shaderpacks` folder of your game folder.
  Open it with `open "$VOXY_GAME_DIR/shaderpacks"`.
- On a 16 GB Mac this test is optional. Expect a lower frame rate.

**Steps**

1. Launch. Press O, or go to *Options... > Video Settings... > Shader
   Packs...*. Pick `BSL_v10.1.3.zip` and click *Apply*.
2. Lower BSL's fog for far terrain: *O > Shader Pack Settings... >
   Environment > Fog > More Fog Density Config > LOD Density*: 0.25.
   Without this, BSL washes far terrain out to white.
3. Enter `voxytest` at noon. Look at the far terrain. It should have BSL's
   shading, and no step in brightness where normal terrain ends. Press F2.
4. Run `/locate biome minecraft:deep_ocean` and teleport there. Fly to about
   Y 120. Far deep water should look dark. Press F2.
5. *Save and Quit to Title*, rejoin once, then quit the game.
6. Run:

   ```bash
   bash ~/voxy-testing/log-check.sh
   grep -F -e 'using pipeline' -e 'AO parity ON' -e 'sky-light floor runtime' -e 'vx fog cap' -e 'disabling shaders' -e 'inject failed' "$VOXY_GAME_DIR/logs/latest.log"
   grep enableShaders "$VOXY_GAME_DIR/config/iris.properties"
   ```

**Pass if**

- The log has `using pipeline 'MetalVxRenderPipeline'`,
  `[Metal-LODTEST] vx LOD AO parity ON` and `vx fog cap ON`.
- Every `loc=` value in the `vx sky-light floor runtime` line is 0 or
  higher.
- There is no `disabling shaders` and no `inject failed`. `log-check.sh`
  says PASS.
- `iris.properties` still says `enableShaders=true`.
- The picture looks like BSL, far terrain included.

**Fail if**

- Iris turns the pack off by itself.
- Far terrain is hidden or pale under the pack.
- A `loc=-1` value, or a `vx fog cap FAILED` line.
- The game crashes.

A single WARN line `vx fog cap: vx branch found but no Fog anchor` is not a
failure if a `vx fog cap ON` line is also there.

**Attach**

- The report zip, the screenshots from steps 3 and 4, and the `java` memory
  in Activity Monitor.

---

### PT-04: Far terrain: edge, grass, water and horizon

- **Maps to:** P2-BSL-VISUALS, UC-06, UC-07
- **Time:** about 25 minutes
- **Goal:** Far terrain looks right. No seam where it starts, grass like
  vanilla grass, deep water darker than shallow water and a clean horizon.

Do it without shaders. If you have Iris and BSL, repeat it with BSL on.
Name your screenshots by part, for example `PT-04-C-deep.png`.

**Steps**

A. The edge

1. At noon, fly to about Y 150 over high ground. Look across the edge
   where normal terrain ends and far terrain starts. Press F1, then F2.
2. Run `/time set 12500` for sunset. Same view, F2. Then run
   `/time set noon`.

B. Grass

3. Run `/locate biome minecraft:plains` and teleport there.
4. Stand on the ground. Look at the grass across the edge. F2.
5. Run `/give @s minecraft:spyglass`. Hold right click on the same grass.
   F2.

C. Water

6. Optional: set Minecraft's *Render Distance* to 8. Far terrain then
   starts at about 128 blocks, so you see more of it.
7. Run `/locate biome minecraft:deep_ocean` and teleport there. Fly to
   about Y 120. Look at the far water. F2.
8. Look at a beach or a river far away. F2.
9. Run `/locate biome minecraft:cold_ocean` for kelp and seagrass. If it
   finds nothing, try `minecraft:ocean` or `minecraft:lukewarm_ocean`. Hover
   at about Y 75 and look down at the kelp past the edge. F2.

D. The horizon

10. Run `/time set noon`. Look at the horizon. F2.
11. Set *Render Distance* back. Quit the game and run `log-check.sh`.

**Pass if**

- A: no step in brightness or color at the edge, at noon or at sunset.
- B: grass past the edge looks like the grass inside it: low, pale and
  scattered. Not dark bushes.
- C: far deep water never looks like bright sand. Beaches and riverbeds
  stay lit, not black. With BSL, far deep water is clearly darker than
  shallow water.
- C: no pale blue square tiles over kelp or seagrass. Far kelp looks like
  dark green, textured columns.
- D: far terrain blends into the sky, without a white washed-out band. With
  BSL, this needs *LOD Density* 0.25 from PT-03.
- `log-check.sh` says PASS.

**Fail if**

- Any point above is wrong. Name the part (A to D) and attach its
  screenshot.

**Attach**

- The screenshots, the F3 coordinates of anything odd and the report zip.

---

### PT-05: Rejoin five times: memory and threads

- **Maps to:** P3-REJOIN-MEMORY
- **Time:** about 20 minutes
- **Goal:** Leaving and rejoining a world does not leak memory or threads,
  and far terrain looks right in every session.

**Before you start**

- Start the game fresh for this test. The log counts below assume one
  launch with nothing else in it.
- "Pause on lost focus" is disabled (F3+P).
- Do not change any setting during the test.

**Steps**

1. Launch and enter `voxytest`.
2. Open a second Terminal tab with Cmd+T. Start the sampler there. It finds
   the game on its own:

   ```bash
   bash ~/voxy-testing/sampler.sh
   ```

   It prints one line every 30 seconds and stops when the game exits.
3. Stand still for 60 seconds. Press F2.
4. Do five cycles. In each one: *Esc > Save and Quit to Title*, wait 10
   seconds, then *Singleplayer > voxytest > Play Selected World*. Stand
   still for 60 seconds and press F2.
5. After the fifth cycle, *Save and Quit to Title*. Wait 30 seconds on the
   title screen and note the footprint the sampler prints. Then quit the
   game.
6. In the first tab:

   ```bash
   bash ~/voxy-testing/log-check.sh
   for m in 'Creating new world engine' 'Shutting down voxy instance' 'Voxy render system created'; do echo "$m: $(grep -cF "$m" "$VOXY_GAME_DIR/logs/latest.log")"; done
   grep -oE 'aux drained=[0-9]+' "$VOXY_GAME_DIR/logs/latest.log" | sort | uniq -c
   grep -h -E '^===|Dedicated Voxy Worker|Async Node Manager|Model factory processor' ~/voxy-test-results/sampler-*-threads.txt | tail -40
   ```

   If the last command says "no matches found" or "No such file or
   directory", `sampler.sh` had no `jcmd`. Skip the thread check and report
   memory only.

**Pass if**

- In the world, memory levels off: the last 3 in-world samples are within
  10% of each other. On the title screen it drops clearly.
- `Creating new world engine` is 6 and `Shutting down voxy instance` is 6:
  one per join and one per leave. `Voxy render system created` is 12, two
  per join.
- Every `aux drained=` value is 0.
- `log-check.sh` says PASS. That includes model atlas CREATED equal to
  FREED.
- The counts of `Dedicated Voxy Worker #`, `Async Node Manager` and
  `Model factory processor` are the same in every thread dump.
- Every screenshot shows textured, colored far terrain.

**Fail if**

- Memory grows by 1 GB or more per cycle and does not level off.
- Voxy's thread counts grow with each cycle.
- An `aux drained=` value above 0, or CREATED above FREED.
- White, pale or untextured far terrain from the second session on.
- The "Saving world" screen hangs.

The `os_threads` column grows by a few threads per join. Those are
Minecraft's network threads, and they stop at 32. That is normal.

**Attach**

- The report zip. It includes the sampler files.
- The footprint in the world and on the title screen.

---

### PT-06: Minimize, restore and resize

- **Maps to:** P3-MINIMISE, P3-RESIZE-STORM
- **Time:** about 10 minutes
- **Goal:** Voxy pauses while the window is minimized and comes back
  cleanly. Window size changes do not break far terrain.

**Steps**

1. Launch and enter `voxytest`.
2. Press Cmd+M, or click the yellow window button. Wait 30 seconds. Click
   Minecraft in the Dock. Watch the far terrain for 30 seconds. Do this 3
   times.
3. Press Cmd+Tab to switch to another app. Wait 30 seconds. Switch back.
4. Drag the window corner to about 5 different sizes, a few seconds apart.
5. Return the window to its normal size. Wait 60 seconds. Press F2.
6. *Save and Quit to Title*, then quit the game. Run:

   ```bash
   bash ~/voxy-testing/log-check.sh
   grep -oE 'aux drained=[0-9]+' "$VOXY_GAME_DIR/logs/latest.log" | sort | uniq -c
   ```

**Pass if**

- Far terrain comes back a few seconds after each restore and each resize,
  at the right size and place.
- No crash.
- `log-check.sh` says PASS. It also checks `composite disabled`,
  `FBO incomplete` and `bindToGlTexture failed`.
- Every `aux drained=` value is 0.

**Fail if**

- The game crashes when you restore it.
- Far terrain is missing, black or in the wrong place after a restore or a
  resize.

One `Viewport width or height was zero` line per minimize is normal.
`log-check.sh` lists it as info.

**Attach**

- The report zip, and how many seconds far terrain took to come back.

---

### PT-07: Voxy settings and Apply

- **Maps to:** P3-SODIUM-APPLY
- **Time:** about 15 minutes
- **Goal:** Changing several Voxy options in one *Apply* does not create a
  second renderer or extra threads.

Voxy's settings are in *Options... > Video Settings... > Voxy*. The
*General* page has *Enable Voxy*, *Service threads*, *Use sodium threads*
and *Chunk Ingest*. The *Rendering* page has *Voxy Rendering*, *Render
distance* and *Enable environmental fog*. Sodium's *Apply* button applies
every pending change at once.

**Steps**

1. Start the game fresh. Enter `voxytest` and wait 60 seconds.
2. In a second Terminal tab, start the sampler with a thread dump on every
   sample:

   ```bash
   bash ~/voxy-testing/sampler.sh --threads-every 1
   ```

3. Open Voxy's settings. On *General*, switch *Use sodium threads*. On
   *Rendering*, turn *Voxy Rendering* off. Click *Apply* once, then *Done*.
   Far terrain disappears. Near terrain stays.
4. Wait 20 seconds. Open the settings again. Set *Use sodium threads* back,
   turn *Voxy Rendering* on and click *Apply* once. Far terrain comes back.
5. Open the settings again. Switch *Use sodium threads* and halve
   *Rendering > Render distance*, for example from 512 to 256. Click
   *Apply* once. Wait 30 seconds.
6. Set both back with one *Apply*. Wait 30 seconds.
7. On *General*, turn *Enable Voxy* off and click *Apply*. Wait 20 seconds.
   Turn it on and click *Apply*. Far terrain comes back.
8. Still in the world, count the live renderers. It must print 1:

   ```bash
   L="$VOXY_GAME_DIR/logs/latest.log"; echo $(( $(grep -cF 'model atlas CREATED' "$L") - $(grep -cF 'model atlas FREED' "$L") ))
   ```

9. Quit the game. Run:

   ```bash
   bash ~/voxy-testing/log-check.sh
   grep -h -E '^===|Dedicated Voxy Worker' ~/voxy-test-results/sampler-*-threads.txt | tail -30
   ```

**Pass if**

- No crash and no `Cannot have multiple renderers`.
- Far terrain goes away and comes back as described.
- The count in step 8 is 1.
- `log-check.sh` says PASS.
- `Dedicated Voxy Worker #` changes with *Use sodium threads*, but returns to
  the same number for the same setting.

**Fail if**

- `Cannot have multiple renderers` appears, or the count is 2 or more.
- Far terrain does not come back.
- Voxy's worker threads grow with every change.

**Attach**

- The report zip and the output of step 8.

---

### PT-08: Render distance changes

- **Maps to:** P3-RENDER-DISTANCE
- **Time:** about 15 minutes
- **Goal:** Each render distance change rebuilds Voxy's renderer. It must
  not leak memory or create new world engines, and far terrain must fill
  in again.

**Steps**

1. Start the game fresh. Enter `voxytest`. Start `sampler.sh` in a second
   Terminal tab. Wait 60 seconds and note the footprint.
2. *Options... > Video Settings... > Sodium > General > Render Distance*:
   8. Click *Apply* and wait 30 seconds. Then set your usual value, then
   12, then your usual value again. Click *Apply* and wait 30 seconds each
   time.
3. *Options... > Video Settings... > Voxy > Rendering > Render distance*:
   half your value, for example 256. Click *Apply* and wait 60 seconds. Set
   it back, click *Apply* and wait 60 seconds.
4. After each change, check that far terrain fills in again. Press F2 at
   the end.
5. Still in the world, run the renderer count from PT-07 step 8. It must
   print 1.
6. Quit the game. Run:

   ```bash
   bash ~/voxy-testing/log-check.sh
   grep -cF 'Creating new world engine' "$VOXY_GAME_DIR/logs/latest.log"
   grep -F -e 'Tried inserting top level pos' -e 'Tried to reserve storage' "$VOXY_GAME_DIR/logs/latest.log"
   ```

**Pass if**

- Far terrain fills in again after every change.
- `Creating new world engine` is 1, for your one join.
- The renderer count is 1, and the last `grep` prints nothing.
- Memory returns close to the start value after you restore the settings.
- `log-check.sh` says PASS.

**Fail if**

- A crash, including Sodium's "Tried to reserve storage for more vertices
  in this buffer than it can hold".
- A new world engine for each change.
- Far terrain does not fill in again, memory rises with every change, or
  the renderer count is above 1.

`Voxy Metal: grew Sodium shared quad index buffer` lines are normal.

**Attach**

- The report zip, and how many seconds far terrain took to fill in after
  each change.

---

### PT-09: Underwater

- **Maps to:** UC-01, UC-02, UC-03, P1-ENV-FOG-DEFAULT
- **Time:** about 20 minutes
- **Goal:** Under water, far terrain stays hidden behind the water fog, the
  seafloor is solid and nothing flickers. The waterline does not flicker.

**Before you start**

- *Options... > Video Settings... > Voxy > Rendering > Enable
  environmental fog* is ON. That is the default.
- Shaders off for steps 1 to 4.

**Steps**

1. Run `/locate biome minecraft:deep_ocean` and teleport there. Wait 30
   seconds at the surface. F2.
2. Swim down about 10 blocks: hold Shift. Stay still for 30 seconds. Take
   three screenshots. Run each command, then press F2:

   ```text
   /tp @s ~ ~ ~ 0 0
   /tp @s ~ ~ ~ 0 -30
   /tp @s ~ ~ ~ 0 45
   ```

   They face straight ahead, 30 degrees up and 45 degrees down.
3. Swim along the bottom for 1 minute.
4. Float at the surface with your eyes at the waterline, facing the
   horizon, for 30 seconds. Then dive and come up 10 times, 1 to 2 seconds
   each.
5. With Iris and BSL: press K and repeat steps 1 to 4.
6. On land, run `/effect give @s minecraft:blindness 10`. Near and far
   terrain should both go dark, and come back when the effect ends.
7. Optional, Prism or Modrinth App only: add the environment variable
   `VOXY_UNDERWATER_LOD=1`, launch again and repeat step 2. It forces far
   terrain to draw under water. Note anything that shows up only with it:
   see-through sand, caves that look flooded, or flicker. Remove the
   variable afterwards.
8. Quit the game and run `log-check.sh`.

**Pass if**

- Under water: one even fog color in every direction. No far islands,
  coast or sky.
- The seafloor is solid. No see-through sand and no square holes.
- Nothing flickers while you stay still.
- At the waterline: no full-screen flicker while floating. A short haze of
  about 0.4 seconds after you come up is intended.
- With BSL: only BSL's water fog. No pale rectangles, no sky-colored holes
  and no ghost water squares at the waterline. Shaders are still on
  afterwards.
- Blindness darkens far terrain too.
- `log-check.sh` says PASS.

**Fail if**

- Any far terrain shape shows through the water fog.
- The seafloor is see-through or has holes.
- Flicker while you stay still, pale panes or ghost squares.
- The shaders turned off by themselves.

The frame rate under water should be the same as above water or higher.

**Attach**

- The screenshots and the report zip. For flicker, a short screen
  recording.

---

### PT-10: Caves

- **Maps to:** UC-09, UC-10
- **Time:** about 15 minutes
- **Goal:** Nothing shows through rock in caves. Far terrain may flash for
  a moment in gaps the game has not built yet, but it must vanish by itself.

**Steps**

1. Shaders off. Run `/locate biome minecraft:lush_caves`. Note its X Y Z.
2. Run `/gamemode spectator`, then `/tp @s X Y Z` with those numbers. Fly
   to an open cave room. Run `/gamemode creative` and wait 20 seconds.
3. Stand still. Take one screenshot per direction. Run each command, then
   press F2:

   ```text
   /tp @s ~ ~ ~ 0 0
   /tp @s ~ ~ ~ 90 0
   /tp @s ~ ~ ~ 180 0
   /tp @s ~ ~ ~ 270 0
   /tp @s ~ ~ ~ 0 -90
   /tp @s ~ ~ ~ 0 90
   ```

4. Fly through the tunnels for 2 minutes: first at normal speed, then
   sprinting.
5. Dig a 1x2 tunnel 8 to 10 blocks into the rock until you break into a
   cave you have not seen. Watch the opening for the first second. A screen
   recording (Cmd+Shift+5) catches one-frame flashes that F2 misses.
6. Run `/locate biome minecraft:dripstone_caves`, teleport there and watch
   the first 2 seconds. Repeat step 3 there.
7. With Iris and BSL: press K and repeat steps 2 to 6. Note whether brief
   flashes look bright, like daylight, or dark.
8. Quit the game and run `log-check.sh`. With BSL, also run
   `grep enableShaders "$VOXY_GAME_DIR/config/iris.properties"`. It must
   say `enableShaders=true`.

**Pass if**

- While you stand still, you only see cave walls. No far terrain, sky,
  water or far terrain shapes through walls, floor or ceiling.
- Any patch after a teleport or a new opening disappears in less than 1
  second.
- `log-check.sh` says PASS.

**Fail if**

- Something shows through solid rock while you stand still.
- A patch stays.
- With BSL, the shaders turned off by themselves.

Bright brief flashes with BSL are not a failure. Note them: they are an
open question. The frame rate underground is about the same as on the
surface. On Metal, Voxy does not skip hidden far terrain yet.

**Attach**

- The screenshots, any screen recording and the report zip.

---

### PT-11: Chunky world generation (optional)

- **Maps to:** 7.1, 7.5
- **Time:** about 25 minutes
- **Goal:** Every chunk that Chunky generates reaches Voxy, is saved and
  shows up as far terrain without you going there. After a rejoin, far
  terrain loads from disk.

**Before you start**

- Chunky 1.4.55 is in `mods`.
- Use a **new** world only. Never run this test on a world you care about.
- Shaders off. The `debug` row in F3 is set to *In Overlay* (section 6).

**Steps**

1. Create a new world named `voxy-chunky`: *Game Mode* Creative, *Allow
   Cheats* ON. On the *World* tab, leave *World Type* on Default. In the
   world, run `/gamerule advance_time false` and
   `/gamerule advance_weather false`. Then *Save and Quit to Title* and
   quit the game.
2. Note the folder sizes before:

   ```bash
   du -sh "$VOXY_GAME_DIR/saves/voxy-chunky/voxy" "$VOXY_GAME_DIR/saves/voxy-chunky/region"
   ```

3. Launch and enter `voxy-chunky`. Fly to about Y 200 above spawn and face
   the horizon. Do not touch anything for 2 minutes. Note the fps from F3.
   This is "before".
4. Press T and run these commands one at a time:

   ```text
   /chunky world minecraft:overworld
   /chunky spawn
   /chunky shape square
   /chunky radius 1000
   /chunky quiet 15
   /chunky start
   ```

   Chunky answers "Task started in minecraft:overworld for the square
   region centered at ... with radius 1000." If it says a task was already
   started, run `/chunky confirm`.
5. Stay still. In F3, the first number after `I/S/AWSC:` is Voxy's ingest
   queue and the second is its save queue. Far terrain should fill in piece
   by piece. Note the fps. This is "during".
6. When the chat says "Task finished for minecraft:overworld. Processed:
   ... (100.00%)", keep F3 open until both queue numbers reach 0. Note how
   many seconds that took.
7. Stay still 2 more minutes. Note the fps. This is "after". Press F2.
8. Run `/chunky cancel`, then `/chunky confirm`. This clears Chunky's saved
   task. Then *Save and Quit to Title*.
9. Run the `du` command from step 2 again.
10. Click *Play Selected World*. Wait 60 seconds without moving. Press F2.
    Quit the game.
11. Run `log-check.sh`.

**Pass if**

- Chunky reaches 100%. Both queues return to 0 within about 1 minute after
  it finishes.
- Far terrain covers the whole generated square, with no holes, without
  you moving.
- The fps "during" is at least half of "before". "After" is within 15% of
  "before".
- The `voxy` folder grows. On the tested Mac it grew by about 70 to 130 MB,
  and `region` by about 85 to 110 MB.
- After the rejoin, far terrain is back within about 60 seconds, with no new
  generation.
- `log-check.sh` says PASS.

**Fail if**

- The ingest queue keeps rising, or does not reach 0 within 2 minutes.
- The generated area shows no far terrain.
- The `voxy` folder does not grow.
- A crash, or a failure in `log-check.sh`.

**Attach**

- The report zip and the screenshots.
- The fps before, during and after, both `du` outputs, the seconds until
  the queues reached 0 and Chunky's last *Rate* line.

---

### PT-12: Performance snapshot

- **Maps to:** P5-AFK-CHECK, P5-BASELINE-SOAK
- **Time:** about 20 minutes
- **Goal:** Record frame rate and memory on your Mac with known settings,
  so results from different Macs can be compared.

**Before you start**

- Plug the Mac in, turn Low Power Mode off and close heavy apps. Let the
  Mac sit idle for a few minutes.
- *VSync* OFF and *Max Framerate* Unlimited. *Reduce FPS when*:
  Minimized. "Pause on lost focus" disabled.
- Keep the window the same size for the whole test. Note the size, or use
  fullscreen.
- Shaders on or off: either works. Say which in your report.
- Optional: to see fps with F3 closed, press F3+F6, type `fps` and set the
  `fps` row to *Always*.

**Steps**

1. With the game closed, check the settings:

   ```bash
   bash ~/voxy-testing/settings-dump.sh
   ```

   It must show `enableVsync:false` and `inactivityFpsLimit:"minimized"`.
2. Launch and enter `voxytest`. Run `/time set noon` and `/weather clear`.
3. Fly to a hill with a wide view of ocean and forest. Note the `XYZ` and
   `Facing` values from F3, so you can repeat the test later. Close F3.
4. Start `sampler.sh` in a second Terminal tab.
5. In the first tab, run `date +%T` and note the time.
6. Click once in the game window. Do not touch anything for 5 minutes.
7. Press F3. Note the lowest and highest fps over 10 seconds, and the
   `UC/GC,#N:` line. Press F2.
8. Quit the game. Run these, with your time from step 5 in place of
   `14:05:00`:

   ```bash
   bash ~/voxy-testing/log-check.sh
   START=14:05:00
   grep -F '[Metal-LayerB' "$VOXY_GAME_DIR/logs/latest.log" | sed -E 's/^\[([0-9:]+)\].*fps=([0-9.-]+).*/\1 \2/' | awk -v s="$START" '$1 >= s && $2 > 0'
   grep -F '[Metal-LayerB' "$VOXY_GAME_DIR/logs/latest.log" | sed -E 's/^\[([0-9:]+)\].*fps=([0-9.-]+).*/\1 \2/' | awk -v s="$START" '$1 >= s && $2 > 0 {print $2}' | sort -n | awk '{v[NR]=$1} END {if (NR) printf "median fps %s over %d windows\n", v[int((NR+1)/2)], NR; else print "no fps lines after START"}'
   ```

   Voxy logs the average fps every 600 frames. The first command lists
   them. The second prints the median.
9. Optional, Prism or Modrinth App only: set `VOXY_FRAME_TIMING=1` before
   you launch. After the test, run
   `grep -F '[Metal-TIMING' "$VOXY_GAME_DIR/logs/latest.log" | tail -5`
   and paste the output. It shows how long Voxy waits for the GPU per
   frame.

**Pass if**

- After the first 2 values, the fps values stay within about 10% of each
  other and never sit at 30.
- The `footprint_mb` values of the last 5 sampler rows differ by less than
  5%.
- No crash. `log-check.sh` says PASS.

**Fail if**

- The fps drops value after value.
- Memory keeps climbing.

If the fps sits at exactly 30 or 10, *Reduce FPS when* is on *AFK*. That is
Minecraft's limiter, not Voxy. Fix the setting and run the test again.

For reference, the tested M4 Max with BSL, Voxy render distance 2016 and
VSync on at 120 Hz had a median of about 37 fps at 2848x1284 and 61 to 75
fps at 1708x960.

**Attach**

Put these numbers in the report. `collect-report.sh` saves most of the
setup in `system.txt` and `settings.txt`.

- Chip, GPU cores, memory and macOS version.
- Window size or fullscreen, and the display.
- `-Xmx`, Minecraft render distance, Voxy render distance, shaders on or
  off.
- The median fps, the F3 fps range, where `footprint_mb` settled, and the
  usual `cpu_pct`.

---

### PT-13: Older macOS and smaller Macs

- **Maps to:** P8-OLDER-MACOS
- **Time:** about 30 minutes
- **Goal:** The Metal library loads on macOS 13, 14 and 15, the shaders
  compile, and Voxy sizes its memory to the Mac.

Run this test if your Mac has macOS 13, 14 or 15, or 16 to 24 GB of
memory.

**Settings**

- `-Xmx4G` on 16 GB, `-Xmx6G` on 24 GB.
- No shader pack.
- Minecraft render distance 8 to 12. Voxy render distance 256 to 512.

**Steps**

1. Do PT-01 with these settings.
2. Run:

   ```bash
   grep -E 'Metal native library|needs macOS 13|Metal device:|geometry arena|UnsatisfiedLinkError' "$VOXY_GAME_DIR/logs/latest.log"
   ```

3. With the world open, note the `java` memory in Activity Monitor and the
   fps from F3.
4. If the shaders fail to compile (no far terrain, errors about shaders or
   MSL in the log) and you use Prism or the Modrinth App: add the
   environment variable `VOXY_METAL_MSL_PIN=0`, launch again and repeat.
   The `Metal device:` line then says `newest the OS offers`. Report
   both runs. On the official launcher, skip this step and report the
   first run.

**Pass if**

- The log has `Metal native library loaded from` and
  `Metal device: ... | macOS 13.x | MSL 3.0 pinned (the macOS 13 floor)`,
  with your macOS version.
- The `geometry arena` line matches your memory: 1024 MB on 16 GB, 1536 MB
  on 24 GB, 2048 MB on 32 GB.
- Far terrain shows and the game does not crash.

**Fail if**

- `Metal native library not available:` appears.
- `Voxy's Metal renderer needs macOS 13 (Ventura) or newer` appears on macOS
  13 or newer.
- The shaders do not compile, or the game crashes.

**8 GB Macs** are not supported. If you want to try anyway, use
`-Xmx3G`, Voxy render distance 256 or less, Minecraft render distance 8 or
less and no shader pack. Report it as PT-13 with "8 GB experiment" in the
title.

**Attach**

- The report zip, two F2 screenshots, the `java` memory in Activity Monitor
  and the fps.

## 9. Troubleshooting

| Problem | What to do |
|---|---|
| Fabric shows an error screen about Sodium at launch | Use Sodium `mc1.21.11-0.8.1` exactly. Remove any other Sodium jar. |
| No far terrain, only plain Sodium | `-Dvoxy.forceMetal=true` is missing. In F3 the `voxy-...` line is red, and the log has `[M9 TRANSITIONAL] Voxy disabled`. |
| Voxy's lines are missing in F3 | Press F3+F6 and set the `debug` row to *In Overlay*. |
| The first launch is slow | Voxy compiles its shaders once and caches them in `~/.voxy/shader-cache`. |
| BSL makes far terrain white | Set BSL's *LOD Density* to 0.25 (PT-03 step 2). |
| The shader pack turned itself off | Iris turns the pack off when Voxy cannot build its renderer. Report it with the log. K or R tries again. |
| The game pauses when you click Terminal | Press F3+P until the chat says "Pause on lost focus: disabled". |
| fps sits at 30 after a minute | Set *Reduce FPS when* to *Minimized*. |
| fps jumps between 60, 40 and 30 | VSync is on. Turn it off for PT-12. |
| Activity Monitor shows much more memory than F3 | Normal. F3 shows only the Java heap. See section 3. |
| A script says "No logs/latest.log in this folder" | `VOXY_GAME_DIR` points to the wrong folder, or the game has not run there yet. See section 4. |
| `grep` or `ls` says `/logs/latest.log: No such file or directory` | This Terminal tab has no `VOXY_GAME_DIR`. Run the `export` line from section 4 again. |
| A script says "Run this script with bash" | Start it with `bash`, as in this guide. |
| `sampler.sh` finds no game | Start the game first. Or pass the process ID: find `java` in Activity Monitor and run `bash ~/voxy-testing/sampler.sh --pid <PID>`. |
| `curl` says error 404 | Paste each `curl` line whole. Or download the scripts with *Code > Download ZIP* (section 5). |
| You forgot to run `collect-report.sh` before the next launch | The previous log is in `logs/` as `<date>-<n>.log.gz`. Attach that file. |
