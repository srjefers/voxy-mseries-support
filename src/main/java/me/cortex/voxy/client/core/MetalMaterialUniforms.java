package me.cortex.voxy.client.core;

import me.cortex.voxy.client.core.gpu.IGpuBuffer;
import me.cortex.voxy.client.core.gpu.RenderBackend;
import me.cortex.voxy.client.core.gpu.RenderEncoder;
import me.cortex.voxy.client.core.metal.MetalBuffer;
import me.cortex.voxy.client.iris.IrisVoxyRenderPipelineData;
import org.lwjgl.system.MemoryUtil;

/** Shared pack uniforms for terrain and coverage, separate from their geometry buffers. */
final class MetalMaterialUniforms implements AutoCloseable {
    static final int BINDING = 10;
    private final IrisVoxyRenderPipelineData data;
    private IGpuBuffer buffer;
    private long capturedFrame = Long.MIN_VALUE;

    MetalMaterialUniforms(IrisVoxyRenderPipelineData data, RenderBackend backend) {
        this.data = data;
        if (data.getUniforms() != null && data.getUniforms().size() > 0) {
            this.buffer = backend.createBuffer(data.getUniforms().size()).zero();
        }
    }

    void update() {
        if (this.buffer != null) {
            long pointer = ((MetalBuffer)this.buffer).getContentsPtr();
            MemoryUtil.memSet(pointer, 0, this.buffer.size());
            this.data.getUniforms().updater().accept(pointer);
        }
    }

    void capture(long frame) {
        if(this.capturedFrame==frame) return;
        this.update();
        this.capturedFrame=frame;
    }
    long pointer() { return this.buffer==null ? 0 : ((MetalBuffer)this.buffer).getContentsPtr(); }

    void bind(RenderEncoder encoder) {
        if (this.buffer != null) encoder.setBuffer(BINDING, this.buffer, 0);
    }

    static String taa(IrisVoxyRenderPipelineData data, int binding, String name) {
        if (data.TAA == null) return null;
        var source = new StringBuilder();
        if (data.getUniforms() != null) {
            source.append("layout(binding = ").append(binding).append(", std140) uniform ShaderUniformBindings ")
                    .append(data.getUniforms().layout()).append(";\n");
        }
        return source.append("vec2 ").append(name).append("()\n").append(data.TAA).append('\n').toString();
    }

    @Override public void close() {
        if (this.buffer != null) { this.buffer.free(); this.buffer = null; }
    }
}
