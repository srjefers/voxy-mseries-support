package me.cortex.voxy.client.core;

import me.cortex.voxy.client.config.VoxyConfig;
import me.cortex.voxy.client.core.gpu.*;
import me.cortex.voxy.client.core.interop.*;
import me.cortex.voxy.client.core.metal.MetalRenderBackend;
import me.cortex.voxy.client.core.model.ModelBakerySubsystem;
import me.cortex.voxy.client.core.rendering.*;
import me.cortex.voxy.client.core.rendering.building.RenderGenerationService;
import me.cortex.voxy.client.core.rendering.hierachical.*;
import me.cortex.voxy.client.core.rendering.section.geometry.BasicSectionGeometryData;
import me.cortex.voxy.common.Logger;
import me.cortex.voxy.common.world.WorldEngine;
import me.cortex.voxy.common.world.other.Mapper;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Options;
import java.lang.reflect.Field;
import static org.mockito.Mockito.*;

/** Failure injection through real constructor, frame owner and teardown interfaces. */
public final class RendererOwnershipRegressionTest {
    private static int failures;
    private interface Case { void run() throws Exception; }
    private static void check(String name, Case test) {
        try { test.run(); System.out.println("PASS: " + name); }
        catch (Throwable error) { failures++; error.printStackTrace(); System.err.println("FAIL: " + name); }
    }
    public static void main(String[] args) throws Exception {
        net.minecraft.SharedConstants.tryDetectVersion(); net.minecraft.server.Bootstrap.bootStrap();
        Logger.SHUTUP = true;
        try (var context = new TestGlContext()) {
            check("failed plane replacement retains live bridge", RendererOwnershipRegressionTest::replacement);
            check("frame teardown attempts later owners after a release failure", RendererOwnershipRegressionTest::frameClose);
            check("constructor failure releases acquired owners in dependency order", RendererOwnershipRegressionTest::construction);
            check("bakery preparation failure releases the partially acquired model store", RendererOwnershipRegressionTest::bakeryConstruction);
        }
        if (failures > 0) throw new AssertionError(failures + " renderer ownership regressions failed");
    }
    private static void replacement() {
        try (var minecraft = mockStatic(Minecraft.class)) {
            minecraft.when(Minecraft::getInstance).thenReturn(mock(Minecraft.class));
            FrameTargetRegressionTest.scenario(1);
        }
    }
    private static void frameClose() throws Exception {
        var frame = new MetalFrameRenderer(null, null, null, null);
        var bridge = mock(IOSurfaceBridge.class);
        var texture = mock(IGpuTexture.class, RETURNS_SELF);
        var backend = mock(MetalRenderBackend.class);
        var textures = new java.util.ArrayList<IGpuTexture>();
        var pipelines = new java.util.ArrayList<IGpuPipeline>();
        when(backend.createTexture()).thenAnswer(call -> {
            var acquired = textures.isEmpty() ? texture : mock(IGpuTexture.class, RETURNS_SELF);
            textures.add(acquired);return acquired;
        });
        when(backend.createBuffer(anyLong())).thenReturn(mock(IGpuBuffer.class));
        when(backend.createGraphicsPipeline(any())).thenAnswer(call -> {
            var acquired = mock(IGpuPipeline.class);pipelines.add(acquired);return acquired;
        });
        var failure = new IllegalStateException("injected release failure");
        doThrow(failure).when(texture).free();
        try (var bridges = mockStatic(IOSurfaceBridge.class)) {
            bridges.when(() -> IOSurfaceBridge.create(anyLong(), anyInt(), anyInt(), any())).thenReturn(bridge);
            var descriptor = new GraphicsPipelineDesc("", "", java.util.Map.of(), null, null, null, null,
                    org.lwjgl.opengl.GL11C.GL_RGBA8, VertexLayout.EMPTY, PipelineState.DEFAULT, "ownership fixture");
            var config = new me.cortex.voxy.client.core.rendering.section.backend.mdic.TerrainShaderConfiguration(descriptor, descriptor);
            var targets = new MetalFrameTargets(backend, new MetalFrameTargets.Layout(16,16,false,false,false,false), config);
            set(frame, "targets", targets);
            try { frame.close(); throw new AssertionError("cleanup failure hidden"); }
            catch (IllegalStateException expected) { require(expected == failure, "close lost first failure"); }
            verify(bridge).close();
            textures.forEach(owner -> verify(owner).free());pipelines.forEach(owner -> verify(owner).close());
            clearInvocations(bridge);textures.forEach(org.mockito.Mockito::clearInvocations);pipelines.forEach(org.mockito.Mockito::clearInvocations);
            frame.close();
            verifyNoInteractions(bridge);textures.forEach(org.mockito.Mockito::verifyNoInteractions);pipelines.forEach(org.mockito.Mockito::verifyNoInteractions);
        }
    }
    private static void construction() throws Exception {
        var backend = mock(RenderBackend.class);
        when(backend.getType()).thenReturn(BackendType.METAL);
        when(backend.getMaxSSBOSize()).thenReturn(1L << 30);
        when(backend.createPersistentBuffer(anyLong(), anyInt())).thenReturn(mock(IGpuPersistentBuffer.class, RETURNS_SELF));
        when(backend.createFence()).thenAnswer(call -> { var fence = mock(IGpuFence.class); when(fence.signaled()).thenReturn(true); return fence; });
        RenderBackendFactory.set(backend);
        VoxyConfig.CONFIG = new VoxyConfig();
        var world = mock(WorldEngine.class); var mapper = mock(Mapper.class);
        when(world.getMapper()).thenReturn(mapper);
        when(mapper.getBiomeEntries()).thenReturn(new Mapper.BiomeEntry[0]);
        var client = mock(Minecraft.class); var options = mock(Options.class);
        when(options.getEffectiveRenderDistance()).thenReturn(12);
        Field optionField = Minecraft.class.getDeclaredField("options"); optionField.setAccessible(true); optionField.set(client, options);
        var failure = new IllegalStateException("injected pipeline failure");
        try (var minecraft = mockStatic(Minecraft.class);
             var iris = mockStatic(me.cortex.voxy.client.core.util.IrisUtil.class);
             var model = mockConstruction(ModelBakerySubsystem.class);
             var meshing = mockConstruction(RenderGenerationService.class);
             var geometry = mockConstruction(BasicSectionGeometryData.class);
             var nodes = mockConstruction(AsyncNodeManager.class);
             var cleaner = mockConstruction(NodeCleaner.class);
             var traversal = mockConstruction(HierarchicalOcclusionTraverser.class);
             var pipelines = mockStatic(RenderPipelineFactory.class)) {
            minecraft.when(Minecraft::getInstance).thenReturn(client);
            pipelines.when(() -> RenderPipelineFactory.createPipeline(any(), any(), any(), any())).thenThrow(failure);
            try { new VoxyRenderSystem(world, null); throw new AssertionError("construction should fail"); }
            catch (RuntimeException expected) { require(expected == failure, "constructor lost original failure"); }
            var node = nodes.constructed().getFirst(); var mesh = meshing.constructed().getFirst();
            var bakery = model.constructed().getFirst();
            var order = inOrder(node, mesh, bakery);
            order.verify(node).quiesce(); order.verify(mesh).quiesce(); order.verify(bakery).quiesce();
            verify(node).stop(); verify(mesh).shutdown(); verify(bakery).shutdown();
            verify(geometry.constructed().getFirst()).free(); verify(world).releaseRef();
            verify(cleaner.constructed().getFirst()).free(); verify(traversal.constructed().getFirst()).free();
            verify(world).setDirtyCallback(null); verify(mapper).setBiomeCallback(null);
        }
    }
    private static void bakeryConstruction() {
        var injected = new IllegalStateException("bakery preparation fault");
        try (var store = mockConstruction(me.cortex.voxy.client.core.model.ModelStore.class);
             var constructors = mockConstruction(me.cortex.voxy.client.core.model.ModelFactory.class, (mock, context) -> { throw injected; })) {
            try { new ModelBakerySubsystem(mock(Mapper.class)); throw new AssertionError("bakery failure hidden"); }
            catch (RuntimeException expected) {
                Throwable cause = expected;
                while (cause.getCause() != null) cause = cause.getCause();
                require(cause == injected, "bakery failure cause lost");
            }
            verify(store.constructed().getFirst()).free();
        }
    }
    private static void set(Object target, String name, Object value) throws Exception {
        var field = target.getClass().getDeclaredField(name); field.setAccessible(true); field.set(target, value);
    }
    private static void require(boolean value, String message) { if (!value) throw new AssertionError(message); }
}
