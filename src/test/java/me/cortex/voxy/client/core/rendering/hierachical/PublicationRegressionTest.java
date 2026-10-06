package me.cortex.voxy.client.core.rendering.hierachical;

import it.unimi.dsi.fastutil.ints.IntOpenHashSet;
import me.cortex.voxy.client.core.gpu.*;
import me.cortex.voxy.client.core.rendering.building.RenderGenerationService;
import me.cortex.voxy.client.core.rendering.section.geometry.BasicSectionGeometryData;
import me.cortex.voxy.client.core.rendering.util.UploadStream;
import me.cortex.voxy.common.util.MemoryBuffer;
import org.lwjgl.system.MemoryUtil;
import java.lang.reflect.*;
import java.util.*;
import static org.mockito.Mockito.*;

/** Exercises the render-thread publication interface with real native scratch and injected GPU failures. */
public final class PublicationRegressionTest {
    private static int failures;
    private static Field field(Class<?> type, String name) throws Exception {
        var field = type.getDeclaredField(name); field.setAccessible(true); return field;
    }
    private static Object payload(AsyncNodeManager manager) throws Exception {
        var acquire = AsyncNodeManager.class.getDeclaredMethod("getMakeResultObject"); acquire.setAccessible(true);
        Object payload = acquire.invoke(manager);
        Object upload = field(payload.getClass(), "geometryUpload").get(payload);
        var append = upload.getClass().getDeclaredMethod("upload", int.class, MemoryBuffer.class); append.setAccessible(true);
        var bytes = new MemoryBuffer(8);
        try { MemoryUtil.memPutLong(bytes.address, 42); append.invoke(upload, 0, bytes); }
        finally { bytes.free(); }
        var scatter = payload.getClass().getDeclaredMethod("getScatterWritePtr", int.class); scatter.setAccessible(true);
        MemoryUtil.memSet((long)scatter.invoke(payload, 0), 0, 16);
        ((IntOpenHashSet)field(payload.getClass(), "tlnDelta").get(payload)).add(0x80000003);
        ((IntOpenHashSet)field(payload.getClass(), "cleanerOperations").get(payload)).add(1);
        field(AsyncNodeManager.class, "results").set(manager, payload);
        return payload;
    }
    private static void check(String name, Runnable test) {
        try { test.run(); System.out.println("PASS: " + name); }
        catch (Throwable failure) { failures++; failure.printStackTrace(); System.err.println("FAIL: " + name); }
    }
    private static void scenario(int failAt, boolean checkOrder) {
        try {
            int before = MemoryBuffer.getCount();
            var order = new ArrayList<String>();
            var backend = RenderBackendFactory.get();
            var geometry = mock(BasicSectionGeometryData.class);
            when(geometry.getGeometryCapacityBytes()).thenReturn(100_000_000L);
            when(geometry.getMaxSectionCount()).thenReturn(32);
            var manager = new AsyncNodeManager(32, geometry, mock(RenderGenerationService.class));
            var cleaner = mock(NodeCleaner.class);
            var injected = new IllegalStateException("publication fault " + failAt);
            var count = new int[1];
            when(backend.beginComputePass()).thenAnswer(call -> {
                var encoder = mock(ComputeEncoder.class);
                int pass = ++count[0];
                doAnswer(draw -> {
                    order.add(pass == 1 ? "geometry" : "nodes");
                    if (failAt == pass) throw injected;
                    return null;
                }).when(encoder).dispatch(anyInt(), anyInt(), anyInt());
                return encoder;
            });
            doAnswer(call -> { order.add("cleaner"); if (failAt == 3) throw injected; return null; }).when(cleaner).updateIds(any());
            manager.setTLNAddRemoveCallbacks(id -> { order.add("ready"); if (failAt == 4) throw injected; }, id -> order.add("removed"));
            payload(manager);
            try {
                if (failAt == 0) {
                    manager.tick(mock(IGpuBuffer.class), cleaner);
                    if (checkOrder && !order.equals(List.of("geometry", "nodes", "cleaner", "ready"))) {
                        throw new AssertionError("readiness escaped before GPU publication: " + order);
                    }
                    manager.tick(null, null); // no duplicate publication
                } else {
                    try { manager.tick(mock(IGpuBuffer.class), cleaner); throw new AssertionError("fault did not escape"); }
                    catch (IllegalStateException failure) { if (failure != injected) throw failure; }
                    try { manager.tick(null, null); throw new AssertionError("partially applied publication was allowed to continue"); }
                    catch (RuntimeException failure) { if (failure.getCause() != injected) throw failure; }
                }
            } finally {
                manager.stop();
                UploadStream.INSTANCE.tick();
                if (MemoryBuffer.getCount() != before) throw new AssertionError("publication lost native scratch: " + (MemoryBuffer.getCount() - before) + " buffers");
            }
        } catch (RuntimeException | Error failure) { throw failure; }
        catch (Exception failure) { throw new RuntimeException(failure); }
    }
    private static void workerAssemblyFailure() {
        try {
            int before = MemoryBuffer.getCount();
            var geometry = mock(BasicSectionGeometryData.class);
            when(geometry.getGeometryCapacityBytes()).thenReturn(100_000_000L); when(geometry.getMaxSectionCount()).thenReturn(32);
            var manager = new AsyncNodeManager(32, geometry, mock(RenderGenerationService.class));
            var nodes = mock(NodeManager.class);
            when(nodes.getNodeUpdates()).thenReturn(new IntOpenHashSet(new int[]{1}));
            var fault = new IllegalStateException("node serialization fault");
            doThrow(fault).when(nodes).writeNode(anyInt(), anyLong());
            field(AsyncNodeManager.class, "manager").set(manager, nodes);
            manager.addTopLevel(0);
            var run = AsyncNodeManager.class.getDeclaredMethod("run"); run.setAccessible(true);
            try {
                try { run.invoke(manager); throw new AssertionError("worker serialization fault not exercised"); }
                catch (InvocationTargetException failure) { if (failure.getCause() != fault) throw failure; }
            } finally { manager.stop(); }
            if (MemoryBuffer.getCount() != before) throw new AssertionError("worker lost the in-flight publication scratch: " + (MemoryBuffer.getCount() - before));
        } catch (RuntimeException | Error failure) { throw failure; }
        catch (Exception failure) { throw new RuntimeException(failure); }
    }
    public static void main(String[] args) {
        me.cortex.voxy.common.Logger.SHUTUP = true;
        var host = new MemoryBuffer(1 << 22);
        var backend = mock(RenderBackend.class);
        var persistent = mock(IGpuPersistentBuffer.class, RETURNS_SELF);
        when(persistent.addr()).thenReturn(host.address); when(persistent.size()).thenReturn(1L << 28);
        when(backend.createPersistentBuffer(anyLong(), anyInt())).thenReturn(persistent);
        when(backend.createComputePipeline(any())).thenReturn(mock(IGpuPipeline.class));
        var fence = mock(IGpuFence.class); when(fence.signaled()).thenReturn(true); when(backend.createFence()).thenReturn(fence);
        RenderBackendFactory.set(backend);
        try {
            check("worker assembly failure retains scratch ownership until stop", PublicationRegressionTest::workerAssemblyFailure);
            check("geometry and node updates precede readiness publication", () -> scenario(0, true));
            for (int stage = 1; stage <= 4; stage++) {
                int failureStage = stage;
                check("failed publication stage " + stage + " reclaims scratch and prevents reuse", () -> scenario(failureStage, false));
            }
        } finally { host.free(); }
        if (failures != 0) throw new AssertionError(failures + " publication regressions failed");
    }
}
