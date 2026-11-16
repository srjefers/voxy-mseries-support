package me.cortex.voxy.client.core.rendering.util;

import me.cortex.voxy.client.core.gpu.IGpuTexture;
import me.cortex.voxy.client.core.gpu.RenderBackendFactory;
import me.cortex.voxy.client.core.rendering.Viewport;
import me.cortex.voxy.common.Logger;
import org.joml.Matrix4f;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;

import java.nio.ByteBuffer;
import java.util.Arrays;

import static org.lwjgl.opengl.GL33C.*;

/**
 * HiZ pyramid for the Metal path, built from MC's depth instead of Voxy's own.
 *
 * <p>LODs only show where MC drew nothing, so like the GL path's stencil trick,
 * every pixel MC covers occludes all LODs behind it. Voxy renders before MC's
 * terrain, so the current frame's depth isn't there yet and last frame's is used:
 * <ol>
 *   <li>capture(): after MC's opaque terrain, reduce its depth to a quarter-res
 *       texture (1.0 if any source pixel is sky, else the farthest depth) and start
 *       an async readback into one of {@link #RING} PBOs.</li>
 *   <li>begin()/finish(), next frame: take the newest finished readback, splat its
 *       covered texels through the camera change on the CPU, max-reduce the mips
 *       and upload them for HOT.</li>
 * </ol>
 * Texels nothing lands on stay sky, so any error only means less culling.
 * {@link #OCCLUDED} is not 0 because screenspace.glsl reads 0 as "no pyramid".
 * All raw GL state touched here is restored so MC's GlStateManager cache stays valid.
 */
public final class McCoverageHiz {
    private static final float OCCLUDED = 1.0e-6f;
    // pyramid base = viewport size rounded down to a power of two, >> BASE_SHIFT
    private static final int BASE_SHIFT = 2;
    // with shaders the GL queue can run more than a frame behind, so one PBO would be overwritten before it's read
    private static final int RING = 3;
    // frames an old capture may be reprojected again while no newer one has finished
    private static final int MAX_HELD_AGE = 4;
    private static final boolean IRIS_DEPTH_FALLBACK = !"0".equals(System.getenv("VOXY_HIZ_IRIS_DEPTH"));

    private static final String FULLSCREEN_VS = """
            #version 410 core
            void main() {
                vec2 p = vec2((gl_VertexID << 1) & 2, gl_VertexID & 2);
                gl_Position = vec4(p * 2.0 - 1.0, 0.0, 1.0);
            }
            """;
    private static final String REDUCE_FS = """
            #version 410 core
            uniform sampler2D depthTex;
            uniform ivec2 outSize;
            layout(location = 0) out float outDepth;
            void main() {
                ivec2 src = textureSize(depthTex, 0);
                ivec2 p = ivec2(gl_FragCoord.xy);
                ivec2 a = (p * src) / outSize;
                ivec2 b = min(((p + 1) * src + outSize - 1) / outSize, src);
                float m = 0.0;
                for (int y = a.y; y < b.y && m < 1.0; y++)
                    for (int x = a.x; x < b.x; x++)
                        m = max(m, texelFetch(depthTex, ivec2(x, y), 0).r);
                outDepth = m >= 1.0 ? 1.0 : m;
            }
            """;

    private int reduceProgram, uDepthTex, uOutSize, vao, captureFbo, captureTex;
    private int baseW, baseH, levels;
    private boolean broken;

    private final int[] ringPbo = new int[RING];
    private final long[] ringFence = new long[RING];
    private final float[][] ringInvVP = new float[RING][16];
    private final double[][] ringCam = new double[RING][3];
    private int ringNext;

    private int useSlot = -1, heldAge = Integer.MAX_VALUE;
    private final float[] heldInvVP = new float[16], curVP = new float[16];
    private final double[] heldCam = new double[3], curCam = new double[3];
    private boolean pending;

    private long cpuCapture;  // last mapped capture, baseW*baseH floats
    private long cpuPyramid;  // every mip level, tightly packed floats
    private float[] captureArr = new float[0], level0Arr = new float[0];
    private IGpuTexture texture;
    private int skipped, reused;

    public IGpuTexture texture() { return this.texture; }
    public int packedSize() { return (this.baseW << 16) | this.baseH; }

    public String diag() {
        String s = (this.broken ? "BROKEN " : "") + "skip=" + this.skipped + " reuse=" + this.reused;
        this.skipped = this.reused = 0;
        return s;
    }

    /** End of a frame, after MC's opaque terrain: reduce the bound framebuffer's depth and start reading it back. */
    public void capture(Viewport<?> vp) {
        if (this.broken || vp.width <= 0 || vp.height <= 0) return;
        try {
            int bw = baseSize(vp.width), bh = baseSize(vp.height);
            if (this.reduceProgram == 0) this.init();
            if (bw != this.baseW || bh != this.baseH) this.resize(bw, bh);

            int depthTex = boundDepthTexture();
            // under Iris the bound FBO may have no depth texture; depthtex0 holds MC-only opaque depth at this point
            if (depthTex == 0 && IRIS_DEPTH_FALLBACK) depthTex = me.cortex.voxy.client.core.util.IrisUtil.irisMainDepthTexId();
            if (depthTex == 0) return;

            final int srcDepth = depthTex;
            this.runPass(() -> {
                glUseProgram(this.reduceProgram);
                glUniform1i(this.uDepthTex, 0);
                glUniform2i(this.uOutSize, this.baseW, this.baseH);
                glBindTexture(GL_TEXTURE_2D, srcDepth);
                glDrawArrays(GL_TRIANGLES, 0, 3);

                int slot = this.ringNext;
                this.ringNext = (slot + 1) % RING;
                if (this.ringFence[slot] != 0L) glDeleteSync(this.ringFence[slot]);
                this.ringFence[slot] = this.readback(this.ringPbo[slot]);
                new Matrix4f(vp.vanillaProjection).mul(vp.modelView).invert().get(this.ringInvVP[slot]);
                this.ringCam[slot][0] = vp.cameraX;
                this.ringCam[slot][1] = vp.cameraY;
                this.ringCam[slot][2] = vp.cameraZ;
            });
        } catch (Throwable t) {
            Logger.error("McCoverageHiz disabled after GL failure", t);
            this.broken = true;
        }
    }

    /** Start of a frame: pick the capture to use. False when there is none, the caller then keeps the always-pass pyramid. */
    public boolean begin(Viewport<?> vp) {
        this.pending = false;
        if (this.broken || this.reduceProgram == 0) return false;
        if (baseSize(vp.width) != this.baseW || baseSize(vp.height) != this.baseH) return false;

        // newest finished capture wins, older ones are dropped
        this.useSlot = -1;
        for (int k = 1; k <= RING; k++) {
            int slot = Math.floorMod(this.ringNext - k, RING);
            long fence = this.ringFence[slot];
            if (fence == 0L) continue;
            if (this.useSlot >= 0) {
                glDeleteSync(fence);
                this.ringFence[slot] = 0L;
            } else if (glGetSynci(fence, GL_SYNC_STATUS, null) == GL_SIGNALED) {
                this.useSlot = slot;
            }
        }
        if (this.useSlot < 0) {
            if (this.heldAge >= MAX_HELD_AGE) {
                this.skipped++;
                return false;
            }
            this.reused++;
        }
        new Matrix4f(vp.vanillaProjection).mul(vp.modelView).get(this.curVP);
        this.curCam[0] = vp.cameraX;
        this.curCam[1] = vp.cameraY;
        this.curCam[2] = vp.cameraZ;
        this.pending = true;
        return true;
    }

    /** Builds and uploads the pyramid for the capture chosen in {@link #begin}. Call as late as possible. */
    public boolean finish() {
        if (!this.pending) return false;
        this.pending = false;
        if (this.useSlot >= 0) {
            int slot = this.useSlot;
            long bytes = (long) this.baseW * this.baseH * 4;
            int prevPbo = glGetInteger(GL_PIXEL_PACK_BUFFER_BINDING);
            glBindBuffer(GL_PIXEL_PACK_BUFFER, this.ringPbo[slot]);
            ByteBuffer mapped = glMapBufferRange(GL_PIXEL_PACK_BUFFER, 0, bytes, GL_MAP_READ_BIT);
            if (mapped != null) {
                MemoryUtil.memCopy(MemoryUtil.memAddress(mapped), this.cpuCapture, bytes);
                glUnmapBuffer(GL_PIXEL_PACK_BUFFER);
            }
            glBindBuffer(GL_PIXEL_PACK_BUFFER, prevPbo);
            glDeleteSync(this.ringFence[slot]);
            this.ringFence[slot] = 0L;
            if (mapped == null) {
                this.heldAge = Integer.MAX_VALUE;
                this.skipped++;
                return false;
            }
            System.arraycopy(this.ringInvVP[slot], 0, this.heldInvVP, 0, 16);
            System.arraycopy(this.ringCam[slot], 0, this.heldCam, 0, 3);
            this.heldAge = 0;
        } else {
            this.heldAge++;
        }
        this.splat();
        this.buildMips();
        return true;
    }

    /**
     * Forward-splats every covered capture texel into pyramid level 0 through the camera change, as a 2x2 texel
     * point so the stretch gaps of a turning camera close up (one sky texel would max-propagate up every mip).
     * Texels next to sky are skipped (one texel of erosion), since their depth is the farthest of a mixed block.
     */
    private void splat() {
        final int w = this.baseW, h = this.baseH, n = w * h;
        if (this.captureArr.length != n) {
            this.captureArr = new float[n];
            this.level0Arr = new float[n];
        }
        final float[] src = this.captureArr, dst = this.level0Arr, inv = this.heldInvVP, cur = this.curVP;
        MemoryUtil.memFloatBuffer(this.cpuCapture, n).get(src);
        Arrays.fill(dst, 1.0f);
        final float dx = (float) (this.heldCam[0] - this.curCam[0]);
        final float dy = (float) (this.heldCam[1] - this.curCam[1]);
        final float dz = (float) (this.heldCam[2] - this.curCam[2]);
        for (int y = 0; y < h; y++) {
            int ym = Math.max(y - 1, 0), yp = Math.min(y + 1, h - 1);
            float ny = (y + 0.5f) / h * 2f - 1f;
            for (int x = 0; x < w; x++) {
                float d = src[y * w + x];
                if (d >= 1f) continue;
                float around = Math.max(Math.max(src[y * w + Math.min(x + 1, w - 1)], src[y * w + Math.max(x - 1, 0)]),
                                        Math.max(src[yp * w + x], src[ym * w + x]));
                if (around >= 1f) continue;

                float nx = (x + 0.5f) / w * 2f - 1f, nz = d * 2f - 1f;
                float pw = inv[3] * nx + inv[7] * ny + inv[11] * nz + inv[15];
                float px = (inv[0] * nx + inv[4] * ny + inv[8] * nz + inv[12]) / pw + dx;
                float py = (inv[1] * nx + inv[5] * ny + inv[9] * nz + inv[13]) / pw + dy;
                float pz = (inv[2] * nx + inv[6] * ny + inv[10] * nz + inv[14]) / pw + dz;
                float cw = cur[3] * px + cur[7] * py + cur[11] * pz + cur[15];
                if (!(cw > 0f)) continue;
                float cx = cur[0] * px + cur[4] * py + cur[8] * pz + cur[12];
                float cy = cur[1] * px + cur[5] * py + cur[9] * pz + cur[13];
                float cz = cur[2] * px + cur[6] * py + cur[10] * pz + cur[14];
                if (Math.abs(cx) > cw || Math.abs(cy) > cw || Math.abs(cz) > cw) continue;

                // texels whose centres fall in [s-1, s+1), like GL rasterises a 2px point
                float sx = (cx / cw * 0.5f + 0.5f) * w, sy = (cy / cw * 0.5f + 0.5f) * h;
                int x0 = Math.max((int) Math.ceil(sx - 1.5f), 0), x1 = Math.min((int) Math.ceil(sx + 0.5f) - 1, w - 1);
                int y0 = Math.max((int) Math.ceil(sy - 1.5f), 0), y1 = Math.min((int) Math.ceil(sy + 0.5f) - 1, h - 1);
                for (int ty = y0; ty <= y1; ty++)
                    for (int tx = x0; tx <= x1; tx++) dst[ty * w + tx] = OCCLUDED;
            }
        }
        MemoryUtil.memFloatBuffer(this.cpuPyramid, n).put(dst);
    }

    private void buildMips() {
        long src = this.cpuPyramid;
        int w = this.baseW, h = this.baseH;
        this.texture.uploadSubImage2D(0, 0, 0, w, h, GL_RED, GL_FLOAT, src);
        for (int level = 1; level < this.levels; level++) {
            int nw = Math.max(1, w >> 1), nh = Math.max(1, h >> 1);
            long dst = src + (long) w * h * 4;
            for (int y = 0; y < nh; y++) {
                int y0 = Math.min(y * 2, h - 1), y1 = Math.min(y * 2 + 1, h - 1);
                for (int x = 0; x < nw; x++) {
                    int x0 = Math.min(x * 2, w - 1), x1 = Math.min(x * 2 + 1, w - 1);
                    float m = Math.max(
                            Math.max(MemoryUtil.memGetFloat(src + ((long) y0 * w + x0) * 4),
                                     MemoryUtil.memGetFloat(src + ((long) y0 * w + x1) * 4)),
                            Math.max(MemoryUtil.memGetFloat(src + ((long) y1 * w + x0) * 4),
                                     MemoryUtil.memGetFloat(src + ((long) y1 * w + x1) * 4)));
                    MemoryUtil.memPutFloat(dst + ((long) y * nw + x) * 4, m);
                }
            }
            this.texture.uploadSubImage2D(level, 0, 0, nw, nh, GL_RED, GL_FLOAT, dst);
            src = dst;
            w = nw;
            h = nh;
        }
    }

    private static int baseSize(int viewportSize) {
        return Math.max(1, Integer.highestOneBit(viewportSize) >> BASE_SHIFT);
    }

    private static int boundDepthTexture() {
        int fb = glGetInteger(GL_DRAW_FRAMEBUFFER_BINDING);
        if (fb == 0) return 0;
        int prevRead = glGetInteger(GL_READ_FRAMEBUFFER_BINDING);
        glBindFramebuffer(GL_READ_FRAMEBUFFER, fb);
        int type = glGetFramebufferAttachmentParameteri(GL_READ_FRAMEBUFFER, GL_DEPTH_ATTACHMENT, GL_FRAMEBUFFER_ATTACHMENT_OBJECT_TYPE);
        int tex = type == GL_TEXTURE
                ? glGetFramebufferAttachmentParameteri(GL_READ_FRAMEBUFFER, GL_DEPTH_ATTACHMENT, GL_FRAMEBUFFER_ATTACHMENT_OBJECT_NAME) : 0;
        glBindFramebuffer(GL_READ_FRAMEBUFFER, prevRead);
        return tex;
    }

    /** Reads the capture into {@code pbo} and fences it, with pack state saved. */
    private long readback(int pbo) {
        glBindFramebuffer(GL_READ_FRAMEBUFFER, this.captureFbo);
        glReadBuffer(GL_COLOR_ATTACHMENT0);
        int prevRow = glGetInteger(GL_PACK_ROW_LENGTH);
        int prevSkipRows = glGetInteger(GL_PACK_SKIP_ROWS);
        int prevSkipPixels = glGetInteger(GL_PACK_SKIP_PIXELS);
        int prevAlign = glGetInteger(GL_PACK_ALIGNMENT);
        glPixelStorei(GL_PACK_ROW_LENGTH, 0);
        glPixelStorei(GL_PACK_SKIP_ROWS, 0);
        glPixelStorei(GL_PACK_SKIP_PIXELS, 0);
        glPixelStorei(GL_PACK_ALIGNMENT, 4);
        glBindBuffer(GL_PIXEL_PACK_BUFFER, pbo);
        glReadPixels(0, 0, this.baseW, this.baseH, GL_RED, GL_FLOAT, 0L);
        long fence = glFenceSync(GL_SYNC_GPU_COMMANDS_COMPLETE, 0);
        glFlush();
        glPixelStorei(GL_PACK_ROW_LENGTH, prevRow);
        glPixelStorei(GL_PACK_SKIP_ROWS, prevSkipRows);
        glPixelStorei(GL_PACK_SKIP_PIXELS, prevSkipPixels);
        glPixelStorei(GL_PACK_ALIGNMENT, prevAlign);
        return fence;
    }

    private void init() {
        int vs = compile(GL_VERTEX_SHADER, FULLSCREEN_VS);
        int fs = compile(GL_FRAGMENT_SHADER, REDUCE_FS);
        this.reduceProgram = glCreateProgram();
        glAttachShader(this.reduceProgram, vs);
        glAttachShader(this.reduceProgram, fs);
        glLinkProgram(this.reduceProgram);
        glDeleteShader(vs);
        glDeleteShader(fs);
        if (glGetProgrami(this.reduceProgram, GL_LINK_STATUS) == 0) {
            throw new IllegalStateException("McCoverageHiz link: " + glGetProgramInfoLog(this.reduceProgram));
        }
        this.uDepthTex = glGetUniformLocation(this.reduceProgram, "depthTex");
        this.uOutSize = glGetUniformLocation(this.reduceProgram, "outSize");
        this.vao = glGenVertexArrays();
        this.captureFbo = glGenFramebuffers();
        this.captureTex = glGenTextures();
        for (int i = 0; i < RING; i++) this.ringPbo[i] = glGenBuffers();
    }

    private static int compile(int stage, String src) {
        int shader = glCreateShader(stage);
        glShaderSource(shader, src);
        glCompileShader(shader);
        if (glGetShaderi(shader, GL_COMPILE_STATUS) == 0) {
            throw new IllegalStateException("McCoverageHiz compile: " + glGetShaderInfoLog(shader));
        }
        return shader;
    }

    private void resize(int bw, int bh) {
        this.baseW = bw;
        this.baseH = bh;
        this.levels = 32 - Integer.numberOfLeadingZeros(Math.max(bw, bh));

        int prevActive = glGetInteger(GL_ACTIVE_TEXTURE);
        glActiveTexture(GL_TEXTURE0);
        int prevBound = glGetInteger(GL_TEXTURE_BINDING_2D);
        int prevDraw = glGetInteger(GL_DRAW_FRAMEBUFFER_BINDING);
        glBindTexture(GL_TEXTURE_2D, this.captureTex);
        glTexImage2D(GL_TEXTURE_2D, 0, GL_R32F, bw, bh, 0, GL_RED, GL_FLOAT, 0L);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_NEAREST);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
        glBindFramebuffer(GL_DRAW_FRAMEBUFFER, this.captureFbo);
        glFramebufferTexture2D(GL_DRAW_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, this.captureTex, 0);
        glBindFramebuffer(GL_DRAW_FRAMEBUFFER, prevDraw);
        glBindTexture(GL_TEXTURE_2D, prevBound);
        glActiveTexture(prevActive);

        int prevPbo = glGetInteger(GL_PIXEL_PACK_BUFFER_BINDING);
        for (int i = 0; i < RING; i++) {
            if (this.ringFence[i] != 0L) {
                glDeleteSync(this.ringFence[i]);
                this.ringFence[i] = 0L;
            }
            glBindBuffer(GL_PIXEL_PACK_BUFFER, this.ringPbo[i]);
            glBufferData(GL_PIXEL_PACK_BUFFER, (long) bw * bh * 4, GL_STREAM_READ);
        }
        glBindBuffer(GL_PIXEL_PACK_BUFFER, prevPbo);
        this.heldAge = Integer.MAX_VALUE;

        long total = 0;
        for (int l = 0, w = bw, h = bh; l < this.levels; l++, w = Math.max(1, w >> 1), h = Math.max(1, h >> 1)) {
            total += (long) w * h * 4;
        }
        this.freeCpu();
        this.cpuCapture = MemoryUtil.nmemAlloc((long) bw * bh * 4);
        this.cpuPyramid = MemoryUtil.nmemAlloc(total);

        if (this.texture != null) this.texture.free();
        this.texture = RenderBackendFactory.get().createTexture()
                .storeUploadable(GL_R32F, this.levels, bw, bh)
                .name("HiZ-MCCoverage");
    }

    /** Runs {@code body} into the capture FBO with every raw GL state it touches saved and restored. */
    private void runPass(Runnable body) {
        int prevProgram = glGetInteger(GL_CURRENT_PROGRAM);
        int prevVao = glGetInteger(GL_VERTEX_ARRAY_BINDING);
        int prevDraw = glGetInteger(GL_DRAW_FRAMEBUFFER_BINDING);
        int prevRead = glGetInteger(GL_READ_FRAMEBUFFER_BINDING);
        int prevActive = glGetInteger(GL_ACTIVE_TEXTURE);
        glActiveTexture(GL_TEXTURE0);
        int prevTex0 = glGetInteger(GL_TEXTURE_BINDING_2D);
        int prevSampler0 = glGetInteger(GL_SAMPLER_BINDING);
        int prevPbo = glGetInteger(GL_PIXEL_PACK_BUFFER_BINDING);
        int[] prevViewport = new int[4];
        glGetIntegerv(GL_VIEWPORT, prevViewport);
        boolean depthTest = glIsEnabled(GL_DEPTH_TEST);
        boolean stencilTest = glIsEnabled(GL_STENCIL_TEST);
        boolean blend = glIsEnabled(GL_BLEND);
        boolean cull = glIsEnabled(GL_CULL_FACE);
        boolean scissor = glIsEnabled(GL_SCISSOR_TEST);
        boolean[] colorMask = new boolean[4];
        try (var stack = MemoryStack.stackPush()) {
            var cm = stack.malloc(4);
            glGetBooleanv(GL_COLOR_WRITEMASK, cm);
            for (int i = 0; i < 4; i++) colorMask[i] = cm.get(i) != 0;
        }

        glDisable(GL_DEPTH_TEST);
        glDisable(GL_STENCIL_TEST);
        glDisable(GL_BLEND);
        glDisable(GL_CULL_FACE);
        glDisable(GL_SCISSOR_TEST);
        glColorMask(true, true, true, true);
        glBindFramebuffer(GL_DRAW_FRAMEBUFFER, this.captureFbo);
        glViewport(0, 0, this.baseW, this.baseH);
        glBindSampler(0, 0);
        glBindVertexArray(this.vao);
        try {
            body.run();
        } finally {
            glBindBuffer(GL_PIXEL_PACK_BUFFER, prevPbo);
            glBindVertexArray(prevVao);
            glBindSampler(0, prevSampler0);
            glBindTexture(GL_TEXTURE_2D, prevTex0);
            glActiveTexture(prevActive);
            glUseProgram(prevProgram);
            glBindFramebuffer(GL_DRAW_FRAMEBUFFER, prevDraw);
            glBindFramebuffer(GL_READ_FRAMEBUFFER, prevRead);
            glViewport(prevViewport[0], prevViewport[1], prevViewport[2], prevViewport[3]);
            glColorMask(colorMask[0], colorMask[1], colorMask[2], colorMask[3]);
            if (depthTest) glEnable(GL_DEPTH_TEST);
            if (stencilTest) glEnable(GL_STENCIL_TEST);
            if (blend) glEnable(GL_BLEND);
            if (cull) glEnable(GL_CULL_FACE);
            if (scissor) glEnable(GL_SCISSOR_TEST);
        }
    }

    private void freeCpu() {
        if (this.cpuPyramid != 0) {
            MemoryUtil.nmemFree(this.cpuPyramid);
            this.cpuPyramid = 0;
        }
        if (this.cpuCapture != 0) {
            MemoryUtil.nmemFree(this.cpuCapture);
            this.cpuCapture = 0;
        }
    }

    public void free() {
        for (int i = 0; i < RING; i++) {
            if (this.ringFence[i] != 0L) {
                glDeleteSync(this.ringFence[i]);
                this.ringFence[i] = 0L;
            }
        }
        if (this.reduceProgram != 0) {
            glDeleteProgram(this.reduceProgram);
            glDeleteVertexArrays(this.vao);
            glDeleteFramebuffers(this.captureFbo);
            glDeleteTextures(this.captureTex);
            for (int i = 0; i < RING; i++) glDeleteBuffers(this.ringPbo[i]);
            this.reduceProgram = 0;
        }
        this.freeCpu();
        if (this.texture != null) {
            this.texture.free();
            this.texture = null;
        }
    }
}
