package me.cortex.voxy.client.core;

import me.cortex.voxy.client.core.interop.TestGlContext;
import me.cortex.voxy.client.core.rendering.Viewport;
import me.cortex.voxy.client.core.util.IrisUtil;
import net.caffeinemc.mods.sodium.client.render.chunk.ChunkRenderMatrices;
import net.irisshaders.iris.Iris;
import net.irisshaders.iris.pipeline.*;
import net.irisshaders.iris.uniforms.SystemTimeUniforms;
import net.minecraft.client.Minecraft;
import org.joml.Matrix4f;
import static org.mockito.Mockito.*;

/** Actual capture/prepare and material entry points, rather than a duplicate frame algorithm. */
public final class WorldFrameProtocolRegressionTest {
    private static int failures;
    private interface Case { void run() throws Exception; }
    private static void check(String name, Case test) {
        try { test.run(); System.out.println("PASS: " + name); }
        catch (Throwable error) { failures++; error.printStackTrace(); System.err.println("FAIL: " + name); }
    }
    public static void main(String[] args) throws Exception {
        net.minecraft.SharedConstants.tryDetectVersion(); net.minecraft.server.Bootstrap.bootStrap();
        me.cortex.voxy.common.Logger.SHUTUP = true;
        try (var context = new TestGlContext(); var minecraft = mockStatic(Minecraft.class)) {
            minecraft.when(Minecraft::getInstance).thenReturn(mock(Minecraft.class));
            check("capture copies inputs and Iris/Sodium reuse one preparation", WorldFrameProtocolRegressionTest::preparation);
            check("failed capture leaves prior frame intact", WorldFrameProtocolRegressionTest::failedCapture);
            check("shadow capture cannot advance main-view history", WorldFrameProtocolRegressionTest::shadow);
            check("material ownership follows main-view capture instead of Iris clock reads", WorldFrameProtocolRegressionTest::material);
            check("mid-frame renderer creation captures before material ownership begins", WorldFrameProtocolRegressionTest::firstMaterial);
        }
        if (failures > 0) throw new AssertionError(failures + " frame protocol regressions failed");
    }
    private static ChunkRenderMatrices inputs() { return new ChunkRenderMatrices(new Matrix4f().perspective(1, 1, 1, 100), new Matrix4f().translation(1, 2, 3)); }
    private static void preparation() {
        var renderer = mock(VoxyRenderSystem.class); var viewport = mock(Viewport.class);
        var source = inputs();
        var projection = new Matrix4f(source.projection());
        when(renderer.setupViewport(any(), any(), anyDouble(), anyDouble(), anyDouble())).thenReturn(viewport);
        WorldFrameCapture.capture(renderer, source, null, 1, 2, 3);
        ((Matrix4f)source.projection()).identity();
        require(WorldFrameCapture.prepare(renderer) == viewport, "Iris preparation failed");
        require(WorldFrameCapture.prepare(renderer, inputs(), null, 4, 5, 6) == viewport, "Sodium prepared a second viewport");
        var matrices = org.mockito.ArgumentCaptor.forClass(ChunkRenderMatrices.class);
        verify(renderer, times(1)).setupViewport(matrices.capture(), any(), eq(1.0), eq(2.0), eq(3.0));
        require(projection.equals(matrices.getValue().projection()), "capture retained mutable input");
        var replacement = mock(VoxyRenderSystem.class);
        WorldFrameCapture.capture(replacement, inputs(), null, 0, 0, 0);
        WorldFrameCapture.release(renderer);
        require(WorldFrameCapture.prepare(renderer) == null, "obsolete renderer prepared replacement frame");
        WorldFrameCapture.release(replacement);
    }
    private static void failedCapture() {
        var renderer = mock(VoxyRenderSystem.class);
        WorldFrameCapture.capture(renderer, inputs(), null, 0, 0, 0);
        long frame = WorldFrameCapture.frame();
        try { WorldFrameCapture.capture(renderer, new ChunkRenderMatrices(null, new Matrix4f()), null, 0, 0, 0); throw new AssertionError("bad input accepted"); }
        catch (NullPointerException expected) { }
        require(WorldFrameCapture.frame() == frame, "failed capture published a new frame identity");
        WorldFrameCapture.release(renderer);
    }
    private static void shadow() {
        var renderer = mock(VoxyRenderSystem.class);
        WorldFrameCapture.capture(renderer, inputs(), null, 0, 0, 0);
        long frame = WorldFrameCapture.frame();
        try (var iris = mockStatic(IrisUtil.class)) {
            iris.when(IrisUtil::shadowsBeingRendered).thenReturn(true);
            WorldFrameCapture.capture(renderer, inputs(), null, 10, 20, 30);
            require(WorldFrameCapture.frame() == frame, "shadow capture advanced main-view identity");
        }
        WorldFrameCapture.release(renderer);
    }
    private static void material() throws Exception {
        var renderer = mock(VoxyRenderSystem.class);
        var generation = mock(IrisRenderingPipeline.class); var manager = mock(PipelineManager.class);
        when(manager.getPipelineNullable()).thenReturn(generation);
        var pipeline = mock(MetalVxRenderPipeline.class, CALLS_REAL_METHODS);
        var state = new MaterialFrameState(generation);
        var field = MetalVxRenderPipeline.class.getDeclaredField("frames"); field.setAccessible(true); field.set(pipeline, state);
        net.fabricmc.loader.impl.launch.FabricLauncherBase.setLauncher(mock(net.fabricmc.loader.impl.launch.FabricLauncher.class));
        try (var iris = mockStatic(Iris.class); var policy = mockStatic(IrisUtil.class)) {
            iris.when(Iris::getPipelineManager).thenReturn(manager);
            WorldFrameCapture.capture(renderer, inputs(), null, 0, 0, 0);
            require(pipeline.beginMaterialFrame(), "captured material frame rejected");
            SystemTimeUniforms.COUNTER.beginFrame();
            require(!pipeline.beginMaterialFrame(), "Iris clock change restarted an already begun main-view frame");
            WorldFrameCapture.capture(renderer, inputs(), null, 0, 0, 0);
            require(pipeline.beginMaterialFrame(), "new main-view capture reused old material identity");
            policy.when(IrisUtil::shadowsBeingRendered).thenReturn(true);
            require(!pipeline.beginMaterialFrame(), "shadow material reentry accepted");
        } finally { WorldFrameCapture.release(renderer); }
    }
    private static void firstMaterial() throws Exception {
        var renderer = mock(VoxyRenderSystem.class); var viewport = mock(Viewport.class);
        when(renderer.setupViewport(any(), any(), anyDouble(), anyDouble(), anyDouble())).thenReturn(viewport);
        var generation = mock(IrisRenderingPipeline.class); var manager = mock(PipelineManager.class);
        when(manager.getPipelineNullable()).thenReturn(generation);
        var pipeline = mock(MetalVxRenderPipeline.class, CALLS_REAL_METHODS);
        var state = new MaterialFrameState(generation);
        var field = MetalVxRenderPipeline.class.getDeclaredField("frames"); field.setAccessible(true); field.set(pipeline, state);
        when(renderer.getPipeline()).thenReturn(pipeline);
        try (var iris = mockStatic(Iris.class); var policy = mockStatic(IrisUtil.class)) {
            iris.when(Iris::getPipelineManager).thenReturn(manager);
            WorldFrameCapture.release(renderer);
            Viewport<?> prepared;
            try {
                var entry = WorldFrameCapture.class.getMethod("prepareTerrain", VoxyRenderSystem.class,
                        ChunkRenderMatrices.class, net.caffeinemc.mods.sodium.client.util.FogParameters.class,
                        double.class, double.class, double.class);
                prepared = (Viewport<?>)entry.invoke(null, renderer, inputs(), null, 0.0, 0.0, 0.0);
            } catch (NoSuchMethodException oldImplementation) {
                // The original Sodium hook takes ownership before its fallback capture.
                prepared = pipeline.beginMaterialFrame() ? WorldFrameCapture.prepare(renderer, inputs(), null, 0, 0, 0) : null;
            }
            require(prepared == viewport, "first material viewport rejected");
            pipeline.publishMaterialFrame();
            require(state.consume(generation, WorldFrameCapture.frame()), "first material frame was published against the previous capture");
        } finally { WorldFrameCapture.release(renderer); }
    }
    private static void require(boolean value, String message) { if (!value) throw new AssertionError(message); }
}
