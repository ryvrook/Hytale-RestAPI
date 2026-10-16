package dev.ryvrook.restapi.console;

import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;

/**
 * Results kept under the key name and the client's Idempotency-Key, in memory only. The first call for a pair
 * claims it and runs, a later call with the same pair gets the same future, so a retry while the first run still
 * goes waits for it, and a retry after it gets the stored answer. A pair is forgotten
 * commands.idempotencySeconds after its run finished.
 *
 * @param <T> the stored result
 */
public final class Idempotency<T> {
    /** Most pairs kept. Past it the oldest finished ones go first. */
    static final int CAP = 10_000;

    /** What {@link #claim} found. */
    public sealed interface Claim<T> permits Fresh, Existing, Conflict {
    }

    /** Nobody had the pair. The caller runs and completes the future. */
    public record Fresh<T>(CompletableFuture<T> future) implements Claim<T> {
    }

    /** The pair was claimed before for the same command. The future is the first run's. */
    public record Existing<T>(CompletableFuture<T> future) implements Claim<T> {
    }

    /** The pair was claimed before for another command. */
    public record Conflict<T>(String firstCommand) implements Claim<T> {
    }

    private static final class Entry<T> {
        final String command;
        final CompletableFuture<T> future = new CompletableFuture<>();
        final long claimedAt;
        volatile long doneAt = -1;

        Entry(String command, long claimedAt) {
            this.command = command;
            this.claimedAt = claimedAt;
        }
    }

    private final Map<String, Entry<T>> entries = new ConcurrentHashMap<>();
    private final LongSupplier clock;

    public Idempotency(LongSupplier millis) {
        this.clock = millis;
    }

    /**
     * Claims the pair for a command. Keys are compared as given. The command text is compared so that the same
     * key on a different command is refused instead of answered with the wrong result.
     */
    public Claim<T> claim(String keyName, String idempotencyKey, String command, long keepMillis) {
        long now = clock.getAsLong();
        prune(now, keepMillis);
        String id = keyName + "\n" + idempotencyKey;
        Entry<T> made = new Entry<>(command, now);
        Entry<T> had = entries.putIfAbsent(id, made);
        if (had == null) {
            made.future.whenComplete((r, e) -> made.doneAt = clock.getAsLong());
            return new Fresh<>(made.future);
        }
        if (!had.command.equals(command)) {
            return new Conflict<>(had.command);
        }
        return new Existing<>(had.future);
    }

    /** Pairs held now, finished or not. */
    public int size() {
        return entries.size();
    }

    /** Forgets everything, for a stop. Runs still going finish but are not kept. */
    public void clear() {
        entries.clear();
    }

    private void prune(long now, long keepMillis) {
        for (Iterator<Entry<T>> it = entries.values().iterator(); it.hasNext(); ) {
            Entry<T> e = it.next();
            if (e.doneAt >= 0 && now - e.doneAt >= keepMillis) {
                it.remove();
            }
        }
        if (entries.size() >= CAP) {
            entries.entrySet().stream()
                    .filter(x -> x.getValue().doneAt >= 0)
                    .sorted(java.util.Comparator.comparingLong(x -> x.getValue().claimedAt))
                    .limit(entries.size() - CAP + 1L)
                    .map(Map.Entry::getKey)
                    .toList()
                    .forEach(entries::remove);
        }
    }
}
