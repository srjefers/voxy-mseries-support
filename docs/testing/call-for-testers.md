<!-- Title: Call for testers: Voxy far terrain on Apple Silicon Macs -->
<!-- Step 2 points at the v0.2.8-alpha-metal.2 pre-release, which is source only (no jar). For a newer build, update it. -->

**Voxy for Apple Silicon** is a fork of [Voxy](https://github.com/MCRcortex/voxy), the Minecraft mod that draws far terrain all the way to the horizon. Upstream Voxy needs OpenGL 4.6, which macOS does not have. This fork draws the far terrain with Metal instead. It is playable today, but it has been tested on one Mac only, an M4 Max. We need yours too.

### Who we are looking for

Any Apple Silicon Mac helps. We especially need:

- Base chips: M1, M2, M3 and M4.
- Pro and Max chips other than the M4 Max.
- Macs with 16 GB of memory. 8 GB Macs are not supported, but a test with `-Xmx3G` is welcome.
- macOS 13, 14 and 15. Only macOS 26 has been tested so far.
- Prism Launcher, Modrinth App and CurseForge users.
- Players with a shader pack (BSL or Complementary) and players without one.
- Developers who can build from source and run the longer A/B tests.

You need Minecraft Java Edition 1.21.11, and Terminal to build the jar once. You do not need to know how to code.

### How long it takes

The first test takes about 30 minutes, plus the one-time jar build: set up a clean launcher profile, play a little, and send the report. Each test after that takes 10 to 30 minutes. Do as many as you like. One report is already useful.

### How to help

1. Read the [testing guide](https://github.com/srjefers/voxy-mseries-support/blob/dev/docs/testing/README.md). Players follow the [player guide](https://github.com/srjefers/voxy-mseries-support/blob/dev/docs/testing/player-track.md), which shows every launcher step.
2. Build the test jar with *Build the jar* in the [pre-release notes](https://github.com/srjefers/voxy-mseries-support/releases/tag/v0.2.8-alpha-metal.2). It is easier than it sounds: you paste one block of text into Terminal and wait a few minutes, or an AI assistant does it with you. There is no download because the Voxy team asked that jar builds not be shared, and building your own is also the safer way to get a mod. Remove any other Voxy jar from your mods folder.
3. Install Fabric Loader 0.18.2, Fabric API 0.140.0+1.21.11 and Sodium `mc1.21.11-0.8.1` (exactly this version). Iris 1.10.3 is optional; 1.10.4 to 1.10.7 break the Video Settings button with Sodium 0.8.1. Do not install voxyworldgenv2.
4. In your launcher profile, raise the memory to `-Xmx6G` (`-Xmx4G` on a 16 GB Mac). Voxy needs no other JVM argument: it turns on by itself.
5. Start with test PT-01 in the player guide, then send a [test report](https://github.com/srjefers/voxy-mseries-support/issues/new?template=test-report.yml).

This is alpha software. Use a separate launcher profile or game folder, and back up any world you care about.

### What happens with your results

- Failures become bug reports and fixes. We reply on your issue if we need more details.
- Your measurements replace the estimates marked † in the README's System requirements table.
- Results from many Macs tell us when the mod is ready to leave alpha.

Questions? Comment below. Planning a long developer phase? Say which one, so two people do not repeat the same work.
