package me.cortex.voxy.common.util;

import java.util.ArrayList;
import java.util.Collections;
import java.util.function.Consumer;

/** Acquired resources unwind once in reverse order, including partial construction and failed releases. */
public final class ResourceScope implements AutoCloseable {
    private final ArrayList<Runnable> releases = new ArrayList<>();

    public <T> T own(T resource, Consumer<? super T> release) {
        this.releases.add(() -> release.accept(resource));
        return resource;
    }
    public void rollback(Throwable failure) {
        try { this.close(); }
        catch (Throwable cleanup) { if (cleanup != failure) failure.addSuppressed(cleanup); }
    }
    @Override public void close() {
        var actions = new ArrayList<>(this.releases);
        this.releases.clear();
        Collections.reverse(actions);
        ResourceCleanup.run(actions.toArray(Runnable[]::new));
    }
}
