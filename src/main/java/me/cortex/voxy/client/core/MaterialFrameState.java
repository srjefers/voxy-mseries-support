package me.cortex.voxy.client.core;

/** Renderer-owned publication: only a completed current-generation frame may resolve once. */
public final class MaterialFrameState {
    private final Object generation;
    private int frame;
    private boolean begun, ready, consumed;

    public MaterialFrameState(Object generation) {
        this.generation = generation;
    }

    public boolean begin(Object generation, int frame, boolean shadow) {
        if (shadow || this.generation != generation || (this.begun && this.frame == frame)) return false;
        this.frame = frame;
        this.begun = true;
        this.ready = false;
        this.consumed = false;
        return true;
    }

    public void publish(int frame) {
        if (this.begun && this.frame == frame) this.ready = true;
    }

    public boolean consume(Object generation, int frame) {
        if (this.generation != generation || !this.begun || !this.ready || this.consumed || this.frame != frame) return false;
        this.consumed = true;
        return true;
    }
}
