package me.cortex.voxy.client.core.rendering;

import me.cortex.voxy.client.core.rendering.building.BuiltSection;
import me.cortex.voxy.client.core.rendering.section.geometry.BasicAsyncGeometryManager;
import me.cortex.voxy.client.core.util.ExpandingObjectAllocationList;
import me.cortex.voxy.common.util.MemoryBuffer;
import org.lwjgl.system.MemoryUtil;

public final class AllocationRegressionTest {
    private static int failures;
    private interface Case { void run() throws Exception; }
    private static void check(String name, Case test) {
        try { test.run(); System.out.println("PASS: " + name); }
        catch (Throwable error) { failures++; System.err.println("FAIL: " + name + ": " + error); }
    }
    private static BuiltSection mesh(long position) {
        return new BuiltSection(position, (byte)0, 0, new MemoryBuffer(8).zero(), new int[8]);
    }
    public static void main(String[] args) {
        check("small meshes allocate 128-quad granules and reuse freed space", () -> {
            var manager = new BasicAsyncGeometryManager(8, 2048);
            var firstMesh = mesh(1);
            int first = manager.uploadSection(firstMesh);
            if (manager.getGeometryUsedBytes() != 1024) throw new AssertionError("tiny mesh wastes more than one 128-quad granule");
            int second = manager.uploadSection(mesh(2));
            if (manager.getGeometryUsedBytes() != 2048) throw new AssertionError("wrong total allocation");
            manager.removeSection(first);
            int reused = manager.uploadSection(mesh(3));
            if (first != reused) throw new AssertionError("section ID was not reused");
            long ptr = MemoryUtil.nmemAlloc(32);
            try {
                manager.writeMetadata(reused, ptr);
                if (MemoryUtil.memGetInt(ptr + 12) != 0) throw new AssertionError("geometry range was not reused");
            } finally { MemoryUtil.nmemFree(ptr); }
            manager.removeSection(second); manager.removeSection(reused);
            if (manager.getGeometryUsedBytes() != 0 || manager.getSectionCount() != 0) throw new AssertionError("allocations leaked");
        });
        check("request allocator rejects exhaustion and reuses released IDs", () -> {
            var ctor = ExpandingObjectAllocationList.class.getConstructor(it.unimi.dsi.fastutil.ints.Int2ObjectFunction.class, int.class);
            @SuppressWarnings("unchecked") var list = (ExpandingObjectAllocationList<String>)ctor.newInstance(
                    (it.unimi.dsi.fastutil.ints.Int2ObjectFunction<String[]>)String[]::new, 2);
            int first = list.put("first"), second = list.put("second");
            try { list.put("overflow"); throw new AssertionError("allocator accepted a reserved/exhausted request ID"); }
            catch (IllegalStateException expected) { }
            if (list.count() != 2 || !list.get(first).equals("first") || !list.get(second).equals("second")) throw new AssertionError("exhaustion corrupted entries");
            list.release(first);
            if (list.put("replacement") != first || !list.get(second).equals("second")) throw new AssertionError("allocator did not safely reuse ID");
        });
        if (failures > 0) throw new AssertionError(failures + " allocation regressions failed");
    }
}
