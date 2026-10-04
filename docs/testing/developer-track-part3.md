# Developer track, part 3: underwater, caves, world generation, release jar

This part covers phases 6, 7 and 8 of the test plan:

| Phase | Test IDs | Topic |
|---|---|---|
| 6 | `UC-00` to `UC-14` | LODs underwater, at the sea surface, on beaches and rivers, and in caves |
| 7 | `P7-*`, `7.1` to `7.7` | Stress while new terrain generates, with Chunky |
| 8 | `P8-*` | The release jar in a clean launcher profile |

Phases 0 to 5 are in [part 1](developer-track-part1.md) and
[part 2](developer-track-part2.md). How to report is in the
[testing overview](README.md#how-to-report).

## Conventions for this part

### Where you run things

- Run every command from the repository root. All paths here are relative to
  it: `run/`, `build/libs/`, `scripts/testing/`.
- The dev client's game folder is `run/`. The testing scripts find it on their
  own when you run them from the repository.
- Phase 8 uses a launcher game folder instead. It sets `GAME` for that.

### Launching the dev client

The standard launch for phases 6 and 7:

```bash
VOXY_QUICKPLAY="voxytest" VOXY_FORCE_METAL=1 ./gradlew runClient -PrunXmx=6G
```

- `VOXY_FORCE_METAL=1` is required. Without it, Voxy turns itself off.
- `VOXY_QUICKPLAY="<world folder>"` joins that world on launch.
- `-PrunXmx=6G` caps the Java heap like a launcher profile with `-Xmx6G`.
- Put every variable on the same line, before `./gradlew`. Do not `export`
  them, so they do not leak into the next run.
- An A/B test adds one `VOXY_*` variable to the standard launch. Quit the game
  fully before you relaunch: *Esc > Save and Quit to Title > Quit Game*.
- `VOXY_DEBUG_CMD='8:tp @p X Y Z YAW PITCH'` runs server commands a fixed
  number of seconds after you join. Separate commands with `;` and write them
  without the leading `/`. Use `@p`, not `@s`. The log shows
  `[VOXY_DEBUG_CMD] running: /...` for each one.

### Worlds and settings

- `voxytest` is the Creative world from the setup in part 1, with *Allow
  Cheats* ON and `/gamerule advance_time false` and
  `/gamerule advance_weather false`.
- `voxy-stress-gen` is created in test `7.1`.
- Keep *Video Settings > Sodium > Performance > Reduce FPS when* on
  *Minimized*. With *AFK*, Minecraft drops to 30 fps after 60 s without input.
- Press F3+P in a world until the chat says "Pause on lost focus: disabled".
  Otherwise the game pauses when you click the Terminal.
- The tests use Minecraft *Render Distance* 32. If your Mac cannot hold 32,
  use your usual value and say so in the report. Log values that depend on it
  change: `rdBlocks` is the render distance times 16, and `cullDist` is
  `rdBlocks` minus 16.

### Shader pack, keys and screenshots

- Iris is always loaded in the dev client. "No pack" means shaders turned off
  with K.
- K turns shaders on or off. The chat says "Toggled shaders to ...". O opens
  the pack list. R reloads shaders.
- The BSL tests use BSL v10.1.3. Download it from its official page and put
  `BSL_v10.1.3.zip` in `run/shaderpacks/`.
- Check the saved shader state with `grep enableShaders run/config/iris.properties`.
- Voxy's F3 lines: press F3+F6, find the row named `debug` and set it to
  *In Overlay*. Then F3 shows `I/S/AWSC:` and `UC/GC,#N:`.
- F2 saves a screenshot to `run/screenshots/`. Press F1 first to hide the HUD.
- On a MacBook, press fn with the F keys (fn+F3), unless you use F1, F2 and so
  on as standard function keys.
- For a repeatable view, use `/tp @s X Y Z YAW PITCH`. Pitch 0 is the horizon,
  -30 is 30 degrees up and 30 is 30 degrees down.

### After every test

1. Quit to the title screen or quit the game.
2. Check the log. Every failure count must be 0:

   ```bash
   bash scripts/testing/log-check.sh
   ```

   `[Metal-CULL] ... back faces of water WILL rasterise` is information only.
   See [Known non-errors](README.md#known-non-errors).
3. Keep a copy of the log under the test ID. The next launch overwrites it.
   Some tests compare against an earlier copy.

   ```bash
   mkdir -p ~/voxy-test-results && cp run/logs/latest.log ~/voxy-test-results/UC-02.log
   ```

   Change `UC-02` to the test you just ran.
4. At the end of the session, build the report zip and attach it to your
   [Test report](README.md#how-to-report) issue with your screenshots:

   ```bash
   bash scripts/testing/collect-report.sh --redact
   ```

### Memory and threads

Tests that track memory ask you to run the sampler in a second Terminal tab,
after the game window appears:

```bash
bash scripts/testing/sampler.sh
```

It writes `~/voxy-test-results/sampler-<date>.csv` and, every 5th sample,
grouped Java thread names. The count of `Dedicated Voxy Worker #` threads must
stay the same through a test. It stops when the game exits or on Ctrl+C.

### Reference numbers

Numbers such as fps, disk growth and CPU come from the reference Mac: an M4
Max with 48 GB on macOS 26. Your numbers will differ. Most pass rules compare
your own before and after values. Report your absolute numbers anyway.

---

## Phase 6: Underwater and caves

All tests use the `voxytest` world. Each test says whether it runs without a
pack, with BSL, or both. `UC-00` turns on *Enable environmental fog* and
prepares the test sites. `UC-14` ends by restoring that setting. Always do the
last two steps of `UC-14`.

| ID | Test | Priority | Min | Pack |
|---|---|---|---|---|
| `UC-00` | Prepare the world, settings and test sites | Essential | 20 | None |
| `UC-01` | Underwater without a pack: no LOD shapes while submerged | Essential | 10 | None |
| `UC-02` | Underwater with BSL: pack fog, no LODs, no pale panes | Essential | 10 | BSL |
| `UC-03` | In and out of the water: no flicker, no ghost water | Recommended | 10 | Both |
| `UC-04` | Short render distance underwater | Recommended | 15 | Both |
| `UC-05` | *Enable environmental fog* OFF: water, lava and blindness | Recommended | 10 | Both |
| `UC-06` | Dark deep-ocean floor versus shallow water at LOD distance | Essential | 20 | Both |
| `UC-07` | Beaches, rivers, kelp and seagrass at LOD distance | Recommended | 15 | Both |
| `UC-08` | Abyss fill and seafloor dim: off by default, active on the injection path | Recommended | 15 | BSL |
| `UC-09` | Caves without a pack: nothing shows through walls, floor or ceiling | Essential | 15 | None |
| `UC-10` | Caves with BSL: no see-through, and check whether brief holes flash bright | Essential | 15 | BSL |
| `UC-11` | Cave mouths, ravines and overhangs seen from far away | Recommended | 20 | Both |
| `UC-12` | Deep Dark and darkness effects | Recommended | 10 | Both |
| `UC-13` | Lava, torch and glowstone light at LOD distance, at night | Recommended | 15 | Both |
| `UC-14` | Spectator mode through terrain, then restore settings | Optional | 10 | Both |

Short on time? Do `UC-00`, `UC-01`, `UC-02`, `UC-03`, `UC-06`, `UC-09`,
`UC-10` and `UC-11`. Then do the last two steps of `UC-14` anyway.

### UC-00: Prepare the world, settings and test sites

**Priority:** Essential. **Time:** 20 min. **Pack:** none.
**Depends on:** `P1-SETUP`.

**Goal.** Put everything in a known state for the water and cave tests. Turn
on *Enable environmental fog*. With it OFF, `UC-01` to `UC-04` are not valid.

**You need.**

- Phases 0 and 1 passed: the game starts and shows LODs without a pack.
- The `voxytest` world, Creative, *Allow Cheats* ON, time and weather frozen.

**Settings.**

- Change: *Enable environmental fog* to ON in *Options > Video Settings >
  Voxy > Rendering*, then Apply. Voxy's default is ON.
- Change: time to noon and weather to clear.
- Check only: *Render Distance* 32, and *FOV Effects* at 100% in
  *Options > Accessibility Settings*.
- Check only: *Use Fog Occlusion* ON in *Video Settings > Sodium > Performance*.

**Launch.** The standard launch.

**Steps.**

1. Before you launch, note your current fog value. `UC-14` restores it.

   ```bash
   grep use_environmental_fog run/config/voxy-config.json
   ```

2. Launch and wait until you are in the world.
3. Press T and type `/gamemode creative`, then `/time set noon`, then
   `/weather clear`.
4. If BSL is on, press K to turn it off.
5. Open *Options > Video Settings > Voxy > Rendering*. Set *Enable
   environmental fog* to ON and press Apply. Voxy's renderer reloads and LODs
   come back in about 20 s.
6. In Terminal, check that the last `[Metal-DEFINES]` line ends in
   `+ USE_ENV_FOG`:

   ```bash
   grep -E 'Metal-DEFINES|bound-mask trans split|NormalRenderPipeline' run/logs/latest.log
   ```

7. Find the test sites and write down their coordinates. The green
   coordinates in a `/locate` reply are clickable. They put a `/tp` in the
   chat. Press Enter.
   - Water: `/locate biome minecraft:deep_ocean`,
     `/locate biome minecraft:cold_ocean` (kelp and seagrass),
     `/locate biome minecraft:beach`, `/locate biome minecraft:river`. If
     `cold_ocean` is not found, try `minecraft:ocean` or
     `minecraft:lukewarm_ocean`.
   - Caves: `/locate biome minecraft:lush_caves`,
     `/locate biome minecraft:dripstone_caves` and
     `/locate structure minecraft:ancient_city`. The ancient city is in the
     Deep Dark, with its floor near Y -51.
   - A steep slope next to flat, open ground.
8. Visit each site and stay 30 to 60 s, so Voxy stores LODs around it. Voxy
   only shows far terrain that you or Chunky loaded.
9. Optional, for the ocean: at the deep-ocean site type `/chunky center`,
   `/chunky radius 1000` and `/chunky start`. Check with `/chunky progress`
   until it finishes. Then type `/chunky cancel` and `/chunky confirm`, so no
   saved task is left.
10. Build the cave mouth. Stand at the foot of the slope, face it, and type
    `/fill ^-5 ^0 ^2 ^5 ^10 ^40 minecraft:air`. This opens an 11 by 11 tunnel,
    39 blocks deep. Note its coordinates.
11. Build the light scene on flat ground:
    `/fill ~3 ~-1 ~3 ~12 ~-1 ~12 minecraft:lava` makes a lava pool, and
    `/fill ~-12 ~ ~-12 ~-3 ~ ~-3 minecraft:glowstone` makes a glowstone
    patch. Place some torches around. Note the coordinates.
12. Optional, to automate the start of later tests, add this to the launch:
    `VOXY_DEBUG_CMD="5:gamemode creative @p;6:time set noon;7:weather clear;8:tp @p X 63 Z 0 0"`.

**Look for.**

- After Apply, the `[Metal-DEFINES]` line without a pack ends in
  `+ USE_ENV_FOG`.
- Far LOD terrain around each site.

**Log lines.**

```
[Metal-DEFINES] terrain shader injections: depth-bound ON + VOXY_FORCE_OPAQUE_ALPHA + atlas bakery + USE_ENV_FOG
[Metal-LODTEST] bound-mask trans split OFF
Voxy renders LODs with its NormalRenderPipeline: shaders are off.
[VOXY_DEBUG_CMD] running: /
```

**Pass.** After Apply, `[Metal-DEFINES]` ends in `+ USE_ENV_FOG`. You have
the coordinates of every site, both scenes are built, and far terrain shows
around each site.

**Fail.** `+ USE_ENV_FOG` does not appear after you turn the option on,
`/locate` cannot find a biome, or far LODs are missing around the sites.

### UC-01: Underwater without a pack: no LOD shapes while submerged

**Priority:** Essential. **Time:** 10 min. **Pack:** none.
**Depends on:** `UC-00`.

**Goal.** With your eyes underwater, vanilla fog ends before 96 blocks, so
far LOD terrain must not show. Check that it looks like vanilla, stays stable,
and the seafloor is never see-through.

**You need.** Shaders off (K), *Enable environmental fog* ON, *Render
Distance* 32, *FOV Effects* 100%.

**Launch.** The standard launch.

**Steps.**

1. Go to the deep-ocean surface with `/tp @s X 63 Z 0 0`. Wait 30 s. Press F2
   and label it `UC01-surface`.
2. Swim down about 10 blocks, holding Shift. Stop and do not touch the mouse
   for 30 s.
3. Take three F2 from the same spot: level with `/tp @s ~ ~ ~ 0 0`, up with
   `/tp @s ~ ~ ~ 0 -30`, down with `/tp @s ~ ~ ~ 0 45`.
4. Swim to the bottom and along it for 1 minute. Enter an underwater cave or
   ravine if you see one.
5. Swim back up and wait 10 s.
6. In Terminal:

   ```bash
   grep -E 'Metal-VIEWPORT|FRUSTUM-DELTA|Metal-LayerB|Metal-FLICKER|Metal-DIAG' run/logs/latest.log | tail -40
   ```

7. A/B: quit and relaunch with `VOXY_UNDERWATER_LOD=1` added. This forces LODs
   to draw underwater. Take the same three F2.
8. If the fog looks the same in the A/B, that is fine. If see-through sand,
   flooded-looking caves or flicker appear only with `=1`, the default
   underwater LOD cutoff is still needed. Note it.

**Look for.**

- Far away, in every direction, only a uniform water-fog color, as in
  vanilla. No islands, far seafloor, coast or sky.
- Near sand and gravel never turn see-through. No square holes to far blocks.
- No flicker while you stay still underwater.
- Fps underwater equal to or higher than above water, because Voxy stops
  drawing LODs.
- One `[Metal-VIEWPORT]` warning the first time you dive is normal.
- `fov UNSTABLE` then `fov stable` when you dive, because *FOV Effects*
  narrows the view in water.
- `renderList.sectionCount` does not drop to a tiny number, around 16,
  underwater.
- In the window when you stayed still underwater, `[Metal-FLICKER]` shows
  `min` equal to `max` and `changes=0`, and `[Metal-DIAG]` shows `cam=` with
  a height below 63.

**Log lines.**

```
[Metal-VIEWPORT] GL_VIEWPORT
[Metal-FRUSTUM-DELTA] fov UNSTABLE
[Metal-FRUSTUM-DELTA] fov stable
renderList.sectionCount=
[Metal-FLICKER f=
changes=0
[Metal-DIAG f=
```

**A/B.** `VOXY_UNDERWATER_LOD=1`.

**Pass.** Uniform fog in every direction, a solid seafloor and no flicker.
The render list is stable and `[Metal-FLICKER]` shows `changes=0` while you
stay still.

**Fail.** Any far LOD shape shows through the fog, the seafloor is
see-through or has holes, or it flashes. Also a fail: `renderList.sectionCount`
drops to about 16, or `[Metal-FLICKER]` has `min` different from `max` with a
still camera.

### UC-02: Underwater with BSL: pack fog, no LODs, no pale panes

**Priority:** Essential. **Time:** 10 min. **Pack:** BSL.
**Depends on:** `UC-00`, `UC-01`.

**Goal.** With a pack, BSL draws the underwater fog and Voxy still draws no
LODs underwater. Check that no LOD shapes, pale rectangles or sky-colored
holes appear.

**You need.** BSL on (K) with `BSL_v10.1.3.zip` picked (O). *Enable
environmental fog* ON: this setting controls the underwater cutoff even with a
pack. *Render Distance* 32.

**Launch.** The standard launch.

**Steps.**

1. Press K to turn BSL on. If the pack is not `BSL_v10.1.3.zip`, press O and
   pick it.
2. Use the same site and framing as `UC-01`. Wait 30 s at the surface. Press F2
   and label it `UC02-surface`.
3. Go down about 10 blocks and stay still 30 s. Take the level, up and down F2
   with the same `/tp` commands as `UC-01`.
4. Swim near a coast, where the far ocean and shore would be in view. Then
   swim along the bottom for 1 minute.
5. Swim up. In Terminal:

   ```bash
   grep -E 'bound-mask trans split|near-cull|FRUSTUM-DELTA|Metal-LayerB' run/logs/latest.log | tail -30
   grep enableShaders run/config/iris.properties
   ```

   The second line must print `enableShaders=true`.
6. A/B: relaunch with `VOXY_UNDERWATER_LOD=1`. Now only BSL's water fog hides
   the LODs. Repeat the underwater screenshots.
7. If pale panes or LOD shapes appear only with `=1`, the cutoff is still
   needed with packs. Save an F2 and note it.

**Look for.**

- Only BSL's blue-green fog. No far land, seafloor or coast.
- No pale or bright rectangles ("panes") floating in the water. No bright
  sky-colored holes.
- No ghost squares of water surface right at the waterline.
- `fov UNSTABLE` and `fov stable` pairs in the log when you dive.
- The line `trans near-cull runtime` with `rdBlocks=512.0 cullDist=496.0`
  appears once per launch.

**Log lines.**

```
[Metal-LODTEST] bound-mask trans split ON
[Metal-LODTEST] translucent near-cull ON (vx contract: no LOD water inside MC render distance; metric=xz-radial, margin=16.0)
[Metal-LODTEST] trans near-cull runtime: rdBlocks=512.0 cullDist=496.0
[Metal-FRUSTUM-DELTA] fov UNSTABLE
[Metal-FRUSTUM-DELTA] fov stable
[Metal-LayerB f=
```

**A/B.** `VOXY_UNDERWATER_LOD=1`.

**Pass.** Only the pack's fog, with no LODs, no pale panes and no ghost
squares at the waterline. `iris.properties` still says `enableShaders=true`.

**Fail.** LOD shapes, pale panes or sky holes underwater, or ghost squares at
the waterline. Also a fail: the shaders turned themselves off.

### UC-03: In and out of the water: no flicker, no ghost water

**Priority:** Recommended. **Time:** 10 min. **Pack:** both.
**Depends on:** `UC-01`, `UC-02`.

**Goal.** Floating at the surface, Minecraft switches many times between eyes
in and out of the water. Check that the waterline does not flicker or leave
ghost squares, without a pack and with BSL.

**You need.** *Enable environmental fog* ON, *Render Distance* 32, *FOV
Effects* 100%.

**Launch.** The standard launch.

**Steps.**

1. Press K to turn BSL off.
2. Float on a calm deep ocean with your eyes at the waterline, looking at the
   horizon, for 30 s. Take three F2 in a row.
3. Dive and surface 10 times, 1 to 2 s each.
4. Swim along the surface for 30 s, tapping Space so your head goes in and out
   of the water.
5. Press K to turn BSL on and repeat steps 2 to 4.
6. In Terminal. Every `fov UNSTABLE` must be followed by `fov stable`:

   ```bash
   grep -E 'FRUSTUM-DELTA' run/logs/latest.log | tail -40
   ```

7. A/B 1: relaunch with `VOXY_FOG_SMOOTH_MS=0` and repeat steps 2 to 4. This
   removes fog smoothing. Expect flicker at the waterline. That proves the
   guard works.
8. A/B 2, BSL only: relaunch with `VOXY_TRANS_SUBMERSION_CLEAR=0` and repeat
   steps 2 to 4. This restores the old behavior. Expect ghost water squares
   that pop in and out at the waterline.

**Look for.**

- When you dive, fog arrives almost at once. When you surface, a haze of about
  0.4 s, then the far LOD view returns. That is on purpose.
- While floating, the far view must NOT switch between landscape and fog color
  on every bob.
- With BSL, no pale or ghost water squares right as you cross the waterline.
- No `fov UNSTABLE` without a later `fov stable` in the log.

**Log lines.**

```
[Metal-FRUSTUM-DELTA] fov UNSTABLE
[Metal-FRUSTUM-DELTA] fov stable
```

**A/B.** `VOXY_FOG_SMOOTH_MS=0`, `VOXY_TRANS_SUBMERSION_CLEAR=0`.

**Pass.** No full-screen flicker while floating, only the short planned haze
on surfacing. With BSL, no ghost squares at the waterline.

**Fail.** The far view flashes on every bob, or LODs pop in and out. Also a
fail: with BSL, water squares appear or freeze at the waterline.

### UC-04: Short render distance underwater

**Priority:** Recommended. **Time:** 15 min. **Pack:** both.
**Depends on:** `UC-01`, `UC-02`.

**Goal.** At *Render Distance* 12 or less, Voxy draws LODs underwater again
once your underwater vision improves, within 30 s, and only the fog hides
them. Check that nothing changes when vision clears, and that the vanilla edge
and LOD water are not visible from below.

**You need.** *Enable environmental fog* ON.

**Settings.**

- *Render Distance* 12, then 6, in *Video Settings > Sodium > General*.
- The `trans near-cull runtime` line appears once per launch and only with
  BSL. To see it with the new value, change the distance before you launch.
- *Use Fog Occlusion* in *Sodium > Performance*. Default ON. The A/B changes it.

**Launch.** The standard launch.

**Steps.**

1. Press K if BSL is on. Set *Render Distance* to 12.
2. In the deep ocean, go down about 5 blocks. Look at the horizon and stay
   still 40 s. Press F2 at 5 s and again at 35 s.
3. Look toward the far surface with `/tp @s ~ ~ ~ 0 -30` and press F2. Look
   down with `/tp @s ~ ~ ~ 0 30` and press F2.
4. In-game A/B, no relaunch: at *Render Distance* 12, set *Use Fog Occlusion*
   OFF, then ON. If a see-through floor near the fog limit appears only with
   ON, note it.
5. Set *Render Distance* to 6 and repeat steps 2 and 3.
6. Press K to turn BSL on. Set *Render Distance* to 12 and repeat steps 2, 3
   and 5.
7. Set *Render Distance* back to 32.
8. Optional: relaunch with `VOXY_UNDERWATER_LOD=1` at *Render Distance* 32 to
   compare.

**Look for.**

- Nothing changes around 30 s. LODs start drawing but stay covered by fog.
- No vanilla world edge, which sits 96 to 192 blocks away. No ring, wall,
  bright band or circle of another color.
- Looking up, no bright sheet or sky-colored hole where LOD water would start.
- With BSL at 6, faint far shapes are acceptable only if they have the same
  dark fog color. No pale panes.
- With BSL and *Render Distance* 12 set before launch, the log shows
  `rdBlocks=192.0 cullDist=176.0`.

**Log lines.**

```
[Metal-LODTEST] trans near-cull runtime: rdBlocks=192.0 cullDist=176.0
[Metal-LayerB f=
```

**A/B.** `VOXY_UNDERWATER_LOD=1`.

**Pass.** Uniform fog at 12 and at 6 in both modes. From below, no edge and no
LOD water sheet.

**Fail.** The far view changes about 30 s after you dive, an edge or ring
shows, or the floor is see-through. Also a fail: a bright or pale sheet above.

### UC-05: Enable environmental fog OFF: water, lava and blindness

**Priority:** Recommended. **Time:** 10 min. **Pack:** both.
**Depends on:** `UC-00`, `UC-04`.

**Goal.** Document what OFF does. It removes vanilla environmental fog while
Voxy draws, and it turns off the underwater LOD cutoff in both modes. The
result helps decide which default to keep.

**You need.** The lava pool from `UC-00`. `UC-01` to `UC-04` done.

**Settings.** *Enable environmental fog* OFF in *Voxy > Rendering* for the
first steps, then ON again at the end. Apply each change.

**Launch.** The standard launch.

**Steps.**

1. Press K if BSL is on. Open *Options > Video Settings > Voxy > Rendering*,
   set *Enable environmental fog* to OFF and press Apply.
2. In the deep ocean, dive. Take level, up and down F2.
3. Type `/effect give @s minecraft:fire_resistance 600 0 true`. Walk into the
   lava pool until your eyes are in the lava, and press F2.
4. On land, type `/effect give @s minecraft:blindness 10` and press F2.
5. In Terminal. The line must NOT end in `+ USE_ENV_FOG`:

   ```bash
   grep -F '[Metal-DEFINES]' run/logs/latest.log | tail -1
   ```

6. Press K to turn BSL on, fog still OFF. Repeat the dive (step 2) and the lava
   (step 3).
7. Set *Enable environmental fog* back to ON and press Apply. Press K to turn
   BSL off.
8. Repeat steps 2 to 4 as a reference. The new `[Metal-DEFINES]` line must end
   in `+ USE_ENV_FOG`.

**Look for.**

- Fog OFF, no pack: no water fog. The seafloor and LOD shapes show through the
  water. That is what this switch does, in upstream Voxy too.
- Fog OFF in lava: you see through the lava. Vanilla shows 1 block, or 5 with
  fire resistance. A known limitation of this fork.
- Fog OFF with blindness: no black fog.
- Fog OFF with BSL: BSL's water fog stays, but Voxy keeps drawing LODs
  underwater. Look for pale panes or a see-through floor.
- Fog ON: water, lava and blindness fog match vanilla.

**Log lines.**

```
[Metal-DEFINES] terrain shader injections:
+ USE_ENV_FOG
```

**Pass.** With fog ON, vanilla water, lava and blindness fog are back. With
fog OFF, everything behaves as described, with no crash, and with BSL no LODs
show through the pack's water fog.

**Fail.** With fog ON, lava or water fog is still missing. Or, with BSL and fog
OFF, LOD shapes or pale panes show underwater. Report that second case as a
finding, because OFF disables the underwater cutoff.

### UC-06: Dark deep-ocean floor versus shallow water at LOD distance

**Priority:** Essential. **Time:** 20 min. **Pack:** BSL, then none.
**Depends on:** `UC-00`.

**Goal.** With BSL, the minimum light of the LOD seafloor drops with the depth
of water above it. Check that the far deep ocean has a dark floor, that
beaches and shallows stay lit, and that without a pack the deep floor does not
look like bright sand.

**You need.** A coast site from `UC-00` where deep ocean meets a beach.

**Settings.** *Render Distance* 8. LODs start at about 112 to 128 blocks, so
the far floor fills much of the screen. Time noon, then midnight.

**Launch.** The standard launch.

**Steps.**

1. Set *Render Distance* to 8. Press K if BSL is off.
2. Fly to Y 100 above the beach, facing open sea:
   `/tp @s X 100 Z <yaw toward the sea> 20`. Wait 60 s for the LODs to fill.
3. Type `/time set noon`. Press F2 and label it `UC06-default-noon`.
4. Type `/time set midnight`. Press F2 and label it `UC06-default-night`.
5. Quit and relaunch with `VOXY_VX_SKY_FLOOR_UNDERWATER=skip`. Use the same
   `/tp` and take F2 at noon and at midnight.
6. Relaunch with `VOXY_VX_SKY_FLOOR_UNDERWATER=1` and take the same shots.
7. Relaunch with the standard launch. Press K to turn BSL off and take the same
   shots as a no-pack reference.
8. Optional: repeat at `/locate biome minecraft:frozen_ocean`. There, every
   translucent LOD counts as water, ice too.

**Look for.**

- Default: far deep water is darker than the shallows and the beach.
- Water color stays smooth across the vanilla edge, from Sodium water to LOD
  water, with no step or ring.
- Shallow LOD floors are not black.
- With `=1`, far deep water looks clear or shallow because the floor shows.
  That proves the switch works.
- With `=skip`, shallows can look almost black. That is why the default
  follows water depth.
- In the log, `follows the LOD water column` appears by default and
  `skips opaque LOD pixels` with `=skip`. `loc=` values of 0 or more mean it is
  active.
- One WARN `opaque resolve ran without` when you join is acceptable.

**Log lines.**

```
[Metal-LODTEST] vx sky-light floor follows the LOD water column
[Metal-LODTEST] vx sky-light floor skips opaque LOD pixels behind LOD water
MetalVxResolvePass: VOXY_VX_SKY_FLOOR active, sky light floored to
[Metal-LODTEST] vx sky-light floor runtime: opaque uVxHasTransDepth loc=
[Metal-LODTEST] vx sky-light floor: opaque resolve ran without
```

**A/B.** `VOXY_VX_SKY_FLOOR_UNDERWATER=skip`, `VOXY_VX_SKY_FLOOR_UNDERWATER=1`.

**Pass.** By default, deep water is darker than shallow water, beaches are
lit and there is no brightness ring. The A/B runs change the far floor
brightness in the stated direction.

**Fail.** By default, the far deep ocean is as bright as the shallows, or
beaches and riverbeds look almost black. Also a fail: a brightness step at the
vanilla edge.

### UC-07: Beaches, rivers, kelp and seagrass at LOD distance

**Priority:** Recommended. **Time:** 15 min. **Pack:** both.
**Depends on:** `UC-00`.

**Goal.** Shallow LOD water must show its lit floor. Water plants like kelp
and seagrass must not bring back the pale blue square tiles.

**You need.** The river, beach and kelp sites from `UC-00`.

**Settings.** *Render Distance* 8. Time noon.

**Launch.** The standard launch.

**Steps.**

1. Set *Render Distance* to 8 and type `/time set noon`.
2. River: fly to Y 90 above the river site. Look along the river past about
   130 blocks, where LODs start. Press F2 without a pack and again with BSL,
   switching with K.
3. Beach: stand in water 1 or 2 blocks deep. Crouch so your eyes go under,
   then stand up.
4. Kelp: in the kelp ocean, stay at Y 75 looking 30 degrees down at the kelp
   fields past the vanilla edge. Press F2 without a pack and with BSL.
5. A/B: relaunch with `VOXY_WLOG_TINT_FIX=0` and repeat step 4. The pale blue
   tiles over kelp and seagrass should come back, which confirms the fix.
6. Optional, diagnostic only: relaunch with `VOXY_DEBUG_WLOG_TINT=1` instead.
   It paints LOD water plants magenta, so you can see where they are.
7. Set *Render Distance* back to 32.

**Look for.**

- Riverbeds and shallow beach water look lit through LOD water, with no
  almost-black bands.
- With your eyes under shallow water, the far view goes into fog, as in
  vanilla. It returns about 0.4 s after you stand up.
- No pale blue or bright square tiles over kelp or seagrass.
- Far kelp looks like dark green, textured columns underwater, not flat black
  blocks.
- The waterline is continuous across the vanilla edge.

**Log lines.**

```
[Metal-LODTEST] waterlogged-plant tint sentinel guard ON
```

**A/B.** `VOXY_WLOG_TINT_FIX=0`.

**Pass.** Shallows are lit, no tiles over plants, and far kelp is textured in
both modes.

**Fail.** Pale blue tiles over kelp or seagrass, or black riverbeds and
shallows. Also a fail: the waterline breaks at the edge.

### UC-08: Abyss fill and seafloor dim: off by default, active on the injection path

**Priority:** Recommended. **Time:** 15 min. **Pack:** BSL.
**Depends on:** `UC-02`, `UC-06`.

**Goal.** Confirm that the abyss fill (a synthetic dark seafloor) and the
water-column seafloor dim are NOT active with BSL by default. Then check that
they still work on the alternative injection path.

**You need.** The saved logs of `UC-02` and `UC-06`. BSL on. *Render
Distance* 32, set before launch. The `UC-06` coast viewpoint. Time noon.

**Launch.** The injection path, with the abyss fill painted bright green:

```bash
VOXY_QUICKPLAY="voxytest" VOXY_FORCE_METAL=1 VOXY_VX_MATERIAL_OPAQUE=0 VOXY_VX_ABYSS_DEBUG=1 ./gradlew runClient -PrunXmx=6G
```

**Steps.**

1. Before you launch, check the saved `UC-02` log. It must print nothing:

   ```bash
   grep -E 'seafloor water-column dim|abyss fill' ~/voxy-test-results/UC-02.log
   ```

   No saved copy? Use `run/logs/latest.log` from any normal BSL session.
2. Launch with the command above.
3. Right after you join, press K if BSL is off, type `/time set noon` and go to
   the coast viewpoint. Press F2 every 10 s for the first minute, and again at
   3 minutes.
4. In the same session, dive, look around and press F2.
5. Type `/give @s minecraft:spyglass`. Leave the water and look at the ocean
   through the spyglass for 10 s.
6. Relaunch with `VOXY_VX_MATERIAL_OPAQUE=0 VOXY_VX_SEAFLOOR_DEBUG=1` instead
   of the two variables above. Red marks the pixels the seafloor dim touches.
   Use the same viewpoint and press F2.
7. Optional, on the injection path: add `VOXY_VX_ABYSS_FILL=0` to turn the fill
   off, or `VOXY_VX_SEAFLOOR_DIM=0` to turn the dim off.

**Look for.**

- Green only where real water has no LOD floor yet: ocean at middle distance,
  in section-shaped patches. It disappears when LOD data arrives.
- Never green on land or while you are underwater. It pauses while you look
  through the spyglass.
- Red stays inside the vanilla ring, on the floor under the water.
- On this path, far LOD land is lit with Minecraft's light, not BSL's. A light
  seam at the ring edge is normal. That is why this path is no longer the
  default.
- `seafloor water-column dim` and `abyss fill ENGAGED` appear only in sessions
  with `VOXY_VX_MATERIAL_OPAQUE=0`.

**Log lines.**

```
[Metal-LODTEST] vx seafloor water-column dim ON
[Metal-LODTEST] seafloor dim first inject frame: engaged=
[Metal-LODTEST] vx abyss fill ENGAGED
abyss fill / seafloor dim / request boost PAUSED
```

**A/B.** `VOXY_VX_MATERIAL_OPAQUE=0`, `VOXY_VX_ABYSS_FILL=0`,
`VOXY_VX_SEAFLOOR_DIM=0`.

**Pass.** Neither line appears in normal sessions. With
`VOXY_VX_MATERIAL_OPAQUE=0`, the fill corrects itself within a few minutes,
never covers real terrain and never appears underwater.

**Fail.** Injection-path lines appear in a normal session. Also a fail: green
stays over areas that already have LOD data, appears on land, or appears
underwater.

### UC-09: Caves without a pack: nothing shows through walls, floor or ceiling

**Priority:** Essential. **Time:** 15 min. **Pack:** none.
**Depends on:** `UC-00`.

**Goal.** Minecraft draws its terrain over the LOD image, so nothing should
show through rock. LODs may only peek for an instant through holes Minecraft
has not built yet, and they must go away on their own.

**You need.** The lush caves and dripstone caves coordinates. Shaders off.
*Enable environmental fog* ON. *Render Distance* 32.

**Launch.** The standard launch.

**Steps.**

1. Press K if BSL is on. Type `/gamemode spectator`, then `/tp @s X Y Z` with
   the lush caves coordinates.
2. Fly into an open cave room. Type `/gamemode creative` and wait 20 s.
3. Stay still. Press F2 facing four directions: `/tp @s ~ ~ ~ 180 0`, then
   `270 0`, `0 0` and `90 0`. Then up with `/tp @s ~ ~ ~ 0 -90` and down with
   `/tp @s ~ ~ ~ 0 90`, with an F2 each.
4. Fly through the tunnels in Creative for 2 minutes, first at normal speed,
   then sprinting.
5. Open a new hole. Dig a 1 by 2 tunnel 8 to 10 blocks into the rock until you
   break into an unknown cave. Watch the opening during the first second.
   macOS screen recording (Cmd+Shift+5) catches one-frame flashes that F2
   misses. Use it here if you can.
6. Teleport straight to the dripstone caves coordinates and watch the first
   2 seconds. Repeat step 3 there.
7. Compare fps underground and at the surface in the log:

   ```bash
   grep -E 'NormalRenderPipeline|bound-mask trans split|Metal-DEFINES|Metal-LayerB|Metal-DIAG' run/logs/latest.log | tail -30
   ```

8. A/B: relaunch with `VOXY_BOUND_DEBUG=1` and repeat step 3. LOD pixels that
   the mask would discard are painted red. In a closed cave, standing still,
   there must be NO red.
9. Optional: relaunch with `VOXY_NO_DEPTH_BOUND=1`, which turns the mask off.
   Caves at rest must look the same, because the walls cover the LODs. Only
   brief holes may differ.

**Look for.**

- At rest, only cave walls. No far mountains, sky, water or LOD shapes through
  walls, floor or ceiling.
- Right after a teleport or a new hole, fog-colored patches or blocky shapes
  for much less than a second are allowed. They must vanish on their own.
- Fps underground about equal to the surface. That is normal: on Metal, Voxy
  does not cull hidden LODs.
- In the cave, `[Metal-DIAG]` shows `cam=` with a height below ground level.
- With `VOXY_BOUND_DEBUG=1`, red marks a spot where Minecraft draws no terrain.

**Log lines.**

```
Voxy renders LODs with its NormalRenderPipeline: shaders are off.
[Metal-LODTEST] bound-mask trans split OFF
[Metal-DEFINES] terrain shader injections: depth-bound ON
+ USE_ENV_FOG
[Metal-DIAG f=
[Metal-LayerB f=
```

**A/B.** `VOXY_BOUND_DEBUG=1`, `VOXY_NO_DEPTH_BOUND=1`.

**Pass.** In neither cave does anything show through rock at rest. Any brief
patch disappears in under 1 s.

**Fail.** Far terrain, sky or LOD shapes show through solid terrain while you
stand still, or a patch stays.

### UC-10: Caves with BSL: no see-through, and check whether brief holes flash bright

**Priority:** Essential. **Time:** 15 min. **Pack:** BSL.
**Depends on:** `UC-09`.

**Goal.** With BSL, LOD pixels are used only where there is no real terrain,
so nothing should show through rock at rest. The risk is that brief holes look
bright, like daylight, instead of dark.

**You need.** BSL on. *Render Distance* 32.

**Launch.** The standard launch.

**Steps.**

1. Press K to turn BSL on.
2. Repeat steps 1 to 7 of `UC-09`, first in lush caves, then in dripstone
   caves, with the same F2 framing.
3. During the new-hole and teleport steps, note whether brief patches look
   bright, like daylight, or dark.
4. In Terminal:

   ```bash
   grep -E 'bound-mask|VOXY_VX_SKY_FLOOR active' run/logs/latest.log | tail -10
   ```

5. A/B: relaunch with `VOXY_VX_SKY_FLOOR=0` and repeat the new hole and the
   teleport. Brief patches should look dark, which confirms the cause. The
   line `VOXY_VX_SKY_FLOOR active` must not appear.
6. Optional: `VOXY_BOUND_DEBUG=1` also works with BSL. No red at rest.
7. At the end, `grep enableShaders run/config/iris.properties` must print
   `enableShaders=true`.

**Look for.**

- At rest, no far terrain, sky or LOD shapes through the walls.
- Brief patches after a teleport or a new hole are fine if they vanish in
  under 1 s. Note whether they are bright.
- `bound-mask split counts` appears about every 10 s.

**Log lines.**

```
[Metal-LODTEST] bound-mask trans split ON
[Metal-LODTEST] bound-mask split counts: opaque=
MetalVxResolvePass: VOXY_VX_SKY_FLOOR active, sky light floored to
```

**A/B.** `VOXY_VX_SKY_FLOOR=0`, `VOXY_BOUND_DEBUG=1`.

**Pass.** Nothing shows through rock at rest and brief patches vanish fast.
If brief patches are bright, report it as a visual finding, not a failure.

**Fail.** You see through rock at rest, or a patch stays. Also a fail: the
shaders are off after the test.

### UC-11: Cave mouths, ravines and overhangs seen from far away

**Priority:** Recommended. **Time:** 20 min. **Pack:** both.
**Depends on:** `UC-00`, `UC-10`.

**Goal.** A cave interior seen from far away should look almost as dark as up
close. With BSL, the LOD interior may look lit and change brightness when it
crosses the vanilla edge.

**You need.** The tunnel from `UC-00`.

**Settings.** *Render Distance* 8. The vanilla world ends at about 128 blocks.
Time noon.

**Launch.** The standard launch.

**Steps.**

1. Set *Render Distance* to 8, type `/time set noon` and press K to turn BSL
   off.
2. From the tunnel mouth, move straight away to about 80 blocks, still in
   vanilla terrain. Aim at the tunnel, press F2 and label it `UC11-near`.
3. Keep going to about 250 blocks, now in the LOD zone, at the same height and
   aiming at the tunnel. Press F2 and label it `UC11-far`. Take another F2
   through the spyglass.
4. If you see a natural ravine or a large cave entrance from far away, take the
   same near and far pair.
5. Press K to turn BSL on and repeat steps 2 to 4.
6. Relaunch with BSL on and `VOXY_VX_SKY_FLOOR=0` added. Repeat steps 2 and 3.
7. Optional: type `/effect give @s minecraft:night_vision 600 0 true` and
   compare near and far again.
8. Optional: relaunch with `VOXY_VX_SKY_FLOOR=15` to exaggerate the effect.

**Look for.**

- The far tunnel interior is almost as dark as the near shot.
- Without a pack, the interior looks dark at a distance.
- With BSL by default: if the interior looks lit like the slope at LOD
  distance but dark up close, note it as a finding. The sky-light floor lights
  LOD caves.
- With `VOXY_VX_SKY_FLOOR=0`, the far interior should get darker. Other far
  surfaces may get darker too. Note it.
- Small caves may close up at a distance, because Voxy merges blocks in the
  coarser LOD levels. That is normal.

**Log lines.**

```
MetalVxResolvePass: VOXY_VX_SKY_FLOOR active, sky light floored to
```

**A/B.** `VOXY_VX_SKY_FLOOR=0`.

**Pass.** Interior brightness is similar near and far in both modes. With
BSL, slightly brighter is acceptable.

**Fail.** The interior looks lit only at LOD distance and jumps between bright
and dark when it crosses the vanilla edge.

### UC-12: Deep Dark and darkness effects

**Priority:** Recommended. **Time:** 10 min. **Pack:** both.
**Depends on:** `UC-00`, `UC-09`.

**Goal.** Deep down, near Y -50, nothing from the LOD world may leak through,
and darkness must look like vanilla. The fog pulses of the Darkness effect
must not make the far LOD field flash.

**You need.** The ancient city coordinates.

**Settings.** *Render Distance* 32.

**Launch.** The standard launch.

**Steps.**

1. Set *Render Distance* to 32 and press K to turn BSL off.
2. Type `/gamemode spectator`, then `/tp @s X -45 Z` with the ancient city
   coordinates. Fly into an open room and type `/gamemode creative`.
3. Stay still 30 s, look around and take four F2.
4. Type `/effect give @s minecraft:darkness 30` and watch for 30 s.
5. Type `/effect give @s minecraft:night_vision 60 0 true` and press F2 to
   compare.
6. Go to the surface with `/gamemode spectator` and `/tp @s ~ 150 ~`. Type
   `/effect give @s minecraft:darkness 20` and watch the horizon.
7. Type `/effect clear @s` and `/gamemode creative`. Press K to turn BSL on and
   repeat steps 2 to 6.

**Look for.**

- Underground, everything is dark as in vanilla, with no bright or
  fog-colored holes and no LOD shapes at rest.
- Night vision brightens the whole view evenly.
- At the surface with Darkness, the far LOD landscape fades in and out with
  the pulses. A short pause of about 0.4 s when the fog clears is normal. A
  strong flash on every pulse is not.
- Fps underground about equal to the surface.
- `[Metal-DIAG]` shows `cam=` with a height between -45 and -50, and
  `[Metal-FLICKER]` shows `changes=0` while you stand still.

**Log lines.**

```
[Metal-LayerB f=
[Metal-DIAG f=
[Metal-FLICKER f=
changes=0
```

**Pass.** Nothing leaks underground, there are no holes, and darkness looks
like vanilla. At the surface, the far field does not flash hard with Darkness.

**Fail.** Bright or fog-colored holes at rest underground, or the far field
flashes hard on every Darkness pulse.

### UC-13: Lava, torch and glowstone light at LOD distance, at night

**Priority:** Recommended. **Time:** 15 min. **Pack:** both.
**Depends on:** `UC-00`.

**Goal.** Block light must reach far LODs. Lava and lit ground should glow at
a distance about as much as up close.

**You need.** The light scene from `UC-00`, with fresh LOD data.

**Settings.** *Render Distance* 8. Time midnight.

**Launch.** The standard launch.

**Steps.**

1. Set *Render Distance* to 8, type `/time set midnight` and press K to turn
   BSL off.
2. About 60 blocks from the lava pool and the glowstone, press F2 and label it
   `UC13-near`.
3. Fly to about 250 blocks, keeping them in view. Press F2 and label it
   `UC13-far`. Take another F2 through the spyglass.
4. Optional: find natural surface lava or lava in a ravine at night and take
   the same pair.
5. Press K to turn BSL on and repeat steps 2 to 4.
6. If an area whose LODs were saved by a build from before 2026-09-25 looks
   dark, type `/voxy import current` to reimport the current world, and look
   again.
7. Do not run an A/B in this world. `VOXY_MIP_BLOCKLIGHT_FIX=0` writes wrong
   LOD data to disk. If you really need it, use a throwaway world.

**Look for.**

- Lava and the ground around torches and glowstone glow warm at LOD distance,
  with a brightness close to the near view.
- Lava does not look like a black or gray square.

**Log lines.**

```
[Voxy-SYNC] mip block-light packing: upstream 0eb618d1 ON
```

**Pass.** Warm glow at a distance in both modes, close to the near view.

**Fail.** Lava or lit areas look dark or black at LOD distance in freshly
built scenes.

### UC-14: Spectator mode through terrain, then restore settings

**Priority:** Optional. **Time:** 10 min. **Pack:** both.
**Depends on:** `UC-09`, `UC-10`.

**Goal.** Record what is normal when you pass through terrain in spectator
mode. You may see cave interiors, or LOD shapes where vanilla would show sky.
What matters is that nothing breaks or stays wrong when you return to open
air. At the end, restore the phase settings.

**Settings.** *Render Distance* 32. At the end, restore *Enable environmental
fog* to the value you noted in `UC-00`, leave BSL on and *Render Distance* 32.

**Launch.** The standard launch.

**Steps.**

1. No time for this test? Still do the last two steps. They restore the phase
   settings.
2. Set *Render Distance* to 32, type `/time set noon` and press K to turn BSL
   off.
3. Next to a slope, type `/gamemode spectator`. Fly slowly into the rock, stop
   inside and look around. Press F2.
4. Fly under a mountain at Y 0, then lower, at Y -40. Press F2 at each height.
5. Dive into the deep ocean in spectator. Underwater vision clears much faster
   in spectator.
6. Fly out into open air, type `/gamemode creative` and check that the view is
   normal.
7. Press K to turn BSL on and repeat steps 3 to 6.
8. In Terminal:

   ```bash
   grep -E 'ERROR|Exception' run/logs/latest.log | tail -20
   ```

9. Restore the settings in game. Open *Options > Video Settings > Voxy >
   Rendering*, set *Enable environmental fog* to the value you noted in
   `UC-00`, and press Apply. If BSL is off, press K. Leave *Render Distance* at
   32 and type `/effect clear @s`.
10. Finish and check the restore. Quit with *Save and Quit to Title*, then
    *Quit Game*. In Terminal, the first line must show the value you noted in
    `UC-00`, and the second must print `enableShaders=true`:

    ```bash
    grep use_environmental_fog run/config/voxy-config.json
    grep enableShaders run/config/iris.properties
    ```

**Look for.**

- Inside blocks you see caves and the back of the terrain, as in vanilla
  spectator.
- Empty space may show LOD terrain shapes or fog color instead of sky. That is
  expected.
- When you leave the wall, the view is normal, no patches stay, and fps return
  to normal.
- The ERROR grep shows only the harmless Realms errors:
  `Couldn't connect to realms`, `Failed to fetch Realms feature flags` and
  `Failed to fetch user properties`.

**Log lines.**

```
[Metal-LayerB f=
ERROR
Exception
```

**Pass.** No crashes and no lasting faults, and the view is normal outside the
terrain. Settings are restored: environmental fog has your noted value and BSL
is on.

**Fail.** The game crashes, the view freezes or turns black, or patches stay
when you return to open air.

---

## Phase 7: Stress while new terrain generates

This phase tests Voxy while new terrain appears: far teleports with death and
respawn, a long flight, dense foliage, and Chunky pregeneration at growing
radii. Chunky 1.4.55 and spark are already in the dev client.

Order:

1. `P7-FAR-TELEPORTS-DEATH`, `P7-LONG-FLIGHT` and `P7-DENSE-TRANSLUCENT` in
   `voxytest`, with BSL on.
2. Press K to turn shaders off. Do `7.1` to `7.7` in `voxy-stress-gen`.
3. After `7.7`, press K again to turn BSL back on.

Keep a second Terminal tab free for `sampler.sh` or for the live log filter.

| ID | Test | Priority | Min | Pack |
|---|---|---|---|---|
| `P7-FAR-TELEPORTS-DEATH` | Far teleports to new terrain, with death and respawn | Recommended | 12 | BSL |
| `P7-LONG-FLIGHT` | Long Creative flight, 10 to 15 minutes, with rain halfway | Recommended | 20 | BSL |
| `P7-DENSE-TRANSLUCENT` | Dense foliage and water at *Render Distance* 32, with shadows | Recommended | 10 | BSL |
| `7.1` | Chunky basic: radius 1000 around spawn, standing still, new world | Essential | 20 | None |
| `7.2` | Chunky medium: radius 2000 standing still, with memory, CPU and disk | Recommended | 20 | None |
| `7.3` | Chunky large: radius 3000 flying in spectator, the realistic worst case | Optional | 40 | None |
| `7.4` | Flight over the generated area: holes, flicker and stuck LODs | Essential | 20 | None |
| `7.5` | Rejoin and restart: LODs load from storage | Essential | 15 | None |
| `7.6` | Far generation: ingested and saved without drawing, plus a Nether variant | Recommended | 20 | None |
| `7.7` | Quit while Chunky generates: loaded shutdown and the safe-stop rule | Recommended | 35 | None |

### The live log filter

`7.1` to `7.7` use this filter in a second Terminal tab. Start it after the
game has started:

```bash
tail -n 0 -f run/logs/latest.log | grep --line-buffered -E 'Chunky|Metal-(CHAIN|DIAG|LayerB|MEM)|Geometry OOM|had an exception|thread died|OutOfMemoryError'
```

### The error grep

`7.1` to `7.7` end with this check. It must print nothing for the first
command:

```bash
grep -nE 'Geometry OOM|had an exception|thread died|Critical error|OutOfMemoryError|was not alive|World still in use|Not all worlds|dead service|Executing had an error' run/logs/latest.log
grep -E 'Metal-CHAIN' run/logs/latest.log | tail -3
```

New crash reports and `hs_err_pid*.log` files from the last 60 minutes are
caught by `bash scripts/testing/log-check.sh`.

### P7-FAR-TELEPORTS-DEATH: Far teleports to new terrain, with death and respawn

**Priority:** Recommended. **Time:** 12 min. **Pack:** BSL.
**Depends on:** `P5-BASELINE-SOAK`.

**Goal.** 15,000-block jumps force new terrain generation and unload and
reload Voxy's LODs. Dying and respawning right after a jump checks that near
terrain does not mix in chunks from another place.

**You need.**

- BSL on. `grep enableShaders run/config/iris.properties` must print
  `enableShaders=true`. If it prints false, press K in the world.
- The `voxytest` world with *Allow Cheats* ON.
- `bash scripts/testing/sampler.sh` running in a second tab once the game
  window appears.
- Before you launch, note the storage size:
  `du -sh run/saves/voxytest/voxy`.

**Settings.** *Reduce FPS when* on *Minimized*. F3+P shows "Pause on lost
focus: disabled".

**Launch.**

```bash
VOXY_QUICKPLAY="voxytest" VOXY_FORCE_METAL=1 VOXY_FRAME_TIMING=1 VOXY_DEBUG_CMD='10:tp @p 0 120 0;70:tp @p 15000 120 15000;130:tp @p -15000 120 15000;190:tp @p 0 120 -15000;250:tp @p 0 120 0' ./gradlew runClient -PrunXmx=6G
```

**Steps.**

1. Launch. The game joins the world and runs the scheduled jumps for about
   5 minutes.
2. At each stop, stand still and let the LOD ring fill again.
3. After the last jump, type `/kill @s` and click *Respawn*. Watch near terrain
   and the LOD ring for 60 s.
4. Type `/tp @s 20000 120 20000` and right away `/kill @s`. Click *Respawn* and
   press F2.
5. Quit and run `bash scripts/testing/log-check.sh`.
6. Run `du -sh run/saves/voxytest/voxy` again and compare.
7. Run:

   ```bash
   grep -F -e '[Metal-DIAG' -e 'Tried inserting top level pos' -e 'Failed to allocate geometry buffer' -e 'Failed to wait for gpu memory' -e 'child existence is changing to 0' run/logs/latest.log | tail -30
   ```

**Look for.**

- How many times `Inner node child existence is changing to 0, this is mild
  bad` appears. The log caps it at 20.
- How long the LOD ring takes to refill at each stop.
- `sections=` in `[Metal-DIAG]` returns to a similar level at each stop.

**Log lines.**

```
[VOXY_DEBUG_CMD] running: /tp
[Metal-DIAG f=
Tried inserting top level pos
Failed to allocate geometry buffer
Failed to wait for gpu memory
Inner node child existence is changing to 0, this is mild bad
```

**A/B.** `VOXY_SYNC_CHUNK_POS_CHECK=0` turns off the chunk-ring guard.
`VOXY_RD_PROCESS_RATE=20` is the other variant to compare.

**Pass.** Fps returns to its base level about 30 s after each jump, and `geom=`
in `[Metal-DIAG]` does not climb step by step when you return to 0,0. After
each respawn, near terrain and LODs are correct, none of the listed errors
appear, and storage grows only for new areas.

**Fail.** Misplaced or wrong chunks after respawn, `geom` climbing without
end, any of the listed errors, or a crash. If you see misplaced chunks, repeat
with `VOXY_SYNC_CHUNK_POS_CHECK=0` to compare.

### P7-LONG-FLIGHT: Long Creative flight, 10 to 15 minutes, with rain halfway

**Priority:** Recommended. **Time:** 20 min. **Pack:** BSL.
**Depends on:** `P5-BASELINE-SOAK`.

**Goal.** Fly nonstop into new terrain for a long time. Look for fps that
drops over time, memory that climbs without end, and crashes.

**You need.** BSL on. `sampler.sh` running in a second tab.

**Settings.** Optional: *VSync* OFF in *Video Settings > Sodium > General*, so
fps is comparable. *Reduce FPS when* on *Minimized* and "Pause on lost focus:
disabled".

**Launch.**

```bash
VOXY_QUICKPLAY="voxytest" VOXY_FORCE_METAL=1 VOXY_FRAME_TIMING=1 VOXY_AUTO_SCREENSHOT=60 VOXY_DEBUG_CMD='5:time set noon;6:weather clear;300:weather rain;600:weather clear' ./gradlew runClient -PrunXmx=6G
```

**Steps.**

1. Launch. It sets noon and clear weather, starts rain at 300 s and clears it
   at 600 s.
2. The game saves a screenshot every 60 s to `run/screenshots/`. You do not
   need F2.
3. Double-tap Space to fly. Hold W and Left Control to go fast.
4. Fly straight in one direction for 10 to 15 minutes over varied terrain.
5. Turn around and fly back for 2 minutes.
6. Quit and run `bash scripts/testing/log-check.sh`.
7. Run:

   ```bash
   grep -F '[Metal-LayerB' run/logs/latest.log | sed -E 's/^(.{10}).*(fps=[0-9.]+).*/\1 \2/'
   grep -F -e '[Metal-BAKE] warmup burst' -e '[Metal-DIAG' run/logs/latest.log | tail -20
   ```

**Look for.** Fps over time, and the memory slope in the sampler CSV.

**Log lines.**

```
[Metal-LayerB f=
[Metal-BAKE] warmup burst ENGAGED
[Metal-BAKE] warmup burst released
[Metal-DIAG f=
```

**A/B.** `VOXY_BAKE_WARMUP_MS` changes the warmup burst budget.
`VOXY_HOT_REQUEST_FLOOR` is the other variable to compare.

**Pass.** Fps does not fall steadily: in the same weather, the median of the
first 3 `[Metal-LayerB]` windows and of the last 3 differ by less than 15%.
`[Metal-BAKE] warmup burst` changes only a few times after joining, `geom` in
`[Metal-DIAG]` levels off, memory grows less than about 50 MB per minute from
minute 3, and there is no `hs_err` file.

**Fail.** Fps keeps falling, the warmup burst flips again and again or stays
ENGAGED, or `geom` and memory climb without leveling off. Also a fail: the JVM
crashes and leaves a `hs_err_pid*.log` in `run/`.

### P7-DENSE-TRANSLUCENT: Dense foliage and water at Render Distance 32, with shadows

**Priority:** Recommended. **Time:** 10 min. **Pack:** BSL.
**Depends on:** `P5-BASELINE-SOAK`.

**Goal.** Scenes with many leaves and lots of water make Voxy grow a shared
Sodium buffer to its maximum. This test looks for a crash or holes in near
terrain when that happens.

**You need.** BSL on.

**Settings.**

- *Render Distance* 32 in *Video Settings > Sodium > General*.
- Press O, then *Shader Pack Settings > Lighting > Shadow Distance*, and set
  its highest value. Note the old value to restore it.
- Also note *Max Shadow Distance* in *Video Settings > Iris > Settings*. It can
  cap BSL's *Shadow Distance*.

**Launch.**

```bash
VOXY_QUICKPLAY="voxytest" VOXY_FORCE_METAL=1 ./gradlew runClient -PrunXmx=6G
```

**Steps.**

1. Launch.
2. Type `/locate biome minecraft:mangrove_swamp`. If none is found, use
   `minecraft:jungle`.
3. Click the coordinates in the chat and press Enter to go there.
4. Fly in slow circles at treetop height for 5 minutes. Look through leaves
   and water.
5. Quit and run `bash scripts/testing/log-check.sh`.
6. Run:

   ```bash
   grep -F -e 'Sodium shared quad index' -e 'suspicious Sodium shared index' run/logs/latest.log
   grep -l 'Tried to reserve storage' run/crash-reports/* 2>/dev/null
   ```

7. Set *Shadow Distance* back to the value you noted.

**Look for.** The highest N reached.

**Log lines.**

```
Voxy Metal: grew Sodium shared quad index buffer via uploadData to
Voxy Metal: skipping suspicious Sodium shared index batch
Voxy Metal: failed to grow Sodium shared quad index buffer without mapping
Tried to reserve storage for more vertices in this buffer than it can hold
```

**Pass.** Only `Voxy Metal: grew Sodium shared quad index buffer via
uploadData to N primitives` lines, with N rising and then staying put. No
`skipping suspicious Sodium shared index batch` and no `failed to grow Sodium
shared quad index buffer without mapping`. No crash and no missing near
terrain.

**Fail.** A crash with `Tried to reserve storage for more vertices in this
buffer than it can hold`, or an `IllegalArgumentException` in the max-elements
check. Also a fail: holes in near terrain.

### 7.1: Chunky basic: radius 1000 around spawn, standing still, new world

**Priority:** Essential. **Time:** 20 min. **Pack:** none.
**Depends on:** `P0-SHADERS`, `P1-SETUP`.

**Goal.** Check end to end that every chunk Chunky generates reaches Voxy, is
saved, and shows as a far LOD while you watch, without going there. Also
measure disk per chunk and fps before, during and after generation.

**You need.**

- The three `P7-*` tests done with BSL on. From here to `7.7`, shaders are off.
- A second Terminal tab in the repository root for the live log filter.
- About 0.5 GB of free disk.

**Settings.**

- *Reduce FPS when* on *Minimized* and "Pause on lost focus: disabled".
- F3+F6: the `debug` row on *In Overlay*.
- *Video Settings > Voxy > General*: *Chunk Ingest* ON (the default). Do not
  change *Service threads* or *Use sodium threads*. Note their values and
  Voxy's *Render distance* in the report. The *Service threads* default
  depends on your CPU core count.
- The same VSync setting as your phase 5 baseline.

**Launch.**

```bash
VOXY_QUICKPLAY="voxy-stress-gen" VOXY_FORCE_METAL=1 VOXY_FRAME_TIMING=1 VOXY_DEBUG_CMD="5:time set noon;6:weather clear" ./gradlew runClient -PrunXmx=6G
```

**Steps.**

1. First time only: launch without joining a world:
   `VOXY_FORCE_METAL=1 ./gradlew runClient -PrunXmx=6G`. Go to *Singleplayer >
   Create New World* and type `voxy-stress-gen` in *World Name*.
2. On the *Game* tab, set *Game Mode* to Creative and *Allow Cheats* ON. On
   the *World* tab, leave *World Type* on Default. The screen must show
   "Save folder: voxy-stress-gen". Create the world.
3. In the world, type `/gamerule advance_time false` and
   `/gamerule advance_weather false`. Then *Esc > Save and Quit to Title >
   Quit Game*.
4. Note the "before" disk sizes:

   ```bash
   du -sh run/saves/voxy-stress-gen/voxy run/saves/voxy-stress-gen/region
   ```

5. Launch with the command above. It joins the world.
6. If BSL is on, press K. `grep enableShaders run/config/iris.properties` must
   print `enableShaders=false`.
7. In the second tab, start the live log filter.
8. Open F3 and check that `I/S/AWSC:` and `UC/GC,#N:` show.
9. Double-tap Space to fly. Hold Space up to about Y 200 above spawn and look
   at the horizon.
10. Do not touch mouse or keyboard for 2 minutes. Note `fps=` from the last two
    `[Metal-LayerB]` lines. That is the "before" reference.
11. Press T and type, one at a time: `/chunky world minecraft:overworld`,
    `/chunky spawn`, `/chunky shape square`, `/chunky radius 1000`,
    `/chunky quiet 15`, `/chunky start`.
12. Expect "Task started in minecraft:overworld for the square region centered
    at ... with radius 1000.". If Chunky replies "A task was already started
    for this world...", type `/chunky confirm`.
13. Stay still while it runs, about 1 to 2 minutes for about 16,000 chunks.
    Chunks you already loaded are skipped, so a very high Rate at the start is
    normal.
14. Watch the first number of `I/S/AWSC` (Voxy's ingest queue) and the second
    (the save queue).
15. Watch the landscape too. Terrain beyond normal view distance should fill in
    by parts.
16. When the log shows "Task finished for minecraft:overworld. Processed: ...
    (100.00%)", keep F3 open until I and S reach 0. Note how many seconds it
    took.
17. Stay still 2 more minutes, the "after" reference, and press F2.
18. Type `/chunky cancel` and then `/chunky confirm` to delete Chunky's saved
    task. Then *Esc > Save and Quit to Title*.
19. Repeat the `du` from step 4. Run the error grep.

**Look for.**

- F3 `I/S/AWSC: I/S/[n]`: I is the ingest queue, S the save queue, n the LOD
  sections in memory.
- F3 `UC/GC,#N:` with used MB and capacity, and `Mem:` with heap use.
- Chunky's progress line: Rate, ETA and Current.
- `[Metal-CHAIN]` `ingestCall`, `ingestNoLight`, `ingestQ` and `ingestProc`
  about every 600 frames. `ingestQ` should grow about 24 times as much as
  `ingestCall`, and `ingestProc` should catch up.
- `[Metal-DIAG]` `sections=` and `geom=`: `geom` grows and stops well below
  capacity, and `sections` levels off. Also `fps=` in `[Metal-LayerB]`.

**Log lines.**

```
[Chunky] Task started in minecraft:overworld for the square region centered at
[Chunky] Task running for minecraft:overworld. Processed:
[Chunky] Task finished for minecraft:overworld. Processed:
[Metal-CHAIN f=
ingestNoLight=
[Metal-DIAG f=
[Metal-LayerB f=
[Metal-MEM] geometry arena
I/S/AWSC:
UC/GC,#N:
```

**A/B.** Set *Video Settings > Voxy > General > Chunk Ingest* OFF. Then
`ingestCall` in `[Metal-CHAIN]` must not move while Chunky runs. Test `7.6`
uses this as a control.

**Pass.** Chunky reaches "Task finished ... (100.00%)". `ingestCall` rises by
about 12,000 with `ingestNoLight` at 0, and I and S return to 0 within
1 minute. LODs cover the whole square with no holes. Fps during generation is
at least half of "before", and "after" is within 15% of "before". On the
reference Mac the `voxy` folder grew about 70 to 130 MB and `region` about
85 to 110 MB. The error grep prints nothing.

**Fail.** `ingestNoLight` rises along with `ingestCall`, I keeps rising or
does not reach 0 within 2 minutes, or the generated ring shows no LODs while
you stand still. Also a fail: the `voxy` folder does not grow, a line in the
error grep, a crash, or a new `hs_err_pid` file.

### 7.2: Chunky medium: radius 2000 standing still, with memory, CPU and disk

**Priority:** Recommended. **Time:** 20 min. **Pack:** none.
**Depends on:** `7.1`.

**Goal.** More than 5 minutes of continuous ingest, save and geometry upload.
Does Voxy's ingest keep up with Chunky? Does the save queue stay near 5,000 or
below? How much CPU, disk and memory does it use?

**You need.**

- `7.1` passed in this world.
- `bash scripts/testing/sampler.sh` running in a second tab before you
  generate. Stop it with Ctrl+C at the end, or let it stop when the game exits.
- Activity Monitor open: on *CPU*, "% CPU" and "Threads" of `java`; on
  *Memory*, "Memory" of `java`; on *Disk*, "Bytes Written" of `java`.
- About 1 GB of free disk.

**Settings.** As in `7.1`: *Minimized*, "Pause on lost focus: disabled", the
`debug` row on *In Overlay*, *Chunk Ingest* ON, shaders off.

**Launch.** As in `7.1`.

**Steps.**

1. Run the `du` from `7.1` and note both sizes.
2. Launch. Start the live log filter. With the sampler, use a third tab.
3. Fly to about Y 200 above spawn and stay still 2 minutes. Note the "before"
   values: `fps=` in `[Metal-LayerB]`, F3 `Mem:`, and `java` % CPU, Memory and
   Bytes Written in Activity Monitor.
4. Press T and type: `/chunky world minecraft:overworld`, `/chunky spawn`,
   `/chunky shape square`, `/chunky radius 2000`, `/chunky quiet 15`,
   `/chunky start`. If asked, type `/chunky confirm`.
5. That is about 63,000 chunks. The 16,000 in the middle already exist, so
   about 47,000 new ones generate in about 3.5 to 7 minutes on the reference
   Mac.
6. Every minute, note Chunky's Rate, the `I/S/AWSC` numbers and `Mem:` from F3,
   and `java` % CPU and Memory from Activity Monitor.
7. Optional: type `/spark tps` once halfway to see the integrated server's
   tick rate.
8. At "Task finished ...", time how long I and S take to reach 0. Then stay
   still 3 more minutes, note the "after" values as in step 3, and press F2.
9. Type `/chunky cancel` and `/chunky confirm`. Then *Esc > Save and Quit to
   Title*.
10. Repeat the `du` and the error grep. Also run:

    ```bash
    grep -c 'geometry arena fragmented' run/logs/latest.log
    ```

**Look for.**

- F3 I (ingest queue) and S (save queue). S should stay near 5,000 or below.
- F3 `[n]` of `I/S/AWSC` and `Mem:`. The heap should rise and fall in a saw
  tooth up to the 6 GB limit.
- Chunky's `Rate: X cps`. An earlier run on the reference Mac saw 115 to 660.
- Activity Monitor: `java` % CPU in normal play was 110 to 230% on the
  reference Mac. About 2 minutes after the queues empty, CPU should return to
  your normal range and Bytes Written should almost stop.
- `ingestProc` in `[Metal-CHAIN]` keeps pace with `ingestQ`. Also `geom` in
  `[Metal-DIAG]`, `fps=` in `[Metal-LayerB]` and the waits in `[Metal-TIMING]`.
- Expected growth on the reference Mac: `voxy` about 0.28 to 0.52 GB, `region`
  about 0.33 to 0.42 GB. The Voxy worker thread count in the sampler does not
  change.

**Log lines.**

```
[Chunky] Task running for minecraft:overworld. Processed:
Rate:
[Metal-CHAIN f=
[Metal-DIAG f=
[Metal-LayerB f=
[Metal-TIMING f=
I/S/AWSC:
Mem:
[Metal-MEM] geometry arena fragmented
Voxy section saving still in progress, estimated
```

**A/B.** Only if S misbehaves: add `VOXY_SAVE_BACKPRESSURE=0` to the launch and
generate a new ring elsewhere, for example `/chunky center 0 12000` with
`/chunky radius 1000`. Without that limit, S should be able to pass 5,000,
which proves the limit is what holds it. Then go back to the normal launch.

**Pass.** I stays low and does not rise from one reading to the next. S stays
at about 5,000 to 6,000 or below. Both reach 0 within 2 minutes of the end,
and `ingestNoLight` stays at 0. `Mem:` rises and falls without staying above
90%. Fps during generation is at least half of "before" and returns to within
15% of "before" 3 minutes after. Sampler memory is flat at the end and the
error grep prints nothing.

**Fail.** I keeps rising, S stays above 5,000 for minutes or keeps growing, or
`Mem:` stays above 90% with stutters, or an `OutOfMemoryError`. Also a fail:
any `had an exception`, `thread died` or `Geometry OOM` line, fps below 30% of
"before" for more than 1 minute, repeated freezes over 2 s, or memory that
keeps rising after the queues empty.

### 7.3: Chunky large: radius 3000 flying in spectator, the realistic worst case

**Priority:** Optional. **Time:** 40 min. **Pack:** none.
**Depends on:** `7.2`.

**Goal.** Everything at once: Chunky generation, player movement, ingest,
save and geometry upload. This is where geometry-space and Java-heap problems
under a 6 GB limit are most likely.

**You need.**

- `7.2` passed.
- `sampler.sh` running, and Activity Monitor open as in `7.2`.
- About 2 GB of free disk.
- Optional, only if the no-pack run is clean: repeat it once with BSL. Press K
  to turn it on, and K again to turn it off before `7.4`. Chunky skips chunks
  that already exist, so use a new ring as in the A/B runs.

**Settings.** As in `7.1`. Switch to spectator with F3+N or
`/gamemode spectator`. In spectator, the mouse wheel changes fly speed.

**Launch.** As in `7.1`.

**Steps.**

1. Measure both folders with `du` as in `7.1`. Launch and start the live log
   filter.
2. Stay still at Y 200 above spawn for 2 minutes. Note the "before" values:
   `fps=`, `Mem:`, and `java` % CPU and Memory.
3. Press T and type: `/chunky world minecraft:overworld`, `/chunky spawn`,
   `/chunky shape square`, `/chunky radius 3000`, `/chunky quiet 15`,
   `/chunky start`. If asked, type `/chunky confirm`.
4. That is about 142,000 chunks, about 79,000 of them new. Stay still for the
   first 3 minutes to measure generation load alone.
5. Switch to spectator and raise the speed with the mouse wheel.
6. Fly a large loop at Y 180 to 220: east to x=2500, south to z=2500, west to
   x=-2500, north to z=-2500, and back to spawn.
7. Repeat the loop until Chunky says "Task finished". Press F2 about every
   2 minutes.
8. Every 2 minutes, note Chunky's Rate, `I/S/AWSC`, `UC/GC,#N` and `Mem:` from
   F3, and `java` % CPU and Memory from Activity Monitor.
9. After "Task finished", go back to spawn, stay still and wait for I=0 and
   S=0. Then note the "after" values over 3 minutes.
10. Type `/chunky cancel` and `/chunky confirm`. Then *Esc > Save and Quit to
    Title* and measure both folders with `du`.
11. Run the error grep, then read `deferrals=N` from the last fragmentation
    line. Keep the world for `7.4`.

    ```bash
    grep 'geometry arena fragmented' run/logs/latest.log | tail -1
    ```

**Look for.**

- The same as `7.2`, plus `geom=used/capacity` in `[Metal-DIAG]`. Capacity is
  physical RAM divided by 16, between 1 and 4 GB (3 GB on 48 GB). Voxy evicts
  LODs when less than 256 MB is free.
- `[Metal-MEM] geometry arena fragmented ... deferrals=N`. It appears on the
  1st, 2nd, 4th, 8th time and so on.
- Warnings that are not failures on their own: `Recieved geometry update but
  not watching it, discarding` and `Large amount of copies, lag will probably
  happen`.
- Stutters while flying: freezes, LODs that do not appear ahead of you, or a
  collapsing frame rate.
- Expected final size on the reference Mac: `voxy` about 0.85 to 1.6 GB,
  `region` about 1.0 to 1.3 GB. The sampler should show a constant number of
  `Dedicated Voxy Worker` threads.

**Log lines.**

```
[Chunky] Task finished for minecraft:overworld. Processed:
[Metal-DIAG f=
[Metal-CHAIN f=
[Metal-LayerB f=
[Metal-TIMING f=
[Metal-MEM] geometry arena fragmented
Geometry OOM
Service 'Ingest service' on thread
Async Node Manager thread died
I/S/AWSC:
UC/GC,#N:
```

**A/B.** Each A/B needs a new ring, for example `/chunky center 0 12000` with
`/chunky radius 2000`.

- If fps or Chunky's Rate collapse: set *Use sodium threads* OFF in *Video
  Settings > Voxy > General*, or lower *Service threads* to 8.
- To mimic the 1 GB geometry arena of a 16 GB Mac, launch with:

  ```bash
  JAVA_TOOL_OPTIONS="-Dvoxy.geometryBufferSizeOverrideMB=1024" VOXY_QUICKPLAY="voxy-stress-gen" VOXY_FORCE_METAL=1 VOXY_FRAME_TIMING=1 ./gradlew runClient -PrunXmx=6G
  ```

  Check that the log says `[Metal-MEM] geometry arena 1024 MB ...
  (-Dvoxy.geometryBufferSizeOverrideMB; ...)`. Expect more `fragmented`
  lines, but never `Geometry OOM`.

**Pass.** No crashes and no freezes over about 2 s. Fps while flying and
generating stays at 40% of "before" or more, and LODs are ahead of you inside
the generated square. I and S reach 0 within 2 minutes of the end,
`ingestNoLight` stays at 0, and no `Geometry OOM`. `deferrals` stays in the
hundreds at most with no missing LODs. "After" fps is within 15% of "before",
and sampler memory levels off.

**Fail.** A crash or an `hs_err` file from today, an `OutOfMemoryError`,
`Mem:` stuck above 90% with stutters, `Geometry OOM`, or `deferrals` in the
thousands with missing LOD areas. Also a fail: any ingest or save exception,
`thread died`, I or S above 0 more than 5 minutes after the end, fps below 25%
of "before" for more than 1 minute, or memory still rising 5 minutes after.

### 7.4: Flight over the generated area: holes, flicker and stuck LODs

**Priority:** Essential. **Time:** 20 min. **Pack:** none.
**Depends on:** `7.3`.

**Goal.** With generation done and Voxy idle, inspect the 6000 by 6000 block
square. Look for LOD holes, flicker, LODs that never gain detail, and seams
at Chunky's 512-block region borders.

**You need.** `7.3` done in this world, with I=0 and S=0 in F3. A notes file
open, for example in TextEdit, to paste coordinates.

**Settings.** As in `7.1`. Spectator mode. F3+C copies your exact position as a
`/tp` command. Only tap it: holding it for 10 seconds crashes the game on
purpose.

**Launch.** As in `7.1`, or stay in the `7.3` session.

**Steps.**

1. Start the live log filter and switch to spectator.
2. Type `/chunky progress`. It must reply "No tasks running.".
3. Type `/tp @s 2900 250 2900`. Stay still 30 s facing spawn. Press F2 and
   F3+C, and paste the position in your notes.
4. Turn around, face outward, stay still 30 s and press F2 again.
5. Repeat steps 3 and 4 at `/tp @s -2900 250 2900`, `/tp @s -2900 250 -2900`
   and `/tp @s 2900 250 -2900`.
6. Fly the diagonals corner to corner through spawn at full speed.
7. Make a slow pass at about Y 120 along x=512 and along x=1024. Look for
   straight-line seams.
8. If you see a hole or something odd, stop, press F3+C and F2, and note what
   you saw.
9. Move closer, to under about 500 blocks, and watch whether it fills when the
   normal chunks load. If it fills, LOD data was missing. It was not a drawing
   bug.
10. *Esc > Save and Quit to Title* and run the error grep.

**Look for.**

- Holes: square gaps showing sky or fog inside the plus or minus 3000 square.
- Flicker: areas that shimmer with a still camera, and the `[Metal-FLICKER]`
  line for those windows.
- Stuck LODs: coarse or blurry terrain more than 30 s after you stop, or a
  collapsing frame rate.
- Seams at multiples of 512 blocks, or misplaced or duplicated terrain.
- Beyond plus or minus 3000 only terrain you already loaded exists. That edge
  is normal, not a hole.

**Log lines.**

```
[Metal-FLICKER f=
min=
max=
changes=
[Metal-LayerB f=
[Metal-DIAG f=
No tasks running.
```

**A/B.** If holes appear, run `/voxy import current`. You will see the
"Voxy import: N/M chunks" bar and then "Voxy world import finished in X
seconds, averaging Y chunks per second" in the chat. If the holes disappear,
storage was missing data and it was not a drawing bug. `/voxy import cancel`
stops the import.

**Pass.** No holes inside the generated square in any screenshot.
`[Metal-FLICKER]` shows `min==max` and `changes=0` with a still camera, not
counting the first window of each stop. LOD detail settles within about 30 s
at each stop, fps while flying is at least 60% of the "before" value of `7.3`,
and the error grep prints nothing.

**Fail.** A hole inside the square that fills as you approach means missing
ingest data. One that stays up close means a drawing bug. Also a fail:
`changes>0` or `min!=max` with a still camera, coarse terrain that never
improves, freezes, seams, misplaced terrain, any error line, or a crash.

### 7.5: Rejoin and restart: LODs load from storage

**Priority:** Essential. **Time:** 15 min. **Pack:** none.
**Depends on:** `7.4`.

**Goal.** Show that everything Chunky generated was saved and comes back from
Voxy's storage, without regenerating or re-ingesting, both after a rejoin and
after a game restart. Also check that quitting with a large store does not
hang.

**You need.** `7.4` passed. `sampler.sh` running.

**Settings.** As in `7.1`.

**Launch.**

```bash
VOXY_QUICKPLAY="voxy-stress-gen" VOXY_FORCE_METAL=1 VOXY_FRAME_TIMING=1 ./gradlew runClient -PrunXmx=6G
```

**Steps.**

1. In game, go to a `7.4` corner with `/tp @s 2900 250 2900` and face spawn.
   Stay still 30 s.
2. Press F3+C to copy the exact place and view, then F2.
3. Type `/chunky progress`. It must say "No tasks running.". Note `sections=`
   and `geom=` from `[Metal-DIAG]` and `ingestCall=` from `[Metal-CHAIN]`.
4. In Terminal, run `du -sh run/saves/voxy-stress-gen/voxy`.
5. Press *Esc > Save and Quit to Title*. Time the "Saving world" screen.
6. Go to *Singleplayer > voxy-stress-gen > Play Selected World*. Press T, paste
   the copied `/execute ... tp` command with Cmd+V and press Enter. Wait 60 s
   without moving and press F2.
7. Press *Esc > Save and Quit to Title > Quit Game*. Relaunch with the command
   above. It joins the world.
8. Paste the same command again, wait 60 s and press F2.
9. Compare the three screenshots. Run the `du` again, then:

   ```bash
   grep -E 'Render shutdown completed|Shutting down voxy instance|Instance shutdown|Creating new world engine|saving still in progress|World still in use|Not all worlds' run/logs/latest.log
   ```

**Look for.**

- How fast far LODs come back on each join.
- `sections` and `geom` in `[Metal-DIAG]` return to the earlier level while
  `ingestCall` rises only by the chunks Minecraft loads around you, about
  4,000 to 5,000. That proves the LODs come from disk.
- How long "Saving world" lasts.
- `Voxy section saving still in progress, estimated N sections remaining.` is
  acceptable: those saves finish before shutdown. Note N.
- Sampler memory per session. After a rejoin it should stay within about 15%
  of the first session.
- Each join creates the renderer twice, so `Render shutdown completed` appears
  twice per join. That is normal.

**Log lines.**

```
Render shutdown completed
Shutting down voxy instance
Instance shutdown
Creating new world engine:
Voxy section saving still in progress, estimated
World still in use after
No tasks running.
```

**Pass.** After the rejoin and after the restart, the whole plus or minus 3000
area shows LODs within about 60 s, the same as the earlier screenshot, with no
Chunky task. `sections` and `geom` return to within 20% of the earlier values
while `ingestCall` rises only about 4,000 to 5,000. The output shows
`Render shutdown completed`, `Shutting down voxy instance` and
`Instance shutdown`. The main menu appears within about 60 s, and the `voxy`
folder does not shrink.

**Fail.** The area is still empty or incomplete after 2 minutes, the terrain
does not match the earlier screenshot, `World still in use after 30000 ms at
shutdown` or `Not all worlds shutdown` appears, "Saving world" lasts more than
1 minute, or memory grows more than 1 GB per session.

### 7.6: Far generation: ingested and saved without drawing, plus a Nether variant

**Priority:** Recommended. **Time:** 20 min. **Pack:** none.
**Depends on:** `7.1`.

**Goal.** Chunks Chunky generates outside Voxy's LOD ring must be ingested and
saved with no GPU cost, and show from storage when you travel there. The
Nether variant opens a second store for another dimension and closes it when
it goes idle.

**You need.** `7.1` passed. Better after `7.5`. Cheats on (the world was
created with *Allow Cheats* ON).

**Settings.** As in `7.1`.

**Launch.** As in `7.5`.

**Steps.**

1. Measure the `voxy` folder with `du`. Launch and start the live log filter.
2. Stay at spawn. Note `sections=` and `geom=` from `[Metal-DIAG]` and
   `ingestCall=` from `[Metal-CHAIN]`.
3. Press T and type: `/chunky world minecraft:overworld`,
   `/chunky center 40000 0`, `/chunky shape square`, `/chunky radius 1000`,
   `/chunky quiet 15`, `/chunky start`. That generates about 16,000 chunks in
   about 1 to 2.5 minutes.
4. Voxy's LOD ring reaches about 32,000 blocks at the largest *Render
   distance* (2016 chunks). So x=40000 is always outside it.
5. While it runs, stay at spawn. `ingestCall` and `ingestQ` rise, but
   `sections`, `geom` and F3 `UC/GC,#N` stay almost flat.
6. After "Task finished" and with I=0 and S=0, measure again with `du`. Expect
   about 100 to 180 MB more.
7. Type `/tp @s 40000 300 1800 180 30`. You are 1800 blocks south of the
   center, facing north and down.
8. The generated square is 800 to 2800 blocks away, beyond normal view, so what
   you see comes only from Voxy's storage. Wait up to 60 s and press F2. You
   should see a clear square edge with nothing beyond it.
9. Nether variant, optional: stay in the overworld and type
   `/chunky world minecraft:the_nether`, `/chunky center 0 0`,
   `/chunky radius 500`, `/chunky start`. When it starts, the log shows
   `Creating new world engine: <number>@...`.
10. About 10 s or more after it finishes, with I=0, the log shows
    `Shutting down idle world: <same number>`. Then type
    `/chunky world minecraft:overworld`.
11. Optional visit: `/execute in minecraft:the_nether run tp @s 0 100 0`. From
    above, the Nether roof in LOD may look like one big slab. That is a known
    issue.
12. Type `/chunky cancel` and `/chunky confirm`. Then *Esc > Save and Quit to
    Title* and run the error grep.

**Look for.**

- `ingestCall` and `ingestQ` in `[Metal-CHAIN]` rise while `geom` and
  `sections` in `[Metal-DIAG]` stay flat.
- How much the `voxy` folder grows.
- Time until the far square appears after the teleport.
- Nether: the `Creating new world engine` and `Shutting down idle world` pair.

**Log lines.**

```
[Metal-CHAIN f=
[Metal-DIAG f=
[Chunky] Task finished for minecraft:overworld
[Chunky] Task finished for minecraft:the_nether
Creating new world engine:
Shutting down idle world:
Service 'Ingest service' on thread
was not alive
```

**A/B.** Voxy's cost during generation: repeat step 3 with
`/chunky center -40000 0` and *Chunk Ingest* OFF in *Video Settings > Voxy >
General*. `ingestCall` must stay flat. Compare Chunky's Rate with the normal
run: the difference is Voxy's cost. Then set *Chunk Ingest* back ON and run
`/voxy import current`. After "Voxy world import finished in ...", the -40000
area must appear from storage with `/tp @s -40000 300 1800 180 30`.

**Pass.** During far generation, `ingestCall` rises by about 16,000,
`ingestNoLight` stays at 0, `geom` and `sections` change by less than about 5%,
and the `voxy` folder grows about 100 to 180 MB. After the teleport, LODs
cover the far square within about 60 s with Chunky idle. In the Nether, one
`Creating new world engine` and then its `Shutting down idle world` appear,
with no ingest exceptions.

**Fail.** The `voxy` folder does not grow, the far square is still empty more
than 2 minutes after you arrive, or `geom` jumps during far generation. That
last one means those positions were being drawn, so check Voxy's *Render
distance*. Also a fail: `Service 'Ingest service' ... had an exception` or
`Tried inserting chunk into WorldEngine that was not alive` near the idle
shutdown.

### 7.7: Quit while Chunky generates: loaded shutdown and the safe-stop rule

**Priority:** Recommended. **Time:** 35 min. **Pack:** none.
**Depends on:** `7.1`.

**Goal.** Check what happens if you quit while Chunky generates and Voxy
still has ingest queued. The code drops that queue, which leaves LOD holes.
Also check that quitting under load does not hang, and validate the safe stop:
pause, wait for I=0 and S=0, then quit.

**You need.** `7.1` passed. Better after `7.6`. The live log filter in a
second tab. Do this test last in the phase: it damages the LODs of a small
area on purpose.

**Settings.** As in `7.1`.

**Launch.** As in `7.5`.

**Steps.**

1. Launch and start the live log filter. Press T and type:
   `/chunky world minecraft:overworld`, `/chunky center 6000 0`,
   `/chunky shape square`, `/chunky radius 1000`, `/chunky quiet 5`,
   `/chunky start`.
2. After about 60 s at a steady Rate, open F3 and press F2 to capture the value
   of I. Copy the last "Current: cx, cz" numbers from the live filter into your
   notes.
3. Right away, press *Esc > Save and Quit to Title*. Do NOT pause Chunky. Time
   the "Saving world" screen.
4. In Terminal:

   ```bash
   grep -E 'Task stopped for|Shutting down voxy instance|saving still in progress|World still in use|had an exception|was not alive' run/logs/latest.log
   ```

5. Rejoin with *Singleplayer > voxy-stress-gen > Play Selected World*.
   `/chunky progress` must say "No tasks running.", because Chunky does not
   resume on its own.
6. Type `/chunky continue`. Expect "Task continuing for minecraft:overworld.".
   Let it finish and wait for I=0 and S=0.
7. Work out the last chunk's block coordinates: X = cx * 16 and Z = cz * 16.
   Type `/tp @s <X> 300 <Z+800> 180 30`.
8. You are 800 blocks south, facing north and down, far enough that normal
   chunks there do not load. Look for square holes near that point and press
   F2.
9. If there are holes, type `/voxy import current` and wait for "Voxy world
   import finished in ..." in the chat. It rereads every overworld region and
   can take minutes. `/voxy import cancel` stops it. Look again and press F2.
10. Control run with the safe stop: type `/chunky center -6000 0`,
    `/chunky radius 1000` and `/chunky start`.
11. After about 60 s, note the last "Current" and type `/chunky pause`. Expect
    "Task paused for minecraft:overworld.".
12. Wait until F3 shows I=0 and S=0, then *Esc > Save and Quit to Title*.
    Rejoin, type `/chunky continue` and let it finish.
13. Check around that last "Current" as in steps 7 and 8. There must be no
    holes.
14. Type `/chunky cancel` and `/chunky confirm`.
15. End of phase: press K to turn BSL back on.
    `grep enableShaders run/config/iris.properties` must print
    `enableShaders=true`.

**Look for.**

- The value of I in F3 when you quit, from the screenshot.
- How long "Saving world" lasts with a busy queue.
- Holes near the last "Current" chunk after resuming, and whether
  `/voxy import current` fills them.
- The same check after the safe-stop control run.

**Log lines.**

```
[Chunky] Task stopped for minecraft:overworld.
Task paused for minecraft:overworld.
Task continuing for minecraft:overworld.
No tasks running.
Shutting down voxy instance
Voxy section saving still in progress, estimated
World still in use after
Voxy import:
Voxy world import finished in
```

**A/B.** Built into the steps: a direct quit (steps 2 to 9) against pause,
drain and quit (steps 10 to 13). `VOXY_SHUTDOWN_WAIT_MS` (default 30000) only
limits how long quitting waits for a world in use. Do not change it in this
test.

**Pass.** Quitting under load reaches the menu within about 60 s, with no
crash, no `World still in use after 30000 ms at shutdown` and no ingest or save
exceptions. Chunky resumes with `/chunky continue` and finishes. The holes from
the direct quit are near the last "Current" and disappear with
`/voxy import current`. The control run has no holes.

**Fail.** A hang or crash on quit, holes in the control run, holes that stay
after `/voxy import current`, or exceptions such as `Tried inserting chunk into
WorldEngine that was not alive` or `Service ... had an exception` on quit.
Holes from the direct quit are a confirmed finding, not a test failure.

---

## Phase 8: The release jar in a clean launcher

This phase tests the release jar the way a player gets it. It runs in a
launcher, in a new and empty game folder, with nothing from `run/` and no dev
client.

The launcher steps are the same as in the
[player guide](player-track.md#2-install-fabric-and-the-mods): install Fabric Loader, create a
Minecraft 1.21.11 profile with its own game folder, add the mods, and set the
JVM arguments. Follow them there. This section adds the jar build, the log
checks and the developer details.

| ID | Test | Priority | Min | Pack |
|---|---|---|---|---|
| `P8-LAUNCHER-CLEAN` | Release jar in a clean launcher profile, without Iris | Essential | 30 | None, no Iris |
| `P8-LAUNCHER-WITH-PACK` | Release jar with Iris 1.10.7 and BSL in the launcher profile | Recommended | 15 | BSL |
| `P8-MULTIPLAYER-SMOKE` | Quick multiplayer test against a local dedicated server | Optional | 20 | None |
| `P8-OLDER-MACOS` | Release jar on macOS 13 to 15 and on a 16 GB Mac | Optional | 30 | None |

### Phase 8 setup

1. Build the jar, or download it from
   [Releases](https://github.com/srjefers/voxy-mseries-support/releases):

   ```bash
   ./gradlew build
   ls build/libs/
   ```

   The name is `voxy-<version>-<commit>.jar`. It must not end in `-dirty`. A
   `-dirty` jar was built from uncommitted changes, so its commit stamp is
   not the code you tested.
2. Pick an empty game folder for the test profile and store it in `GAME`, in
   every Terminal tab you use. With the official launcher, set the profile's
   *Game directory* to this folder. With Prism, the Modrinth App or
   CurseForge, use the instance's *Open folder* button and paste that path.

   ```bash
   GAME="$HOME/voxy-release-test"
   ```

3. The scripts take the folder as an argument:

   ```bash
   bash scripts/testing/settings-dump.sh "$GAME"
   bash scripts/testing/log-check.sh "$GAME"
   bash scripts/testing/collect-report.sh --redact "$GAME"
   ```

4. JVM arguments for the profile: `-Xmx6G -Dvoxy.forceMetal=true`. Of the
   switches in this plan, only two are JVM properties that any launcher can
   pass: `-Dvoxy.forceMetal=true` and `-Dvoxy.geometryBufferSizeOverrideMB`.
   `VOXY_*` environment variables need Prism (10.0.2 or newer) or the Modrinth
   App, which have an environment variable field. The official launcher and
   CurseForge do not.

### P8-LAUNCHER-CLEAN: Release jar in a clean launcher profile, without Iris

**Priority:** Essential. **Time:** 30 min. **Pack:** none, no Iris.
**Depends on:** `P0-BUILD`.

**Goal.** Check that the jar alone loads its native libraries and starts Voxy
on Metal, without help from the dev environment. That is the failure in issue
#18. It is also the first test with Voxy's default config, which has *Enable
environmental fog* ON.

**You need.**

- The jar from the setup, not ending in `-dirty`.
- A launcher (official, Prism, Modrinth App or CurseForge) and Fabric Loader
  0.18.2 or newer for 1.21.11.
- A new, empty game folder in `GAME`. Copy nothing from `run/`.
- In `mods`: Fabric API `0.140.0+1.21.11`, Sodium exactly `mc1.21.11-0.8.1`
  for Fabric, and the Voxy jar.
- No Iris in this test. Never `voxyworldgenv2` 2.2.2, which is incompatible.
- Java: keep the Java 21 arm64 runtime the launcher ships.

**Settings.**

- JVM arguments: replace any `-Xmx` with `-Xmx6G` and add
  `-Dvoxy.forceMetal=true`.
- If you compare fps: *Video Settings > Sodium > Performance > Reduce FPS when*
  on *Minimized*.
- If you watch Activity Monitor with the world open: press F3+P first, until
  the chat says "Pause on lost focus: disabled".

**Setup commands.** Run them with the game closed. The first line clears the
shader cache, so the jar compiles everything with its own libraries. The dev
client rebuilds the cache on its next launch.

```bash
rm -rf ~/.voxy/shader-cache
mkdir -p "$GAME/mods"
cp build/libs/voxy-*.jar "$GAME/mods/"
ls "$GAME/mods"
```

`build/libs/` must hold only one Voxy jar. Otherwise copy the one you mean by
its full name.

**Steps.**

1. Run the setup commands.
2. Put Fabric API and Sodium in `"$GAME/mods"`. Create the launcher profile as
   in the [player guide](player-track.md#2-install-fabric-and-the-mods), with its game folder set to
   `GAME`.
3. Launch. Go to *Singleplayer > Create New World* and set *World Name* to
   `voxytest`.
4. On the *Game* tab, set *Game Mode* to Creative and *Allow Cheats* ON. Press
   *Create New World*.
5. Double-tap Space to fly and explore for 2 to 3 minutes. LODs should reach
   the horizon. Note how long the first launch took: it compiles all shaders
   from scratch.
6. Press F2. The screenshot goes to `"$GAME/screenshots"`.
7. Press F3+F6, type `debug` in *Search...* and set the `debug` row to
   *In Overlay*. Searching for "voxy" finds nothing. Voxy's rows are `debug`
   and `version`, and `version` always shows with F3.
8. Press Esc, then F3. You should see `I/S/AWSC:` and `UC/GC,#N:`, and a line
   with the Voxy version and commit, the same as the jar name.
9. Dive into water for 20 seconds. With the default config, near water and far
   LODs fade into murky fog.
10. With the world open, check the `java` memory in Activity Monitor.
11. Press *Esc > Save and Quit to Title* and rejoin with *Play Selected World*.
    Do it twice, then press *Quit Game*.
12. Check the log, the config, the world store and the shader cache:

    ```bash
    grep -E 'Metal native library loaded|Using Metal render backend|VOXY_FORCE_METAL|Metal device:|geometry arena|Voxy render system created|UnsatisfiedLinkError|NoClassDefFoundError|Failed to locate library|librocksdbjni|not bundled in jar|native library not available|shaderc|spvc|Voxy disabled on METAL' "$GAME/logs/latest.log"
    grep use_environmental_fog "$GAME/config/voxy-config.json"
    ls "$GAME/saves/voxytest/voxy"
    ls ~/.voxy/shader-cache | wc -l
    ```

    The world's `voxy` folder must exist and hold files. The shader cache must
    hold files again.
13. Run `bash scripts/testing/log-check.sh "$GAME"` and
    `bash scripts/testing/collect-report.sh --redact "$GAME"`. The report copies
    `latest.log`, and the next launch overwrites it.
14. Negative test: remove `-Dvoxy.forceMetal=true` from the JVM arguments and
    launch again. Join the world, open F3 and look at *Options > Video
    Settings*.
15. Quit and run the first `grep -E` line from step 12 again.
16. Put `-Dvoxy.forceMetal=true` back in the JVM arguments. The next tests need
    it.

**Look for.**

- `java` memory in Activity Monitor. See the
  [Tiers](../../README.md#tiers) table for what to expect on your Mac.
- How long LODs take to fill the horizon.
- How long the first launch takes.

**Log lines.**

```
Metal native library loaded from
Using Metal render backend (Apple Silicon)
[VOXY_FORCE_METAL] Voxy enabled on METAL backend
Metal device:
[Metal-MEM] geometry arena
Voxy render system created with
UnsatisfiedLinkError
NoClassDefFoundError
Failed to locate library
Metal native library not bundled in jar; falling back to OpenGL
Metal native library not available:
[M9 TRANSITIONAL] Voxy disabled on METAL backend
```

**Pass.** With the property, LODs reach the horizon, rejoins work, the water
looks murky, and `voxy-config.json` says `use_environmental_fog` true. The log
has `Metal native library loaded from`, `Using Metal render backend (Apple
Silicon)`, `[VOXY_FORCE_METAL] Voxy enabled on METAL backend`,
`MSL 3.0 pinned`, `[Metal-MEM] geometry arena` and
`using pipeline 'NormalRenderPipeline'`. Without the property,
`[M9 TRANSITIONAL] Voxy disabled on METAL backend` appears, there are no LODs
and no Voxy entry in *Video Settings*, and the game runs normally.

**Fail.** Any `UnsatisfiedLinkError`, `NoClassDefFoundError`, `Failed to
locate library`, `librocksdbjni`, `shaderc` or `spvc` error,
`Metal native library not bundled in jar` or `Metal native library not
available`. These mean the jar is missing a library. Also a fail: no LODs with
the property, Voxy turning on without it, or a crash.

### P8-LAUNCHER-WITH-PACK: Release jar with Iris 1.10.7 and BSL in the launcher profile

**Priority:** Recommended. **Time:** 15 min. **Pack:** BSL.
**Depends on:** `P8-LAUNCHER-CLEAN`.

**Goal.** Most players use a shader pack. Check that the Iris integration
works outside the dev environment, with the libraries bundled inside the Iris
jar itself.

**You need.**

- `P8-LAUNCHER-CLEAN` passed, with `-Dvoxy.forceMetal=true` back in the JVM
  arguments.
- Iris 1.10.7 for 1.21.11, Fabric version, in `"$GAME/mods"`.
- `BSL_v10.1.3.zip` in `"$GAME/shaderpacks"`.

**Settings.**

- Press O to open the pack list, pick `BSL_v10.1.3.zip` and press Apply. Or use
  *Options > Video Settings > Shader Packs*.
- Lower BSL's fog for LODs: *Shader Pack Settings > Environment > Fog >
  More Fog Density Config > LOD Density* to 0.25. Without it, BSL washes far
  LODs out to white. See [Known issues](../../README.md#known-issues--not-yet-done).

**Setup commands.** With the game closed. The `cp` line works if you already
have BSL in `run/shaderpacks/` from earlier phases. Otherwise copy the zip
from your download.

```bash
mkdir -p "$GAME/shaderpacks"
cp run/shaderpacks/BSL_v10.1.3.zip "$GAME/shaderpacks/"
```

**Steps.**

1. Run the setup commands and put the Iris jar in `"$GAME/mods"`.
2. Launch the same profile, pick BSL and join `voxytest`.
3. Look at far LODs. They should have the pack's shading and no light step at
   the border between vanilla terrain and LODs. Press F2.
4. Look at deep water far away. It should look dark. If there is no ocean
   nearby, type `/locate biome minecraft:deep_ocean` and click the
   coordinates.
5. Press *Esc > Save and Quit to Title*, rejoin once, then press *Quit Game*.
6. Run:

   ```bash
   grep -E 'using pipeline|sky-light floor runtime|LOD AO parity|disabling shaders|inject failed' "$GAME/logs/latest.log"
   bash scripts/testing/log-check.sh "$GAME"
   ```

**Look for.** `java` memory in Activity Monitor with the pack. Per the README,
with a pack the frame bridges cost 68 bytes per pixel.

**Log lines.**

```
using pipeline 'MetalVxRenderPipeline'
[Metal-LODTEST] vx sky-light floor runtime
[Metal-LODTEST] vx LOD AO parity ON
Failed to create shader rendering pipeline, disabling shaders!
inject failed
```

**Pass.** The log has `using pipeline 'MetalVxRenderPipeline'`,
`[Metal-LODTEST] vx LOD AO parity ON` and `[Metal-LODTEST] vx sky-light floor
runtime` with every `loc=` value at 0 or more. No `disabling shaders` and no
`inject failed`. The picture looks as in `P2-BSL-VISUALS`.

**Fail.** Iris turns the pack off, LODs are hidden or pale under the pack, or
the game crashes.

### P8-MULTIPLAYER-SMOKE: Quick multiplayer test against a local dedicated server

**Priority:** Optional. **Time:** 20 min. **Pack:** none.
**Depends on:** `P8-LAUNCHER-CLEAN`.

**Goal.** On a server, Voxy stores LODs in another folder, creates its
instance when you connect and closes it when you disconnect. Check that cycle
with the release jar. The bug fixed in commit `75500907` only happened in
published jars: Voxy never shut down on disconnect.

**You need.**

- `P8-LAUNCHER-CLEAN` passed, with `-Dvoxy.forceMetal=true` in the JVM
  arguments.
- A Fabric 1.21.11 dedicated server with Fabric API, on this Mac or your local
  network. Fabric's server launcher is at
  [fabricmc.net/use/server](https://fabricmc.net/use/server/). Voxy on the
  server is optional.

**Settings.** To fly, type in the server console: `gamemode creative <your
name>`.

**Steps.**

1. Start the server and wait until its console says `Done`.
2. Launch the profile. Go to *Multiplayer > Add Server*, set *Server Address*
   to `localhost` and press *Done*.
3. Pick the server and press *Join Server*. If "Caution: Third-Party Online
   Play" appears, press *Proceed*.
4. Explore for 5 minutes. LODs appear as chunks arrive.
5. Press *Esc > Disconnect*. Reconnect, explore 2 minutes and disconnect
   again.
6. Press *Quit Game* and run:

   ```bash
   grep -F -e 'Server info null' -e 'Network handle null' -e 'Creating new world engine' -e 'Shutting down voxy instance' "$GAME/logs/latest.log"
   ls "$GAME/.voxy/saves"
   bash scripts/testing/log-check.sh "$GAME"
   ```

**Look for.** How fast LODs fill in. The limit is the server's view distance.

**Log lines.**

```
Server info null
Network handle null
Creating new world engine:
Shutting down voxy instance
```

**Pass.** No `Server info null`, and `ls` shows a folder named after the server
address, `localhost` or `localhost_<port>`, not `UNKNOWN`. One
`Shutting down voxy instance` per disconnect and a new
`Creating new world engine` per connect, and LODs appear as chunks arrive.

**Fail.** `Server info null` or `Network handle null`: LODs land in `UNKNOWN`
and can mix between servers. Also a fail: Voxy does not shut down on
disconnect, or the game crashes on reconnect.

Server addresses appear in the log and in the folder name. `--redact` does not
hide them. Check the files before you attach them.

### P8-OLDER-MACOS: Release jar on macOS 13 to 15 and on a 16 GB Mac

**Priority:** Optional. **Time:** 30 min. **Pack:** none.
**Depends on:** `P8-LAUNCHER-CLEAN`.

**Goal.** So far only macOS 26 on a 48 GB M4 Max was tested. Check on another
Mac that the Metal library loads from macOS 13, that shaders compile with MSL
3.0, and that LOD memory scales with RAM.

**You need.**

- An Apple Silicon Mac on macOS 13, 14 or 15, ideally with 16 to 24 GB.
- The same jar and mods as `P8-LAUNCHER-CLEAN`.

**Settings.** The README's Minimum tier:

- `-Xmx4G` in the JVM arguments, and no shader pack.
- Minecraft *Render Distance* 8 to 12 (*Video Settings*, Sodium's *General*
  page).
- Voxy *Render distance* 256 to 512 chunks (*Video Settings > Voxy >
  Rendering*).
- For fps notes, *Reduce FPS when* on *Minimized* (*Performance* page).

**Steps.**

1. Run `P8-LAUNCHER-CLEAN` on that Mac with these settings.
2. When done, run:

   ```bash
   grep -E 'Metal native library|needs macOS 13|Metal device:|geometry arena|UnsatisfiedLinkError' "$GAME/logs/latest.log"
   ```

3. Attach the report zip, 2 F2 screenshots and the `java` memory from Activity
   Monitor with the world open.
4. If shaders do not compile, repeat with `VOXY_METAL_MSL_PIN=0`. Then the
   `Metal device:` line says `newest the OS offers`.
   - Prism or the Modrinth App: add `VOXY_METAL_MSL_PIN=0` in the instance's
     environment variable field.
   - Official launcher: quit the launcher, run
     `launchctl setenv VOXY_METAL_MSL_PIN 0`, and open the launcher again.
     When done, run `launchctl unsetenv VOXY_METAL_MSL_PIN`.

**Look for.** Fps and memory, for the README's Tiers table.

**Log lines.**

```
Metal native library loaded from
Metal native library not available:
Voxy's Metal renderer needs macOS 13 (Ventura) or newer
Metal device:
[Metal-MEM] geometry arena
```

**A/B.** `VOXY_METAL_MSL_PIN=0`.

**Pass.** The log has `Metal native library loaded from` and
`Metal device: ... | macOS 13.x, 14.x or 15.x | MSL 3.0 pinned`. A 16 GB Mac
shows `[Metal-MEM] geometry arena 1024 MB for 16 GB of unified memory`, a
24 GB Mac 1536 MB, and LODs show without a crash.

**Fail.** `Metal native library not available:`, which means the Metal
library did not load. Or `Voxy's Metal renderer needs macOS 13 (Ventura) or
newer` on macOS 13 or newer. Also a fail: shaders do not compile.

---

## When you finish

1. Quit the game fully.
2. Restore the settings you backed up in part 1, or check them with
   `bash scripts/testing/settings-dump.sh`. Phase 6 changes *Enable
   environmental fog*, and `P7-DENSE-TRANSLUCENT` changes BSL's *Shadow
   Distance*.
3. `grep enableShaders run/config/iris.properties` must print
   `enableShaders=true`. If it prints false, launch, press K once, check that
   BSL is on, and quit.
4. Report each session as described in the
   [testing overview](README.md#how-to-report). For each test, give the ID,
   the result (Pass, Fail, Partial or Blocked) and what you saw. Attach the
   report zip, your F2 screenshots and the numbers you wrote down, such as
   fps, timings and folder sizes.
5. After a crash, `collect-report.sh` includes the newest crash report and
   `hs_err_pid*.log` if they are less than 60 minutes old. Older files may be
   from earlier sessions, so check the date before you attach one by hand.
