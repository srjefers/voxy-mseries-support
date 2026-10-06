package me.cortex.voxy.client.core.rendering;

import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;

import java.util.ArrayList;
import java.util.Map;

/** Sodium owns uploaded coverage; renderers consume consolidated changes at the frame boundary. */
public final class SectionCoverageTracker {
    public static final SectionCoverageTracker INSTANCE = new SectionCoverageTracker();
    public enum Coverage { NONE, OPAQUE, TRANSLUCENT }
    public enum Delivery { DELTAS, GENERATION_RESET }
    public record Generation(long id) {}
    public record Update(boolean reset, long generation, Map<Long, Coverage> changes) {}

    private final Long2ObjectOpenHashMap<Coverage> coverage = new Long2ObjectOpenHashMap<>();
    private final ArrayList<Subscription> subscribers = new ArrayList<>();
    private Generation generation;
    private long nextGeneration;

    public synchronized Generation beginGeneration() {
        this.generation = new Generation(++this.nextGeneration);
        this.clear();
        return this.generation;
    }

    public synchronized void endGeneration(Generation source) {
        if (source != this.generation) return;
        this.generation = null;
        this.clear();
    }

    private void clear() {
        this.coverage.clear();
        for (var subscriber : this.subscribers) {
            if (subscriber.pending != null) subscriber.pending.clear();
            subscriber.reset = true;
        }
    }

    public synchronized boolean publish(Generation source, long position, Coverage state) {
        if (source == null || source != this.generation) return false;
        if (state == coverageAt(position)) return true;
        if (state == Coverage.NONE) this.coverage.remove(position);
        else this.coverage.put(position, state);
        for (var subscriber : this.subscribers) {
            if (subscriber.pending != null) subscriber.pending.put(position, state);
        }
        return true;
    }

    public synchronized Generation currentGeneration() { return this.generation; }

    public synchronized Coverage coverageAt(long position) {
        return this.coverage.getOrDefault(position, Coverage.NONE);
    }

    public synchronized Subscription subscribe() {
        return this.subscribe(Delivery.DELTAS);
    }

    public synchronized Subscription subscribe(Delivery delivery) {
        var subscription = new Subscription(delivery);
        if (subscription.pending != null) subscription.pending.putAll(this.coverage);
        this.subscribers.add(subscription);
        return subscription;
    }

    public final class Subscription implements AutoCloseable {
        private final Long2ObjectOpenHashMap<Coverage> pending;
        private boolean reset = true;
        private boolean closed;
        private Subscription(Delivery delivery) {
            this.pending = delivery == Delivery.DELTAS ? new Long2ObjectOpenHashMap<>() : null;
        }

        public Update drain() {
            synchronized (SectionCoverageTracker.this) {
                if (this.closed) throw new IllegalStateException("Coverage subscription is closed");
                var update = new Update(this.reset, generation == null ? 0 : generation.id(),
                        this.pending == null ? Map.of() : Map.copyOf(this.pending));
                if (this.pending != null) this.pending.clear();
                this.reset = false;
                return update;
            }
        }

        @Override public void close() {
            synchronized (SectionCoverageTracker.this) {
                if (this.closed) return;
                this.closed = true;
                if (this.pending != null) this.pending.clear();
                subscribers.remove(this);
            }
        }
    }
}
