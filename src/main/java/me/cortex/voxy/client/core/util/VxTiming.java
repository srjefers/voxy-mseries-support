package me.cortex.voxy.client.core.util;

import it.unimi.dsi.fastutil.ints.IntArrayFIFOQueue;
import me.cortex.voxy.common.Logger;

import java.util.ArrayDeque;

import static org.lwjgl.opengl.GL11C.GL_TRUE;
import static org.lwjgl.opengl.GL15C.GL_CURRENT_QUERY;
import static org.lwjgl.opengl.GL15C.GL_QUERY_RESULT;
import static org.lwjgl.opengl.GL15C.GL_QUERY_RESULT_AVAILABLE;
import static org.lwjgl.opengl.GL15C.GL_SAMPLES_PASSED;
import static org.lwjgl.opengl.GL15C.glBeginQuery;
import static org.lwjgl.opengl.GL15C.glEndQuery;
import static org.lwjgl.opengl.GL15C.glGenQueries;
import static org.lwjgl.opengl.GL15C.glGetQueryObjecti;
import static org.lwjgl.opengl.GL15C.glGetQueryi;
import static org.lwjgl.opengl.GL33C.GL_TIME_ELAPSED;
import static org.lwjgl.opengl.GL33C.glGetQueryObjectui64;

/**
 * VOXY_FRAME_TIMING=1 companion probe for the GL SIDE of the Metal frame:
 * the vx-contract fullscreen passes that run at the head of Sodium's SOLID
 * pass (VxIrisSideChannel depth decodes, MetalVxResolvePass opaque/trans
 * resolves, VxContractInjector inject, the IOSurface→GL rect-texture
 * acquires, the compositor blit) plus the lightmap mirror readback.
 *
 * Why: {@link FrameTiming} ranks the three Metal-side CPU waits, but every
 * GL pass above was unmeasured — GL calls are asynchronous so a nanoTime
 * bracket only shows driver submission cost, and the pack-shaded opaque
 * resolve (5481ed12) runs BSL's whole deferred LOD shading in a fullscreen
 * GL draw per frame. Each span therefore gets BOTH a CPU nanoTime bracket
 * and a GL_TIME_ELAPSED timer query (ARB_timer_query, core GL 3.3, present
 * on Apple GL 4.1) whose result is collected asynchronously frames later
 * (GL_QUERY_RESULT_AVAILABLE first — never a pipeline stall).
 *
 * Nesting: GL_TIME_ELAPSED queries cannot nest (inject wraps the
 * side-channel decodes; the material resolves wrap runOne), so the probe
 * runs ONE live query at a time and switches it at every span edge. Each
 * segment carries the bitmask of the spans open while it ran; on collection
 * its elapsed time is credited to every span in the mask, so an outer span's
 * GPU time correctly includes its children.
 *
 * Coverage: the opaque LOD draw (runOne opaque, or the inject colour draw on
 * the trans-only path) discards every non-LOD pixel, so a GL_SAMPLES_PASSED
 * occlusion query around it counts exactly the LOD-covered framebuffer
 * pixels — the "how much of the screen is LOD" fraction the fullscreen
 * passes' cost scales with.
 *
 * Cost when off: {@link #ENABLED} is a static final read once; every call
 * site guards on it so the JIT folds the probe to nothing and no GL query
 * object is ever created. All producer sites are Metal-only classes/paths;
 * the GL backend never reaches them. Render-thread only (no atomics).
 *
 * Kill switches: VOXY_VX_TIMING=0 keeps VOXY_FRAME_TIMING=1 exactly as
 * before this probe (no queries, no [Metal-VXTIMING] line);
 * VOXY_VX_TIMING_GPU=0 keeps the CPU brackets but issues no GL queries.
 */
public final class VxTiming {
    public static final boolean ENABLED = FrameTiming.ENABLED && !"0".equals(System.getenv("VOXY_VX_TIMING"));
    private static final boolean GPU_WANTED = !"0".equals(System.getenv("VOXY_VX_TIMING_GPU"));

    // Span ids (bit positions in the segment mask — keep < 32).
    public static final int LIGHTMAP = 0;          // LightMapHelper.syncFromMc (glGetTexImage 16x16 + Metal upload)
    public static final int ACQUIRE = 1;           // IOSurfaceBridgeCompositor.acquire*RectTex (CGLTexImageIOSurface2D resync)
    public static final int SC_OPAQUE = 2;         // VxIrisSideChannel.resolve   -> vxDepthTexOpaque
    public static final int SC_TRANS = 3;          // VxIrisSideChannel.resolveTrans -> vxDepthTexTrans
    public static final int SC_ABYSS = 4;          // VxIrisSideChannel.abyssDepth
    public static final int INJECT = 5;            // VxContractInjector.inject (total, incl. children)
    public static final int INJECT_COLOUR = 6;     //   inject colour draw into the pack's opaque vx targets
    public static final int INJECT_TRANS = 7;      //   inject translucent passthrough / spike
    public static final int RESOLVE = 8;           // MetalVxResolvePass.resolve (total, incl. children)
    public static final int RESOLVE_OPAQUE = 9;    //   runOne(voxy_opaque)
    public static final int RESOLVE_TRANS = 10;    //   runOne(voxy_translucent)
    public static final int RESOLVE_TRANS_ONLY = 11; // MetalVxResolvePass.resolveTranslucentOnly (total)
    public static final int COMPOSITE = 12;        // IOSurfaceBridgeCompositor.composite / compositeIrisGbuffer

    private static final String[] NAMES = {
            "lightmap", "acquire", "scOpaque", "scTrans", "scAbyss",
            "inject", "injectColour", "injectTrans",
            "resolve", "resolveOpaque", "resolveTrans", "resolveTransOnly",
            "composite"
    };
    private static final int PASSES = NAMES.length;

    // ---- per-frame CPU accumulators (folded into the window at beginFrame) ----
    private static final long[] cpuFrameNs = new long[PASSES];
    private static final int[] callsFrame = new int[PASSES];
    private static boolean frameOpen;

    // ---- window accumulators (reset by report) ----
    private static final long[] cpuWinNs = new long[PASSES];
    private static final long[] cpuWinMaxNs = new long[PASSES];
    private static final long[] callsWin = new long[PASSES];
    private static final long[] gpuWinNs = new long[PASSES];
    private static final long[] gpuWinMaxNs = new long[PASSES];
    private static int cpuFrames;
    private static int gpuFrames;
    private static int droppedFrames;
    private static long covSamples;
    private static long covPixels;
    private static int covFrames;

    // ---- span stack ----
    private static final int MAX_DEPTH = 8;
    private static final int[] stackPass = new int[MAX_DEPTH];
    private static final long[] stackStart = new long[MAX_DEPTH];
    private static int depth;
    private static int stackMask;

    // ---- GPU query machinery ----
    private static final int MAX_SEGMENTS = 96;      // per frame; ~2 per span edge
    private static final int MAX_INFLIGHT = 8;       // frames awaiting results before we drop the oldest
    private static final IntArrayFIFOQueue POOL = new IntArrayFIFOQueue();
    private static final ArrayDeque<Frame> INFLIGHT = new ArrayDeque<>();
    private static final ArrayDeque<Frame> FREE = new ArrayDeque<>();
    private static Frame cur;
    private static int curQuery;
    private static int curMask;
    private static int covQueryLive;
    private static int gpuState;                     // 0 = unchecked, 1 = supported, -1 = unsupported
    private static boolean gpuActive;                // GPU queries armed for the current frame
    private static boolean loggedOn;
    private static boolean loggedForeign;
    private static final long[] scratchGpu = new long[PASSES];

    private static final class Frame {
        final int[] queries = new int[MAX_SEGMENTS];
        final int[] masks = new int[MAX_SEGMENTS];
        int n;
        int covQuery;
        long covPixels;
        int lastIssued;
    }

    private VxTiming() {}

    /**
     * Frame boundary. Called once per Metal frame from runPipelineMetal (before
     * the lightmap sync and before the SOLID-hook GL passes). Folds the previous
     * frame's CPU spans into the window, collects any GPU results that became
     * available (non-blocking), and arms the query ring for this frame.
     */
    public static void beginFrame() {
        if (!ENABLED) return;
        if (frameOpen) {
            closeFrame();
        }
        frameOpen = true;
        collectReady();
        gpuActive = false;
        if (!GPU_WANTED) {
            logOnce();
            return;
        }
        if (gpuState == 0) {
            try {
                var caps = org.lwjgl.opengl.GL.getCapabilities();
                gpuState = (caps.OpenGL33 || caps.GL_ARB_timer_query) ? 1 : -1;
            } catch (Throwable t) {
                gpuState = -1;
            }
        }
        logOnce();
        if (gpuState != 1) return;
        // A GL_TIME_ELAPSED query owned by someone else (an Iris/Sodium
        // profiler) would make our glBeginQuery an INVALID_OPERATION and
        // corrupt their result — skip the GPU half for this frame instead.
        if (glGetQueryi(GL_TIME_ELAPSED, GL_CURRENT_QUERY) != 0) {
            if (!loggedForeign) {
                loggedForeign = true;
                Logger.warn("[Metal-VXTIMING] a foreign GL_TIME_ELAPSED query is live at the frame head — GPU timing skipped on such frames");
            }
            return;
        }
        cur = FREE.isEmpty() ? new Frame() : FREE.pop();
        cur.n = 0;
        cur.covQuery = 0;
        cur.covPixels = 0;
        cur.lastIssued = 0;
        gpuActive = true;
    }

    /** Open a span. Nested spans are fine (see class doc). */
    public static void begin(int pass) {
        if (!ENABLED) return;
        if (!frameOpen || depth >= MAX_DEPTH) return;
        stackPass[depth] = pass;
        stackStart[depth] = System.nanoTime();
        depth++;
        stackMask |= 1 << pass;
        callsFrame[pass]++;
        if (gpuActive) switchSegment();
    }

    /** Close the innermost open span with this id (defensive against unbalanced calls). */
    public static void end(int pass) {
        if (!ENABLED) return;
        if (!frameOpen || depth == 0) return;
        long now = System.nanoTime();
        int i = depth - 1;
        while (i >= 0 && stackPass[i] != pass) i--;
        if (i < 0) return;
        // Pop everything above it too (a span left open by an exception).
        for (int j = depth - 1; j >= i; j--) {
            cpuFrameNs[stackPass[j]] += now - stackStart[j];
        }
        depth = i;
        stackMask = 0;
        for (int j = 0; j < depth; j++) stackMask |= 1 << stackPass[j];
        if (gpuActive) switchSegment();
    }

    /**
     * Arm the per-frame LOD coverage occlusion query around a draw whose
     * fragment shader discards every non-LOD pixel. First caller per frame
     * wins; {@code pixels} is the target's width*height.
     */
    public static void beginCoverage(long pixels) {
        if (!ENABLED) return;
        if (!gpuActive || cur == null || cur.covQuery != 0 || covQueryLive != 0 || pixels <= 0) return;
        if (glGetQueryi(GL_SAMPLES_PASSED, GL_CURRENT_QUERY) != 0) return;
        int q = takeQuery();
        glBeginQuery(GL_SAMPLES_PASSED, q);
        covQueryLive = q;
        cur.covQuery = q;
        cur.covPixels = pixels;
        cur.lastIssued = q;
    }

    public static void endCoverage() {
        if (!ENABLED) return;
        if (covQueryLive == 0) return;
        glEndQuery(GL_SAMPLES_PASSED);
        covQueryLive = 0;
    }

    /**
     * Emit the [Metal-VXTIMING] window line next to [Metal-TIMING] and reset
     * the window. Silent when no GL-side pass ran (GL backend, no pack).
     */
    public static void report(int metalFrame) {
        if (!ENABLED) return;
        if (cpuFrames == 0 && gpuFrames == 0) return;
        StringBuilder sb = new StringBuilder(512);
        sb.append(String.format(java.util.Locale.ROOT,
                "[Metal-VXTIMING f=%d] GL-side vx passes, per-frame avg/max ms over %d frames (gpu | cpu):",
                metalFrame, cpuFrames));
        boolean any = false;
        for (int p = 0; p < PASSES; p++) {
            if (callsWin[p] == 0) continue;
            any = true;
            sb.append(' ').append(NAMES[p]).append('=');
            if (gpuFrames > 0) {
                sb.append(String.format(java.util.Locale.ROOT, "%.2f/%.2f",
                        gpuWinNs[p] / (double) gpuFrames / 1e6, gpuWinMaxNs[p] / 1e6));
            } else {
                sb.append("n/a");
            }
            sb.append('|').append(String.format(java.util.Locale.ROOT, "%.2f/%.2f",
                    cpuWinNs[p] / (double) Math.max(cpuFrames, 1) / 1e6, cpuWinMaxNs[p] / 1e6));
            if (callsWin[p] != cpuFrames) {
                sb.append(String.format(java.util.Locale.ROOT, "(x%.1f)", callsWin[p] / (double) Math.max(cpuFrames, 1)));
            }
        }
        if (!any) sb.append(" (no passes ran)");
        sb.append(" ; lodCoverage=");
        if (covFrames > 0 && covPixels > 0) {
            sb.append(String.format(java.util.Locale.ROOT, "%.1f%% (opaque LOD px / fb px, %d frames)",
                    100.0 * covSamples / (double) covPixels, covFrames));
        } else {
            sb.append("n/a");
        }
        sb.append(" gpuFrames=").append(gpuFrames);
        if (droppedFrames > 0) sb.append(" droppedUnreadFrames=").append(droppedFrames);
        if (!GPU_WANTED) sb.append(" (gpu OFF: VOXY_VX_TIMING_GPU=0)");
        else if (gpuState == -1) sb.append(" (gpu n/a: no ARB_timer_query)");
        Logger.info(sb.toString());

        java.util.Arrays.fill(cpuWinNs, 0);
        java.util.Arrays.fill(cpuWinMaxNs, 0);
        java.util.Arrays.fill(callsWin, 0);
        java.util.Arrays.fill(gpuWinNs, 0);
        java.util.Arrays.fill(gpuWinMaxNs, 0);
        cpuFrames = 0;
        gpuFrames = 0;
        droppedFrames = 0;
        covSamples = 0;
        covPixels = 0;
        covFrames = 0;
    }

    // ------------------------------------------------------------------

    private static void logOnce() {
        if (loggedOn) return;
        loggedOn = true;
        String gpu = !GPU_WANTED ? "OFF (VOXY_VX_TIMING_GPU=0, CPU brackets only)"
                : gpuState == 1 ? "GL_TIME_ELAPSED async ring (segmented, nesting-safe) + GL_SAMPLES_PASSED coverage"
                : "UNAVAILABLE (no ARB_timer_query, CPU brackets only)";
        Logger.info("[Metal-VXTIMING] GL-side vx pass timing ON: cpu nanoTime brackets; gpu " + gpu
                + "; reports every 600 frames next to [Metal-TIMING]; VOXY_VX_TIMING=0 disables");
    }

    private static void closeFrame() {
        // Any spans still open (exception mid-pass): charge them to now.
        if (depth > 0) {
            long now = System.nanoTime();
            for (int j = 0; j < depth; j++) cpuFrameNs[stackPass[j]] += now - stackStart[j];
            depth = 0;
            stackMask = 0;
        }
        if (covQueryLive != 0) {
            glEndQuery(GL_SAMPLES_PASSED);
            covQueryLive = 0;
        }
        if (gpuActive) {
            if (curQuery != 0) {
                glEndQuery(GL_TIME_ELAPSED);
                recordSegment();
            }
            if (cur.n > 0 || cur.covQuery != 0) {
                INFLIGHT.addLast(cur);
            } else {
                FREE.push(cur);
            }
            cur = null;
            gpuActive = false;
        }
        for (int p = 0; p < PASSES; p++) {
            cpuWinNs[p] += cpuFrameNs[p];
            if (cpuFrameNs[p] > cpuWinMaxNs[p]) cpuWinMaxNs[p] = cpuFrameNs[p];
            callsWin[p] += callsFrame[p];
            cpuFrameNs[p] = 0;
            callsFrame[p] = 0;
        }
        cpuFrames++;
        frameOpen = false;
    }

    /** End the live GL_TIME_ELAPSED segment (if any) and start one for the current stack. */
    private static void switchSegment() {
        if (curQuery != 0) {
            glEndQuery(GL_TIME_ELAPSED);
            recordSegment();
        }
        if (depth > 0 && cur.n < MAX_SEGMENTS) {
            int q = takeQuery();
            glBeginQuery(GL_TIME_ELAPSED, q);
            curQuery = q;
            curMask = stackMask;
        }
    }

    private static void recordSegment() {
        cur.queries[cur.n] = curQuery;
        cur.masks[cur.n] = curMask;
        cur.n++;
        cur.lastIssued = curQuery;
        curQuery = 0;
        curMask = 0;
    }

    private static int takeQuery() {
        return POOL.isEmpty() ? glGenQueries() : POOL.dequeueInt();
    }

    private static void recycle(Frame f) {
        for (int i = 0; i < f.n; i++) POOL.enqueue(f.queries[i]);
        if (f.covQuery != 0) POOL.enqueue(f.covQuery);
        f.n = 0;
        f.covQuery = 0;
        FREE.push(f);
    }

    /**
     * Non-blocking result collection: only the oldest frame whose LAST issued
     * query reports GL_QUERY_RESULT_AVAILABLE is read (queries complete in
     * order, so its earlier ones are available too). A frame still pending
     * after MAX_INFLIGHT newer frames is dropped unread rather than waited on.
     */
    private static void collectReady() {
        while (!INFLIGHT.isEmpty()) {
            Frame f = INFLIGHT.peekFirst();
            boolean ready = f.lastIssued == 0
                    || glGetQueryObjecti(f.lastIssued, GL_QUERY_RESULT_AVAILABLE) == GL_TRUE;
            if (!ready) {
                if (INFLIGHT.size() > MAX_INFLIGHT) {
                    INFLIGHT.pollFirst();
                    recycle(f);
                    droppedFrames++;
                    continue;
                }
                break;
            }
            INFLIGHT.pollFirst();
            java.util.Arrays.fill(scratchGpu, 0);
            for (int i = 0; i < f.n; i++) {
                long ns = glGetQueryObjectui64(f.queries[i], GL_QUERY_RESULT);
                int mask = f.masks[i];
                while (mask != 0) {
                    int p = Integer.numberOfTrailingZeros(mask);
                    mask &= mask - 1;
                    if (p < PASSES) scratchGpu[p] += ns;
                }
            }
            for (int p = 0; p < PASSES; p++) {
                gpuWinNs[p] += scratchGpu[p];
                if (scratchGpu[p] > gpuWinMaxNs[p]) gpuWinMaxNs[p] = scratchGpu[p];
            }
            if (f.n > 0) gpuFrames++;
            if (f.covQuery != 0) {
                covSamples += glGetQueryObjectui64(f.covQuery, GL_QUERY_RESULT);
                covPixels += f.covPixels;
                covFrames++;
            }
            recycle(f);
        }
    }
}
