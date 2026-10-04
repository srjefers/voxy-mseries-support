package me.cortex.voxy.client.core.util;

/**
 * VOXY_FRAME_TIMING=1 — per-stage frame cost probe for the Metal path.
 *
 * The 120fps work needs a ranking of the three synchronous GPU waits
 * (HOT request readback / post-buildDrawCalls flush / end-frame bridge
 * handoff, all commit+waitUntilCompleted through MetalRenderBackend.submit)
 * against the ~2-JNI-calls-per-draw indirect loop in MetalRenderEncoder —
 * no per-stage timing existed, so every prior fps sample conflated them.
 *
 * Accumulators are render-thread only (no atomics needed: every producer
 * and the [Metal-TIMING] reporter run on the render thread). ENABLED is a
 * static final read once at class load: when the env var is unset the JIT
 * folds every call-site guard to a no-op, so the probe is provably free —
 * and the GL backend never accumulates because all producer sites are
 * Metal-only paths.
 */
public final class FrameTiming {
    public static final boolean ENABLED = "1".equals(System.getenv("VOXY_FRAME_TIMING"));

    /** Wait #1: HOT request-queue direct-read submit (HierarchicalOcclusionTraverser). */
    public static long hotReadbackNs;
    /** Wait #2: post-buildDrawCalls flush for the CPU baseInstance reads (AbstractRenderPipeline). */
    public static long drawCallFlushNs;
    /** Wait #3: end-of-frame bridge handoff submit (AbstractRenderPipeline). */
    public static long bridgeFlushNs;
    /** The per-draw indirect JNI loop (MetalRenderEncoder.drawIndexedIndirect). */
    public static long jniDrawLoopNs;
    /** Draws issued through that loop (2 JNI crossings each). */
    public static long jniDrawCount;
    /** The MC-lightmap glGetTexImage readback + mirror upload (LightMapHelper.syncFromMc). */
    public static long lightmapSyncNs;
    /** Frames on which that readback actually ran (the rest were throttled as unchanged). */
    public static long lightmapSyncCount;
    /** IOSurface GL re-specifications actually issued (CGLTexImageIOSurface2D, IOSurfaceBridgeCompositor.resync). */
    public static long resyncCount;
    /** Re-specifications skipped by the once-per-bridge-per-frame gate (VOXY_BRIDGE_RESYNC). */
    public static long resyncSkipped;
    /** Wall time inside the issued re-specifications (incl. the 2 glGetInteger + rebinds around each). */
    public static long resyncNs;

    private FrameTiming() {}

    public static void reset() {
        hotReadbackNs = 0;
        drawCallFlushNs = 0;
        bridgeFlushNs = 0;
        jniDrawLoopNs = 0;
        jniDrawCount = 0;
        lightmapSyncNs = 0;
        lightmapSyncCount = 0;
        resyncCount = 0;
        resyncSkipped = 0;
        resyncNs = 0;
    }
}
