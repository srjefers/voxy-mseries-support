package me.cortex.voxy.client.core;

import it.unimi.dsi.fastutil.ints.IntOpenHashSet;
import me.cortex.voxy.client.core.gpu.*;
import me.cortex.voxy.client.core.interop.*;
import me.cortex.voxy.client.core.metal.MetalRenderBackend;
import me.cortex.voxy.client.core.rendering.hierachical.*;
import me.cortex.voxy.client.core.rendering.section.backend.mdic.*;
import me.cortex.voxy.client.core.rendering.util.*;
import me.cortex.voxy.common.util.MemoryBuffer;
import org.lwjgl.system.MemoryUtil;
import java.util.*;
import static org.mockito.Mockito.*;

/** Tests actual frame orchestration and clear uniforms, including repeated work and generation replacement. */
public final class VisibilityClockRegressionTest {
    public static void main(String[] args) throws Exception {
        net.minecraft.SharedConstants.tryDetectVersion(); net.minecraft.server.Bootstrap.bootStrap();
        me.cortex.voxy.common.Logger.SHUTUP = true;
        var host = new MemoryBuffer(1 << 22);
        var backend = mock(MetalRenderBackend.class);
        var persistent = mock(IGpuPersistentBuffer.class, RETURNS_SELF);
        when(persistent.addr()).thenReturn(host.address); when(persistent.size()).thenReturn(1L << 28);
        when(backend.createPersistentBuffer(anyLong(), anyInt())).thenReturn(persistent);
        when(backend.createBuffer(anyLong())).thenAnswer(call -> mock(IGpuBuffer.class, RETURNS_SELF));
        when(backend.createTexture()).thenReturn(mock(IGpuTexture.class, RETURNS_SELF));
        when(backend.createComputePipeline(any())).thenReturn(mock(IGpuPipeline.class));
        when(backend.createGraphicsPipeline(any())).thenReturn(mock(IGpuPipeline.class));
        when(backend.beginRenderPass(any())).thenReturn(mock(RenderEncoder.class));
        var fence = mock(IGpuFence.class); when(fence.signaled()).thenReturn(true); when(backend.createFence()).thenReturn(fence);
        var tags = new ArrayList<Integer>();
        when(backend.beginComputePass()).thenAnswer(call -> {
            var encoder = mock(ComputeEncoder.class);
            doAnswer(push -> { if ((int)push.getArgument(2) == 8) tags.add(MemoryUtil.memGetInt((long)push.getArgument(1) + 4)); return null; })
                    .when(encoder).setBytes(anyInt(), anyLong(), anyInt());
            return encoder;
        });
        RenderBackendFactory.set(backend);
        try (var context = new TestGlContext(); var minecraft = mockStatic(net.minecraft.client.Minecraft.class);
             var iris = mockStatic(me.cortex.voxy.client.core.util.IrisUtil.class); var bridges = mockStatic(IOSurfaceBridge.class)) {
            minecraft.when(net.minecraft.client.Minecraft::getInstance).thenReturn(mock(net.minecraft.client.Minecraft.class));
            bridges.when(() -> IOSurfaceBridge.create(anyLong(), anyInt(), anyInt(), any())).thenReturn(mock(IOSurfaceBridge.class));
            for (int generation = 0; generation < 2; generation++) {
                var nodes = mock(AsyncNodeManager.class);
                when(nodes.getGeometryCapacity()).thenReturn(1L << 30);
                var cleaner = new NodeCleaner(nodes);
                var policy = mock(AbstractRenderPipeline.class);
                when(policy.materialPolicy()).thenReturn(MetalMaterialPolicy.SHADERS_OFF);
                policy.sectionRenderer = mock(MDICSectionRenderer.class);
                doAnswer(call -> { cleaner.updateIds(new IntOpenHashSet(new int[]{0x80000001})); return null; }).when(nodes).tick(any(), any());
                var viewport = mock(MDICViewport.class); viewport.width = 16; viewport.height = 16; viewport.frameId = 1000;
                var hiz = me.cortex.voxy.client.core.rendering.Viewport.class.getDeclaredField("hiZBuffer"); hiz.setAccessible(true); hiz.set(viewport, mock(HiZBuffer.class));
                try (var frame = new MetalFrameRenderer(policy, nodes, cleaner, mock(HierarchicalOcclusionTraverser.class))) {
                    frame.render(viewport, 0); frame.render(viewport, 0);
                    viewport.frameId = 1001; frame.render(viewport, 0);
                } finally { cleaner.free(); UploadStream.INSTANCE.tick(); }
            }
            if (!tags.equals(List.of(1000,1000,1001,1000,1000,1001))) throw new AssertionError("cleaner clear tags differ from draw tags: " + tags);
            System.out.println("PASS: upload clear, repeated work, subsequent capture and replacement use the main-view visibility tag");
        } finally { host.free(); }
    }
}
