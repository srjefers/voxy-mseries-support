package me.cortex.voxy.common.util;

/** Attempts independent releases, retaining the original failure and every later failure. */
public final class ResourceCleanup {
    private ResourceCleanup() {}
    public static void run(Runnable... releases) {
        Throwable failure = null;
        for (var release : releases) {
            try { release.run(); }
            catch (Throwable error) {
                if (failure == null) failure = error;
                else if (failure != error) failure.addSuppressed(error);
            }
        }
        if (failure instanceof Error error) throw error;
        if (failure instanceof RuntimeException error) throw error;
        if (failure != null) throw new IllegalStateException("Resource cleanup failed", failure);
    }

    /** Joining must establish quiescence before restoring the caller's interrupt status. */
    public static void join(Thread thread) {
        boolean interrupted = false;
        while (thread.isAlive()) {
            try { thread.join(); }
            catch (InterruptedException error) { interrupted = true; }
        }
        if (interrupted) Thread.currentThread().interrupt();
    }
}
