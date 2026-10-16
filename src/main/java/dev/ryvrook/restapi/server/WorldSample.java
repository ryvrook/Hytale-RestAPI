package dev.ryvrook.restapi.server;

/**
 * One world as its own thread saw it at the last sample.
 *
 * @param name        world name
 * @param players     players in it
 * @param targetTps   the tick rate it aims for, 30 on 0.7.1
 * @param tps         ticks per second over the last 10 seconds as /world perf counts them, -1 before the first sample
 * @param tickAvgMs   average tick length over the last 10 seconds in milliseconds
 * @param tickMaxMs   longest tick over the last 10 seconds in milliseconds
 * @param chunks      loaded chunks
 * @param entities    entities in its entity store
 * @param sampledAt   epoch millis of the sample, 0 before the first
 * @param waitingSince epoch millis when the sample now pending was posted, 0 when none is pending
 */
public record WorldSample(String name, int players, int targetTps, double tps, double tickAvgMs, double tickMaxMs,
                          int chunks, int entities, long sampledAt, long waitingSince) {
    /** True when a posted sample has waited longer than limitMs, so the world thread is stuck or very slow. */
    public boolean stalled(long now, long limitMs) {
        return waitingSince > 0 && now - waitingSince > limitMs;
    }

    WorldSample waiting(long since) {
        return new WorldSample(name, players, targetTps, tps, tickAvgMs, tickMaxMs, chunks, entities, sampledAt, since);
    }

    static WorldSample empty(String name) {
        return new WorldSample(name, 0, 0, -1, 0, 0, 0, 0, 0, 0);
    }

    /**
     * Ticks per second the way the engine's /world perf works it out, 1e9 divided by the larger of the average
     * tick length and the tick step, both in nanos. -1 without data.
     */
    static double tps(double avgNanos, long stepNanos) {
        if (!(avgNanos > 0)) {
            return -1;
        }
        return 1_000_000_000.0 / Math.max(avgNanos, stepNanos);
    }
}
