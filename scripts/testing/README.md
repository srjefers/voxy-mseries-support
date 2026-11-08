# Testing scripts

These scripts help you test Voxy on your Mac and report the results on
GitHub. They read your game folder. They never change it.

You need:

- A Mac with Apple Silicon and macOS 13 or newer.
- Terminal. The scripts use the `/bin/bash` that ships with macOS.
  You do not need Homebrew.
- Optional: a Java JDK for thread names in `sampler.sh`. The official
  launcher's Java already includes the tool it needs (`jcmd`).

Get the scripts from this repository: clone it, or use
*Code > Download ZIP* on GitHub. Players can also download only the five
files into `~/voxy-testing`, as shown in
[the player guide](../../docs/testing/player-track.md#5-get-the-test-scripts).
Keep the five files in one folder, because the scripts share `lib.sh`.

Run each script with `bash`, from any folder. The examples here use the
path inside the repository. With the player download, use
`~/voxy-testing/` instead of `scripts/testing/`:

```bash
bash scripts/testing/log-check.sh
bash ~/voxy-testing/log-check.sh
```

Every script has `--help`.

## The scripts

| Script | When to run it | What it does |
|---|---|---|
| `settings-dump.sh` | Before a test, game closed | Prints the saved settings that change results |
| `sampler.sh` | During a test, game open | Records memory, CPU and threads every 30 s |
| `log-check.sh` | After a test, game closed | Checks the log for known Voxy failures |
| `collect-report.sh` | At the end | Puts everything in one zip for your report |

### settings-dump.sh

Prints, without changing anything:

- Minecraft (`options.txt`): `enableVsync`, `maxFps`, `inactivityFpsLimit`,
  `pauseOnLostFocus`, `renderDistance`, `simulationDistance`, `guiScale`,
  `fullscreen`. Each line has a short note, for example "VSync ON".
- Voxy (`config/voxy-config.json`), with the names the Voxy menu uses.
  For example, `section_render_distance` 63 shows as "Render distance" 2016.
- Iris (`config/iris.properties`): `enableShaders` and `shaderPack`.
- How many files are in Voxy's shader cache, `~/.voxy/shader-cache`.

Run it with the game closed. The game saves these files when you press
Apply or quit.

### sampler.sh

Start the game first. Then run this in a second Terminal tab:

```bash
bash scripts/testing/sampler.sh
```

It finds the game's Java process and samples it until the game exits or
you press Ctrl+C. Each sample adds one line to
`~/voxy-test-results/sampler-<date>.csv`:

| Column | Meaning |
|---|---|
| `time` | Local time |
| `rss_mb` | Resident memory, from `ps` |
| `cpu_pct` | CPU use, from `ps`. 100 means one full core |
| `os_threads` | Threads the OS sees in the process |
| `footprint_mb` | Memory footprint, from `/usr/bin/footprint` |
| `pid` | Process ID, so two sessions in one file stay apart |

On the first sample and every 5th sample after it, it also writes Java
thread names, grouped and counted, to `sampler-<date>-threads.txt`.

Options: `--interval SECONDS` (default 30), `--threads-every N` (default 5,
0 turns it off), `--pid PID` (if your launcher is not detected; find the
`java` process in Activity Monitor), `--out DIR`.

If no game is running, it says so and exits with status 1.

### log-check.sh

Run it after you quit the game, or with the game on the title screen.
It reads `logs/latest.log` and prints:

- The Voxy version, the jar in `mods/`, the `Metal device:` line, and
  whether Voxy turned itself on.
- A count for each known failure message. Each count must be 0.
- How many model atlases were created and freed. After you quit, the two
  numbers must match. Inside a world, one atlas can still be open.
- Crash reports and `hs_err_pid*.log` files from the last 60 minutes.
- Info-only counts. These are **not** failures:
  - `[Metal-CULL] ... back faces of water WILL rasterise`: normal without a
    shader pack. See "Log messages checked during testing" in the main
    README.
  - `Viewport width or height was zero`: one per minimize.
  - `Voxy section saving still in progress`: saves that finished at quit.

Exit status: 0 means pass, 1 means at least one failure, 2 means the
script could not run (for example, no log found).

The next game launch overwrites `latest.log`. Run `collect-report.sh`
before you start the game again.

### collect-report.sh

```bash
bash scripts/testing/collect-report.sh --redact
```

It creates `~/voxy-test-results/report-<date>_<time>/` and a zip of it
next to the folder. The folder holds:

- `system.txt`: macOS version, Mac model, chip, memory, GPU cores, display
  resolution and power source.
- `versions.txt`: Minecraft, Fabric Loader, Fabric API, Voxy, Sodium, Iris,
  Java and Metal device, read from the log. Also the full mod list and the
  jar names in `mods/`.
- `log-check.txt` and `settings.txt`: the output of the two scripts above.
- `latest.log`: a copy of the log.
- The newest crash report and `hs_err_pid*.log`, only if they are less than
  60 minutes old.
- `sampler.sh` files changed in the last 2 hours.
- `screenshots.txt`: the names of screenshots from the last 2 hours. The
  screenshots are not copied. Attach the ones that show the problem yourself.

Attach the zip and your screenshots to your report on GitHub.

**Privacy.** The log contains your Minecraft name, file paths with your Mac
user name, and the addresses of servers you joined. `--redact` replaces your
home folder path, Mac user name and Minecraft name in the report copies. It
does not touch server addresses. Check the files before you share them.

## Which game folder the scripts use

All scripts except `sampler.sh` need your game folder. They pick it in this
order and print which one they chose:

1. The folder you pass as the first argument.
2. The `VOXY_GAME_DIR` environment variable.
3. The `run/` folder, when the scripts sit inside the Voxy repository.
   This is the dev client started with `./gradlew runClient`.
4. `~/Library/Application Support/minecraft`, the official launcher's
   default folder.

Pass the folder if you use another launcher, or a launcher profile with
its own "Game directory". Quote it, because these paths often have spaces:

```bash
bash scripts/testing/log-check.sh "$HOME/Library/Application Support/minecraft"
export VOXY_GAME_DIR="/path/to/your instance"
```

For Prism Launcher, the Modrinth App or CurseForge, use the instance's
"Open folder" button and pass that folder. For a Prism or MultiMC instance
you can pass the instance folder itself. The scripts find its `minecraft`
or `.minecraft` subfolder.

The scripts stop with a clear message if the folder has no
`logs/latest.log`. Start the game once with that folder first.

Results go to `~/voxy-test-results`. To use another folder, pass `--out DIR`
or set `VOXY_RESULTS_DIR`.

## A typical test session

1. Quit the game. Run `settings-dump.sh` and check the values the test asks
   for.
2. Start the game with these JVM arguments in your launcher profile:
   `-Xmx6G -Dvoxy.forceMetal=true` (`-Xmx4G` on a 16 GB Mac). Without
   `-Dvoxy.forceMetal=true`, Voxy turns itself off and you only see Sodium.
3. Optional: run `sampler.sh` in a second Terminal tab.
4. Do the test. Press F2 for screenshots. On a MacBook, press fn+F2.
5. Quit the game. Run `log-check.sh`.
6. Run `collect-report.sh --redact` and attach the zip to your report.

Before you measure frame rates:

- Set *Video Settings > Sodium > Performance > Reduce FPS when* to
  *Minimized*. With *AFK*, Minecraft drops to 30 fps after 60 seconds
  without input.
- Press F3+P in a world until the chat says "Pause on lost focus: disabled".
  Otherwise the game pauses when you click the Terminal.
- Turn VSync off. With VSync on, the frame rate snaps to 60, 40 or 30.
