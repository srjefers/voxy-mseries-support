package me.cortex.voxy.client.core;

/** Render-thread sampling budget: at most two readbacks per second for one minute. */
final class DiagnosticWindow {
    private final boolean enabled;
    private boolean started;
    private long start;
    private long last;

    DiagnosticWindow(boolean enabled) { this.enabled = enabled; }

    boolean shouldSample(long now) {
        if (!this.enabled) return false;
        if (!this.started) {
            this.started = true;
            this.start = this.last = now;
            return true;
        }
        if (now - this.start >= 60_000_000_000L || now - this.last < 500_000_000L) return false;
        this.last = now;
        return true;
    }
}
