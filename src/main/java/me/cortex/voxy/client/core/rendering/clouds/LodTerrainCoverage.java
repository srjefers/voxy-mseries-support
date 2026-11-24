package me.cortex.voxy.client.core.rendering.clouds;

import me.cortex.voxy.common.world.WorldEngine;
import me.cortex.voxy.common.world.WorldSection;
import me.cortex.voxy.common.world.other.Mapper;

import java.util.Arrays;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Where the LOD terrain is, in 16-block columns, so the LOD cloud layer draws clouds
 * over LOD terrain only and not over ground that was never generated or explored.
 *
 * <p>A 512-block LOD column with any data is not precise enough: a region the
 * player only crossed the edge of has a few chunks of terrain and void everywhere
 * else. So the source is the voxels themselves: every top-level section (level 4,
 * 512 blocks, 32x32x32 voxels of 16 blocks) passes through the mesher, which hands
 * its voxels to {@link #onTopLevelSection}; a 16-block column counts as terrain when
 * any voxel in it is not air (ungenerated ground is air).
 *
 * <p>Sections are kept when they leave the LOD ring: when they come back the node
 * manager may reuse cached geometry without meshing them again, and the data is
 * still right (one instance per render system, so per world and session). 128 bytes
 * per section.
 *
 * <p>Threads: the mesher's service threads write, the render thread reads
 * ({@link #fill}). {@link #version} changes whenever the map does.
 */
public final class LodTerrainCoverage {
    /** 16-block cells per side of a top-level section. */
    public static final int CELLS = 32;
    public static final int CELL_BLOCKS = 16;

    /** Top-level section position -> 32 rows (z) of 32 bits (x): 16-block columns with terrain. */
    private final ConcurrentHashMap<Long, int[]> sections = new ConcurrentHashMap<>();
    private final AtomicInteger version = new AtomicInteger();

    /** Mesher thread: the voxels of the top-level section at {@code pos}, about to be meshed. */
    public void onTopLevelSection(long pos, WorldSection section) {
        if (WorldEngine.getLevel(pos) != WorldEngine.MAX_LOD_LAYER) return;
        long[] data = section._unsafeGetRawDataArray();
        int[] rows = new int[CELLS];
        boolean any = false;
        for (int y = 0; y < CELLS; y++) {
            for (int z = 0; z < CELLS; z++) {
                int base = WorldSection.getIndex(0, y, z);
                int row = rows[z];
                for (int x = 0; x < CELLS; x++) {
                    if (!Mapper.isAir(data[base + x])) {
                        row |= 1 << x;
                    }
                }
                if (row != 0) {
                    rows[z] = row;
                    any = true;
                }
            }
        }
        int[] previous = any ? this.sections.put(pos, rows) : this.sections.remove(pos);
        if (previous == null ? any : !Arrays.equals(previous, rows)) {
            this.version.incrementAndGet();
        }
    }

    public int version() {
        return this.version.get();
    }

    public int sectionCount() {
        return this.sections.size();
    }

    /**
     * Render thread: lay the 16-block columns out in a {@code size} x {@code size} grid of
     * 512-block columns centred on column ({@code centreX}, {@code centreZ}). Column
     * (gx, gz) of the grid owns words {@code (gx + gz * size) * 32 .. +31}, one per row
     * of 16-block cells (z), one bit per cell (x). Sections at every height of a column
     * are merged. {@code size} is odd.
     */
    public void fill(int[] words, int centreX, int centreZ, int size) {
        Arrays.fill(words, 0);
        int half = size / 2;
        for (var e : this.sections.entrySet()) {
            long pos = e.getKey();
            int gx = WorldEngine.getX(pos) - centreX + half;
            int gz = WorldEngine.getZ(pos) - centreZ + half;
            if (gx < 0 || gz < 0 || gx >= size || gz >= size) continue;
            int base = (gx + gz * size) * CELLS;
            int[] rows = e.getValue();
            for (int z = 0; z < CELLS; z++) {
                words[base + z] |= rows[z];
            }
        }
    }
}
