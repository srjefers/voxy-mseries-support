package me.cortex.voxy.client.core.model;

import me.cortex.voxy.client.core.gpu.IGpuBuffer;
import me.cortex.voxy.client.core.gpu.RenderBackend;
import me.cortex.voxy.client.core.rendering.util.UploadStream;
import org.lwjgl.system.MemoryUtil;

/** Render-thread owner of a bounded, growing Metal tint table; binding 11. */
public final class ModelTintBuffer implements AutoCloseable {
    public static final int BINDING = 11;
    private final RenderBackend backend;
    private IGpuBuffer buffer;
    private int capacity = 512;

    public ModelTintBuffer(RenderBackend backend) {
        this.backend = backend;
        this.buffer = backend.createBuffer((long)this.capacity * ModelTintData.BYTES_PER_MODEL).zero().name("ModelTintWeights");
    }
    public IGpuBuffer buffer() {
        if (this.buffer == null) throw new IllegalStateException("Tint buffer closed");
        return this.buffer;
    }
    public void upload(int model, byte[] data) {
        if (model<0 || model>=65536 || data.length!=ModelTintData.BYTES_PER_MODEL) throw new IllegalArgumentException("Invalid model tint slot");
        buffer();
        if (model >= this.capacity) {
            UploadStream.INSTANCE.commit();
            this.backend.submit(); // Old uploads and previous draws must finish before replacement.
            int next = Math.min(65536, Integer.highestOneBit(model) << 1);
            IGpuBuffer replacement = this.backend.createBuffer((long)next * ModelTintData.BYTES_PER_MODEL).zero().name("ModelTintWeights");
            try {
                this.backend.copyBufferSubData(this.buffer, replacement, 0, 0, this.buffer.size());
                this.backend.submit();
            } catch (Throwable error) { replacement.free(); throw error; }
            this.buffer.free(); this.buffer = replacement; this.capacity = next;
        }
        long destination = UploadStream.INSTANCE.upload(this.buffer, (long)model * ModelTintData.BYTES_PER_MODEL, data.length);
        for (int i=0; i<data.length; i++) MemoryUtil.memPutByte(destination+i, data[i]);
        UploadStream.INSTANCE.commit();
    }
    @Override public void close() {
        if (this.buffer != null) { this.buffer.free(); this.buffer = null; }
    }
}
