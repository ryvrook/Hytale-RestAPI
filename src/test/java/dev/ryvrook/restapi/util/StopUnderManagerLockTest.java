package dev.ryvrook.restapi.util;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The plugin manager holds the write side of its lock for a whole server stop and calls RestAPI's shutdown under
 * it. A sibling check that looked a plugin up then parked on the read side while it held RestAPI's monitor.
 */
class StopUnderManagerLockTest {
    private static final List<String> PLUGINS = List.of("Ryvrook:TrackerAPI", "Ryvrook:Atlas");

    /** A look that takes the read lock like PluginManager.getPlugins does. */
    private static Supplier<List<String>> engineLike(ReentrantReadWriteLock rw) {
        return () -> {
            rw.readLock().lock();
            try {
                return PLUGINS;
            } finally {
                rw.readLock().unlock();
            }
        };
    }

    /** Holds the write lock on another thread, as the engine's stop does, until the returned latch counts down. */
    private static CountDownLatch holdWrite(ReentrantReadWriteLock rw) throws InterruptedException {
        CountDownLatch held = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        Thread t = new Thread(() -> {
            rw.writeLock().lock();
            try {
                held.countDown();
                release.await();
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            } finally {
                rw.writeLock().unlock();
            }
        }, "manager-stop");
        t.setDaemon(true);
        t.start();
        assertTrue(held.await(5, TimeUnit.SECONDS));
        return release;
    }

    @Test
    void aLookupUnderTheWriteLockAnswersTheLastStateAtOnce() throws Exception {
        ReentrantReadWriteLock rw = new ReentrantReadWriteLock();
        GatedLookup gate = new GatedLookup(() -> rw, () -> false);
        assertSame(PLUGINS, gate.read("plugins", engineLike(rw), List.of()));
        CountDownLatch release = holdWrite(rw);
        try {
            long t0 = System.nanoTime();
            assertSame(PLUGINS, gate.read("plugins", engineLike(rw), List.of()), "the last answer, so no group drops");
            assertEquals("none", gate.read("plugin Ryvrook:Feats", () -> "seen", "none"), "nothing known yet is none");
            assertTrue(System.nanoTime() - t0 < TimeUnit.MILLISECONDS.toNanos(200), "never waits for the manager");
        } finally {
            release.countDown();
        }
    }

    @Test
    void theOwnerOfTheWriteLockStillLooksUp() {
        ReentrantReadWriteLock rw = new ReentrantReadWriteLock();
        GatedLookup gate = new GatedLookup(() -> rw, () -> false);
        rw.writeLock().lock();
        try {
            assertSame(PLUGINS, gate.read("plugins", engineLike(rw), List.of()));
        } finally {
            rw.writeLock().unlock();
        }
    }

    @Test
    void aStoppingServerGetsTheLastAnswerWithoutALook() {
        ReentrantReadWriteLock rw = new ReentrantReadWriteLock();
        AtomicBoolean stopping = new AtomicBoolean();
        GatedLookup gate = new GatedLookup(() -> rw, stopping::get);
        assertSame(PLUGINS, gate.read("plugins", engineLike(rw), List.of()));
        stopping.set(true);
        assertSame(PLUGINS, gate.read("plugins", () -> {
            throw new AssertionError("looked while stopping");
        }, List.of()));
    }

    @Test
    void withoutTheLockFieldEveryLookGoesThrough() {
        GatedLookup gate = new GatedLookup(() -> null, () -> false);
        assertEquals("a", gate.read("k", () -> "a", "none"));
        assertEquals("none", gate.read("k", () -> null, "none"));
    }

    @Test
    void stopIsBoundedEvenWithAStuckSchedulerTask() throws Exception {
        ReentrantReadWriteLock rw = new ReentrantReadWriteLock();
        ScheduledThreadPoolExecutor scheduler = new ScheduledThreadPoolExecutor(2, r -> {
            Thread t = new Thread(r, "RestAPI-scheduler-test");
            t.setDaemon(true);
            return t;
        });
        CountDownLatch release = holdWrite(rw);
        CountDownLatch entered = new CountDownLatch(1);
        AtomicReference<Thread> stuck = new AtomicReference<>();
        // An ungated engine look, the old behaviour, parks uninterruptibly on the read lock.
        scheduler.execute(() -> {
            stuck.set(Thread.currentThread());
            entered.countDown();
            engineLike(rw).get();
        });
        assertTrue(entered.await(5, TimeUnit.SECONDS));
        try {
            long t0 = System.nanoTime();
            assertFalse(Pools.stop(scheduler), "the stuck task is left behind");
            long ms = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - t0);
            assertTrue(ms < 2_000, "stop took " + ms + " ms");
        } finally {
            release.countDown();
        }
        stuck.get().join(5_000);
        assertEquals(Thread.State.TERMINATED, stuck.get().getState());
    }
}
