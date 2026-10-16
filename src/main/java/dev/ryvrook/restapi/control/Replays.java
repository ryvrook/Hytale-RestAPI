package dev.ryvrook.restapi.control;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.ryvrook.restapi.http.ApiException;
import dev.ryvrook.restapi.http.Call;
import dev.ryvrook.restapi.http.Response;
import dev.ryvrook.restapi.http.Route;
import dev.ryvrook.restapi.text.Messages;

import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.function.IntSupplier;
import java.util.function.LongSupplier;

/**
 * Idempotency for the dangerous control routes, such as a plugin reload or a stop. The first call with an
 * Idempotency-Key header runs and its answer is kept under the key name plus that header for
 * control.idempotencySeconds. A retry with the same pair gets the kept answer with replayed true and runs
 * nothing, a retry while the first call still runs waits for it. Kept in memory only, a server restart forgets
 * them. Without the header the route runs every time.
 */
final class Replays {
    static final String HEADER = "idempotency-key";
    static final int MAX_KEY_LENGTH = 200;

    private record Entry(CompletableFuture<Response> answer, long expires) {
    }

    private final Map<String, Entry> kept = new ConcurrentHashMap<>();
    private final LongSupplier clock;
    private final IntSupplier seconds;

    Replays(LongSupplier clock, IntSupplier seconds) {
        this.clock = clock;
        this.seconds = seconds;
    }

    /** The handler with idempotency around it. */
    Route.Handler wrap(Route.Handler handler) {
        return call -> run(call, handler);
    }

    Response run(Call call, Route.Handler handler) throws Exception {
        String idem = call.request().header(HEADER);
        int keep = seconds.getAsInt();
        if (idem == null || idem.isBlank() || keep <= 0) {
            return handler.handle(call);
        }
        if (idem.length() > MAX_KEY_LENGTH) {
            throw ApiException.badRequest("bad_idempotency_key",
                    Messages.get(ControlText.IDEMPOTENCY_KEY, "max", MAX_KEY_LENGTH));
        }
        long now = clock.getAsLong();
        prune(now);
        String slot = call.keyName() + "\n" + call.request().method() + " " + call.request().path() + "\n" + idem.trim();
        CompletableFuture<Response> mine = new CompletableFuture<>();
        Entry before = kept.putIfAbsent(slot, new Entry(mine, now + keep * 1000L));
        if (before != null && before.expires > now) {
            return replayed(before.answer);
        }
        if (before != null) {
            kept.put(slot, new Entry(mine, now + keep * 1000L));
        }
        try {
            Response r = handler.handle(call);
            mine.complete(r);
            return r;
        } catch (Exception | Error e) {
            mine.completeExceptionally(e);
            throw e;
        }
    }

    private static Response replayed(CompletableFuture<Response> answer) throws Exception {
        Response r;
        try {
            r = answer.get();
        } catch (ExecutionException e) {
            if (e.getCause() instanceof Exception x) {
                throw x;
            }
            throw (Error) e.getCause();
        }
        Response out = r;
        try {
            JsonElement e = JsonParser.parseString(r.bodyText());
            if (e.isJsonObject()) {
                JsonObject o = e.getAsJsonObject().deepCopy();
                o.addProperty("replayed", true);
                out = Response.json(r.status(), o);
                for (Map.Entry<String, String> h : r.headers().entrySet()) {
                    out = out.with(h.getKey(), h.getValue());
                }
            }
        } catch (RuntimeException ignored) {
            // Not JSON, the header below still says it.
        }
        return out.with("Idempotent-Replayed", "true");
    }

    private void prune(long now) {
        for (Iterator<Entry> it = kept.values().iterator(); it.hasNext(); ) {
            Entry e = it.next();
            if (e.expires <= now && e.answer.isDone()) {
                it.remove();
            }
        }
    }

    int size() {
        return kept.size();
    }
}
