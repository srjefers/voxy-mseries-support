package me.cortex.voxy.client.core.metal;

import me.cortex.voxy.client.core.gpu.RenderEncoder;
import me.cortex.voxy.client.core.gpu.RenderPassDesc;
import me.cortex.voxy.common.Logger;

/**
 * H17: multisample antialiasing for the LOD pass on Metal.
 *
 * <p>Far LOD terrain is made of block steps that project to about one pixel: on a snowy slope 600-900
 * blocks away each row of the image is either a white snow top or the dark side of the next step.
 * With one sample per pixel every row is all one or the other, so a camera turn of a fraction of a
 * pixel flips whole rows, and the slopes crawl ("lines that move like parallax"). Upstream GL draws
 * into Minecraft's framebuffer, which has no antialiasing either, so it shows the same.
 *
 * <p>With {@code SAMPLES} samples per pixel the rows resolve to partial coverage instead, so they move
 * smoothly. The LOD pass draws into a multisample colour + depth pair that lives only in tile memory
 * (memoryless) and the colour is resolved into the IOSurface bridge when the pass ends: no extra RAM
 * or bandwidth, the cost is the per-sample depth test and coverage on the GPU.
 *
 * <p>Only the plain path uses it (no Iris export, no vx material planes, no solid-bridge test): those
 * read the LOD depth after the pass or draw into several planes, and keep one sample. The GL backend
 * never reaches this class.
 *
 * <p>{@code VOXY_LOD_MSAA}: samples, default 4; {@code 1} (or {@code 0}) is the kill switch.
 */
public final class MetalLodMsaa implements AutoCloseable {
    public static final int SAMPLES = parseSamples(System.getenv("VOXY_LOD_MSAA"));

    private long color;
    private long depth;
    private int width;
    private int height;
    private int colorFormat;
    private int samples;
    private boolean disabled;
    private boolean loggedSkip;

    private static int parseSamples(String v) {
        if (v == null || v.isBlank()) return 4;
        try {
            int n = Integer.parseInt(v.trim());
            return n <= 1 ? 1 : Math.min(n, 8);
        } catch (NumberFormatException e) {
            return 4;
        }
    }

    /** Sample count actually in use (0 before the first pass; may be below SAMPLES if the GPU refuses it). */
    public int samples() {
        return this.samples;
    }

    /** Whether the LOD pass should be multisampled at all (env + earlier failures). */
    public boolean enabled() {
        return SAMPLES > 1 && !this.disabled;
    }

    /** Logs once that a path which needs one sample is drawing the LOD without MSAA. */
    public void noteSkipped(String why) {
        if (this.enabled() && !this.loggedSkip) {
            this.loggedSkip = true;
            Logger.info("[Metal-LODTEST] H17 LOD MSAA skipped on this path (" + why + "): one sample per pixel");
        }
    }

    /**
     * Opens {@code pass} multisampled: its single colour attachment and its depth are replaced by the
     * MSAA pair, and the colour resolves into {@code pass}'s colour texture. Returns null (and turns
     * itself off for the session) if the device refuses the textures; the caller then opens the pass
     * normally.
     */
    public RenderEncoder begin(MetalRenderBackend mrb, RenderPassDesc pass) {
        if (!this.enabled() || pass.colorAttachments().size() != 1 || pass.depthAttachment() == null) {
            return null;
        }
        int w = pass.viewportWidth();
        int h = pass.viewportHeight();
        long resolveHandle = MetalHandleMap.getHandle(pass.colorAttachments().get(0).texture().id());
        int fmt = MetalNative.mtlTextureGetPixelFormat(resolveHandle);
        if (!this.ensure(mrb.device(), w, h, fmt)) {
            return null;
        }
        return mrb.beginRenderPassMultisample(pass, new long[]{this.color}, this.depth, this.samples);
    }

    private boolean ensure(long device, int w, int h, int fmt) {
        if (this.color != 0 && this.width == w && this.height == h && this.colorFormat == fmt) {
            return true;
        }
        this.release();
        int n = SAMPLES;
        while (n > 1 && !MetalNative.mtlDeviceSupportsTextureSampleCount(device, n)) n >>= 1;
        if (n <= 1) {
            return this.fail("the GPU supports no multisample count <= " + SAMPLES);
        }
        // Memoryless first (tile memory only, Apple GPUs); private storage as the fallback.
        for (boolean ml : new boolean[]{true, false}) {
            long c = MetalNative.mtlDeviceNewMultisampleTexture(device, fmt, w, h, n, ml);
            long d = c == 0 ? 0 : MetalNative.mtlDeviceNewMultisampleTexture(device,
                    MetalFormatUtil.MTLPixelFormatDepth32Float, w, h, n, ml);
            if (c != 0 && d != 0) {
                boolean first = this.samples == 0;
                this.color = c;
                this.depth = d;
                this.width = w;
                this.height = h;
                this.colorFormat = fmt;
                this.samples = n;
                if (first) {
                    Logger.info("[Metal-LODTEST] H17 LOD MSAA " + n + "x ON (" + (ml ? "memoryless" : "private")
                            + " colour+depth " + w + "x" + h + ", colour resolved into the bridge);"
                            + " VOXY_LOD_MSAA=1 turns it off");
                }
                return true;
            }
            if (c != 0) MetalNative.mtlRelease(c);
            if (d != 0) MetalNative.mtlRelease(d);
        }
        return this.fail("could not create " + n + "x textures " + w + "x" + h + " format " + fmt);
    }

    private boolean fail(String why) {
        this.disabled = true;
        Logger.warn("[Metal-LODTEST] H17 LOD MSAA OFF for this session: " + why);
        return false;
    }

    private void release() {
        if (this.color != 0) MetalNative.mtlRelease(this.color);
        if (this.depth != 0) MetalNative.mtlRelease(this.depth);
        this.color = 0;
        this.depth = 0;
    }

    @Override
    public void close() {
        this.release();
    }
}
