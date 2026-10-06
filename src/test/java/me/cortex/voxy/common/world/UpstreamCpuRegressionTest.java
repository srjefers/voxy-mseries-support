package me.cortex.voxy.common.world;

import me.cortex.voxy.client.core.rendering.hierachical.NodeStore;
import me.cortex.voxy.common.world.other.Mapper;
import me.cortex.voxy.common.world.other.Mipper;

public final class UpstreamCpuRegressionTest {
    private static int failures;
    private static void check(String name, Runnable test) {
        try { test.run(); System.out.println("PASS: " + name); }
        catch (Throwable error) { failures++; System.err.println("FAIL: " + name + ": " + error); }
    }
    private static void expect(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }

    public static void main(String[] args) {
        check("all 256 uniform air-light combinations survive mip packing", () -> {
            for (int light = 0; light < 256; light++) {
                long air = Mapper.airWithLight(light);
                long result = Mipper.mip(air, air, air, air, air, air, air, air, null);
                expect(Mapper.getLightId(result) == light, "light " + light + " became " + Mapper.getLightId(result));
                expect(Mapper.isAir(result), "mip introduced a block");
            }
        });
        check("asymmetric air mip averages independent light nibbles", () -> {
            long[] voxels = new long[8];
            int blockSum = 0, skySum = 0;
            for (int i = 0; i < 8; i++) {
                int block = i * 2, sky = 15 - i;
                blockSum += block; skySum += sky;
                voxels[i] = Mapper.airWithLight((block << 4) | sky);
            }
            long result = Mipper.mip(voxels[0], voxels[1], voxels[2], voxels[3], voxels[4], voxels[5], voxels[6], voxels[7], null);
            expect(Mapper.getLightId(result) == ((blockSum / 8) << 4 | (skySum + 7) / 8), "nibbles mixed or overflowed");
        });
        check("19-bit requests preserve independent leaf flags", () -> {
            NodeStore nodes = new NodeStore(128);
            int node = nodes.allocate();
            for (int request : new int[]{0, 65535, 65536, 131071, (1 << 19) - 1}) {
                nodes.setAllChildrenAreLeaf(node, true);
                nodes.setNodeRequest(node, request);
                expect(nodes.getNodeRequest(node) == request, "request truncated: " + request);
                expect(nodes.getAllChildrenAreLeaf(node), "request cleared leaf flag");
                nodes.setAllChildrenAreLeaf(node, false);
                expect(nodes.getNodeRequest(node) == request, "leaf flag changed request");
            }
            nodes.free(node);
        });
        check("invalid requests rejected without mutating the node", () -> {
            NodeStore nodes = new NodeStore(128);
            int node = nodes.allocate();
            nodes.setNodeRequest(node, 7);
            for (int request : new int[]{-1, 1 << 19, Integer.MAX_VALUE}) {
                boolean rejected = false;
                try { nodes.setNodeRequest(node, request); } catch (IllegalStateException expected) { rejected = true; }
                expect(rejected, "accepted invalid request: " + request);
                expect(nodes.getNodeRequest(node) == 7, "rejected request modified state");
            }
        });
        check("node allocation reuses released slots with sentinel reset", () -> {
            NodeStore nodes = new NodeStore(128);
            int first = nodes.allocate();
            nodes.setNodeRequest(first, 42);
            nodes.setAllChildrenAreLeaf(first, true);
            nodes.free(first);
            int reused = nodes.allocate();
            expect(first == reused, "released slot not reused");
            expect(nodes.getNodeRequest(reused) == NodeStore.REQUEST_ID_MSK, "stale request");
            expect(!nodes.getAllChildrenAreLeaf(reused), "stale leaf flag");
            expect(nodes.getNodeGeometry(reused) == -1, "stale geometry");
        });
        if (failures != 0) throw new AssertionError(failures + " upstream CPU regressions failed");
    }
}
