package dev.ryvrook.restapi.control;

import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Lock;

import static org.junit.jupiter.api.Assertions.*;

class SuiteLockTest {
    @Test
    void theLockIsTheOneInTheSystemProperties() {
        Lock a = SuiteLock.shared();
        assertSame(a, SuiteLock.shared());
        assertSame(a, System.getProperties().get("ryvrook.dependents.lock"));
    }

    @Test
    void anotherHolderMakesUsGoAheadWithoutItAfterTheWait() throws Exception {
        CountDownLatch taken = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        Thread other = new Thread(() -> {
            Lock l = SuiteLock.shared();
            l.lock();
            taken.countDown();
            try {
                release.await(5, TimeUnit.SECONDS);
            } catch (InterruptedException ignored) {
                // Test ends.
            } finally {
                l.unlock();
            }
        });
        other.start();
        assertTrue(taken.await(5, TimeUnit.SECONDS));
        try (SuiteLock.Held h = SuiteLock.acquire(0)) {
            assertFalse(h.held());
        }
        release.countDown();
        other.join(5000);
        try (SuiteLock.Held h = SuiteLock.acquire(1)) {
            assertTrue(h.held());
        }
        try (SuiteLock.Held h = SuiteLock.acquire(0)) {
            assertTrue(h.held(), "close gave the lock back");
        }
    }
}
