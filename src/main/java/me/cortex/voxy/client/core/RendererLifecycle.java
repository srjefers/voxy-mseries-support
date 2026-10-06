package me.cortex.voxy.client.core;

import me.cortex.voxy.common.util.ResourceCleanup;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.function.Consumer;

/** A generation owns all acquisitions, including partial construction; GPU release requires quiescence. */
final class RendererLifecycle implements AutoCloseable {
    enum Phase { DETACH, DOWNLOAD, NODE_STOP, MESH_STOP, BAKERY_STOP, UPLOAD, RELEASE, FINALIZE, WORLD }
    private final EnumMap<Phase, ArrayList<Runnable>> releases = new EnumMap<>(Phase.class);

    void on(Phase phase, Runnable action) {
        this.releases.computeIfAbsent(phase, ignored -> new ArrayList<>()).add(action);
    }
    <T> T own(T value, Phase phase, Consumer<T> release) {
        this.on(phase, () -> release.accept(value));
        return value;
    }
    @Override public void close() {
        for (var phase : Phase.values()) {
            var actions = this.releases.get(phase);
            if (actions == null) continue;
            // A failed quiescence/wait must never authorize dependent GPU frees.
            if (phase.ordinal() < Phase.RELEASE.ordinal()) {
                while (!actions.isEmpty()) {
                    actions.getFirst().run();
                    actions.removeFirst();
                }
                this.releases.remove(phase);
            } else {
                var remaining = new ArrayList<Runnable>();
                for (var later : Phase.values()) {
                    if (later.ordinal() < phase.ordinal()) continue;
                    var next = this.releases.remove(later);
                    if (next != null) {
                        if (later == Phase.RELEASE) java.util.Collections.reverse(next);
                        remaining.addAll(next);
                    }
                }
                ResourceCleanup.run(remaining.toArray(Runnable[]::new));
                return;
            }
        }
    }
}
