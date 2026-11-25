package me.cortex.voxy.client.core.metal;

import me.cortex.voxy.client.core.gpu.IGpuPipeline;

/**
 * Metal implementation of {@link IGpuPipeline}: holds a MTLRenderPipelineState
 * plus the libraries and functions it depends on so they stay alive for the
 * pipeline's lifetime.
 *
 * Closing this object releases all four handles in reverse-creation order
 * (state → fragment fn → vertex fn → library). The libraries are owned 1:1
 * with the pipeline today; M11+ will introduce a library cache when shader
 * permutation pressure justifies it.
 */
public final class MetalGraphicsPipeline implements IGpuPipeline {

    private long pipelineState;
    private long vertexLibrary;
    private long fragmentLibrary;
    private long vertexFunction;
    private long fragmentFunction;
    /** Depth-stencil state object — 0 if no depth testing/writing requested. */
    private long depthStencilState;
    /** Encoder-time raster state pulled from PipelineState; applied each setPipeline. */
    final int cullMode;
    final int winding;
    final int fillMode;
    /**
     * H17: builds this pipeline's state again with another rasterSampleCount (the multisampled LOD
     * pass). Variants are created on first use and cached by sample count; index 0/1 is unused.
     */
    private final java.util.function.IntToLongFunction variantFactory;
    private final String label;
    private final long[] variants = new long[9];

    MetalGraphicsPipeline(long pipelineState, long vertexLibrary, long fragmentLibrary,
                          long vertexFunction, long fragmentFunction,
                          long depthStencilState, int cullMode, int winding, int fillMode,
                          java.util.function.IntToLongFunction variantFactory, String label) {
        this.variantFactory = variantFactory;
        this.label = label;
        this.pipelineState = pipelineState;
        this.vertexLibrary = vertexLibrary;
        this.fragmentLibrary = fragmentLibrary;
        this.vertexFunction = vertexFunction;
        this.fragmentFunction = fragmentFunction;
        this.depthStencilState = depthStencilState;
        this.cullMode = cullMode;
        this.winding = winding;
        this.fillMode = fillMode;
    }

    public long pipelineStateHandle() {
        return this.pipelineState;
    }

    /**
     * The state to bind in a pass whose attachments have {@code sampleCount} samples: the normal
     * state for 1, a lazily built variant otherwise (H17 multisampled LOD pass).
     */
    long pipelineStateHandle(int sampleCount) {
        if (sampleCount <= 1) return this.pipelineState;
        if (this.variantFactory == null || sampleCount >= this.variants.length) {
            throw new IllegalStateException("pipeline " + this.label + " has no " + sampleCount + "-sample variant");
        }
        long v = this.variants[sampleCount];
        if (v == 0) {
            v = this.variantFactory.applyAsLong(sampleCount);
            if (v == 0) {
                throw new RuntimeException("Pipeline " + this.label + " x" + sampleCount + " link failed: "
                        + MetalNative.mtlGetLastCompileError());
            }
            if (this.label != null) MetalNative.mtlSetLabel(v, this.label + " x" + sampleCount);
            this.variants[sampleCount] = v;
        }
        return v;
    }

    long depthStencilStateHandle() {
        return this.depthStencilState;
    }

    @Override
    public void close() {
        for (int i = 0; i < this.variants.length; i++) {
            if (this.variants[i] != 0) {
                MetalNative.mtlRelease(this.variants[i]);
                this.variants[i] = 0;
            }
        }
        if (this.pipelineState != 0) {
            MetalNative.mtlRelease(this.pipelineState);
            this.pipelineState = 0;
        }
        if (this.depthStencilState != 0) {
            MetalNative.mtlRelease(this.depthStencilState);
            this.depthStencilState = 0;
        }
        if (this.fragmentFunction != 0) {
            MetalNative.mtlRelease(this.fragmentFunction);
            this.fragmentFunction = 0;
        }
        if (this.vertexFunction != 0) {
            MetalNative.mtlRelease(this.vertexFunction);
            this.vertexFunction = 0;
        }
        if (this.fragmentLibrary != 0) {
            MetalNative.mtlRelease(this.fragmentLibrary);
            this.fragmentLibrary = 0;
        }
        if (this.vertexLibrary != 0) {
            MetalNative.mtlRelease(this.vertexLibrary);
            this.vertexLibrary = 0;
        }
    }
}
