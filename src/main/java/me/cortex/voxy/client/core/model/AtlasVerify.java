package me.cortex.voxy.client.core.model;

import me.cortex.voxy.client.core.gpu.IGpuTexture;
import me.cortex.voxy.client.core.metal.MetalTexture;
import me.cortex.voxy.common.Logger;
import org.lwjgl.system.MemoryUtil;

import java.util.Locale;
import java.util.zip.CRC32;

/**
 * Rejoin-gray forensics probe (VOXY_ATLAS_VERIFY=1, or =N for the sampling
 * stride; default stride 16). Metal-only, render-thread-only, no GL calls.
 *
 * Adjudicates WHERE the model-atlas pixels are lost on world rejoin: the
 * failing run's counters prove ~1050 bakes with real pixels were written via
 * mtlTextureReplaceRegion into the live session-2 atlas, yet the draw samples
 * pale recycled memory. Three distinguishable outcomes:
 *
 *  - WRITE MISMATCH (readback right after the write differs from the bytes
 *    just uploaded): the MTLTexture object is dead/aliased at write time —
 *    native over-release / pointer-reuse use-after-free at world teardown.
 *  - WRITE MATCH but SENTINEL DRIFT (a cell verified earlier changes without
 *    a new upload): a stale writer is scribbling the atlas after the fact.
 *  - Always MATCH: the atlas content is good; the defect is on the sampling
 *    side (binding / mirror / pack), not the data path.
 *
 * Both checks CRC mip 0 of one 48x32 model cell (6 KB) — negligible cost at
 * stride 16, and the readback is a unified-memory memcpy (no GL, no SIGBUS
 * class). Water-animated cells are excluded from sentinel duty because the
 * WaterAnimator legitimately rewrites them every tick.
 */
public final class AtlasVerify {
    private static final int STRIDE = parseStride();
    private static final int CELL_W = ModelFactory.MODEL_TEXTURE_SIZE * 3;
    private static final int CELL_H = ModelFactory.MODEL_TEXTURE_SIZE * 2;
    private static final int CELL_BYTES = CELL_W * CELL_H * 4;
    private static final int SENTINEL_PERIOD = 600;

    private static long scratch;
    private static int uploadCounter;
    private static int tickCounter;
    private static int mismatches;

    // Sentinel: one known-verified cell re-checked periodically for drift.
    private static int sentinelModelId = -1;
    private static long sentinelCrc;
    private static long sentinelHandle;

    private static int parseStride() {
        String v = System.getenv("VOXY_ATLAS_VERIFY");
        if (v == null || v.isBlank() || "0".equals(v.trim())) return 0;
        try {
            int n = Integer.parseInt(v.trim());
            return n <= 1 ? 16 : n; // "1" = enabled with the default stride
        } catch (NumberFormatException e) {
            return 16;
        }
    }

    public static boolean enabled() {
        return STRIDE > 0;
    }

    /**
     * Called right after a bake's atlas writes (all mips) landed, while the
     * upload's CPU-side pixel buffer is still alive. mip0Addr points at the
     * face-major mip-0 pixels exactly as handed to uploadSubImage2D(0, ...).
     */
    public static void onUpload(IGpuTexture atlas, int modelId, long mip0Addr, boolean waterAnimated) {
        if (STRIDE == 0 || !(atlas instanceof MetalTexture tex)) return;
        if ((uploadCounter++ % STRIDE) != 0) return;

        int x = (modelId & 0xFF) * CELL_W;
        int y = ((modelId >> 8) & 0xFF) * CELL_H;
        if (scratch == 0) scratch = MemoryUtil.nmemAllocChecked(CELL_BYTES);
        tex.getBytes(0, x, y, CELL_W, CELL_H, scratch);

        long expected = crc(mip0Addr);
        long actual = crc(scratch);
        if (expected == actual) {
            if (!waterAnimated) {
                sentinelModelId = modelId;
                sentinelCrc = expected;
                sentinelHandle = tex.getHandle();
            }
            if ((uploadCounter - 1) % (STRIDE * 8) == 0) { // keep the OK noise low
                Logger.info(String.format(Locale.ROOT,
                        "[Metal-ATLASVERIFY] write MATCH model=%d cell=(%d,%d) crc=%08x handle=0x%x",
                        modelId, x, y, expected, tex.getHandle()));
            }
        } else {
            mismatches++;
            Logger.warn(String.format(Locale.ROOT,
                    "[Metal-ATLASVERIFY] write MISMATCH #%d model=%d cell=(%d,%d) expectedCrc=%08x actualCrc=%08x"
                    + " handle=0x%x readBytes=%s expectedBytes=%s"
                    + " => atlas texture dead/aliased at write time (native lifetime bug)",
                    mismatches, modelId, x, y, expected, actual, tex.getHandle(),
                    firstBytes(scratch), firstBytes(mip0Addr)));
        }
    }

    /**
     * Called once per render-thread tick (even with no uploads pending).
     * Re-verifies the sentinel cell every ~600 ticks to catch post-hoc
     * scribbling by a stale writer.
     */
    public static void tick(IGpuTexture atlas) {
        if (STRIDE == 0 || sentinelModelId < 0 || !(atlas instanceof MetalTexture tex)) return;
        if ((tickCounter++ % SENTINEL_PERIOD) != 0) return;
        if (tex.getHandle() != sentinelHandle) {
            // Store was recreated since the sentinel was adopted — drop it;
            // the next verified upload re-adopts on the new texture.
            sentinelModelId = -1;
            return;
        }
        int x = (sentinelModelId & 0xFF) * CELL_W;
        int y = ((sentinelModelId >> 8) & 0xFF) * CELL_H;
        if (scratch == 0) scratch = MemoryUtil.nmemAllocChecked(CELL_BYTES);
        tex.getBytes(0, x, y, CELL_W, CELL_H, scratch);
        long actual = crc(scratch);
        if (actual != sentinelCrc) {
            Logger.warn(String.format(Locale.ROOT,
                    "[Metal-ATLASVERIFY] SENTINEL DRIFT model=%d cell=(%d,%d) was=%08x now=%08x handle=0x%x bytes=%s"
                    + " => a stale writer scribbled the atlas after the verified write",
                    sentinelModelId, x, y, sentinelCrc, actual, tex.getHandle(), firstBytes(scratch)));
            // Re-arm on the drifted value so repeated drift keeps logging.
            sentinelCrc = actual;
        }
    }

    private static long crc(long addr) {
        CRC32 c = new CRC32();
        c.update(MemoryUtil.memByteBuffer(addr, CELL_BYTES));
        return c.getValue();
    }

    private static String firstBytes(long addr) {
        StringBuilder sb = new StringBuilder(35);
        for (int i = 0; i < 16; i++) {
            sb.append(String.format(Locale.ROOT, "%02x", MemoryUtil.memGetByte(addr + i) & 0xFF));
        }
        return sb.toString();
    }

    private AtlasVerify() {}
}
