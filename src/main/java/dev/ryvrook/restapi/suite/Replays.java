package dev.ryvrook.restapi.suite;

import dev.ryvrook.restapi.http.ApiException;
import dev.ryvrook.restapi.http.Response;

import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

/**
 * Keeps the answer to a write under the key name plus its Idempotency-Key header, in memory only, so a retry of a
 * Coinage move gets the first answer with {@code replayed} set and moves nothing. A retry that arrives while the
 * first call still runs waits for it. Answers are dropped after the configured seconds and at a reload of RestAPI.
 */
public final class Replays {
    private record Slot(CompletableFuture<Response> answer, long at) {
    }

    public static final String HEADER = "Idempotency-Key";
    private static final int MAX = 10_000;

    private final Map<String, Slot> slots = new ConcurrentHashMap<>();
    private final LongSupplier clock;

    public Replays(LongSupplier clock) {
        this.clock = clock;
    }

    /**
     * Runs the write once per key name and idempotency key. A null idempotency key runs it every time. The
     * replayed answer carries the header {@code Idempotency-Replayed: true}.
     */
    public Response run(String keyName, String idempotencyKey, long keepMillis, Supplier<Response> write) {
        if (idempotencyKey == null) {
            return write.get();
        }
        if (idempotencyKey.isEmpty() || idempotencyKey.length() > 128
                || !idempotencyKey.chars().allMatch(c -> c > 32 && c < 127)) {
            throw ApiException.badRequest("bad_idempotency_key", SuiteText.get(SuiteText.BAD_IDEMPOTENCY));
        }
        long now = clock.getAsLong();
        prune(now, keepMillis);
        String id = keyName + "\n" + idempotencyKey;
        CompletableFuture<Response> mine = new CompletableFuture<>();
        Slot existing = slots.putIfAbsent(id, new Slot(mine, now));
        if (existing != null && existing.answer().isDone() && now - existing.at() > keepMillis) {
            slots.remove(id, existing);
            return run(keyName, idempotencyKey, keepMillis, write);
        }
        if (existing != null) {
            try {
                return replayed(existing.answer().get());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new ApiException(503, "interrupted", "interrupted");
            } catch (ExecutionException e) {
                // The first call failed before it moved anything, so this one may try again.
                slots.remove(id, existing);
                return run(keyName, idempotencyKey, keepMillis, write);
            }
        }
        try {
            Response r = write.get();
            mine.complete(r);
            return r;
        } catch (ApiException e) {
            // Refused before the ledger was asked, such as a bad amount. Keep the refusal, it is the answer.
            Response r = Response.error(e.status(), e.code(), e.getMessage());
            mine.complete(r);
            throw e;
        } catch (RuntimeException e) {
            slots.remove(id);
            mine.completeExceptionally(e);
            throw e;
        }
    }

    /** The stored answer with {@code "replayed": true} in a JSON object body and the header Idempotency-Replayed. */
    static Response replayed(Response r) {
        Response out = r;
        if (Response.JSON.equals(r.contentType())) {
            try {
                com.google.gson.JsonElement e = com.google.gson.JsonParser.parseString(r.bodyText());
                if (e.isJsonObject()) {
                    e.getAsJsonObject().addProperty("replayed", true);
                    out = Response.json(r.status(), e);
                    for (Map.Entry<String, String> h : r.headers().entrySet()) {
                        out = out.with(h.getKey(), h.getValue());
                    }
                }
            } catch (RuntimeException ignored) {
                // Not JSON after all, sent as stored.
            }
        }
        return out.with("Idempotency-Replayed", "true");
    }

    public int size() {
        return slots.size();
    }

    public void clear() {
        slots.clear();
    }

    private void prune(long now, long keepMillis) {
        if (slots.size() < 256) {
            return;
        }
        for (Iterator<Slot> it = slots.values().iterator(); it.hasNext(); ) {
            Slot s = it.next();
            if (now - s.at() > keepMillis && s.answer().isDone()) {
                it.remove();
            }
        }
        if (slots.size() > MAX) {
            // A flood of distinct keys. Drop the finished ones, oldest first is not worth a sort here.
            slots.values().removeIf(s -> s.answer().isDone());
        }
    }
}
