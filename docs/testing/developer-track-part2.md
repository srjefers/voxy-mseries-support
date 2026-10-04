# Developer track, part 2: lifecycle, failure paths, performance

This part covers phases 3, 4 and 5 of the test plan:

| Phase | Test IDs | Topic |
|---|---|---|
| 3 | `P3-*` | Lifecycle and memory: minimize, rejoin, option changes, reloads, dimensions and worlds |
| 4 | `P4-*` | Failure paths: a simulated renderer failure, and no opt-in |
| 5 | `P5-*` | Performance: fps, wait times and memory with a still camera |

Phases 0 to 2 are in [part 1](developer-track-part1.md). Phases 6 to 8 are in
[part 3](developer-track-part3.md). How to report is in the
[testing overview](README.md#how-to-report).

## Conventions for this part

### Where you run things

- Run every command from the repository root. All paths here are relative to
  it: `run/`, `scripts/testing/`.
- The dev client's game folder is `run/`. The testing scripts find it on their
  own when you run them from the repository. If you exported `VOXY_GAME_DIR`
  for a launcher, pass `run` as the first argument instead, for example
  `bash scripts/testing/log-check.sh run`.
- Results go to `~/voxy-test-results`. Create it once:
  `mkdir -p ~/voxy-test-results`.
- Run one game at a time.

### Launching the dev client

The standard launch for all three phases:

```bash
VOXY_QUICKPLAY="voxytest" VOXY_FORCE_METAL=1 ./gradlew runClient -PrunXmx=6G
```

- `VOXY_FORCE_METAL=1` is required. Without it, Voxy turns itself off.
  `P4-NO-OPTIN-FALLBACK` leaves it out on purpose.
- `VOXY_QUICKPLAY="<world folder>"` joins that world on launch.
- `-PrunXmx=6G` caps the Java heap like a launcher profile with `-Xmx6G`.
  Every memory comparison needs the same cap, so always use it.
- Put every variable on the same line, before `./gradlew`. Do not `export`
  them, so they do not leak into the next run.
- An A/B test adds one `VOXY_*` variable to the launch. Quit the game fully
  before you relaunch: *Esc > Save and Quit to Title > Quit Game*.
- The Gradle daemon is off, so every launch starts cold. That is normal.

Debug variables used in this part:

| Variable | What it does |
|---|---|
| `VOXY_DEBUG_CMD='5:time set noon;8:tp @p X Y Z YAW PITCH'` | Runs server commands a fixed number of seconds after each world join. Separate commands with `;`, write them without the leading `/`, and use `@p`, not `@s`. The log shows `[VOXY_DEBUG_CMD] running: /...` for each one. |
| `VOXY_DEBUG_REJOIN=45` | After 45 s in the world, leaves to the title screen and rejoins the `VOXY_QUICKPLAY` world. Once per launch. |
| `VOXY_AUTO_SCREENSHOT=20` | Takes a screenshot every 20 s into `run/screenshots/`. |
| `VOXY_FRAME_TIMING=1` | Adds `[Metal-TIMING]` lines with the frame's wait times, and the `[Metal-VXTIMING]` GPU pass timing. |

### Worlds and settings

- `voxytest` is the Creative world from the setup in part 1, with *Allow
  Cheats* ON and `/gamerule advance_time false` and
  `/gamerule advance_weather false`.
- Keep *Video Settings > Sodium > Performance > Reduce FPS when* on
  *Minimized*. With *AFK*, Minecraft drops to 30 fps after 60 s without input.
- Press F3+P in a world until the chat says "Pause on lost focus: disabled".
  Otherwise the game pauses when you click the Terminal.
- Before each phase, quit the game and check the saved settings. This is
  `P0-SETTINGS` from part 1:

  ```bash
  bash scripts/testing/settings-dump.sh
  ```

  For phases 3 and 4 you want `inactivityFpsLimit:"minimized"`,
  `pauseOnLostFocus:false`, `enableShaders=true` and
  `shaderPack=BSL_v10.1.3.zip`. Phase 5 also needs `enableVsync:false` and
  `maxFps:260` (Unlimited).

### Shader pack, keys and screenshots

- Iris 1.10.7 is always loaded in the dev client. Phases 3 and 5 run with BSL
  v10.1.3 on. Put `BSL_v10.1.3.zip` in `run/shaderpacks/`, as in part 1.
- `P3-IRIS-RECREATE` also needs Complementary Reimagined r5.8.1. Download it
  from its official page and put `ComplementaryReimagined_r5.8.1.zip` in
  `run/shaderpacks/`.
- K turns shaders on or off. The chat says "Toggled shaders to ...". O opens
  the pack list. R reloads shaders. F3+T reloads resources.
- K saves its state in `run/config/iris.properties`. Check it with
  `grep enableShaders run/config/iris.properties`.
- Voxy's F3 lines: press F3+F6, type `debug` in *Search...*, and set the row
  `debug` to *In Overlay*. Then F3 shows `I/S/AWSC:` and `UC/GC,#N:`.
  Searching `voxy` finds nothing.
- F2 saves a screenshot to `run/screenshots/`. Press F1 first to hide the HUD.
- On a MacBook, press fn with the F keys (fn+F3), unless you use F1, F2 and so
  on as standard function keys.

### Fixed viewpoint

Some tests compare screenshots or numbers from the same view.

1. Go to the spot and open F3.
2. Write down the three numbers of `XYZ:` and the two numbers in brackets on
   `Facing:`. They are the yaw and the pitch.
3. Add `VOXY_DEBUG_CMD='8:tp @p X Y Z YAW PITCH'` to the launch, with your
   numbers. Eight seconds after each join, the game puts you at that view.

### After every test

1. Quit to the title screen or quit the game.
2. Check the log. This is `P0-LOG-HEALTH` from part 1. Every failure count
   must be 0, and the last line must say `== Result: PASS`:

   ```bash
   bash scripts/testing/log-check.sh
   ```

   It also prints how many model atlases were created and freed. After you
   quit, the two numbers must match. Its "Info only" lines are not failures.
   See [Known non-errors](README.md#known-non-errors).
3. Run the extra commands the test lists. They read `run/logs/latest.log`.
4. Keep a copy of the log under the test ID. The next launch overwrites it.

   ```bash
   cp run/logs/latest.log ~/voxy-test-results/P3-MINIMISE.log
   ```

   Change `P3-MINIMISE` to the test you just ran. Tests with rounds add the
   round, for example `P5-LEVERS-AB-A1.log`.
5. At the end of the session, build the report zip and attach it to your
   [Test report](README.md#how-to-report) issue with your screenshots:

   ```bash
   bash scripts/testing/collect-report.sh --redact
   ```

   The zip holds the last log, the log check, your settings and the sampler
   files from the last 2 hours. Your named log copies are not redacted. Check
   them before you attach any of them.

### Memory and threads

Tests that track memory ask you to run the sampler in a second Terminal tab,
after the game window appears:

```bash
bash scripts/testing/sampler.sh
```

- Every 30 s it prints one line and appends it to
  `~/voxy-test-results/sampler-<date>.csv`, with `rss_mb`, `cpu_pct`,
  `os_threads` and `footprint_mb`. `footprint_mb` is the number Activity
  Monitor shows. F3 shows only the Java heap.
- It stops when the game exits or on Ctrl+C.
- Show the last samples: `tail -5 ~/voxy-test-results/sampler-$(date +%F).csv`.

Tests that compare threads add `--threads-every 1`. Then every sample also
writes Java thread names, grouped and counted, to
`~/voxy-test-results/sampler-<date>-threads.txt`. Each block starts with
`=== <time>  pid <PID>  sample <N>`. To follow Voxy's own threads over time:

```bash
grep -E '^#####|^===|Dedicated Voxy Worker|Async Node Manager|Model factory processor' ~/voxy-test-results/sampler-$(date +%F)-threads.txt
```

- Each `#####` line marks a new sampler run. Read the blocks under the newest
  one.
- When a step says "note a checkpoint", write down the time on the sampler's
  last line. Later, compare the blocks at those times.
- Compare blocks taken while you were in a world. On the title screen Voxy's
  workers are gone, which is normal.

Live renderers. Inside a world this must print 1:

```bash
echo $(( $(grep -cF 'model atlas CREATED' run/logs/latest.log) - $(grep -cF 'model atlas FREED' run/logs/latest.log) ))
```

If the game freezes, save a full thread dump before you force quit, and
attach it. `jcmd` comes with your JDK.

```bash
PID=$(bash -c '. scripts/testing/lib.sh && voxy_newest_game_pid') && jcmd "$PID" Thread.print > ~/voxy-test-results/hang-threads.txt
```

### Counting log lines

Several tests count how often lines appear. This prints one count per text.
A text that is missing from the output appeared 0 times.

```bash
grep -oF -e 'Render shutdown completed' -e 'Creating new world engine' run/logs/latest.log | sort | uniq -c
```

### Frame rate numbers

`[Metal-LayerB f=N]` lines carry the frame rate of each measuring window as
`fps=`. The first line can show `fps=-1.0`, which means nothing was measured
yet. Ignore it.

Frame rate over time:

```bash
grep -F '[Metal-LayerB' run/logs/latest.log | sed -E 's/^\[([0-9:]+)\].*(fps=-?[0-9.]+).*/\1 \2/'
```

Median frame rate of one log. Set `F` to the log you want:

```bash
F=run/logs/latest.log
grep -oE 'Metal-LayerB f=[0-9]+\] fps=[0-9.]+' "$F" | sed 's/.*fps=//' | sort -n | awk '{v[NR]=$1} END {if (NR) print "median fps " v[int((NR+1)/2)] " over " NR " windows"}'
```

### Reference numbers

Numbers such as fps and memory come from the reference Mac: an M4 Max with
48 GB on macOS 26. Your numbers will differ. Most pass rules compare your own
before and after values. Report your absolute numbers anyway.

**Owner status** is the result on the reference Mac, as in part 1:
*Verified* passed there, *Partial* passed in part, *Pending* is written but
not run yet, and *New* was never run. Pending and New tests are where your
help counts most. A Verified test still needs results from other Macs.

Some tests name items of the [backlog](../BACKLOG.md), such as `A21`, or of
the [stress test plan](../STRESS-TEST-PLAN.md), such as `S1`. You do not need
to read them to run the test.

---

## Phase 3: Lifecycle and memory

This phase tests everything that destroys and recreates Voxy's renderer:
minimizing, leaving and rejoining, changing options, reloading shaders or
resources, and moving between dimensions and worlds. It also checks that
memory and threads do not grow with each cycle.

Use the `voxytest` world with BSL on and `-PrunXmx=6G`. Keep *Reduce FPS
when* on *Minimized* and *Pause on lost focus* off, as `P1-SETUP` left them.

| ID | Test | Priority | Min | Pack |
|---|---|---|---|---|
| `P3-MINIMISE` | Minimize, restore and switch apps | Recommended | 5 | BSL |
| `P3-REJOIN-MEMORY` | Leave and rejoin 6 times, tracking memory and threads | Essential | 20 | BSL |
| `P3-SODIUM-APPLY` | *Voxy Rendering* and *Use sodium threads* in one Apply | Essential | 15 | BSL |
| `P3-RENDER-DISTANCE` | Minecraft and Voxy render distance changes | Recommended | 12 | BSL |
| `P3-RESIZE-STORM` | Window size, fullscreen and *GUI Scale* changes | Recommended | 12 | BSL |
| `P3-IRIS-RECREATE` | Iris pipeline rebuilds: R, a pack option, a pack change and K | Recommended | 15 | BSL, Complementary |
| `P3-RESOURCE-RELOAD` | F3+T and a resource pack | Recommended | 10 | BSL |
| `P3-VOXY-RELOAD` | `/voxy reload` five times in the world | Recommended | 15 | BSL |
| `P3-DIMENSION-HOPS` | Nether, End and Overworld hops | Recommended | 15 | BSL |
| `P3-WORLD-HOP` | Switch worlds and check the session 2 log lines | Recommended | 8 | BSL |
| `P3-SESSION2-BUILD-STALL` | Sodium build stall in session 2, *Use sodium threads* ON vs OFF | Recommended | 20 | BSL |
| `P3-GEOMETRY-GRANULE-AB` | Geometry memory with 128-element blocks | Optional | 12 | BSL |

Short on time? Do `P3-REJOIN-MEMORY`, `P3-SODIUM-APPLY` and `P3-VOXY-RELOAD`.

### P3-MINIMISE: Minimize, restore and switch apps

**Priority:** Recommended. **Time:** 5 min. **Pack:** BSL.
**Owner status:** Verified. **Depends on:** `P2-BSL-VISUALS`.

**Goal.** Check that Voxy skips its frames while the window is minimized and
comes back cleanly when you restore it. This is a regression test for a crash
that is already fixed.

**You need.** BSL on.

**Launch.** The standard launch.

**Steps.**

1. In the world, press Cmd+M or the yellow window button. Wait 30 s.
2. Click the Minecraft icon in the Dock to restore it. Watch the far
   landscape for 30 s.
3. Repeat steps 1 and 2 three times.
4. Switch to another app with Cmd+Tab, wait 30 s and come back to the game.
5. Quit and do the steps in [After every test](#after-every-test).

**Look for.**

- How many seconds the LODs take to come back after each restore.
- One `Viewport width or height was zero` line per minimize is normal. It
  can also show in the chat. `log-check.sh` counts it under "Info only".

**Log lines.**

```
Viewport width or height was zero, skipping Voxy frames until it is valid again
composite disabled
```

**Pass.** The LODs come back a few seconds after each restore. No crash and
no `composite disabled`.

**Fail.** The game closes on restore, LODs are missing after a restore, or
`composite disabled` appears.

### P3-REJOIN-MEMORY: Leave and rejoin 6 times, tracking memory and threads

**Priority:** Essential. **Time:** 20 min. **Pack:** BSL.
**Owner status:** Verified. **Depends on:** `P2-BSL-VISUALS`.

**Goal.** Check that leaving and rejoining the world leaks no memory,
textures or threads, and that LODs are not white or pale in the second
session. This is a regression test. Memory settled on the reference Mac on
2026-09-26.

**You need.**

- BSL on.
- The sampler with thread names, in a second Terminal tab.
- *Pause on lost focus* off.

**Settings.** Always use `-PrunXmx=6G`. Memory comparisons need the same heap
cap.

**Launch.**

```bash
VOXY_QUICKPLAY="voxytest" VOXY_FORCE_METAL=1 VOXY_DEBUG_REJOIN=45 VOXY_AUTO_SCREENSHOT=20 VOXY_DEBUG_CMD='5:time set noon;6:weather clear' ./gradlew runClient -PrunXmx=6G
```

In a second tab, after the game window appears:

```bash
bash scripts/testing/sampler.sh --threads-every 1
```

**Steps.**

1. Launch the game. When the window appears, start the sampler.
2. The game joins `voxytest`. After 45 s it leaves to the title screen and
   rejoins by itself. The log shows `[VOXY_DEBUG_REJOIN] leaving to title`,
   then `[VOXY_DEBUG_REJOIN] rejoining`.
3. Stay still 60 s after the rejoin. Note a checkpoint: "join 1".
4. Do 5 cycles by hand: *Esc > Save and Quit to Title*, wait 10 s, then
   *Singleplayer > voxytest > Play Selected World*. Stay still 60 s in the
   world each time.
5. After the fifth cycle, note a checkpoint: "join 6".
6. *Save and Quit to Title* and wait 30 s on the title screen. Write down the
   `footprint_mb` the sampler shows there. Then press *Quit Game*. The
   sampler stops by itself.
7. Do steps 1 and 2 of [After every test](#after-every-test). Then count the
   lifecycle lines and the `aux drained` values:

   ```bash
   grep -oF -e 'session GL objects released' -e 'Render shutdown completed' -e 'Shutting down voxy instance' -e 'Voxy render system created' -e 'Creating new world engine' run/logs/latest.log | sort | uniq -c
   grep -oE 'aux drained=[0-9]+' run/logs/latest.log | sort | uniq -c
   ```

8. Compare Voxy's thread counts at the "join 1" and "join 6" checkpoints with
   the thread command from [Memory and threads](#memory-and-threads).
9. Compare the automatic screenshots of each session in `run/screenshots/`.
10. Finish with steps 4 and 5 of [After every test](#after-every-test).

**Look for.**

- `rss_mb`, `footprint_mb` and `os_threads` in each cycle. On the reference
  Mac (48 GB) with `-PrunXmx=6G`, the footprint in the world stays around 12
  to 13 GB. Macs with less memory show less.
- This test has 7 joins (the first join, the automatic rejoin and 5 by hand)
  and 7 exits. Each join prints 2 `Voxy render system created` and 1
  `Creating new world engine`. Each exit prints 1
  `Shutting down voxy instance`. The 2 creations per join are a known issue,
  not a failure.
- Threads that must stay the same at both checkpoints:
  `Dedicated Voxy Worker #` (5 on the reference Mac), `Async Node Manager` (1),
  `Model factory processor` (1) and `Chunk Render Task Executor #` (10 on the
  reference Mac).
- Threads that change and are normal: `Netty Local IO #` grows by 3 per join
  up to 32. It belongs to Minecraft. `Thread-#` belongs to Chunky and drops
  after 5 minutes without use.
- The game sets noon and clear weather 5 s after each join on purpose, so the
  screenshots compare.

**Log lines.**

```
[VOXY_DEBUG_REJOIN] leaving to title
[VOXY_DEBUG_REJOIN] rejoining
IOSurfaceBridgeCompositor: session GL objects released (aux drained=
Render shutdown completed
Shutting down voxy instance
Voxy render system created with
Creating new world engine:
[Metal-ATLASLIFE] model atlas CREATED
[Metal-ATLASLIFE] model atlas FREED
```

**A/B.**

- `VOXY_GL_SESSION_RELEASE=0` brings back the aux texture leak.
- `VOXY_METAL_ARENA_RAM_SCALE=0` uses a fixed 4 GB geometry arena.
- `VOXY_POOL_SHUTDOWN_FINALLY=0` or `VOXY_SHUTDOWN_PER_STEP=0` restore the old
  shutdown path.

**Pass.** Memory in the world settles: the last 3 samples in the world are
within 10% of each other. Memory drops clearly on the title screen. Every
exit prints `aux drained=0`, CREATED equals FREED at the end, and Voxy's own
threads do not change. Every screenshot shows LODs with texture and color.

**Fail.** Memory grows 1 GB or more per cycle without settling, or Voxy's own
threads grow each cycle. Also a fail: CREATED greater than FREED,
`aux drained=` with a number above 0, white, pale or untextured LODs from
session 2 on, or a *Saving world* screen that hangs.

### P3-SODIUM-APPLY: Voxy Rendering and Use sodium threads in one Apply

**Priority:** Essential. **Time:** 15 min. **Pack:** BSL.
**Owner status:** Partial. **Depends on:** `P2-BSL-VISUALS`.

**Goal.** Check that two options that reload the renderer, applied together,
do not create a second renderer. Also test the thread split, and turning
*Enable Voxy* off and on inside the world.

**You need.**

- BSL on.
- The sampler with thread names, in a second Terminal tab.
- *Pause on lost focus* off.

**Settings.**

- Open *Options... > Video Settings...* and pick *Voxy* in the list on the
  left.
- *General* page: *Enable Voxy*, *Service threads*, *Use sodium threads* and
  *Chunk Ingest*.
- *Rendering* page: *Voxy Rendering*, *Pixels^2 of subdivision size* and
  *Render distance*.
- Sodium's *Apply* button applies all pending changes at once.

**Launch.** The standard launch. In a second tab, after the game window
appears:

```bash
bash scripts/testing/sampler.sh --threads-every 1
```

**Steps.**

1. Join the world and wait 60 s.
2. Open *Options... > Video Settings... > Voxy*. On *General*, flip *Use
   sodium threads*. On *Rendering*, turn *Voxy Rendering* off.
3. Press *Apply* once, then *Done*. The LODs disappear and near terrain stays.
4. Wait 20 s and open the menu again. Set *Use sodium threads* back, turn
   *Voxy Rendering* on, and press *Apply* once. The LODs come back.
5. Open the menu again. Flip *Use sodium threads*, lower *Rendering > Render
   distance* to about half (for example 2016 to 1024), and press *Apply* once.
6. Wait 30 s. Set both options back in a single *Apply*.
7. Optional: on *General*, turn *Enable Voxy* off, press *Apply* and wait
   20 s. Then turn it on and press *Apply*.
8. Still in the world, count the live renderers with the command from
   [Memory and threads](#memory-and-threads). It must print 1.
9. Check Voxy's thread counts over the test with the thread command from
   [Memory and threads](#memory-and-threads).
10. Quit and do the steps in [After every test](#after-every-test).

**Look for.**

- Each *Apply* leaves a pair of `Voxy render system created` and
  `Render shutdown completed`.
- `Shutting down voxy instance` only appears when you turn *Enable Voxy* off.
- The count of `Dedicated Voxy Worker #` changes with *Use sodium threads*,
  but it must always return to the same value for the same setting.
- Flipping *Use sodium threads* alone passed on 2026-09-26. The new part is
  combining it with another option in the same *Apply*.

**Log lines.**

```
Voxy render system created with
Render shutdown completed
[Metal-ATLASLIFE] model atlas CREATED
[Metal-ATLASLIFE] model atlas FREED
Cannot have multiple renderers
Shutting down voxy instance
```

**Pass.** No crash and no `Cannot have multiple renderers`. The LODs turn off
and come back as expected, and turning *Enable Voxy* back on restores them.
The live renderer count is 1 in the world, and Voxy's threads do not grow as
you repeat the changes.

**Fail.** The `IllegalStateException` `Cannot have multiple renderers`
appears, or the live renderer count is 2. Also a fail: the LODs do not come
back when you turn them on again, or Voxy's threads grow with each change.

### P3-RENDER-DISTANCE: Minecraft and Voxy render distance changes

**Priority:** Recommended. **Time:** 12 min. **Pack:** BSL.
**Owner status:** Pending. **Depends on:** `P3-REJOIN-MEMORY`.

**Goal.** Each change of Minecraft's render distance destroys and rebuilds
Voxy's whole renderer. Check that this leaks no memory, creates no new world
engines, and that the LOD ring fills again.

**You need.**

- BSL on.
- The sampler in a second Terminal tab.
- *Pause on lost focus* off.

**Settings.**

- Minecraft: *Options... > Video Settings... > Sodium > General > Render
  Distance*. Press *Apply* after each value.
- Voxy: *Options... > Video Settings... > Voxy > Rendering > Render
  distance*. The menu shows it in chunks, in steps of 32.
- Write down both starting values. `settings-dump.sh` shows them as
  `renderDistance` and "Render distance".

**Launch.** The standard launch. In a second tab, after the game window
appears:

```bash
bash scripts/testing/sampler.sh
```

**Steps.**

1. Join the world, wait 60 s and write down `footprint_mb`.
2. Set Minecraft's *Render Distance* to 8, press *Apply* and wait 30 s. Then
   32, then 12, then your starting value, with *Apply* and a 30 s wait each
   time.
3. Set Voxy's *Render distance* to 512, press *Apply* and wait 60 s. Then
   set your starting value (2016 on the reference Mac), press *Apply* and wait
   60 s.
4. After each change, check that the LOD ring fills again. Press F2 at the
   end.
5. Still in the world, count the live renderers with the command from
   [Memory and threads](#memory-and-threads). It must print 1.
6. Quit and run `log-check.sh`. Then:

   ```bash
   grep -cF 'Creating new world engine' run/logs/latest.log
   grep -F -e 'Sodium shared quad index' -e 'Tried inserting top level pos' run/logs/latest.log
   ```

7. Finish with steps 4 and 5 of [After every test](#after-every-test).

**Look for.**

- How many seconds the ring takes to fill after each change.
- `footprint_mb` at each change.
- Each change prints `Render shutdown completed` and a new pair of atlas
  CREATED and FREED.
- At 32 you can see `Voxy Metal: grew Sodium shared quad index buffer via
  uploadData to N primitives`. That is normal.

**Log lines.**

```
Render shutdown completed
[Metal-ATLASLIFE] model atlas CREATED
[Metal-ATLASLIFE] model atlas FREED
Creating new world engine:
Voxy Metal: grew Sodium shared quad index buffer via uploadData to
Tried inserting top level pos
```

**A/B.** `VOXY_RD_PROCESS_RATE=20` restores the ring fill speed from before
the upstream sync.

**Pass.** Each change rebuilds the renderer and the LOD ring fills again, but
`Creating new world engine` appears only once, at the join. The live renderer
count is 1, `Tried inserting top level pos` does not appear, and memory comes
back near the starting value once the settings are restored.

**Fail.** A crash, including Sodium's `Tried to reserve storage for more
vertices in this buffer than it can hold`, or a new
`Creating new world engine` for each change. Also a fail: the ring does not
fill again, memory grows with each change, or the live renderer count is
above 1.

### P3-RESIZE-STORM: Window size, fullscreen and GUI Scale changes

**Priority:** Recommended. **Time:** 12 min. **Pack:** BSL.
**Owner status:** Pending. **Depends on:** `P3-REJOIN-MEMORY`.

**Goal.** Each size change recreates the surfaces that Metal and GL share.
Check that no change leaves the LODs off for good and that no textures leak.

**You need.**

- BSL on, so the test also covers the textures Voxy shares with the pack.
- The sampler in a second Terminal tab.
- *Pause on lost focus* off.

**Settings.** *GUI Scale* is in *Options... > Video Settings... > Sodium >
General*. Write down your value first. `settings-dump.sh` shows it as
`guiScale` (0 means Auto).

**Launch.**

```bash
VOXY_QUICKPLAY="voxytest" VOXY_FORCE_METAL=1 VOXY_AUTO_SCREENSHOT=15 ./gradlew runClient -PrunXmx=6G
```

In a second tab, after the game window appears:

```bash
bash scripts/testing/sampler.sh
```

**Steps.**

1. Join the world, wait 60 s and write down `footprint_mb`.
2. Press F11 10 times, 1 s apart.
3. Drag the window corner through about 10 different sizes.
4. Set *GUI Scale* to 1, then 4, then Auto. Then set your own value again.
5. Return the window to its original size, wait 60 s and press F2. Keep this
   window size until you finish phase 5.
6. *Save and Quit to Title*, so the `aux drained` line prints. Then press
   *Quit Game*.
7. Run `log-check.sh`. Then:

   ```bash
   grep -F -e 'side-channel ready' -e 'aux drained' -e 'survived their bridge close' -e 'composite disabled' -e 'FBO incomplete' -e 'bindToGlTexture failed' run/logs/latest.log | tail -20
   grep -cF 'aux bridge bound to GL tex' run/logs/latest.log
   ```

8. Finish with steps 4 and 5 of [After every test](#after-every-test).

**Look for.**

- The last `VxIrisSideChannel: vx depth side-channel ready (` line must show
  the final window size in pixels.
- `aux rect texture(s) survived their bridge close` must not appear.
- How many `aux bridge bound to GL tex` lines there are. They grow with each
  size change, which is normal.

**Log lines.**

```
VxIrisSideChannel: vx depth side-channel ready (
IOSurfaceBridgeCompositor: session GL objects released (aux drained=
aux rect texture(s) survived their bridge close
IOSurfaceBridgeCompositor: aux bridge bound to GL tex
composite disabled
bindToGlTexture failed
FBO incomplete
```

**A/B.** `VOXY_GL_SESSION_RELEASE=0`.

**Pass.** After the storm, the LODs have the right size and position, and
there is no `composite disabled`, `bindToGlTexture failed` or
`FBO incomplete`. The exit prints `aux drained=0`, and memory is back near the
starting value one minute after the storm.

**Fail.** The LODs stay black or empty after a size change, or
`aux drained=` shows a number above 0. Also a fail: memory grows hundreds of
MB per change and does not come down.

### P3-IRIS-RECREATE: Iris pipeline rebuilds: R, a pack option, a pack change and K

**Priority:** Recommended. **Time:** 15 min. **Pack:** BSL and
Complementary Reimagined.
**Owner status:** Pending. **Depends on:** `P3-REJOIN-MEMORY`.

**Goal.** Each Iris reload creates a new pipeline, and Voxy must attach to it.
Check that the LODs do not turn pale, white or sky blue, and that the pack
does not turn itself off.

**You need.**

- BSL on.
- `ComplementaryReimagined_r5.8.1.zip` in `run/shaderpacks/`. See
  [Shader pack, keys and screenshots](#shader-pack-keys-and-screenshots).
- A [fixed viewpoint](#fixed-viewpoint). Do not move during the test, so the
  screenshots compare.

**Settings.** O opens the pack list. *Shader Pack Settings...* opens the
pack's options.

**Launch.**

```bash
VOXY_QUICKPLAY="voxytest" VOXY_FORCE_METAL=1 VOXY_AUTO_SCREENSHOT=10 VOXY_DEBUG_CMD='5:time set noon;6:weather clear' ./gradlew runClient -PrunXmx=6G
```

**Steps.**

1. Press R 5 times, 5 s apart. Press F2.
2. Press O, then *Shader Pack Settings... > Lighting*, and change *Ambient
   Occlusion*. Press *Apply*, then *Done*. Wait 15 s and press F2.
3. Repeat step 2 to set *Ambient Occlusion* back.
4. Press O, pick `ComplementaryReimagined_r5.8.1.zip`, and press *Apply*,
   then *Done*. Wait 30 s and press F2.
5. Press O, pick `BSL_v10.1.3.zip`, and press *Apply*, then *Done*. Wait 30 s
   and press F2.
6. Press K to turn shaders off and wait 20 s. Press K again to turn them on,
   wait 20 s and press F2.
7. Quit and run `log-check.sh`. Then:

   ```bash
   grep -F -e 'Iris pack state changed' -e 'using pipeline' -e 'vx resolve reset' -e 'inject failed' run/logs/latest.log
   grep -cF 'disabling shaders' run/logs/latest.log
   grep -E 'enableShaders|shaderPack' run/config/iris.properties
   ```

8. Finish with steps 4 and 5 of [After every test](#after-every-test).

**Look for.**

- Each K prints `Iris pack state changed (gbufferInject` and a new
  `Voxy render system created`.
- While shaders are off, `using pipeline 'NormalRenderPipeline'` is expected.
- How the LODs look with Complementary. This is for information only.
- How long the rebuild takes after each change.
- The K toggle alone passed on 2026-09-26.

**Log lines.**

```
Iris pack state changed (gbufferInject
using pipeline 'MetalVxRenderPipeline'
[Metal-LODTEST] vx resolve reset
inject failed
Failed to create shader rendering pipeline, disabling shaders!
```

**A/B.** `VOXY_VX_RESOLVE_REBUILD=0` restores the old behavior without the
reset. Use it only to reproduce stale-pipeline bugs.

**Pass.** No crash, and the LODs have color in every screenshot. With BSL and
with Complementary, the log shows `using pipeline 'MetalVxRenderPipeline'`.
`disabling shaders` counts 0, and `iris.properties` ends with
`enableShaders=true` and `shaderPack=BSL_v10.1.3.zip`.

**Fail.** Pale, white or sky-blue LODs after a reload, `inject failed`, or a
crash. Also a fail: the pack ends up off without a message, with
`enableShaders=false`.

### P3-RESOURCE-RELOAD: F3+T and a resource pack

**Priority:** Recommended. **Time:** 10 min. **Pack:** BSL.
**Owner status:** Pending. **Depends on:** `P3-REJOIN-MEMORY`.

**Goal.** A resource reload rebuilds the renderer and copies the block atlas
for Voxy again. Check that the LOD textures do not turn gray and that water
still animates.

**You need.**

- BSL on.
- A view of a textured slope with water.

**Settings.** *Options... > Resource Packs... > Programmer Art*. It comes
with the game.

**Launch.**

```bash
VOXY_QUICKPLAY="voxytest" VOXY_FORCE_METAL=1 VOXY_AUTO_SCREENSHOT=20 VOXY_DEBUG_CMD='5:time set noon;6:weather clear' ./gradlew runClient -PrunXmx=6G
```

**Steps.**

1. Press F3+T 3 times. Wait for each reload to finish and press F2 after each
   one.
2. Open *Options... > Resource Packs...* and move *Programmer Art* to the
   *Selected* column. Press *Done*.
3. Wait for the reload to finish and look at the far LODs. Their textures must
   change to the old style. Press F2.
4. Remove *Programmer Art*, press *Done*, then F2.
5. Quit and run `log-check.sh`. Then:

   ```bash
   grep -F -e '[Metal-MIRROR]' -e '[Metal-WATERANIM]' -e 'model atlas' run/logs/latest.log | tail -20
   ```

6. Finish with steps 4 and 5 of [After every test](#after-every-test).

**Look for.** `greyPct` and `opaquePct` of each reload.

**Log lines.**

```
[Metal-MIRROR] warmup sync#
[Metal-WATERANIM] block atlas not ready
[Metal-ATLASLIFE] model atlas CREATED
[Metal-ATLASLIFE] model atlas FREED
```

**A/B.** `VOXY_ATLAS_ZERO_INIT=0`.

**Pass.** Each reload prints a new `[Metal-MIRROR] warmup sync#50 (FROZEN)`
with a low `greyPct`, near 1.2. The LOD textures follow the active pack with
no gray or white cells. `[Metal-WATERANIM] block atlas not ready` does not
appear, and CREATED equals FREED at the end.

**Fail.** A high `greyPct`, gray or white cells in the LODs, or textures that
do not change with *Programmer Art*. Also a fail: water animation turns off,
or the atlas counts do not match.

### P3-VOXY-RELOAD: /voxy reload five times in the world

**Priority:** Recommended. **Time:** 15 min. **Pack:** BSL.
**Owner status:** New. **Depends on:** `P3-REJOIN-MEMORY`.

**Goal.** `/voxy reload` shuts down and recreates all of Voxy while the world
stays loaded. Check that this shutdown does not freeze the game or leave
threads or memory behind. No other test covers it.

**You need.**

- BSL on.
- The sampler with thread names, in a second Terminal tab.
- *Pause on lost focus* off.

**Launch.**

```bash
VOXY_QUICKPLAY="voxytest" VOXY_FORCE_METAL=1 VOXY_DEBUG_CMD='5:time set noon;6:weather clear' ./gradlew runClient -PrunXmx=6G
```

In a second tab, after the game window appears:

```bash
bash scripts/testing/sampler.sh --threads-every 1
```

**Steps.**

1. Join the world and wait 60 s. Write down `footprint_mb` and note a
   checkpoint: "reload 0".
2. Press T, type `/voxy reload` and press Enter. The LODs disappear and come
   back. Wait 30 s and press F2.
3. Repeat step 2 four more times, for 5 reloads in total.
4. Note a checkpoint: "reload 5".
5. Still in the world, count the live renderers with the command from
   [Memory and threads](#memory-and-threads). It must print 1.
6. *Save and Quit to Title > Quit Game*. Run `log-check.sh`. Then:

   ```bash
   grep -oF -e 'Shutting down voxy instance' -e 'Instance shutdown' -e 'Creating new world engine' -e 'Voxy render system created' run/logs/latest.log | sort | uniq -c
   ```

7. Compare Voxy's thread counts at the two checkpoints with the thread command
   from [Memory and threads](#memory-and-threads).
8. Finish with steps 4 and 5 of [After every test](#after-every-test).
9. If the game freezes during a reload, save a full thread dump before you
   force quit. Use the `jcmd` command from
   [Memory and threads](#memory-and-threads), and attach the file.

**Look for.**

- How many seconds each reload takes to bring the LODs back.
- The counts of 6 are the 5 reloads plus the exit. `Creating new world engine`
  counts 6 or more, and `Voxy render system created` grows with each reload.
- `log-check.sh` must show CREATED equal to FREED after you quit.
- Threads to compare: `Dedicated Voxy Worker #`, `Async Node Manager` and
  `Model factory processor`.
- Optional, in phase 7: run one `/voxy reload` while Chunky generates in test
  `7.2`. A stutter of a few seconds is normal. A hang is not.

**Log lines.**

```
Shutting down voxy instance
Instance shutdown
Creating new world engine:
Voxy render system created with
[Metal-ATLASLIFE] model atlas CREATED
[Metal-ATLASLIFE] model atlas FREED
Voxy must be enabled in settings to use this
Cannot have multiple renderers
Not all worlds shutdown
World still in use after
```

**A/B.** `VOXY_POOL_SHUTDOWN_FINALLY=0` or `VOXY_SHUTDOWN_PER_STEP=0` restore
the old shutdown path. Use them only to compare if a hang shows up.

**Pass.** Each reload brings the LODs back in about 30 s, without freezing.
`Shutting down voxy instance` and `Instance shutdown` count 6 each. Voxy's
thread counts are the same at both checkpoints, and memory grows no more than
half a GB per reload.

**Fail.** The game freezes on a reload, the LODs do not come back, or the live
renderer count is 2. Also a fail: Voxy's threads or memory grow with each
reload, or `Cannot have multiple renderers`, `Not all worlds shutdown` or
`World still in use after` appears.

### P3-DIMENSION-HOPS: Nether, End and Overworld hops

**Priority:** Recommended. **Time:** 15 min. **Pack:** BSL.
**Owner status:** Pending. **Depends on:** `P3-REJOIN-MEMORY`.

**Goal.** Each dimension has its own world engine, and each hop rebuilds the
renderer and the Iris pipeline. Check that terrain does not mix between
dimensions, that the Overworld LODs come back with color, and that memory does
not grow with each hop.

**You need.**

- BSL on.
- `voxytest` with *Allow Cheats* ON, for `/execute`.
- Your Overworld spot. Read it on the F3 line `XYZ:`.
- The sampler in a second Terminal tab.
- *Pause on lost focus* off.

**Launch.**

```bash
VOXY_QUICKPLAY="voxytest" VOXY_FORCE_METAL=1 VOXY_AUTO_SCREENSHOT=20 VOXY_DEBUG_CMD='5:time set noon;6:weather clear' ./gradlew runClient -PrunXmx=6G
```

In a second tab, after the game window appears:

```bash
bash scripts/testing/sampler.sh
```

**Steps.**

1. Type `/execute in minecraft:the_nether run tp @s 0 100 0` and wait 60 s.
   You are in Creative, so landing inside netherrack is safe. Fly out.
2. Type `/execute in minecraft:overworld run tp @s X Y Z` with your spot.
   Wait 60 s and press F2.
3. Type `/execute in minecraft:the_end run tp @s 0 80 0` and wait 60 s.
4. Go back to your Overworld spot as in step 2. Wait 60 s and press F2.
5. Repeat the whole cycle 3 times, and watch `footprint_mb` in the sampler.
6. Quit and run `log-check.sh`. Then:

   ```bash
   grep -F -e 'Creating new world engine' -e 'Shutting down idle world' -e 'Reloading pipeline on dimension change' -e 'Shutting down voxy instance' -e 'was not alive' run/logs/latest.log
   ```

7. Finish with steps 4 and 5 of [After every test](#after-every-test).

**Look for.**

- The `Reloading pipeline on dimension change:` line printed when you join the
  world (for example `null => minecraft:overworld`) is not a hop.
- One `Creating new world engine:` per arrival is normal, and so is a
  `Shutting down idle world:` about 10 s after you leave a dimension.
- The far Nether is mostly empty. Only the areas you visited have LODs.
- From above, the Nether roof can look like a slab. That is a known issue.

**Log lines.**

```
Creating new world engine:
Shutting down idle world:
Reloading pipeline on dimension change:
=> minecraft:the_nether
=> minecraft:the_end
Shutting down voxy instance
Tried inserting chunk into WorldEngine that was not alive
had an exception
```

**Pass.** Each hop prints an Iris line `Reloading pipeline on dimension
change:` that ends in `=> minecraft:the_nether`, `=> minecraft:the_end` or
`=> minecraft:overworld`. The Overworld LODs come back with texture and color,
`Shutting down voxy instance` only appears when you leave the world, and
memory does not grow with each cycle.

**Fail.** Overworld terrain in the Nether or the End, or pale or white LODs
when you come back. Also a fail: a crash, memory that grows each cycle,
`Shutting down voxy instance` during a hop, or an ingest error such as
`Tried inserting chunk into WorldEngine that was not alive` or
`had an exception`.

### P3-WORLD-HOP: Switch worlds and check the session 2 log lines

**Priority:** Recommended. **Time:** 8 min. **Pack:** BSL.
**Owner status:** Pending. **Depends on:** `P3-REJOIN-MEMORY`.

**Goal.** Check that terrain from one world never shows in another. Also look
for silent failures in session 2 or 3, when a message printed in session 1 is
not repeated.

**You need.**

- BSL on.
- A second world in `run/saves/`. If you have none, create one with
  *Singleplayer > Create New World*, any name, with *Allow Cheats* ON so the
  noon and clear-weather commands work there too.

**Launch.**

```bash
VOXY_QUICKPLAY="voxytest" VOXY_FORCE_METAL=1 VOXY_AUTO_SCREENSHOT=20 VOXY_DEBUG_CMD='5:time set noon;6:weather clear' ./gradlew runClient -PrunXmx=6G
```

**Steps.**

1. World A, `voxytest`, opens. Wait 60 s and press F2.
2. *Save and Quit to Title > Singleplayer >* the second world *> Play
   Selected World*. Wait 60 s and press F2.
3. *Save and Quit to Title* and join `voxytest` again. Wait 60 s and press F2.
4. Quit and run `log-check.sh`.
5. Split the log by session:

   ```bash
   awk '/Shutting down voxy instance/{s++} {print "session " s+1 ": " $0}' run/logs/latest.log | grep -E 'Creating new world engine|side-channel ready|warmup sync#50|sky-light floor runtime|inject failed|composite disabled'
   ```

6. Finish with steps 4 and 5 of [After every test](#after-every-test).

**Look for.**

- Which lines print only in the first session, once per process.
- The game sets noon and clear weather 5 s after each join, also in world B.
  That is on purpose.

**Log lines.**

```
Creating new world engine:
VxIrisSideChannel: vx depth side-channel ready (
[Metal-MIRROR] warmup sync#50 (FROZEN)
[Metal-LODTEST] vx sky-light floor runtime
Shutting down voxy instance
```

**Pass.** World B never shows terrain from A, and the LODs are fine in session
3. Each session prints its own `Creating new world engine:`, with a different
hash for A and B, plus `VxIrisSideChannel: vx depth side-channel ready`,
`[Metal-MIRROR] warmup sync#50 (FROZEN)` and
`[Metal-LODTEST] vx sky-light floor runtime`.

**Fail.** Terrain from the other world shows. Or a session looks wrong and the
log says nothing because its lines are missing. Keep that screenshot and the
log.

### P3-SESSION2-BUILD-STALL: Sodium build stall in session 2, Use sodium threads ON vs OFF

**Priority:** Recommended. **Time:** 20 min. **Pack:** BSL.
**Owner status:** Pending. **Depends on:** `P3-REJOIN-MEMORY`.

**Goal.** After a rejoin, Sodium's near chunks can stall half built, near
55%. This A/B with *Use sodium threads* ON and OFF shows whether the cause is
the thread pool Voxy shares with Sodium.

**You need.**

- BSL on.
- *Reduce FPS when* on *Minimized*, so the AFK cap does not slow the game
  while you stand still.

**Settings.**

- Round A: *Voxy > General > Use sodium threads* ON. That is the default.
- Round B: *Use sodium threads* OFF. Press *Apply* and quit before you start
  round B.

**Launch.** The same command for both rounds:

```bash
VOXY_QUICKPLAY="voxytest" VOXY_FORCE_METAL=1 VOXY_DEBUG_REJOIN=45 VOXY_FRAME_TIMING=1 ./gradlew runClient -PrunXmx=6G
```

**Steps.**

1. Round A. In session 1, before the game leaves by itself at 45 s, open F3
   and write down the numbers on the `C:` line.
2. After the automatic rejoin (session 2), stand still for 3 minutes with F3
   open. Watch Sodium's line `C: <built>/<total> D: <distance>` and the line
   `Chunk Builder: Schd=NN | Busy=NN (NN%) | Total=NN`.
3. Press F2 at minute 1 and at minute 3.
4. Open *Options... > Video Settings... > Voxy > General*, turn *Use sodium
   threads* off, press *Apply* and quit.
5. Run `log-check.sh` and save the round's log:

   ```bash
   cp run/logs/latest.log ~/voxy-test-results/P3-SESSION2-BUILD-STALL-A.log
   ```

6. Round B. Launch the same command and repeat steps 1 to 3. Quit, run
   `log-check.sh` and save the log the same way, ending in `-B.log`.
7. Show the section count over time for each round. Run it once per log:

   ```bash
   F=~/voxy-test-results/P3-SESSION2-BUILD-STALL-A.log
   grep -F '[Metal-DIAG' "$F" | sed -E 's/^\[([0-9:]+)\].*(sections=[0-9]+).*/\1 \2/'
   ```

8. Turn *Use sodium threads* back ON.

**Look for.**

- The F3 `C:` numbers over time, in sessions 1 and 2 of each round.
- `sections=` on the `[Metal-DIAG]` lines over time.

**Log lines.**

```
[VOXY_DEBUG_REJOIN] rejoining
[Metal-DIAG f=
```

**A/B.** *Use sodium threads* in *Voxy > General*. In the config file it is
`dont_use_sodium_builder_threads`, and `settings-dump.sh` shows it.

**Pass.** In session 2 of both rounds, Sodium's built count reaches the same
plateau as in session 1, and `Schd` drops to near 0.

**Fail.** Session 2 stalls only with the option ON: `C:` stays at about half,
and `Schd` stays above 0 while `Busy` is 0. That confirms the shared pool as
the cause. Report both rounds.

### P3-GEOMETRY-GRANULE-AB: Geometry memory with 128-element blocks

**Priority:** Optional. **Time:** 12 min. **Pack:** BSL.
**Owner status:** Pending. **Depends on:** `P3-REJOIN-MEMORY`.

**Goal.** An upstream change cut the smallest geometry block from 1024 to 128
elements, and promises about 20% less geometry memory. Measure it with the
same view in two rounds.

**You need.**

- BSL on.
- A [fixed viewpoint](#fixed-viewpoint).
- Voxy's `debug` row in F3 set to *In Overlay*.
- *Reduce FPS when* on *Minimized*.

**Launch.** Replace `X Y Z YAW PITCH` with your viewpoint in both commands.

```bash
# Round A: granule 128, the default
VOXY_QUICKPLAY="voxytest" VOXY_FORCE_METAL=1 VOXY_DEBUG_CMD='5:time set noon;8:tp @p X Y Z YAW PITCH' ./gradlew runClient -PrunXmx=6G
# Round B: granule 1024
VOXY_GEOMETRY_ALLOC_ALIGN=1024 VOXY_QUICKPLAY="voxytest" VOXY_FORCE_METAL=1 VOXY_DEBUG_CMD='5:time set noon;8:tp @p X Y Z YAW PITCH' ./gradlew runClient -PrunXmx=6G
```

**Steps.**

1. Launch round A. After the teleport, stand still for 3 minutes.
2. Read the F3 line `UC/GC,#N:`. It shows the MB used and the capacity. Close
   F3.
3. Quit. Read the granule line and the last `[Metal-DIAG]` line:

   ```bash
   grep -F '[Voxy-SYNC] geometry allocation granule' run/logs/latest.log
   grep -F '[Metal-DIAG' run/logs/latest.log | tail -2
   ```

4. Run `log-check.sh` and save the round's log:

   ```bash
   cp run/logs/latest.log ~/voxy-test-results/P3-GEOMETRY-GRANULE-AB-A.log
   ```

5. Repeat steps 1 to 4 with round B. Save its log the same way, ending in
   `-B.log`.

**Look for.**

- The MB used on the F3 line `UC/GC,#N:`.
- `geom=` and `sections=` on the `[Metal-DIAG]` line.

**Log lines.**

```
[Voxy-SYNC] geometry allocation granule:
[Metal-DIAG f=
```

**A/B.** `VOXY_GEOMETRY_ALLOC_ALIGN=1024`.

**Pass.** Round A uses clearly less geometry memory than round B with the same
view, about 20% less. The log shows
`[Voxy-SYNC] geometry allocation granule: 128 elements` in A and
`1024 elements` in B.

**Fail.** A uses as much geometry memory as B, or more, or A is missing
sections.

---

## Phase 4: Failure paths

This phase causes failures on purpose, to check that Voxy fails safely: no
crash, and no change to your settings. Both tests use the `voxytest` world.
The first needs BSL on. The second runs without `VOXY_FORCE_METAL`, with the
pack in whatever state you left it.

| ID | Test | Priority | Min | Pack |
|---|---|---|---|---|
| `P4-CTOR-FAIL-WITH-PACK` | Simulated renderer failure with BSL on: cleanup, shaders off for this session only | Essential | 10 | BSL |
| `P4-NO-OPTIN-FALLBACK` | Without the opt-in, Voxy turns off and the game runs as plain Sodium | Recommended | 4 | Any |

### P4-CTOR-FAIL-WITH-PACK: Simulated renderer failure with BSL on

**Priority:** Essential. **Time:** 10 min. **Pack:** BSL.
**Owner status:** Pending. **Depends on:** `P1-NOPACK-START`.

**Goal.** If Voxy cannot build its renderer with BSL on, it must release
everything it already created and keep showing LODs without shaders. Iris
must turn the pack off for this session only, without saving
`enableShaders=false`.

**You need.**

- `P1-NOPACK-START` passed. The game falls into that no-pack path during this
  test.
- BSL on: `grep enableShaders run/config/iris.properties` prints
  `enableShaders=true`.
- *Pause on lost focus* off, so the game does not pause when you click the
  Terminal.

Never use `VOXY_CTOR_FAIL_ONCE` without a shader pack. Without a pack it
crashes on purpose.

**Settings.** BSL on, shaders ON.

**Launch.** Two launches:

```bash
# Launch 1: simulated failure
VOXY_CTOR_FAIL_ONCE=1 VOXY_QUICKPLAY="voxytest" VOXY_FORCE_METAL=1 ./gradlew runClient -PrunXmx=6G
# Launch 2: normal
VOXY_QUICKPLAY="voxytest" VOXY_FORCE_METAL=1 ./gradlew runClient -PrunXmx=6G
```

**Steps.**

1. Start launch 1. The world opens with shaders off, with a plain look, and
   with LODs. That is expected in this session.
2. With the game open, in a second Terminal tab, check that the saved state
   did not change. It must still print `enableShaders=true`:

   ```bash
   grep enableShaders run/config/iris.properties
   ```

3. In the same tab, start the sampler with thread names:

   ```bash
   bash scripts/testing/sampler.sh --threads-every 1
   ```

   After the second sample line, check the newest block with the thread
   command from [Memory and threads](#memory-and-threads).
   `Async Node Manager` and `Model factory processor` must count 1 each, not
   2. Leave the sampler running. It stops when the game quits.
4. Go back to the game and press R (Reload Shaders). BSL must come back, and
   the LODs must show the pack's shading.
5. Press F2.
6. *Save and Quit to Title*, then *Quit Game*.
7. Before you launch again, run `log-check.sh`, save the log, and look for
   the failure-path lines:

   ```bash
   cp run/logs/latest.log ~/voxy-test-results/P4-CTOR-FAIL-WITH-PACK.log
   grep -F -e 'partially built components' -e 'Voxy could not build its renderer' -e 'NormalRenderPipeline' -e 'using pipeline' -e 'Could not restore enableShaders' run/logs/latest.log
   ```

8. Start launch 2, without `VOXY_CTOR_FAIL_ONCE`. BSL must be on from the
   start. Then quit.
9. Check the saved state again with
   `grep -E 'enableShaders|shaderPack' run/config/iris.properties`.

**Look for.**

- The error trace with `VOXY_CTOR_FAIL_ONCE: simulated renderer construction
  failure` is expected. It is not a failure. `log-check.sh` counts
  `Voxy render system construction failed` under "Info only".
- If `was not freed` lines appear, write down the class names. They belong to
  backlog item `A16`.
- Compare the memory of this session without shaders with a normal session.
  Leftovers would add up to several GB.
- Optional A/B: `VOXY_CTOR_FAILURE_CLEANUP=0` restores the old behavior, which
  releases nothing.
- Optional A/B: `VOXY_IRIS_DISABLE_PERSIST=1` saves `enableShaders=false`, as
  upstream does. If you try it, turn BSL back on with K afterwards.

**Log lines.**

```
Voxy render system construction failed, releasing
VOXY_CTOR_FAIL_ONCE: simulated renderer construction failure
Voxy could not build its renderer with shader pack
Voxy renders LODs with its NormalRenderPipeline: shaders are off.
using pipeline 'MetalVxRenderPipeline'
Error releasing a partially built renderer component
Could not restore enableShaders in iris.properties
Cannot have multiple renderers
was not freed
```

**A/B.** `VOXY_CTOR_FAILURE_CLEANUP=0` and `VOXY_IRIS_DISABLE_PERSIST=1`. See
*Look for*.

**Pass.** The log has exactly one ERROR `Voxy render system construction
failed, releasing 8 partially built components` and one `Voxy could not build
its renderer with shader pack`. Then it has `Voxy renders LODs with its
NormalRenderPipeline: shaders are off.` and, after you press R,
`using pipeline 'MetalVxRenderPipeline'`. `iris.properties` stays at `true`,
BSL is on from the start of launch 2, Voxy's threads are not doubled, and
`log-check.sh` passes for launch 1.

**Fail.** The game crashes on join, `iris.properties` changes to
`enableShaders=false`, R does not bring BSL back, or there are no LODs in the
session without shaders. Also a fail: the component count is not 8, threads
are doubled, or `Error releasing a partially built renderer component`,
`Could not restore enableShaders in iris.properties` or
`Cannot have multiple renderers` appears.

### P4-NO-OPTIN-FALLBACK: Without the opt-in, Voxy turns off and the game runs as plain Sodium

**Priority:** Recommended. **Time:** 4 min. **Pack:** any.
**Owner status:** Verified. **Depends on:** `P0-BUILD`.

**Goal.** Check the safety net. Without the explicit opt-in, Voxy turns itself
off, and the player sees plain Sodium instead of a crash. A player whose
launcher profile lacks `-Dvoxy.forceMetal=true` gets the same path.

**You need.** The game closed. `env | grep VOXY_` must not list
`VOXY_FORCE_METAL`. If it does, run `unset VOXY_FORCE_METAL` in this tab.

**Launch.** Note that `VOXY_FORCE_METAL` is NOT set:

```bash
VOXY_QUICKPLAY="voxytest" ./gradlew runClient -PrunXmx=6G
```

**Steps.**

1. Launch the game with the command above.
2. Look at the horizon. Terrain must end at the vanilla render distance, with
   no LODs. If BSL is on, it keeps working.
3. Open *Options... > Video Settings...*. The bar on the left must have no
   *Voxy* entry.
4. Close the game.
5. Check the log:

   ```bash
   grep -F -e 'M9 TRANSITIONAL' -e 'Voxy render system created' run/logs/latest.log
   ```

6. Do the steps in [After every test](#after-every-test). Here `log-check.sh`
   shows `Voxy opt-in:  WARN: Voxy disabled itself`, and the result is a PASS
   with a warning. That is expected for this test.

**Look for.**

- In F3, Voxy's version line can show in red. It means Voxy is installed but
  not available, which is expected.
- The `/voxy` commands do not exist in this session.
- After this test, always launch with `VOXY_FORCE_METAL=1` again.

**Log lines.**

```
[M9 TRANSITIONAL] Voxy disabled on METAL backend. Set VOXY_FORCE_METAL=1 to enable the Metal render path.
Voxy render system created
```

**Pass.** The log has `[M9 TRANSITIONAL] Voxy disabled on METAL backend. Set
VOXY_FORCE_METAL=1 to enable the Metal render path.` and no
`Voxy render system created`. There are no LODs and no *Voxy* menu entry, and
the game runs normally without a crash.

**Fail.** The game crashes, LODs appear, or the *Voxy* entry shows in the menu
while Voxy is off.

---

## Phase 5: Performance

This phase measures fps, wait times and memory with a still camera. It also
compares the performance levers, day against night, and the weather. All tests
use the `voxytest` world with BSL on. Always start with `P5-AFK-CHECK`.

| ID | Test | Priority | Min | Pack |
|---|---|---|---|---|
| `P5-AFK-CHECK` | Minecraft's AFK fps cap, on vs off (backlog `A21`) | Essential | 12 | BSL |
| `P5-BASELINE-SOAK` | Still-camera baseline: fps, waits, GPU pass time, flicker, memory (stress `S1`) | Recommended | 15 | BSL |
| `P5-LEVERS-AB` | A/B of the performance levers (backlog `A1` to `A4`) | Recommended | 30 | BSL |
| `P5-LEVERS-CORRECTNESS` | The levers leave no stale brightness or LOD edge | Recommended | 10 | BSL |
| `P5-DAY-NIGHT` | Day vs night fps, without VSync or the AFK cap (backlog `A21`) | Essential | 20 | BSL |
| `P5-WEATHER` | Clear, rain, thunder and night (stress `S3`, backlog `A10`) | Recommended | 15 | BSL |

Short on time? Do `P5-AFK-CHECK`, `P5-BASELINE-SOAK` and `P5-DAY-NIGHT`.

### Measuring rules

These apply to every phase 5 test:

- *Video Settings > Sodium > General*: *VSync* OFF and *Max Framerate* at
  *Unlimited*, the far right. With VSync on, the frame rate snaps to the
  display rate divided by a whole number, which hides the real cost.
- *Reduce FPS when* on *Minimized*, and *Pause on lost focus* off.
- Check all four with `settings-dump.sh` before you start:
  `enableVsync:false`, `maxFps:260`, `inactivityFpsLimit:"minimized"` and
  `pauseOnLostFocus:false`.
- Keep the window size from the end of `P3-RESIZE-STORM` for every run. Do not
  resize.
- Keep F3 closed while you measure. If the log shows
  `[Metal-VXTIMING] vanilla GL_TIME_ELAPSED query live at the frame head`, F3
  was open. Repeat the run.
- Plug in the Mac, turn off Low Power Mode and close heavy apps. Let the Mac
  rest for 5 minutes before you start. `collect-report.sh` writes the power
  state to `system.txt`.
- After launch, click the game window once, so it has focus. Then do not
  touch anything unless a step says so.
- Use the viewpoint from `P5-BASELINE-SOAK` for `X Y Z YAW PITCH` in every
  phase 5 command.

### P5-AFK-CHECK: Minecraft's AFK fps cap, on vs off

**Priority:** Essential. **Time:** 12 min. **Pack:** BSL.
**Owner status:** New. **Depends on:** `P1-SETUP`, `P3-REJOIN-MEMORY`.

**Goal.** Check whether Minecraft's idle fps cap explains the fps drop in
backlog item `A21`. Also confirm that with *Minimized* the cap is gone before
you take any phase 5 measurement.

**You need.**

- `P1-SETUP` done.
- BSL on.
- The Mac plugged in, with Low Power Mode off.

**Settings.**

- *Options... > Video Settings...*, *General* page: *VSync* OFF and *Max
  Framerate* at *Unlimited*.
- *Performance* page, *Reduce FPS when*: *AFK* in the first part, *Minimized*
  in the second. Leave it on *Minimized* at the end.
- *Pause on lost focus* off.
- F3 closed.

**Launch.**

```bash
VOXY_QUICKPLAY="voxytest" VOXY_FORCE_METAL=1 VOXY_FRAME_TIMING=1 VOXY_DEBUG_CMD='5:gamerule advance_time false;6:gamerule advance_weather false;7:time set noon;8:weather clear' ./gradlew runClient -PrunXmx=6G
```

**Steps.**

1. In the world, press Esc and open *Options... > Video Settings...*. On the
   *General* page set *VSync* OFF and *Max Framerate* to *Unlimited*.
2. On the *Performance* page set *Reduce FPS when* to *AFK*. Press *Apply*,
   then Esc until you are back in the game.
3. Fly to a high point with a wide view of LODs. In Terminal, run `date +%T`
   and write down the time.
4. Click the game window once. Touch nothing for 3 minutes.
5. Press 1, then 2, to change the hotbar slot. This counts as activity but
   does not move the camera.
6. Wait 60 s without touching anything.
7. Press Esc and open *Options... > Video Settings... > Performance*. Set
   *Reduce FPS when* to *Minimized*, press *Apply* and go back to the game.
8. Click the game window once. Touch nothing for 3 minutes.
9. Quit and run `log-check.sh`. Show the frame rate over time:

   ```bash
   grep -F '[Metal-LayerB' run/logs/latest.log | sed -E 's/^\[([0-9:]+)\].*(fps=-?[0-9.]+).*/\1 \2/'
   ```

10. Finish with steps 4 and 5 of [After every test](#after-every-test).

**Look for.**

- While the cap is active, each fps window lasts about 20 s.
- With VSync on, the cap and the VSync steps look alike: 30 is 120 divided by
  4 on a 120 Hz display. That is why VSync must be off.
- If the AFK part shows the drop, say in your report that `A21` matches
  Minecraft's AFK cap and is not Voxy.
- If you moved the camera or opened F3, the data is not usable. Repeat the
  test.

**Log lines.**

```
[Metal-LayerB f=
```

**A/B.** *Reduce FPS when*: *AFK* against *Minimized*.

**Pass.** In the AFK part, about 60 s after the last key press, `fps=` drops
to 30 or lower, and it recovers within one window after you press 1 and 2. In
the *Minimized* part, fps varies no more than 10% over the 3 minutes.

**Fail.** If the *Minimized* part also drops, something else slows the Mac
down. Note Low Power Mode and the temperature. If the AFK part does not drop,
the cap does not explain `A21`, and the case stays open for `P5-DAY-NIGHT`.

### P5-BASELINE-SOAK: Still-camera baseline

**Priority:** Recommended. **Time:** 15 min. **Pack:** BSL.
**Owner status:** Pending. **Depends on:** `P5-AFK-CHECK`.

**Goal.** Set the reference numbers for every other phase 5 test. Also check
that the new GPU timing probe (`A1`) works.

**You need.**

- `P5-AFK-CHECK` done, with *Reduce FPS when* on *Minimized*.
- BSL on.
- The sampler in a second Terminal tab.
- The [measuring rules](#measuring-rules).

**Launch.**

```bash
VOXY_QUICKPLAY="voxytest" VOXY_FORCE_METAL=1 VOXY_FRAME_TIMING=1 VOXY_DEBUG_CMD='5:gamerule advance_time false;6:gamerule advance_weather false;7:time set noon;8:weather clear;9:tp @p X Y Z YAW PITCH' ./gradlew runClient -PrunXmx=6G
```

In a second tab, after the game window appears:

```bash
bash scripts/testing/sampler.sh
```

**Steps.**

1. First time only: launch without the `;9:tp @p X Y Z YAW PITCH` part. Fly to
   a hill with a view of ocean and forest.
2. Press F3. Write down `XYZ` and the two `Facing` numbers, the yaw and the
   pitch. Then quit.
3. Use those numbers for `X Y Z YAW PITCH` in every phase 5 command.
4. Launch with the full command and start the sampler. Then click the game
   window once.
5. After the teleport, do not touch the mouse or keyboard for 10 minutes.
   Keep F3 closed.
6. Quit and run `log-check.sh`. Save the log. Later tests compare against
   it.
7. Pull the numbers:

   ```bash
   cp run/logs/latest.log ~/voxy-test-results/P5-BASELINE-SOAK.log
   grep -F '[Metal-LayerB' run/logs/latest.log | sed -E 's/^\[([0-9:]+)\].*(fps=-?[0-9.]+).*/\1 \2/'
   grep -F '[Metal-TIMING' run/logs/latest.log | tail -5
   grep -F -A20 '[Metal-VXTIMING f=' run/logs/latest.log | tail -21
   grep -F '[Metal-FLICKER' run/logs/latest.log | tail -5
   tail -5 ~/voxy-test-results/sampler-$(date +%F).csv
   ```

8. Write down the median fps (see [Frame rate numbers](#frame-rate-numbers)),
   `hotWait`, `drawFlushWait`, `bridgeWait`, `jniDrawLoop` and `draws=`. Also
   the biggest GPU passes in the `VXTIMING` block, and `lodCoverage=`.
9. Finish with step 5 of [After every test](#after-every-test).

**Look for.**

- The median fps.
- `hotWait`, `drawFlushWait`, `bridgeWait` and `jniDrawLoop`, in ms.
- `draws=`.
- The biggest GPU passes in `VXTIMING`.
- `lodCoverage=`.
- The level where `footprint_mb` settles.
- At startup, `[Metal-VXTIMING] GL-side vx pass timing ON` must appear.

**Log lines.**

```
[Metal-LayerB f=
[Metal-TIMING f=
[Metal-VXTIMING] GL-side vx pass timing ON
[Metal-VXTIMING f=
[Metal-FLICKER f=
[Metal-DIAG f=
```

**Pass.** After the first 2 windows, the `[Metal-LayerB]` fps varies no more
than 10% and never drops to 30. Every `[Metal-FLICKER]` window after startup
has `min` equal to `max` and `changes=0`. The last 5 memory samples differ by
less than 5%. A `VXTIMING` block appears every 600 frames, and there is no
crash and no `hs_err` file.

**Fail.** The fps drops window after window, or falls to 30 or 10 because of
the AFK cap. `FLICKER` shows `changes` above 0, or memory keeps growing. If
`[Metal-VXTIMING] vanilla GL_TIME_ELAPSED query live at the frame head`
appears, F3 was open. Repeat the run.

### P5-LEVERS-AB: A/B of the performance levers

**Priority:** Recommended. **Time:** 30 min. **Pack:** BSL.
**Owner status:** Pending. **Depends on:** `P5-BASELINE-SOAK`.

**Goal.** Check that the four levers `A1` to `A4` together do not make the
game slower, and that each lever's counter shows its effect. The levers are
the lightmap sync (`A2`), the bridge re-specification (`A3`), the native draw
batch (`A4`) and the GPU timing probe (`A1`).

**You need.**

- `P5-BASELINE-SOAK` done, with the same viewpoint and window size.
- The [measuring rules](#measuring-rules).
- A cool Mac. Let it rest 3 minutes between runs.

**Launch.**

```bash
# Run A: levers on, the defaults
VOXY_QUICKPLAY="voxytest" VOXY_FORCE_METAL=1 VOXY_FRAME_TIMING=1 VOXY_DEBUG_CMD='5:gamerule advance_time false;6:gamerule advance_weather false;7:time set noon;8:weather clear;9:tp @p X Y Z YAW PITCH' ./gradlew runClient -PrunXmx=6G
# Run B: levers A2, A3 and A4 off
VOXY_LIGHTMAP_SYNC_EVERY_FRAME=1 VOXY_BRIDGE_RESYNC=every VOXY_METAL_DRAW_BATCH=0 VOXY_QUICKPLAY="voxytest" VOXY_FORCE_METAL=1 VOXY_FRAME_TIMING=1 VOXY_DEBUG_CMD='5:gamerule advance_time false;6:gamerule advance_weather false;7:time set noon;8:weather clear;9:tp @p X Y Z YAW PITCH' ./gradlew runClient -PrunXmx=6G
# Run C, optional: without the A1 probe
VOXY_VX_TIMING=0 VOXY_QUICKPLAY="voxytest" VOXY_FORCE_METAL=1 VOXY_FRAME_TIMING=1 VOXY_DEBUG_CMD='5:gamerule advance_time false;6:gamerule advance_weather false;7:time set noon;8:weather clear;9:tp @p X Y Z YAW PITCH' ./gradlew runClient -PrunXmx=6G
```

**Steps.**

1. Do the runs in this order: A, B, then A again. Let the Mac rest 3 minutes
   between runs.
2. In each run, click the window once and stay still for 5 minutes after the
   teleport, with F3 closed. Then quit.
3. After each run, run `log-check.sh` and save the log under the run's name:
   `P5-LEVERS-AB-A1.log`, `P5-LEVERS-AB-B.log` and `P5-LEVERS-AB-A2.log`. For
   example, after the first A:

   ```bash
   cp run/logs/latest.log ~/voxy-test-results/P5-LEVERS-AB-A1.log
   ```

4. Pull the numbers from each saved log. Run this once per log, changing `F`:

   ```bash
   F=~/voxy-test-results/P5-LEVERS-AB-A1.log
   grep -F '[Metal-LayerB' "$F" | sed -E 's/^\[([0-9:]+)\].*(fps=-?[0-9.]+).*(lightmapSync=.*)/\1 \2 \3/'
   grep -F '[Metal-TIMING' "$F" | tail -3
   grep -F -e 'lightmap sync throttled' -e '[Metal-RESYNC]' -e 'indirect draw loop' "$F"
   ```

   Then get each run's median fps with the median command from
   [Frame rate numbers](#frame-rate-numbers), with the same `F`.
5. Compare the median fps of A1 and A2. That difference is the noise. Then
   compare A with B.
6. Optional: do run C and save its log as `P5-LEVERS-AB-C.log`. Compare its
   fps with A. Run C has no `VXTIMING` block.
7. Finish with step 5 of [After every test](#after-every-test).

**Look for.**

- `A4`: with the same `draws=`, `jniDrawLoop` on `[Metal-TIMING]` is lower in
  A.
- `A3`: `resync=` per frame is lower in A, and `skipped` is above 0.
- `A2`: on `[Metal-LayerB]`, the second number of `lightmapSync=` counts the
  skipped reads. It must be above 0 in A and 0 in B.
- Startup lines in A: `[Metal] lightmap sync throttled to lightmap-version`,
  `[Metal-RESYNC] IOSurface GL re-specification mode=ONCE` and
  `[Metal] indirect draw loop: native batch ON`.
- Startup lines in B: `every-frame`, `EVERY acquire` and `native batch OFF`.
- The fps of A1, B, A2 and C, and the biggest `VXTIMING` passes in A.
- If the fps stays near 30, the AFK cap is on. Check *Reduce FPS when*.

**Log lines.**

```
[Metal] lightmap sync throttled to
[Metal-RESYNC] IOSurface GL re-specification mode=
[Metal] indirect draw loop: native batch ON
[Metal] indirect draw loop: native batch OFF
native batch export missing in the loaded dylib
[Metal-TIMING f=
[Metal-LayerB f=
```

**A/B.**

- `A2`: `VOXY_LIGHTMAP_SYNC_EVERY_FRAME=1`.
- `A3`: `VOXY_BRIDGE_RESYNC=every`.
- `A4`: `VOXY_METAL_DRAW_BATCH=0`.
- `A1`: `VOXY_VX_TIMING=0` or `VOXY_VX_TIMING_GPU=0`.

**Pass.** The median fps of A is equal to or higher than B's, within the noise
between A1 and A2, and nothing looks different. The counters and startup lines
under *Look for* show each lever on in A and off in B. Run C stays within A's
noise.

**Fail.** A is slower than B beyond the noise, or looks different. Repeat B
with one kill switch at a time to find the lever. If
`native batch export missing in the loaded dylib` appears, an old Metal
library was loaded from `java.library.path`.

### P5-LEVERS-CORRECTNESS: The levers leave no stale brightness or LOD edge

**Priority:** Recommended. **Time:** 10 min. **Pack:** BSL.
**Owner status:** Pending. **Depends on:** `P5-LEVERS-AB`.

**Goal.** Check that levers `A2` and `A3` leave no old image on screen. With
`A2`, the brightness of far LODs could lag behind. With `A3`, the LOD edge
could drag behind the camera.

**You need.**

- BSL on.
- A hill with a view over the edge between near terrain and LODs.
- `voxytest` with *Allow Cheats* ON, for the chat commands.

**Settings.** *Options... > Video Settings...*, *General* page,
*Brightness*. You move it between *Moody* and *Bright* during the test.

**Launch.**

```bash
VOXY_QUICKPLAY="voxytest" VOXY_FORCE_METAL=1 VOXY_DEBUG_CMD='5:time set midnight;6:weather clear' ./gradlew runClient -PrunXmx=6G
```

**Steps.**

1. It is night. Stand on the hill and look at the edge between near terrain
   and LODs.
2. Type `/effect give @s minecraft:night_vision infinite`. Near terrain and
   far LODs must brighten at the same time, with no visible ring.
3. Type `/effect clear @s`. Both must darken together.
4. Open *Options... > Video Settings...* and move *Brightness* from *Moody* to
   *Bright* and back. The far LODs must follow.
5. Type `/weather thunder` and wait for a lightning flash. The far field must
   flash with the near terrain. Then type `/weather clear`.
6. At the edge, move quickly left and right with A and D, and turn quickly.
   Press F2 while you move.
7. Quit and run `log-check.sh`. Check the lever startup lines:

   ```bash
   grep -F -e 'lightmap sync throttled' -e 'IOSurface GL re-specification mode' run/logs/latest.log
   ```

8. If something lags, repeat with the matching kill switch added to the
   launch: `VOXY_LIGHTMAP_SYNC_EVERY_FRAME=1` for brightness, and
   `VOXY_BRIDGE_RESYNC=every` for a dragging edge.
9. Finish with steps 4 and 5 of [After every test](#after-every-test).

**Look for.**

- The delay between the near and the far brightness change.
- Whether the F2 screenshot shows a ghost edge.

**Log lines.**

```
[Metal] lightmap sync throttled to lightmap-version
[Metal-RESYNC] IOSurface GL re-specification mode=ONCE
```

**A/B.** `VOXY_LIGHTMAP_SYNC_EVERY_FRAME=1` and `VOXY_BRIDGE_RESYNC=every`.

**Pass.** Near and far change brightness together with night vision,
*Brightness* and lightning, and the LOD edge does not drag when you move. The
log has `[Metal] lightmap sync throttled to lightmap-version` and
`[Metal-RESYNC] IOSurface GL re-specification mode=ONCE`.

**Fail.** The far LODs keep the old brightness for a visible time, or the LOD
edge falls behind the near terrain. If the run with the kill switch fixes it,
that lever has a bug.

### P5-DAY-NIGHT: Day vs night fps, without VSync or the AFK cap

**Priority:** Essential. **Time:** 20 min. **Pack:** BSL.
**Owner status:** Pending. **Depends on:** `P5-AFK-CHECK`, `P5-BASELINE-SOAK`.

**Goal.** Switch between day and night with a still camera to explain the fps
drop in backlog item `A21`. Tell apart a real night cost in BSL, a Mac that
heats up, and no difference at all.

**You need.**

- `P5-AFK-CHECK` done, with *Reduce FPS when* on *Minimized*.
- The viewpoint from `P5-BASELINE-SOAK`.
- The [measuring rules](#measuring-rules), with a cool Mac that rested 5
  minutes.

**Launch.** The game switches the time every 180 s on its own:

```bash
VOXY_QUICKPLAY="voxytest" VOXY_FORCE_METAL=1 VOXY_FRAME_TIMING=1 VOXY_DEBUG_CMD='5:gamerule advance_time false;6:gamerule advance_weather false;7:weather clear;8:time set noon;9:tp @p X Y Z YAW PITCH;190:time set midnight;370:time set noon;550:time set midnight' ./gradlew runClient -PrunXmx=6G
```

**Steps.**

1. Launch and click the window once. Stay still for about 12 minutes while
   four phases of about 3 minutes pass: day, night, day and night.
2. Optional: note the fan noise or the Mac's temperature in each phase.
3. Quit and run `log-check.sh`.
4. Save the log and split the fps by phase:

   ```bash
   cp run/logs/latest.log ~/voxy-test-results/P5-DAY-NIGHT.log
   grep -F -e '[VOXY_DEBUG_CMD] running' -e '[Metal-LayerB' run/logs/latest.log | sed -E 's/^\[([0-9:]+)\].*(running: .*|fps=-?[0-9.]+).*/\1 \2/'
   ```

5. Check that Voxy's work stays the same between phases:

   ```bash
   grep -F '[Metal-LayerB' run/logs/latest.log | sed -E 's/^\[([0-9:]+)\].*(renderList.sectionCount=[0-9]+).*(draws opaque=[0-9]+).*/\1 \2 \3/'
   ```

6. Compare the `[Metal-TIMING]` lines and the `[Metal-VXTIMING]` blocks
   between phases.
7. Finish with step 5 of [After every test](#after-every-test).

**Look for.** One of these four cases:

- Case 1, the AFK cap: `P5-AFK-CHECK` already confirmed or ruled it out.
- Case 2, time of day: both nights are slower and the second day recovers.
  `VXTIMING` shows which GL pass grows, or `[Metal-TIMING]` shows a Metal wait
  that grows.
- Case 3, heat: the fps drops slowly through all phases, whatever the time.
- Case 4, no difference: the original report came from the AFK cap or from
  VSync.

Also write down the median fps, the waits, `VXTIMING` and `draws opaque=` for
each phase.

**Log lines.**

```
[VOXY_DEBUG_CMD] running: /time set midnight
[VOXY_DEBUG_CMD] running: /time set noon
[Metal-LayerB f=
[Metal-TIMING f=
[Metal-VXTIMING f=
```

**Pass.** You reach one of the four cases under *Look for* and back it with
numbers. In every case, `draws opaque=` and `renderList.sectionCount` stay
nearly the same between phases.

**Fail.** The data is not usable if the camera moved, F3 was open, VSync was
on, or the fps sat at 30 or 10. Repeat the test. If Voxy's counters change
between day and night, that is a real change in Voxy's work. Report it.

### P5-WEATHER: Clear, rain, thunder and night

**Priority:** Recommended. **Time:** 15 min. **Pack:** BSL.
**Owner status:** Pending. **Depends on:** `P5-BASELINE-SOAK`.

**Goal.** Voxy has no rain code, yet about 10 fps were lost in the rain. Find
which part grows in the rain, and confirm that the far ring does not look
washed out in rain or at night.

**You need.**

- `P5-AFK-CHECK` done, with *Reduce FPS when* on *Minimized*.
- The viewpoint from `P5-BASELINE-SOAK`.
- BSL on.
- The [measuring rules](#measuring-rules).

**Launch.** The game changes the weather or the time every 90 s on its own,
and takes a screenshot every 45 s:

```bash
VOXY_QUICKPLAY="voxytest" VOXY_FORCE_METAL=1 VOXY_FRAME_TIMING=1 VOXY_AUTO_SCREENSHOT=45 VOXY_DEBUG_CMD='5:gamerule advance_time false;6:gamerule advance_weather false;10:time set noon;12:weather clear;13:tp @p X Y Z YAW PITCH;100:weather rain;190:weather thunder;280:time set midnight;370:weather clear;460:time set noon' ./gradlew runClient -PrunXmx=6G
```

**Steps.**

1. Launch and click the window once. Stay still for about 9 minutes.
2. You do not need to press F2. The game saves the screenshots.
3. Quit and run `log-check.sh`.
4. Save the log and split the fps by phase:

   ```bash
   cp run/logs/latest.log ~/voxy-test-results/P5-WEATHER.log
   grep -F -e '[VOXY_DEBUG_CMD] running' -e '[Metal-LayerB' run/logs/latest.log | sed -E 's/^\[([0-9:]+)\].*(running: .*|fps=-?[0-9.]+).*/\1 \2/'
   ```

5. Compare `[Metal-TIMING]` and `[Metal-VXTIMING]` between clear and rain.
6. Check the fog cap:

   ```bash
   grep -F 'vx fog cap' run/logs/latest.log
   ```

7. Look at the screenshots of each phase in `run/screenshots/`, above all the
   rain and night ones.
8. Finish with step 5 of [After every test](#after-every-test).

**Look for.**

- The fps in each phase.
- Which waits change with rain and with thunder.
- If rain fps divided by clear fps is below 0.7, find the value that grew:
  `hotWait`, `drawFlushWait`, `bridgeWait`, `jniDrawLoop` or a `VXTIMING`
  pass. If none grew, the cost is the pack's own.
- Each automatic screenshot reads the frame back from GL. Expect a small
  hitch every 45 s, not a trend.

**Log lines.**

```
[VOXY_DEBUG_CMD] running: /weather rain
[Metal-LayerB f=
[Metal-TIMING f=
[Metal-LODTEST] vx fog cap ON
vx fog cap FAILED
```

**A/B.** `VOXY_VX_FOG_CAP=0` and `VOXY_LIGHTMAP_SYNC_EVERY_FRAME=1`.

**Pass.** Rain fps divided by clear fps is 0.7 or more, or your report names
the value that grew. `[Metal-LODTEST] vx fog cap ON` appears without
`FAILED`, the far ring does not look washed out in the rain and night
screenshots, and `[Metal-FLICKER]` stays stable.

**Fail.** The ratio is below 0.7 and no value explains it, the far ring looks
washed out, `vx fog cap FAILED` appears, or `FLICKER` is unstable. If the fps
stays near 30 in every phase, the AFK cap was on. Repeat the run.

---

## When you finish

1. Quit the game fully.
2. Set back what this part changed: *Use sodium threads* ON, your *GUI Scale*,
   your *Brightness*, and both render distances. Leave *Reduce FPS when* on
   *Minimized* if you go on to part 3.
3. Restore the settings you backed up in
   [part 1](developer-track-part1.md#back-up-your-settings), or check them with
   `bash scripts/testing/settings-dump.sh`.
4. `grep enableShaders run/config/iris.properties` must print
   `enableShaders=true`. If it prints false, launch, press K once, check that
   BSL is on, and quit.
5. Report each session as described in the
   [testing overview](README.md#how-to-report). For each test, give the ID,
   the result (Pass, Fail, Partial or Blocked) and what you saw. Attach the
   report zip, your F2 screenshots and the numbers you wrote down, such as
   fps, wait times and `footprint_mb`.
6. After a crash, `collect-report.sh` includes the newest crash report and
   `hs_err_pid*.log` if they are less than 60 minutes old. Older files may be
   from earlier sessions, so check the date before you attach one by hand.
