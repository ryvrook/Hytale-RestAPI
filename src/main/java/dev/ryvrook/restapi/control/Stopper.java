package dev.ryvrook.restapi.control;

import com.google.gson.JsonObject;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.LongSupplier;

/**
 * The stop countdown behind POST /api/server/stop. One stop can be pending. With announce on the players hear the
 * time left at the start and at the marks in {@link #MARKS} that fall inside the countdown, then the fire callback
 * runs the shutdown. A cancel removes every task that has not run yet.
 *
 * <p>The tasks live on RestAPI's scheduler. A /restapi reload keeps them, a reload or unload of the plugin itself
 * drops them along with the scheduler, so the stop does not happen then.
 */
final class Stopper {
    /** Seconds before the stop at which players hear the time left. */
    static final int[] MARKS = {3600, 1800, 900, 600, 300, 120, 60, 30, 10, 5, 4, 3, 2, 1};

    /** One planned stop. */
    record Plan(String id, long atMillis, int delaySeconds, int exitCode, boolean restart, String reason,
                boolean announce, String key, String client) {
        JsonObject json(long now) {
            JsonObject o = new JsonObject();
            o.addProperty("stop", id);
            o.addProperty("at", Instant.ofEpochMilli(atMillis).toString());
            o.addProperty("secondsLeft", Math.max(0, (atMillis - now + 999) / 1000));
            o.addProperty("exitCode", exitCode);
            o.addProperty("restart", restart);
            o.addProperty("reason", reason);
            o.addProperty("announce", announce);
            o.addProperty("key", key);
            return o;
        }
    }

    /** What the countdown tells the outside world. */
    interface Hooks {
        /** Tell the players how long is left, in seconds. 0 means now. */
        void announce(Plan plan, int secondsLeft);

        /** Run the shutdown. Called once, off the scheduler's own threads is the caller's job. */
        void fire(Plan plan);
    }

    private final ScheduledExecutorService scheduler;
    private final LongSupplier clock;
    private final Hooks hooks;
    private Plan pending;
    private final List<ScheduledFuture<?>> tasks = new ArrayList<>();

    Stopper(ScheduledExecutorService scheduler, LongSupplier clock, Hooks hooks) {
        this.scheduler = scheduler;
        this.clock = clock;
        this.hooks = hooks;
    }

    /** The marks below the delay, largest first. A 5 second countdown gives 4, 3, 2 and 1. */
    static List<Integer> marks(int delaySeconds) {
        List<Integer> out = new ArrayList<>();
        for (int m : MARKS) {
            if (m < delaySeconds) {
                out.add(m);
            }
        }
        return out;
    }

    /**
     * Plans a stop, or returns null when one is already pending. A delay of 0 fires at once on the scheduler.
     */
    synchronized Plan schedule(int delaySeconds, int exitCode, boolean restart, String reason, boolean announce,
                               String key, String client) {
        if (pending != null) {
            return null;
        }
        long now = clock.getAsLong();
        Plan plan = new Plan(newId(), now + delaySeconds * 1000L, delaySeconds, exitCode, restart, reason, announce,
                key, client);
        pending = plan;
        if (announce && delaySeconds > 0) {
            tasks.add(scheduler.schedule(() -> hooks.announce(plan, delaySeconds), 0, TimeUnit.MILLISECONDS));
            for (int m : marks(delaySeconds)) {
                tasks.add(scheduler.schedule(() -> hooks.announce(plan, m), (delaySeconds - m) * 1000L, TimeUnit.MILLISECONDS));
            }
        }
        tasks.add(scheduler.schedule(() -> run(plan), delaySeconds * 1000L, TimeUnit.MILLISECONDS));
        return plan;
    }

    private void run(Plan plan) {
        synchronized (this) {
            if (pending != plan) {
                return;
            }
            tasks.clear();
        }
        if (plan.announce()) {
            hooks.announce(plan, 0);
        }
        hooks.fire(plan);
    }

    /** Cancels the pending stop and returns it, or null when none was pending or it already fired. */
    synchronized Plan cancel() {
        Plan p = pending;
        if (p == null || clock.getAsLong() >= p.atMillis() && tasks.isEmpty()) {
            return null;
        }
        for (ScheduledFuture<?> t : tasks) {
            t.cancel(false);
        }
        tasks.clear();
        pending = null;
        return p;
    }

    synchronized Plan pending() {
        return pending;
    }

    private static String newId() {
        byte[] b = new byte[4];
        ThreadLocalRandom.current().nextBytes(b);
        return "stop-" + HexFormat.of().formatHex(b);
    }
}
