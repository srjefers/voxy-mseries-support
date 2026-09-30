package me.cortex.voxy.client.core.rendering;

import com.mojang.blaze3d.platform.Window;
import me.cortex.voxy.client.core.VoxyRenderSystem;
import net.minecraft.SharedConstants;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.server.Bootstrap;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import org.joml.Vector4f;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Mocks only Minecraft inputs; executes the actual production projection method. */
public final class ProjectionRegressionTest {
    public static void main(String[] args) throws Exception {
        SharedConstants.tryDetectVersion(); Bootstrap.bootStrap();
        Minecraft client = mock(Minecraft.class);
        GameRenderer renderer = mock(GameRenderer.class);
        var rendererField = Minecraft.class.getDeclaredField("gameRenderer"); rendererField.setAccessible(true);
        rendererField.set(client, renderer);
        Window window = mock(Window.class);
        when(client.getWindow()).thenReturn(window);
        DeltaTracker delta = mock(DeltaTracker.class);
        when(client.getDeltaTracker()).thenReturn(delta);
        when(renderer.getDepthFar()).thenReturn(512f);
        when(renderer.getRenderDistance()).thenReturn(64f);
        var method = VoxyRenderSystem.class.getDeclaredMethod("computeProjectionMat", Matrix4fc.class);
        method.setAccessible(true);
        int failures = 0, cases = 0;
        try (var singleton = mockStatic(Minecraft.class)) {
            singleton.when(Minecraft::getInstance).thenReturn(client);
            for (float fov : new float[]{35, 70, 110}) {
                when(renderer.getFov(any(), anyFloat(), eq(true))).thenReturn(fov);
                for (int width : new int[]{1280, 1920}) {
                    when(window.getWidth()).thenReturn(width); when(window.getHeight()).thenReturn(720);
                    for (boolean shiftedRaw : new boolean[]{false, true}) {
                        Matrix4f raw = new Matrix4f().perspective((float)Math.toRadians(fov), width / 720f,
                                shiftedRaw ? .075f : .05f, shiftedRaw ? 768f : 512f);
                        if (shiftedRaw) raw.m20(.007f).m21(-.011f).m00(raw.m00() * 1.15f);
                        when(renderer.getProjectionMatrix(fov)).thenAnswer(invocation -> new Matrix4f(raw));
                        Matrix4f cameraExtra = new Matrix4f().translation(.025f, -.017f, .006f).rotateZ(.025f).rotateY(.012f);
                        Matrix4f base = raw.mul(cameraExtra, new Matrix4f());
                        Matrix4f original = new Matrix4f(base);
                        Matrix4f result = (Matrix4f)method.invoke(null, base);
                        Matrix4f expected = new Matrix4f(raw).m22((48000f + 16f) / (16f - 48000f))
                                .m32((2 * 48000f * 16f) / (16f - 48000f)).mul(cameraExtra);
                        cases++;
                        try {
                            if (!base.equals(original, .00001f)) throw new AssertionError("mutated Minecraft projection");
                            if (!result.equals(expected, .001f)) throw new AssertionError("lost raw camera projection or extra transforms");
                            for (var point : new Vector4f[]{new Vector4f(12, 8, -300, 1), new Vector4f(-8, 2, -64, 1)}) {
                                Vector4f vanilla = original.transform(new Vector4f(point));
                                Vector4f lod = result.transform(new Vector4f(point));
                                if (Math.abs(vanilla.x / vanilla.w - lod.x / lod.w) > .00005f
                                        || Math.abs(vanilla.y / vanilla.w - lod.y / lod.w) > .00005f) {
                                    throw new AssertionError("screen-space camera alignment changed");
                                }
                            }
                        } catch (AssertionError error) {
                            failures++; System.err.println("FAIL: fov=" + fov + " width=" + width + " shifted=" + shiftedRaw + " " + error);
                        }
                    }
                }
            }
        }
        if (failures > 0) throw new AssertionError(failures + "/" + cases + " production projection cases failed");
        System.out.println("PASS: " + cases + " production projection cases: FOV, resize, raw offsets, bobbing and camera transforms");
    }
}
