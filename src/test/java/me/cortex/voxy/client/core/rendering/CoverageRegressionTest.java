package me.cortex.voxy.client.core.rendering;

import me.cortex.voxy.client.config.VoxyConfig;
import me.cortex.voxy.client.core.IGetVoxyRenderSystem;
import me.cortex.voxy.client.core.VoxyRenderSystem;
import me.cortex.voxy.client.mixin.sodium.MixinRenderSectionManager;
import net.caffeinemc.mods.sodium.client.render.chunk.RenderSection;
import net.caffeinemc.mods.sodium.client.render.chunk.RenderSectionFlags;
import net.caffeinemc.mods.sodium.client.render.chunk.data.BuiltSectionInfo;
import net.minecraft.SharedConstants;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.core.SectionPos;
import net.minecraft.server.Bootstrap;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

import static org.mockito.Mockito.*;

/** Runs the real upload callback and Sodium metadata API, without a GPU or a world. */
public final class CoverageRegressionTest {
    private static int failures;
    private static LevelRenderer levelRenderer;
    private static VoxyRenderSystem system;
    private interface Case { void run() throws Exception; }
    private static void check(String name, Case test) {
        try { test.run(); System.out.println("PASS: " + name); }
        catch (Throwable error) { failures++; System.err.println("FAIL: " + name + ": " + error); }
    }
    private static void set(Object target, Class<?> owner, String name, Object value) throws Exception {
        Field field = owner.getDeclaredField(name); field.setAccessible(true); field.set(target, value);
    }
    private static MixinRenderSectionManager manager() throws Exception {
        var manager = new MixinRenderSectionManager();
        var level = mock(ClientLevel.class);
        set(level, ClientLevel.class, "levelRenderer", levelRenderer);
        set(manager, MixinRenderSectionManager.class, "level", level);
        Method reset = java.util.Arrays.stream(MixinRenderSectionManager.class.getDeclaredMethods())
                .filter(method -> method.getName().equals("voxy$resetChunkTracker")).findFirst().orElseThrow();
        reset.setAccessible(true);
        reset.invoke(manager, level, 8, null, null, null);
        // Preceding implementation has a second constructor callback owning its static mirror.
        try {
            Method legacyReset = MixinRenderSectionManager.class.getDeclaredMethod("voxy$resetBoundMirror",
                    org.spongepowered.asm.mixin.injection.callback.CallbackInfo.class);
            legacyReset.setAccessible(true); legacyReset.invoke(manager, new Object[]{null});
        } catch (NoSuchMethodException ignored) { }
        return manager;
    }
    private static BuiltSectionInfo info(int flags, boolean opaque) throws Exception {
        var info = mock(BuiltSectionInfo.class, withSettings().extraInterfaces(IVoxyBuiltSectionInfo.class));
        set(info, BuiltSectionInfo.class, "flags", flags);
        when(((IVoxyBuiltSectionInfo)info).voxy$hasOpaque()).thenReturn(opaque);
        return info;
    }
    private static void upload(MixinRenderSectionManager manager, RenderSection section, BuiltSectionInfo info) throws Exception {
        Method method = MixinRenderSectionManager.class.getDeclaredMethod("voxy$updateOnUpload", RenderSection.class, BuiltSectionInfo.class);
        method.setAccessible(true); method.invoke(manager, section, info);
    }
    private static String coverage(RenderSection section) throws Exception {
        long pos = SectionPos.asLong(section.getChunkX(), section.getChunkY(), section.getChunkZ());
        try {
            Class<?> tracker = Class.forName("me.cortex.voxy.client.core.rendering.SectionCoverageTracker");
            Object instance = tracker.getField("INSTANCE").get(null);
            return tracker.getMethod("coverageAt", long.class).invoke(instance, pos).toString();
        } catch (ClassNotFoundException precedingImplementation) {
            for (String name : new String[]{"MIRROR_OPAQUE", "MIRROR_TRANS_ONLY"}) {
                Field mirror = ChunkBoundRenderer.class.getDeclaredField(name); mirror.setAccessible(true);
                if (((it.unimi.dsi.fastutil.longs.LongOpenHashSet)mirror.get(null)).contains(pos)) {
                    return name.equals("MIRROR_OPAQUE") ? "OPAQUE" : "TRANSLUCENT";
                }
            }
            return "NONE";
        }
    }
    private static void expect(RenderSection section, String expected) throws Exception {
        String actual = coverage(section);
        if (!actual.equals(expected)) throw new AssertionError("expected " + expected + " got " + actual);
    }
    public static void main(String[] args) throws Exception {
        SharedConstants.tryDetectVersion(); Bootstrap.bootStrap(); VoxyConfig.CONFIG.ingestEnabled = false;
        levelRenderer = mock(LevelRenderer.class, withSettings().extraInterfaces(IGetVoxyRenderSystem.class));
        system = mock(VoxyRenderSystem.class);
        set(system, VoxyRenderSystem.class, "chunkBoundRenderer", mock(ChunkBoundRenderer.class));
        when(((IGetVoxyRenderSystem)levelRenderer).getVoxyRenderSystem()).thenReturn(system);
        check("upload, duplicate metadata and removal", () -> {
            var manager = manager(); var section = new RenderSection(null, 1, 2, 3);
            upload(manager, section, info(RenderSectionFlags.MASK_HAS_BLOCK_GEOMETRY, true)); expect(section, "OPAQUE");
            upload(manager, section, info(RenderSectionFlags.MASK_HAS_BLOCK_GEOMETRY, true)); expect(section, "OPAQUE");
            upload(manager, section, null); expect(section, "NONE");
            upload(manager, section, null); expect(section, "NONE");
        });
        check("same flags still permit opaque/translucent reclassification", () -> {
            var manager = manager(); var section = new RenderSection(null, 4, 2, 3);
            upload(manager, section, info(RenderSectionFlags.MASK_HAS_BLOCK_GEOMETRY, true));
            upload(manager, section, info(RenderSectionFlags.MASK_HAS_BLOCK_GEOMETRY, false)); expect(section, "TRANSLUCENT");
        });
        check("block-entity-only sections do not mask terrain", () -> {
            var manager = manager(); var section = new RenderSection(null, 5, 2, 3);
            upload(manager, section, info(RenderSectionFlags.MASK_HAS_BLOCK_ENTITIES, true)); expect(section, "NONE");
        });
        check("coverage is maintained while the Voxy renderer is absent", () -> {
            var manager = manager(); var section = new RenderSection(null, 6, 2, 3);
            when(((IGetVoxyRenderSystem)levelRenderer).getVoxyRenderSystem()).thenReturn(null);
            try { upload(manager, section, info(RenderSectionFlags.MASK_HAS_BLOCK_GEOMETRY, true)); expect(section, "OPAQUE"); }
            finally { when(((IGetVoxyRenderSystem)levelRenderer).getVoxyRenderSystem()).thenReturn(system); }
        });
        check("obsolete manager callbacks cannot repopulate coverage", () -> {
            var obsolete = manager(); var section = new RenderSection(null, 7, 2, 3);
            manager();
            upload(obsolete, section, info(RenderSectionFlags.MASK_HAS_BLOCK_GEOMETRY, true)); expect(section, "NONE");
        });
        check("disposed and empty sections do not retain coverage", () -> {
            var manager = manager(); var section = new RenderSection(null, 8, 2, 3);
            upload(manager, section, info(RenderSectionFlags.MASK_HAS_BLOCK_GEOMETRY, true));
            section.delete(); upload(manager, section, info(RenderSectionFlags.MASK_HAS_BLOCK_GEOMETRY, true)); expect(section, "NONE");
            upload(manager, section, BuiltSectionInfo.EMPTY); expect(section, "NONE");
        });
        check("replacement renderer seeds once and drains consolidated changes", () -> {
            Class<?> trackerType = Class.forName("me.cortex.voxy.client.core.rendering.SectionCoverageTracker");
            Object tracker = trackerType.getConstructor().newInstance();
            Object generation = trackerType.getMethod("beginGeneration").invoke(tracker);
            Class<?> coverageType = Class.forName(trackerType.getName() + "$Coverage");
            Object opaque = java.util.Arrays.stream(coverageType.getEnumConstants()).filter(v -> v.toString().equals("OPAQUE")).findFirst().orElseThrow();
            Object translucent = java.util.Arrays.stream(coverageType.getEnumConstants()).filter(v -> v.toString().equals("TRANSLUCENT")).findFirst().orElseThrow();
            Method publish = trackerType.getMethod("publish", generation.getClass(), long.class, coverageType);
            publish.invoke(tracker, generation, 99L, opaque);
            Object subscription = trackerType.getMethod("subscribe").invoke(tracker);
            Method drain = subscription.getClass().getMethod("drain");
            Object update = drain.invoke(subscription);
            Method changes = update.getClass().getMethod("changes");
            if (((java.util.Map<?, ?>)changes.invoke(update)).size() != 1) throw new AssertionError("renderer was not seeded");
            if (!((java.util.Map<?, ?>)changes.invoke(drain.invoke(subscription))).isEmpty()) throw new AssertionError("seed replayed twice");
            publish.invoke(tracker, generation, 99L, opaque);
            if (!((java.util.Map<?, ?>)changes.invoke(drain.invoke(subscription))).isEmpty()) throw new AssertionError("duplicate addition queued");
            publish.invoke(tracker, generation, 99L, translucent);
            publish.invoke(tracker, generation, 99L, opaque);
            if (!((java.util.Map<?, ?>)changes.invoke(drain.invoke(subscription))).get(99L).equals(opaque)) throw new AssertionError("reclassifications did not consolidate");
            trackerType.getMethod("beginGeneration").invoke(tracker);
            if ((boolean)publish.invoke(tracker, generation, 99L, opaque)) throw new AssertionError("stale generation accepted");
            update = drain.invoke(subscription);
            if (!(boolean)update.getClass().getMethod("reset").invoke(update)) throw new AssertionError("replacement did not clear renderer");
            if (!((java.util.Map<?, ?>)changes.invoke(update)).isEmpty()) throw new AssertionError("replacement replayed obsolete geometry");
            subscription.getClass().getMethod("close").invoke(subscription);
        });
        if (failures > 0) throw new AssertionError(failures + " real upload/coverage regressions failed");
    }
}
