package me.cortex.voxy.client.core.rendering;

import me.cortex.voxy.client.VoxyClientInstance;
import me.cortex.voxy.client.config.VoxyConfig;
import me.cortex.voxy.client.mixin.minecraft.MixinClientPacketListener;
import me.cortex.voxy.client.mixin.minecraft.MixinMinecraft;
import me.cortex.voxy.commonImpl.VoxyCommon;
import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

import static org.mockito.Mockito.*;

/** Exercises the actual session hooks without starting worlds, renderers or worker threads. */
public final class ClientSessionRegressionTest {
    private static int failures;
    private interface Case { void run() throws Exception; }
    private static Field sessionField() throws Exception {
        try { return Class.forName("me.cortex.voxy.client.ClientSessionEvents").getField("inSession"); }
        catch (ClassNotFoundException precedingImplementation) { return VoxyClientInstance.class.getField("isInGame"); }
    }
    private static void start() throws Exception {
        Method hook = java.util.Arrays.stream(MixinClientPacketListener.class.getDeclaredMethods())
                .filter(method -> method.getName().equals("voxy$init")).findFirst().orElseThrow();
        hook.setAccessible(true); hook.invoke(new MixinClientPacketListener(), null, null);
    }
    private static void end() throws Exception {
        Method hook = MixinMinecraft.class.getDeclaredMethod("voxy$injectWorldClose",
                org.spongepowered.asm.mixin.injection.callback.CallbackInfo.class);
        hook.setAccessible(true); hook.invoke(new MixinMinecraft(), new Object[]{null});
    }
    private static void check(String name, Case test) {
        try { sessionField().setBoolean(null, false); test.run(); System.out.println("PASS: " + name); }
        catch (Throwable error) { failures++; System.err.println("FAIL: " + name + ": " + error); }
    }
    public static void main(String[] args) throws Exception {
        SharedConstants.tryDetectVersion(); Bootstrap.bootStrap();
        try (var common = mockStatic(VoxyCommon.class)) {
            common.when(VoxyCommon::isAvailable).thenReturn(false);
            VoxyConfig.CONFIG = new VoxyConfig(); // Initialize while unavailable; never write Fabric config.
            check("unavailable Voxy still tracks joining and leaving a session", () -> {
                start();
                if (!sessionField().getBoolean(null)) throw new AssertionError("session was not tracked");
                end();
                if (sessionField().getBoolean(null)) throw new AssertionError("session remained active");
                common.verify(VoxyCommon::createInstance, never());
            });
            common.reset();
            check("disconnect cleans up even if availability changed after joining", () -> {
                common.when(VoxyCommon::isAvailable).thenReturn(true);
                VoxyConfig.CONFIG.enabled = true;
                start();
                common.verify(VoxyCommon::createInstance);
                common.when(VoxyCommon::isAvailable).thenReturn(false);
                end();
                common.verify(VoxyCommon::shutdownInstance);
                if (sessionField().getBoolean(null)) throw new AssertionError("session remained active");
            });
            common.reset();
            check("duplicate login and disconnect hooks do not duplicate instance ownership", () -> {
                common.when(VoxyCommon::isAvailable).thenReturn(true);
                VoxyConfig.CONFIG.enabled = true;
                start(); start(); end(); end(); start(); end();
                common.verify(VoxyCommon::createInstance, times(2));
                common.verify(VoxyCommon::shutdownInstance, times(2));
                if (sessionField().getBoolean(null)) throw new AssertionError("session remained active");
            });
        }
        if (failures > 0) throw new AssertionError(failures + " client-session regressions failed");
    }
}
