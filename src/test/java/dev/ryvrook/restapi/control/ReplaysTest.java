package dev.ryvrook.restapi.control;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.ryvrook.restapi.auth.ApiKey;
import dev.ryvrook.restapi.auth.KeyStore;
import dev.ryvrook.restapi.auth.Scope;
import dev.ryvrook.restapi.http.ApiException;
import dev.ryvrook.restapi.http.Call;
import dev.ryvrook.restapi.http.Request;
import dev.ryvrook.restapi.http.Response;
import dev.ryvrook.restapi.http.Route;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.EnumSet;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

class ReplaysTest {
    @TempDir
    Path dir;
    private ApiKey a;
    private ApiKey b;
    private final AtomicLong now = new AtomicLong(1_000_000);
    private final AtomicInteger runs = new AtomicInteger();
    private Replays replays;
    private Route.Handler handler;

    @BeforeEach
    void setUp() throws Exception {
        KeyStore keys = new KeyStore(dir.resolve("keys.json"));
        keys.create("ops", EnumSet.of(Scope.PLUGINS), "test");
        keys.create("ci", EnumSet.of(Scope.PLUGINS), "test");
        a = keys.list().stream().filter(k -> k.name().equals("ops")).findFirst().orElseThrow();
        b = keys.list().stream().filter(k -> k.name().equals("ci")).findFirst().orElseThrow();
        replays = new Replays(now::get, () -> 600);
        handler = replays.wrap(call -> {
            JsonObject o = new JsonObject();
            o.addProperty("run", runs.incrementAndGet());
            return Response.json(200, o);
        });
    }

    private Call call(ApiKey key, String idem) {
        Request r = Request.of("POST", "/api/plugins/Ryvrook:Lullaby/reload", "127.0.0.1");
        if (idem != null) {
            r = r.with("Idempotency-Key", idem);
        }
        return new Call(r, key, "127.0.0.1", Map.of());
    }

    private static JsonObject body(Response r) {
        return JsonParser.parseString(r.bodyText()).getAsJsonObject();
    }

    @Test
    void aRetryWithTheSameKeyRunsNothing() throws Exception {
        Response first = handler.handle(call(a, "k1"));
        Response again = handler.handle(call(a, "k1"));
        assertEquals(1, runs.get());
        assertEquals(1, body(again).get("run").getAsInt());
        assertTrue(body(again).get("replayed").getAsBoolean());
        assertFalse(body(first).has("replayed"));
        assertEquals("true", again.headers().get("Idempotent-Replayed"));
    }

    @Test
    void anotherKeyNameOrIdempotencyKeyRunsAgain() throws Exception {
        handler.handle(call(a, "k1"));
        handler.handle(call(b, "k1"));
        handler.handle(call(a, "k2"));
        assertEquals(3, runs.get());
    }

    @Test
    void noHeaderRunsEveryTime() throws Exception {
        handler.handle(call(a, null));
        handler.handle(call(a, null));
        handler.handle(call(a, " "));
        assertEquals(3, runs.get());
    }

    @Test
    void anAnswerExpires() throws Exception {
        handler.handle(call(a, "k1"));
        now.addAndGet(601_000);
        handler.handle(call(a, "k1"));
        assertEquals(2, runs.get());
    }

    @Test
    void zeroSecondsKeepsNothing() throws Exception {
        Replays off = new Replays(now::get, () -> 0);
        Route.Handler h = off.wrap(c -> Response.json(200, new JsonObject()));
        h.handle(call(a, "k1"));
        h.handle(call(a, "k1"));
        assertEquals(0, off.size());
    }

    @Test
    void anErrorIsKeptAndRepeated() {
        AtomicInteger n = new AtomicInteger();
        Route.Handler h = replays.wrap(c -> {
            n.incrementAndGet();
            throw new ApiException(409, "plugin_refused", "no");
        });
        assertEquals("plugin_refused", assertThrows(ApiException.class, () -> h.handle(call(a, "e"))).code());
        assertEquals("plugin_refused", assertThrows(ApiException.class, () -> h.handle(call(a, "e"))).code());
        assertEquals(1, n.get());
    }

    @Test
    void aLongKeyIsRefused() {
        ApiException e = assertThrows(ApiException.class, () -> handler.handle(call(a, "x".repeat(201))));
        assertEquals("bad_idempotency_key", e.code());
    }

    @Test
    void aRetryWhileTheFirstRunsWaitsForIt() throws Exception {
        CountDownLatch inside = new CountDownLatch(1);
        CountDownLatch go = new CountDownLatch(1);
        AtomicInteger n = new AtomicInteger();
        Route.Handler slow = replays.wrap(c -> {
            n.incrementAndGet();
            inside.countDown();
            go.await(5, TimeUnit.SECONDS);
            return Response.json(200, new JsonObject());
        });
        CompletableFuture<Response> first = CompletableFuture.supplyAsync(() -> {
            try {
                return slow.handle(call(a, "w"));
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });
        assertTrue(inside.await(5, TimeUnit.SECONDS));
        CompletableFuture<Response> second = CompletableFuture.supplyAsync(() -> {
            try {
                return slow.handle(call(a, "w"));
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });
        Thread.sleep(100);
        assertFalse(second.isDone());
        go.countDown();
        assertEquals(200, first.get(5, TimeUnit.SECONDS).status());
        assertTrue(body(second.get(5, TimeUnit.SECONDS)).get("replayed").getAsBoolean());
        assertEquals(1, n.get());
    }
}
