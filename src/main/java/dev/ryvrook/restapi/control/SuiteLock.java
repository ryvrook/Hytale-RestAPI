package dev.ryvrook.restapi.control;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

/**
 * The lock every reloader of the suite takes, from the reload rule in TrackerAPI's docs/DEVELOPMENT.md. It is a
 * ReentrantLock stored under ryvrook.dependents.lock in System.getProperties(), the one object every plugin class
 * loader sees. TrackerAPI and Coinage hold it while they reload their dependents, RestAPI holds it while a plugin
 * route loads, unloads or reloads a plugin, so the two never run into each other.
 */
public final class SuiteLock {
    private SuiteLock() {
    }

    public static final String KEY = "ryvrook.dependents.lock";

    /** The shared lock, made on first use by whichever plugin gets there first. */
    public static Lock shared() {
        Object o = System.getProperties().computeIfAbsent(KEY, k -> new ReentrantLock());
        return o instanceof Lock l ? l : new ReentrantLock();
    }

    /**
     * Waits up to the given seconds. The result says whether the lock was taken. Going ahead without it after the
     * wait is what TrackerAPI does too, a stuck reloader elsewhere must not block an admin forever.
     */
    public static Held acquire(long seconds) throws InterruptedException {
        Lock lock = shared();
        boolean held = lock.tryLock(Math.max(0, seconds), TimeUnit.SECONDS);
        return new Held(lock, held);
    }

    /** The outcome of {@link #acquire}. close() gives the lock back when it was taken. */
    public record Held(Lock lock, boolean held) implements AutoCloseable {
        @Override
        public void close() {
            if (held) {
                lock.unlock();
            }
        }
    }
}
