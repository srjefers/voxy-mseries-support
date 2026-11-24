package me.cortex.voxy.client.core.rendering.clouds;

import me.cortex.voxy.client.core.gl.shader.ShaderLoader;
import me.cortex.voxy.client.core.gpu.BackendType;
import me.cortex.voxy.client.core.gpu.GraphicsPipelineDesc;
import me.cortex.voxy.client.core.gpu.IGpuBuffer;
import me.cortex.voxy.client.core.gpu.IGpuPipeline;
import me.cortex.voxy.client.core.gpu.PipelineState;
import me.cortex.voxy.client.core.gpu.RenderBackend;
import me.cortex.voxy.client.core.gpu.RenderEncoder;
import me.cortex.voxy.client.core.gpu.VertexLayout;
import me.cortex.voxy.client.core.metal.MetalBuffer;
import me.cortex.voxy.client.core.rendering.Viewport;
import me.cortex.voxy.client.core.rendering.util.MetalMvpUtil;
import me.cortex.voxy.common.util.JomlCompat;
import net.minecraft.util.Mth;
import org.joml.Matrix4f;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;

import java.util.Map;

/**
 * The LOD cloud layer's Metal pass (see {@link LodClouds} for the why). Draws one
 * proxy quad inside the LOD render pass, after the LOD terrain: the fragment shader
 * ({@code clouds/lod_clouds.frag}) marches the cloud cells, writes the hit's depth
 * and alpha-blends the cloud into the LOD colour bridge. Depth test against the LOD
 * depth (no write) hides clouds behind LOD terrain; the vanilla terrain and the
 * vanilla cloud mesh are drawn later, on top, by GL.
 *
 * <p>Owned by {@code AbstractRenderPipeline}; Metal only, called only on the blit
 * composite path (no shader pack).
 */
public final class LodCloudLayer {
    /** Same colour format the LOD terrain pipelines declare for the bridge. */
    private static final int GL_RGBA8 = 0x8058;
    private static final int PARAMS_BINDING = 0;
    private static final int CELLS_BINDING = 1;
    private static final int COVERAGE_BINDING = 2;
    /** std140 size of {@code LodCloudParams} (lod_clouds_params.glsl). */
    private static final int PARAMS_SIZE = 160;
    /** Size of a LOD column (a top-level node) in blocks. */
    private static final int COLUMN_BLOCKS = 512;
    /** Largest coverage grid side (columns); 257 columns span ±65 km. */
    private static final int MAX_COVERAGE_SIZE = 257;
    /** Coverage grid uploads at most 4 times a second. */
    private static final long COVERAGE_UPLOAD_INTERVAL_NS = 250_000_000L;
    /** Cells marched per pixel before a grazing ray counts as a cloud wall. */
    private static final int MAX_MARCH_CELLS = 48;

    /** Alpha blend for colour; the bridge's alpha is left as the LOD pass wrote it. */
    private static final PipelineState STATE = new PipelineState(
            PipelineState.DepthState.TEST_NO_WRITE,
            new PipelineState.BlendState(true,
                    PipelineState.BlendFactor.SRC_ALPHA, PipelineState.BlendFactor.ONE_MINUS_SRC_ALPHA, PipelineState.BlendOp.ADD,
                    PipelineState.BlendFactor.ZERO, PipelineState.BlendFactor.ONE, PipelineState.BlendOp.ADD),
            PipelineState.RasterState.NO_CULL);

    private IGpuPipeline pipeline;
    private IGpuBuffer cellBuffer;
    private int cellGeneration = Integer.MIN_VALUE;

    // Where the LOD terrain is around the camera: a grid of 512-block columns, 32 words
    // of 32 bits per column, one bit per 16-block cell (see LodTerrainCoverage.fill).
    private IGpuBuffer coverageBuffer;
    private int[] coverageWords;
    private int coverageSize;
    private int coverageVersion = Integer.MIN_VALUE;
    private int coverageCentreX;
    private int coverageCentreZ;
    private long coverageUploadNanos;

    /**
     * Encode the layer into the open LOD render pass, if it runs this frame. Must be
     * called after the LOD terrain draws, inside the same encoder (needs their depth).
     * {@code coverage} limits the clouds to LOD terrain; null = no limit.
     */
    public void render(RenderBackend backend, RenderEncoder enc, Viewport<?> viewport, LodTerrainCoverage coverage) {
        LodClouds.Frame f = LodClouds.consumeFrame();
        if (f == null) return;
        try {
            if (this.pipeline == null) {
                this.pipeline = backend.createGraphicsPipeline(new GraphicsPipelineDesc(
                        ShaderLoader.parse("voxy:clouds/lod_clouds.vert"),
                        ShaderLoader.parse("voxy:clouds/lod_clouds.frag"),
                        Map.of(),
                        null, null,
                        null, null,
                        GL_RGBA8,
                        VertexLayout.EMPTY,
                        STATE,
                        "LodCloudLayer"));
            }
            if (!this.uploadCells(backend)) return;
        } catch (Throwable t) {
            LodClouds.markBroken(t);
            return;
        }

        // The coverage grid is centred on the camera's LOD column (512 blocks,
        // RenderDistanceTracker's >> 9) as of its last upload; the shader gets the camera
        // position relative to that column's corner.
        boolean limited = false;
        if (coverage != null) {
            try {
                limited = this.uploadCoverage(backend, coverage,
                        Mth.floor(viewport.cameraX) >> 9, Mth.floor(viewport.cameraZ) >> 9);
            } catch (Throwable t) {
                LodClouds.markBroken(t);
                return;
            }
        }
        float inColumnX = (float) (viewport.cameraX - this.coverageCentreX * (double) COLUMN_BLOCKS);
        float inColumnZ = (float) (viewport.cameraZ - this.coverageCentreZ * (double) COLUMN_BLOCKS);

        var mvp = new Matrix4f(viewport.MVP);
        // Same depth convention as the LOD terrain (MDICSectionRenderer.uploadUniformBuffer).
        if (MetalMvpUtil.METAL_NDC_REMAP && backend.getType() != BackendType.OPENGL) {
            MetalMvpUtil.applyNdcRemap(mvp);
        }

        try (var stack = MemoryStack.stackPush()) {
            long addr = stack.nmalloc(16, PARAMS_SIZE);
            writeParams(addr, mvp, f);
            MemoryUtil.memPutFloat(addr + 144, inColumnX);           // 144: coverage
            MemoryUtil.memPutFloat(addr + 148, inColumnZ);
            MemoryUtil.memPutFloat(addr + 152, limited ? this.coverageSize : 0.0f);
            MemoryUtil.memPutFloat(addr + 156, limited ? 1.0f : 0.0f);
            enc.setPipeline(this.pipeline);
            enc.setBytes(PARAMS_BINDING, addr, PARAMS_SIZE);
            enc.setBuffer(CELLS_BINDING, this.cellBuffer, 0);
            // Bound even when unused (the shader skips it): keeps every declared slot valid.
            enc.setBuffer(COVERAGE_BINDING, limited ? this.coverageBuffer : this.cellBuffer, 0);
            enc.draw(RenderEncoder.PRIMITIVE_TRIANGLE_STRIP, 0, 4, 1, 0);
        }
        LodClouds.onDrawn(f);
    }

    /** (Re)upload the cloud cell bits after a resource reload. False when there are none. */
    private boolean uploadCells(RenderBackend backend) {
        int generation = LodClouds.textureGeneration();
        if (generation == this.cellGeneration && this.cellBuffer != null) return true;
        int[] bits = LodClouds.occupancy();
        if (bits == null || bits.length == 0) return false;
        long size = (long) bits.length * 4L;
        if (this.cellBuffer == null || this.cellBuffer.size() != size) {
            if (this.cellBuffer != null) this.cellBuffer.free();
            this.cellBuffer = backend.createBuffer(size).name("LodCloudLayer.cells");
        }
        // Shared storage: the CPU writes straight into the buffer. The Metal frame is
        // synchronous (the previous frame's command buffer has completed), so no draw
        // is reading it now.
        if (!(this.cellBuffer instanceof MetalBuffer mb) || mb.getContentsPtr() == 0) {
            throw new IllegalStateException("cloud cell buffer is not CPU-visible: " + this.cellBuffer);
        }
        long ptr = mb.getContentsPtr();
        for (int i = 0; i < bits.length; i++) {
            MemoryUtil.memPutInt(ptr + (long) i * 4L, bits[i]);
        }
        this.cellGeneration = generation;
        return true;
    }

    /**
     * Rebuild the coverage grid around the camera's column when the LOD terrain or the
     * camera's column changed, at most every {@link #COVERAGE_UPLOAD_INTERVAL_NS} (the
     * grid is ~2 MB at 63 LOD distance, and the terrain changes every frame while LODs
     * stream in). Between uploads the shader keeps reading the last grid, positioned
     * by its own centre, so it stays correct, only a little behind.
     */
    private boolean uploadCoverage(RenderBackend backend, LodTerrainCoverage coverage, int centreX, int centreZ) {
        int size = Math.min(2 * (int) Math.ceil(LodClouds.lodRadiusBlocks() / COLUMN_BLOCKS) + 3, MAX_COVERAGE_SIZE);
        int version = coverage.version();
        long now = System.nanoTime();
        if (this.coverageBuffer != null && size == this.coverageSize) {
            boolean changed = version != this.coverageVersion
                    || centreX != this.coverageCentreX || centreZ != this.coverageCentreZ;
            if (!changed || now - this.coverageUploadNanos < COVERAGE_UPLOAD_INTERVAL_NS) {
                return true;
            }
        }
        int words = size * size * LodTerrainCoverage.CELLS;
        if (this.coverageWords == null || this.coverageWords.length != words) {
            this.coverageWords = new int[words];
        }
        coverage.fill(this.coverageWords, centreX, centreZ, size);
        long bytes = (long) words * 4L;
        if (this.coverageBuffer == null || this.coverageBuffer.size() != bytes) {
            if (this.coverageBuffer != null) this.coverageBuffer.free();
            this.coverageBuffer = backend.createBuffer(bytes).name("LodCloudLayer.coverage");
        }
        if (!(this.coverageBuffer instanceof MetalBuffer mb) || mb.getContentsPtr() == 0) {
            throw new IllegalStateException("cloud coverage buffer is not CPU-visible: " + this.coverageBuffer);
        }
        // Shared storage, synchronous Metal frame: nothing reads it right now (see uploadCells).
        MemoryUtil.memIntBuffer(mb.getContentsPtr(), words).put(this.coverageWords);
        if (this.coverageVersion == Integer.MIN_VALUE) {
            me.cortex.voxy.common.Logger.info("[Metal-CLOUDS] clouds only over LOD terrain: grid of "
                    + size + "x" + size + " LOD columns, 16-block cells, "
                    + coverage.sectionCount() + " top-level sections with terrain so far");
        }
        this.coverageSize = size;
        this.coverageVersion = version;
        this.coverageCentreX = centreX;
        this.coverageCentreZ = centreZ;
        this.coverageUploadNanos = now;
        return true;
    }

    /** Layout of {@code LodCloudParams} in lod_clouds_params.glsl (std140). */
    private static void writeParams(long addr, Matrix4f mvp, LodClouds.Frame f) {
        JomlCompat.put(mvp, addr);                                   // 0: mat4 MVP
        MemoryUtil.memPutFloat(addr + 64, f.entryY);                 // 64: slab
        MemoryUtil.memPutFloat(addr + 68, f.exitY);
        MemoryUtil.memPutFloat(addr + 72, f.fadeEnd);                //     quad half size
        MemoryUtil.memPutFloat(addr + 76, f.entryShade);
        MemoryUtil.memPutFloat(addr + 80, f.offX);                   // 80: cellPos
        MemoryUtil.memPutFloat(addr + 84, f.offZ);
        MemoryUtil.memPutFloat(addr + 88, f.meshRadiusCells);
        MemoryUtil.memPutFloat(addr + 92, LodClouds.DEBUG_TINT ? 1.0f : 0.0f);
        MemoryUtil.memPutInt(addr + 96, f.cellX);                    // 96: cellInfo
        MemoryUtil.memPutInt(addr + 100, f.cellZ);
        MemoryUtil.memPutInt(addr + 104, f.texWidth);
        MemoryUtil.memPutInt(addr + 108, f.texHeight);
        MemoryUtil.memPutFloat(addr + 112, f.red);                   // 112: colour
        MemoryUtil.memPutFloat(addr + 116, f.green);
        MemoryUtil.memPutFloat(addr + 120, f.blue);
        MemoryUtil.memPutFloat(addr + 124, f.alpha);
        MemoryUtil.memPutFloat(addr + 128, f.fadeStart);             // 128: fade
        MemoryUtil.memPutFloat(addr + 132, f.fadeEnd);
        MemoryUtil.memPutFloat(addr + 136, MAX_MARCH_CELLS);
        MemoryUtil.memPutFloat(addr + 140, 0.0f);
    }

    public void close() {
        if (this.pipeline != null) {
            this.pipeline.close();
            this.pipeline = null;
        }
        if (this.cellBuffer != null) {
            this.cellBuffer.free();
            this.cellBuffer = null;
        }
        this.cellGeneration = Integer.MIN_VALUE;
        if (this.coverageBuffer != null) {
            this.coverageBuffer.free();
            this.coverageBuffer = null;
        }
        this.coverageVersion = Integer.MIN_VALUE;
    }
}
