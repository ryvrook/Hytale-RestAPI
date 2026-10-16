package dev.ryvrook.restapi.siblings;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Lock;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

/**
 * RestAPI reloading itself after a bound sibling came back with new classes and that sibling has no dependents pass
 * of its own, SocialBridge 0.2.0 today. Runs on a daemon thread of its own, since the scheduler is shut down by the
 * reload it asks for. It waits a few seconds so the sibling finishes its start, takes the suite lock that every
 * dependents pass holds, and reloads only when the plugin manager still holds the instance that asked. When a pass
 * reloaded RestAPI meanwhile, as TrackerAPI's does after it reloaded SocialBridge, nothing is done a second time.
 */
public final class SelfReload {
    public enum Outcome { RELOADED, SKIPPED, FAILED }

    static final long DELAY_MILLIS = 3_000;
    static final long LOCK_SECONDS = 30;
    static final long SETTLE_MILLIS = 15_000;

    private SelfReload() {
    }

    /** Reloads under the lock when {@code current} still answers {@code self}. */
    public static Outcome run(Supplier<Object> current, Object self, BooleanSupplier reload, Lock lock)
            throws InterruptedException {
        boolean held = lock.tryLock(LOCK_SECONDS, TimeUnit.SECONDS);
        try {
            if (current.get() != self) {
                return Outcome.SKIPPED;
            }
            if (reload.getAsBoolean()) {
                return Outcome.RELOADED;
            }
            // The manager answers false when it finishes the reload on a future, wait for the new instance.
            long until = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(SETTLE_MILLIS);
            while (current.get() == self) {
                if (System.nanoTime() > until) {
                    return Outcome.FAILED;
                }
                Thread.sleep(100);
            }
            return Outcome.RELOADED;
        } finally {
            if (held) {
                lock.unlock();
            }
        }
    }

    /**
     * Starts the thread. Everything it needs is built before it sleeps, a reload in the meantime leaves this class
     * loader unable to load classes it has not loaded yet. {@code failed} runs when the reload did not happen.
     */
    public static void schedule(Supplier<Object> current, Object self, BooleanSupplier reload, Lock lock,
                                Runnable failed) {
        Outcome done = Outcome.FAILED;
        Thread t = new Thread(() -> {
            try {
                Thread.sleep(DELAY_MILLIS);
                if (run(current, self, reload, lock) == done) {
                    failed.run();
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (RuntimeException | LinkageError e) {
                failed.run();
            }
        }, "RestAPI-relink");
        t.setDaemon(true);
        t.start();
    }
}
