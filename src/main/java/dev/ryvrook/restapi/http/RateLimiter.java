package dev.ryvrook.restapi.http;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;

/**
 * A token bucket per name, such as a key id or an address. A bucket holds one minute of requests and refills
 * all the time, so a dashboard that fires ten requests at once is fine as long as its average stays under the
 * limit. The same idea as the Atlas limiter, here keyed by key id for requests and by address for failed logins.
 */
public final class RateLimiter {
    /** Buckets kept before idle ones are dropped. */
    static final int PRUNE_AT = 10_000;
    private static final long IDLE_MS = 2 * 60_000L;

    private static final class Bucket {
        double tokens;
        long last;
    }

    private final Map<String, Bucket> buckets = new ConcurrentHashMap<>();
    private final LongSupplier clock;
    private volatile long prunedAt;

    public RateLimiter() {
        this(System::currentTimeMillis);
    }

    public RateLimiter(LongSupplier clock) {
        this.clock = clock;
    }

    /**
     * Takes one request from the bucket. Returns 0 when it is allowed, else the whole seconds until the next one
     * would be, at least 1. A limit of 0 or less lets everything through.
     */
    public long acquire(String name, int perMinute) {
        if (perMinute <= 0) {
            return 0;
        }
        long now = clock.getAsLong();
        double perMs = perMinute / 60_000.0;
        long[] wait = new long[1];
        buckets.compute(name, (k, b) -> {
            if (b == null) {
                b = new Bucket();
                b.tokens = perMinute;
            } else {
                b.tokens = Math.min(perMinute, b.tokens + Math.max(0, now - b.last) * perMs);
            }
            b.last = now;
            if (b.tokens >= 1) {
                b.tokens -= 1;
            } else {
                wait[0] = Math.max(1, (long) Math.ceil((1 - b.tokens) / perMs / 1000.0));
            }
            return b;
        });
        if (buckets.size() > PRUNE_AT && now - prunedAt >= 1_000) {
            prunedAt = now;
            prune();
        }
        return wait[0];
    }

    /** Drops buckets idle for two minutes, which are full again by then anyway. */
    public void prune() {
        long now = clock.getAsLong();
        buckets.entrySet().removeIf(e -> now - e.getValue().last > IDLE_MS);
    }

    int size() {
        return buckets.size();
    }
}
