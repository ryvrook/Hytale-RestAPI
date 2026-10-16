package dev.ryvrook.restapi.util;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;

/** Bounded stops for RestAPI's own pools, so a stuck task never holds up the server stop. */
public final class Pools {
    /** How long a stop lets a running task finish before it interrupts it. */
    public static final long GRACE_MILLIS = 1_000;
    /** How long a stop waits for an interrupted task. */
    public static final long INTERRUPT_MILLIS = 500;

    private Pools() {
    }

    /**
     * Refuses new work, lets running tasks finish for up to {@link #GRACE_MILLIS}, then interrupts them and waits
     * {@link #INTERRUPT_MILLIS} more. A task still running then is left behind. Returns true when the pool ended.
     */
    public static boolean stop(ExecutorService ex) {
        return stop(ex, GRACE_MILLIS, INTERRUPT_MILLIS);
    }

    public static boolean stop(ExecutorService ex, long graceMillis, long interruptMillis) {
        if (ex == null) {
            return true;
        }
        ex.shutdown();
        try {
            if (ex.awaitTermination(graceMillis, TimeUnit.MILLISECONDS)) {
                return true;
            }
            ex.shutdownNow();
            return ex.awaitTermination(interruptMillis, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            ex.shutdownNow();
            Thread.currentThread().interrupt();
            return ex.isTerminated();
        }
    }
}
