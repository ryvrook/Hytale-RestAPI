package dev.ryvrook.restapi.http;

import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

class RateLimiterTest {
    @Test
    void aFullBucketAllowsOneMinuteOfRequestsAtOnce() {
        AtomicLong now = new AtomicLong(1_000_000);
        RateLimiter l = new RateLimiter(now::get);
        for (int i = 0; i < 60; i++) {
            assertEquals(0, l.acquire("k", 60), "request " + i);
        }
        assertEquals(1, l.acquire("k", 60));
    }

    @Test
    void theBucketRefillsOverTime() {
        AtomicLong now = new AtomicLong(0);
        RateLimiter l = new RateLimiter(now::get);
        for (int i = 0; i < 6; i++) {
            l.acquire("k", 6);
        }
        assertEquals(10, l.acquire("k", 6));
        now.addAndGet(10_000);
        assertEquals(0, l.acquire("k", 6));
    }

    @Test
    void namesHaveTheirOwnBuckets() {
        AtomicLong now = new AtomicLong(0);
        RateLimiter l = new RateLimiter(now::get);
        assertEquals(0, l.acquire("a", 1));
        assertTrue(l.acquire("a", 1) > 0);
        assertEquals(0, l.acquire("b", 1));
    }

    @Test
    void zeroMeansNoLimit() {
        RateLimiter l = new RateLimiter(() -> 0);
        for (int i = 0; i < 1000; i++) {
            assertEquals(0, l.acquire("k", 0));
        }
        assertEquals(0, l.size());
    }

    @Test
    void idleBucketsArePruned() {
        AtomicLong now = new AtomicLong(0);
        RateLimiter l = new RateLimiter(now::get);
        l.acquire("a", 10);
        now.addAndGet(3 * 60_000);
        l.prune();
        assertEquals(0, l.size());
    }
}
