package me.cortex.voxy.client.core.rendering.hierachical;

import it.unimi.dsi.fastutil.ints.IntOpenHashSet;
import me.cortex.voxy.client.core.gpu.*;
import me.cortex.voxy.client.core.rendering.building.*;
import me.cortex.voxy.client.core.rendering.hierachical.*;
import me.cortex.voxy.client.core.rendering.section.geometry.BasicSectionGeometryData;
import me.cortex.voxy.common.util.MemoryBuffer;
import java.lang.reflect.*;
import java.util.concurrent.ConcurrentLinkedDeque;
import static org.mockito.Mockito.*;

public final class AsyncRegressionTest {
    private static int failures;
    private interface Case { void run() throws Exception; }
    private static void check(String name, Case test) {
        try { test.run(); System.out.println("PASS: " + name); }
        catch (Throwable error) { failures++; System.err.println("FAIL: " + name + ": " + error); }
    }
    private static Field field(String name) throws Exception {
        Field field = AsyncNodeManager.class.getDeclaredField(name); field.setAccessible(true); return field;
    }
    private static AsyncNodeManager manager() throws Exception {
        var data = mock(BasicSectionGeometryData.class);
        when(data.getGeometryCapacityBytes()).thenReturn(100_000_000L);
        when(data.getMaxSectionCount()).thenReturn(32);
        var manager = new AsyncNodeManager(32, data, mock(RenderGenerationService.class));
        var nodes = mock(NodeManager.class);
        when(nodes.getNodeUpdates()).thenReturn(new IntOpenHashSet());
        field("manager").set(manager, nodes);
        return manager;
    }
    public static void main(String[] args) throws Exception {
        var backend = mock(RenderBackend.class);
        when(backend.createComputePipeline(any())).thenReturn(mock(IGpuPipeline.class));
        RenderBackendFactory.set(backend);
        check("geometry batches stop after approximately 1 MiB without dropping queued meshes", () -> {
            var manager = manager();
            var nodes = (NodeManager)field("manager").get(manager);
            var processed = new java.util.ArrayList<BuiltSection>();
            doAnswer(call -> { processed.add(call.getArgument(0)); return null; }).when(nodes).processGeometryResult(any());
            for (int i = 0; i < 4; i++) {
                var mesh = new BuiltSection(i, (byte)0, 0, new MemoryBuffer(600_000), new int[8]);
                Method submit = AsyncNodeManager.class.getDeclaredMethod("submitGeometryResult", BuiltSection.class);
                submit.setAccessible(true); submit.invoke(manager, mesh);
            }
            Method run = AsyncNodeManager.class.getDeclaredMethod("run"); run.setAccessible(true);
            try {
                run.invoke(manager);
                if (processed.size() != 2) throw new AssertionError("processed " + processed.size() + " meshes instead of two");
                @SuppressWarnings("unchecked") var queue = (ConcurrentLinkedDeque<BuiltSection>)field("geometryUpdateQueue").get(manager);
                if (queue.size() != 2) throw new AssertionError("remaining meshes were lost");
            } finally { manager.stop(); processed.forEach(BuiltSection::free); }
        });
        check("async worker failures reach the render thread and shutdown still cleans up", () -> {
            var manager = manager();
            var nodes = (NodeManager)field("manager").get(manager);
            var failure = new AssertionError("test worker failure");
            doThrow(failure).when(nodes).processGeometryResult(any());
            Method submit = AsyncNodeManager.class.getDeclaredMethod("submitGeometryResult", BuiltSection.class);
            submit.setAccessible(true); submit.invoke(manager, BuiltSection.empty(123));
            manager.start();
            var thread = (Thread)field("thread").get(manager); thread.join(5000);
            try {
                if (thread.isAlive()) throw new AssertionError("worker did not exit");
                try { manager.tick(null, null); throw new AssertionError("worker failure was silently lost"); }
                catch (RuntimeException expected) { if (expected.getCause() != failure) throw expected; }
            } finally { manager.stop(); }
        });
        check("an empty node table does not dispatch cleaner work or readbacks", () -> {
            var buffer = mock(IGpuBuffer.class);
            when(buffer.zero()).thenReturn(buffer);
            when(backend.createBuffer(anyLong())).thenReturn(buffer);
            var manager = mock(AsyncNodeManager.class);
            when(manager.getGeometryCapacity()).thenReturn(1L);
            var cleaner = new NodeCleaner(manager);
            clearInvocations(backend);
            try { cleaner.tick(buffer); verify(backend, never()).beginComputePass(); }
            finally { cleaner.free(); }
        });
        if (failures > 0) throw new AssertionError(failures + " async regressions failed");
    }
}
