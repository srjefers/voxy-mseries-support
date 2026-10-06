package me.cortex.voxy.client.core;

import me.cortex.voxy.client.core.gpu.*;
import me.cortex.voxy.client.core.interop.*;
import me.cortex.voxy.client.core.metal.MetalRenderBackend;
import me.cortex.voxy.client.core.rendering.hierachical.*;
import me.cortex.voxy.client.core.rendering.section.backend.mdic.*;
import me.cortex.voxy.client.core.rendering.util.HiZBuffer;
import java.util.*;
import static org.mockito.Mockito.*;

/** Allocation faults at every target acquisition, through the actual frame owner. No game or display. */
public final class FrameTargetRegressionTest {
    private record Owned(Object resource, Runnable release) { }
    private static int failures;
    private static void check(String name, Runnable test) {
        try { test.run(); System.out.println("PASS: " + name); }
        catch (Throwable error) { failures++; error.printStackTrace(); System.err.println("FAIL: " + name); }
    }
    static void scenario(int failedAllocation) { scenario(failedAllocation,MetalMaterialPolicy.CONTRACT); }
    static int scenario(int failedAllocation,MetalMaterialPolicy mode) {
        var backend = mock(MetalRenderBackend.class);
        RenderBackendFactory.set(backend);
        var acquired = new ArrayList<Owned>();
        var count = new int[1]; var injectAt = new int[1];
        var failure = new IllegalStateException("target allocation fault " + failedAllocation);
        Runnable acquisition = () -> { if (++count[0] == injectAt[0]) throw failure; };
        when(backend.createTexture()).thenAnswer(call -> {
            acquisition.run(); var texture = mock(IGpuTexture.class, RETURNS_SELF);
            var dimensions = new int[2];
            when(texture.store(anyInt(), anyInt(), anyInt(), anyInt())).thenAnswer(store -> {
                dimensions[0] = store.getArgument(2); dimensions[1] = store.getArgument(3); return texture;
            });
            when(texture.getWidth()).thenAnswer(size -> dimensions[0]); when(texture.getHeight()).thenAnswer(size -> dimensions[1]);
            acquired.add(new Owned(texture, texture::free)); return texture;
        });
        when(backend.createBuffer(anyLong())).thenAnswer(call -> {
            acquisition.run(); var buffer = mock(IGpuBuffer.class, RETURNS_SELF);
            when(buffer.size()).thenReturn((long)call.getArgument(0));
            acquired.add(new Owned(buffer, buffer::free)); return buffer;
        });
        when(backend.beginRenderPass(any())).thenReturn(mock(RenderEncoder.class));
        when(backend.createFence()).thenAnswer(call -> { var fence = mock(IGpuFence.class); when(fence.signaled()).thenReturn(true); return fence; });
        when(backend.createPersistentBuffer(anyLong(), anyInt())).thenReturn(mock(IGpuPersistentBuffer.class, RETURNS_SELF));
        when(backend.createGraphicsPipeline(any())).thenAnswer(call -> {
            acquisition.run();var graphics=mock(IGpuPipeline.class);acquired.add(new Owned(graphics,graphics::close));return graphics;
        });
        var pipeline = mock(AbstractRenderPipeline.class);
        when(pipeline.vxMaterialMode()).thenReturn(mode!=MetalMaterialPolicy.SHADERS_OFF);
        when(pipeline.vxOpaqueMaterialMode()).thenReturn(mode.opaque());
        when(pipeline.materialPolicy()).thenReturn(mode);
        int allocationCount=0;
        pipeline.sectionRenderer = mock(MDICSectionRenderer.class);
        var viewport = mock(MDICViewport.class); viewport.width = 16; viewport.height = 16;
        try {
            var hiz = me.cortex.voxy.client.core.rendering.Viewport.class.getDeclaredField("hiZBuffer"); hiz.setAccessible(true);
            hiz.set(viewport, mock(HiZBuffer.class));
        } catch (Exception error) { throw new RuntimeException(error); }
        var frame = new MetalFrameRenderer(pipeline, mock(AsyncNodeManager.class), mock(NodeCleaner.class), mock(HierarchicalOcclusionTraverser.class));
        try (var bridges = mockStatic(IOSurfaceBridge.class);
             var iris = mockStatic(me.cortex.voxy.client.core.util.IrisUtil.class);
             var exports = mockConstruction(MetalDepthExport.class);
             var restores = mockConstruction(MetalDepthRestore.class)) {
            bridges.when(() -> IOSurfaceBridge.create(anyLong(), anyInt(), anyInt(), any())).thenAnswer(call -> {
                acquisition.run(); var bridge = mock(IOSurfaceBridge.class);
                when(bridge.width()).thenReturn((int)call.getArgument(1)); when(bridge.height()).thenReturn((int)call.getArgument(2));
                when(bridge.asGpuTexture()).thenReturn(mock(IGpuTexture.class));
                acquired.add(new Owned(bridge, bridge::close)); return bridge;
            });
            try {
                frame.render(viewport, 0);
                var originalBridge = frame.metalBridge();
                var old = List.copyOf(acquired); allocationCount = count[0];
                frame.render(viewport, 0);
                if (count[0] != allocationCount) throw new AssertionError("same-size frame reallocated targets");
                if (failedAllocation > allocationCount) throw new AssertionError("unexercised acquisition " + failedAllocation);
                injectAt[0] = count[0] + failedAllocation;
                viewport.width = 32; viewport.height = 32;
                if (failedAllocation != 0) {
                    try { frame.render(viewport, 0); throw new AssertionError("allocation fault not exercised"); }
                    catch (IllegalStateException error) { if (error != failure) throw error; }
                    for (var owner : old) verifyRelease(owner, 0);
                    if (frame.metalBridge() != originalBridge) throw new AssertionError("failed resize published partial targets");
                    for (var owner : acquired.subList(old.size(), acquired.size())) verifyRelease(owner, 1);
                } else {
                    injectAt[0] = 0;
                    frame.render(viewport, 0);
                    for (var owner : old) verifyRelease(owner, 1);
                }
            } finally { frame.close(); }
            for (var owner : acquired) verifyRelease(owner, 1);
            for (var helper : exports.constructed()) verify(helper).close();
            for (var helper : restores.constructed()) verify(helper).close();
        }
        return allocationCount;
    }
    private static void verifyRelease(Owned owner, int times) {
        if (owner.resource instanceof IOSurfaceBridge bridge) verify(bridge, org.mockito.Mockito.times(times)).close();
        else if (owner.resource instanceof IGpuBuffer buffer) verify(buffer, org.mockito.Mockito.times(times)).free();
        else if (owner.resource instanceof IGpuTexture texture) verify(texture, org.mockito.Mockito.times(times)).free();
        else if (owner.resource instanceof IGpuPipeline pipeline) verify(pipeline, org.mockito.Mockito.times(times)).close();
    }
    public static void main(String[] args) {
        net.minecraft.SharedConstants.tryDetectVersion(); net.minecraft.server.Bootstrap.bootStrap();
        me.cortex.voxy.common.Logger.SHUTUP = true;
        try (var context = new TestGlContext(); var minecraft = mockStatic(net.minecraft.client.Minecraft.class)) {
            minecraft.when(net.minecraft.client.Minecraft::getInstance).thenReturn(mock(net.minecraft.client.Minecraft.class));
            int plainCount=scenario(0,MetalMaterialPolicy.SHADERS_OFF);
            for(int stage=1;stage<=plainCount;stage++) {
                int allocation=stage;
                check("shaders-off target rollback "+stage,()->scenario(allocation,MetalMaterialPolicy.SHADERS_OFF));
            }
            check("successful target replacement retires the complete old set", () -> scenario(0));
            for (int stage = 1; stage <= 13; stage++) {
                int allocation = stage;
                check("failed resize allocation " + stage + " retains old set and rolls back new targets", () -> scenario(allocation));
            }
        }
        if (failures > 0) throw new AssertionError(failures + " target ownership regressions failed");
    }
}
