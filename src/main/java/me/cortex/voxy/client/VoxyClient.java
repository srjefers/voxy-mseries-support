package me.cortex.voxy.client;

import me.cortex.voxy.client.core.IGetVoxyRenderSystem;
import me.cortex.voxy.client.core.VoxyRenderSystem;
import me.cortex.voxy.client.core.gl.Capabilities;
import me.cortex.voxy.client.core.gpu.RenderBackend;
import me.cortex.voxy.client.core.gpu.RenderBackendFactory;
import me.cortex.voxy.client.core.model.bakery.BudgetBufferRenderer;
import me.cortex.voxy.client.core.rendering.util.SharedIndexBuffer;
import me.cortex.voxy.common.Logger;
import me.cortex.voxy.commonImpl.VoxyCommon;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.debug.DebugScreenDisplayer;
import net.minecraft.client.gui.components.debug.DebugScreenEntries;
import net.minecraft.client.gui.components.debug.DebugScreenEntry;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.LevelChunk;
import org.jspecify.annotations.Nullable;

import java.util.HashSet;
import java.util.function.Consumer;
import java.util.function.Function;

public class VoxyClient implements ClientModInitializer {
    private static final HashSet<String> FREX = new HashSet<>();

    public static void initVoxyClient() {
        Capabilities.init();//Ensure clinit is called

        if (Capabilities.INSTANCE.hasBrokenDepthSampler) {
            Logger.error("AMD broken depth sampler detected, voxy does not work correctly and has been disabled, this will hopefully be fixed in the future");
        }

        // Query the active render backend instead of OpenGL capabilities directly:
        // on macOS Apple Silicon the OpenGL driver tops out at 4.1 (no compute,
        // no indirect draws), which would fail this check even though the Metal
        // backend supports both. MetalRenderBackend reports compute and indirect
        // as available; the OpenGL backend delegates to the legacy Capabilities.
        RenderBackend backend = RenderBackendFactory.get();
        Logger.info("Render backend: " + backend.getType()
                + " (compute=" + backend.hasCompute()
                + ", indirectParameters=" + backend.hasIndirectParameters() + ")");

        boolean systemSupported = backend.hasCompute() && backend.hasIndirectParameters() && !Capabilities.INSTANCE.hasBrokenDepthSampler;

        // M9 transitional: even though MetalRenderBackend reports compute=true and
        // indirectParameters=true, Voxy's render path (MDICSectionRenderer,
        // HiZBuffer2, HierarchicalOcclusionTraverser, ChunkBoundRenderer,
        // bakery, AbstractRenderPipeline) is still raw OpenGL DSA and aborts
        // the JVM the first time the GL driver hits an unsupported call on
        // Apple's frozen GL 4.1. Until that work lands (M9-M11 migration to
        // the encoder API + IOSurface bridge), force-disable Voxy on
        // non-OpenGL backends so the mixins find VoxyRenderSystem == null
        // and Sodium's chunk render runs unmodified — the user sees normal
        // close-distance MC + Sodium rendering instead of a blue screen.
        // Feature flag to opt into the Metal render path. The flag exists
        // separately from regular env vars so opting in is intentional —
        // until the full MDIC encoder migration lands, this path produces
        // a clear color via the IOSurfaceBridge, not actual LOD chunks.
        boolean forceMetal = "1".equals(System.getenv("VOXY_FORCE_METAL"))
                || "true".equals(System.getenv("VOXY_FORCE_METAL"))
                || "true".equals(System.getProperty("voxy.forceMetal", "false"));

        if (systemSupported && backend.getType() != me.cortex.voxy.client.core.gpu.BackendType.OPENGL) {
            if (forceMetal) {
                Logger.info("[VOXY_FORCE_METAL] Voxy enabled on " + backend.getType()
                        + " backend. Render output flows through the IOSurface bridge; "
                        + "MDIC draws real LOD geometry with the Metal model atlas bakery. "
                        + "Remaining M13 gaps: depth import (real HiZ + cull stub) and SSAO. "
                        + "Set VOXY_BAKERY_OFF=1 for the hash-colour fallback.");
            } else {
                Logger.warn("[M9 TRANSITIONAL] Voxy disabled on " + backend.getType()
                        + " backend. Set VOXY_FORCE_METAL=1 to enable the Metal render path.");
                systemSupported = false;
            }
        }

        if (systemSupported) {

            SharedIndexBuffer.INSTANCE.id();
            BudgetBufferRenderer.init();

            VoxyCommon.setInstanceFactory(VoxyClientInstance::new);

            if (!Capabilities.INSTANCE.subgroup) {
                Logger.warn("GPU does not support subgroup operations, expect some performance degradation");
            }

        } else {
            Logger.error("Voxy is unsupported on your system.");
        }
    }

    @Override
    public void onInitializeClient() {
        // Iris-pack + Metal coexistence now happens at the SOLID-pass head:
        // IrisGbufferInjector draws the LOD bridge into the pack's terrain
        // gbuffer (MixinDefaultChunkRenderer). The former HUD-time late
        // composite that lived here is gone — it ran after Iris finalized but
        // painted over the pack's post chain and skipped entirely with the
        // HUD hidden (F1).
        DebugScreenEntries.register(Identifier.fromNamespaceAndPath("voxy", "version"), new DebugScreenEntry() {
            @Override
            public void display(DebugScreenDisplayer lines, @Nullable Level level, @Nullable LevelChunk levelChunk, @Nullable LevelChunk levelChunk2) {
                if (!VoxyCommon.isAvailable()) {
                    lines.addLine(ChatFormatting.RED + "voxy-"+VoxyCommon.MOD_VERSION);//Voxy installed, not avalible
                    return;
                }
                var instance = VoxyCommon.getInstance();
                if (instance == null) {
                    lines.addLine(ChatFormatting.YELLOW + "voxy-" + VoxyCommon.MOD_VERSION);//Voxy avalible, no instance active
                    return;
                }
                VoxyRenderSystem vrs = null;
                var wr = Minecraft.getInstance().levelRenderer;
                if (wr != null) vrs = ((IGetVoxyRenderSystem) wr).getVoxyRenderSystem();

                //Voxy instance active
                lines.addLine((vrs==null?ChatFormatting.DARK_GREEN:ChatFormatting.GREEN)+"voxy-"+VoxyCommon.MOD_VERSION);
            }
        });

        DebugScreenEntries.register(Identifier.fromNamespaceAndPath("voxy","debug"), new VoxyDebugScreenEntry());
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) -> {
            if (VoxyCommon.isAvailable()) {
                dispatcher.register(VoxyCommands.register());
            }
        });

        // VOXY_AUTO_SCREENSHOT=<seconds>: periodically save an in-game
        // screenshot via MC's own Screenshot API (lands in run/screenshots).
        // Debug-loop aid — headless verification can capture frames without
        // desktop screencapture (which fails when other windows are
        // frontmost on the test machine).
        String autoShot = System.getenv("VOXY_AUTO_SCREENSHOT");
        if (autoShot != null && !autoShot.isBlank()) {
            int parsedInterval;
            try {
                parsedInterval = Math.max(2, Integer.parseInt(autoShot.trim()));
            } catch (NumberFormatException e) {
                parsedInterval = 10;
            }
            final long intervalNanos = parsedInterval * 1_000_000_000L;
            final long[] last = {System.nanoTime()};
            net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents.END_CLIENT_TICK.register(client -> {
                if (client.level == null || client.getMainRenderTarget() == null) return;
                long now = System.nanoTime();
                if (now - last[0] < intervalNanos) return;
                last[0] = now;
                net.minecraft.client.Screenshot.grab(client.gameDirectory,
                        client.getMainRenderTarget(), component -> {});
            });
            Logger.info("VOXY_AUTO_SCREENSHOT active: every " + parsedInterval + "s");
        }

        // VOXY_DEBUG_REJOIN=<seconds>: after N seconds in-world, save-quit to
        // the title screen via the exact pause-menu chain
        // (Minecraft.disconnectFromWorld) and re-join the VOXY_QUICKPLAY world
        // exactly once. Automates the leave+rejoin white-LOD repro without
        // menu interaction (--quickPlaySingleplayer is a one-shot vanilla boot
        // cookie, so the rejoin must issue openWorld itself — the same call
        // vanilla QuickPlay.joinSingleplayerWorld makes). Diagnostic-only,
        // default off; pair with VOXY_AUTO_SCREENSHOT.
        String rejoin = System.getenv("VOXY_DEBUG_REJOIN");
        if (rejoin != null && !rejoin.isBlank()) {
            int rejoinSecs;
            try {
                rejoinSecs = Math.max(2, Integer.parseInt(rejoin.trim()));
            } catch (NumberFormatException e) {
                rejoinSecs = 15;
            }
            final long delayNanos = rejoinSecs * 1_000_000_000L;
            final String rejoinWorld = System.getenv("VOXY_QUICKPLAY");
            // 0 await-world, 1 in-world timer, 2 await-title, 3 title-settle, 4 done
            final int[] phase = {0};
            final long[] t0 = {0};
            final int[] settle = {0};
            net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents.END_CLIENT_TICK.register(client -> {
                switch (phase[0]) {
                    case 0 -> {
                        if (client.level != null) {
                            t0[0] = System.nanoTime();
                            phase[0] = 1;
                        }
                    }
                    case 1 -> {
                        if (client.level == null) {
                            phase[0] = 0;
                            return;
                        }
                        if (System.nanoTime() - t0[0] < delayNanos) return;
                        phase[0] = 2;
                        Logger.info("[VOXY_DEBUG_REJOIN] leaving to title");
                        // schedule() enqueues on the client event loop (execute()
                        // would run inline mid-tick); disconnectFromWorld is the
                        // pause-menu "Save and Quit" chain: level.disconnect ->
                        // disconnectWithSavingScreen -> setScreen(TitleScreen).
                        client.schedule(() -> client.disconnectFromWorld(
                                net.minecraft.client.multiplayer.ClientLevel.DEFAULT_QUIT_MESSAGE));
                    }
                    case 2 -> {
                        if (client.level == null
                                && client.screen instanceof net.minecraft.client.gui.screens.TitleScreen) {
                            settle[0] = 0;
                            phase[0] = 3;
                        }
                    }
                    case 3 -> {
                        // ~2s at title: let VoxyRenderSystem shutdown + the
                        // resolve-pass reset finish before the rejoin.
                        if (++settle[0] < 40) return;
                        phase[0] = 4; // fires exactly once per process
                        if (rejoinWorld == null || rejoinWorld.isBlank()) {
                            Logger.error("[VOXY_DEBUG_REJOIN] set VOXY_QUICKPLAY=<world folder> for the rejoin target");
                            return;
                        }
                        Logger.info("[VOXY_DEBUG_REJOIN] rejoining '" + rejoinWorld + "'");
                        // Same call vanilla QuickPlay.joinSingleplayerWorld makes:
                        client.createWorldOpenFlows().openWorld(rejoinWorld,
                                () -> client.setScreen(new net.minecraft.client.gui.screens.TitleScreen()));
                    }
                    default -> {}
                }
            });
            Logger.info("VOXY_DEBUG_REJOIN active: leave+rejoin after " + rejoinSecs + "s in-world");
        }

        FabricLoader.getInstance()
                .getEntrypoints("frex_flawless_frames", Consumer.class)
                .forEach(api -> ((Consumer<Function<String,Consumer<Boolean>>>)api).accept(name->active->{if (active) {
                    FREX.add(name);
                } else {
                    FREX.remove(name);
                }}));
    }

    public static boolean isFrexActive() {
        return !FREX.isEmpty();
    }

    public static int getOcclusionDebugState() {
        return 0;
    }

    public static boolean disableSodiumChunkRender() {
        return false;// getOcclusionDebugState() != 0;
    }
}
