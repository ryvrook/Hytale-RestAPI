package dev.ryvrook.restapi.util;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

/**
 * A plugin manager read that never waits for the manager. The manager answers {@code getPlugin} and
 * {@code getPlugins} under the read side of its lock and holds the write side for a whole server stop and for every
 * {@code /plugin reload}, calling RestAPI's shutdown under it. A sibling check that asked then parked on the lock
 * while it held RestAPI's own monitor, and the shutdown waited for that monitor. So the read lock is only tried.
 * While the manager holds the write lock, and always once the server is stopping, the last answer for that key is
 * given again, or {@code none} when there is none yet. The next look after the manager let go sees the real state.
 * Holds no engine class, so the tests run it without a server. Safe from any thread.
 */
public final class GatedLookup {
    private static final Object NULL = new Object();

    private final Supplier<ReadWriteLock> lock;
    private final BooleanSupplier stopping;
    private final Map<String, Object> last = new ConcurrentHashMap<>();

    /**
     * {@code lock} gives the manager's lock, or null when it is not known, and then every look goes through.
     * {@code stopping} is true once the server stops, and then no look is made.
     */
    public GatedLookup(Supplier<ReadWriteLock> lock, BooleanSupplier stopping) {
        this.lock = lock;
        this.stopping = stopping;
    }

    /** The answer of {@code look}, or the last one under {@code key} when the manager is busy. */
    @SuppressWarnings("unchecked")
    public <T> T read(String key, Supplier<T> look, T none) {
        if (stopped()) {
            return lastOr(key, none);
        }
        ReadWriteLock rw;
        try {
            rw = lock == null ? null : lock.get();
        } catch (RuntimeException | LinkageError e) {
            rw = null;
        }
        if (rw == null) {
            return remember(key, look.get(), none);
        }
        if (!rw.readLock().tryLock()) {
            return lastOr(key, none);
        }
        try {
            return remember(key, look.get(), none);
        } finally {
            rw.readLock().unlock();
        }
    }

    private boolean stopped() {
        try {
            return stopping != null && stopping.getAsBoolean();
        } catch (RuntimeException | LinkageError e) {
            return false;
        }
    }

    @SuppressWarnings("unchecked")
    private <T> T lastOr(String key, T none) {
        Object o = last.get(key);
        return o == null || o == NULL ? none : (T) o;
    }

    private <T> T remember(String key, T found, T none) {
        last.put(key, found == null ? NULL : found);
        return found == null ? none : found;
    }
}
