package dev.ryvrook.restapi.console;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.hypixel.hytale.server.core.Message;
import dev.ryvrook.restapi.auth.ApiKey;
import dev.ryvrook.restapi.auth.Scope;
import dev.ryvrook.restapi.config.Settings;
import dev.ryvrook.restapi.http.Access;
import dev.ryvrook.restapi.http.ApiException;
import dev.ryvrook.restapi.http.Call;
import dev.ryvrook.restapi.http.Request;
import dev.ryvrook.restapi.http.Response;
import dev.ryvrook.restapi.http.Route;
import dev.ryvrook.restapi.http.Sse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;

import static org.junit.jupiter.api.Assertions.*;

class ConsoleRoutesTest {
    private final FakeEngine engine = new FakeEngine();
    private final ApiKey key = new ApiKey("admin", "1a2b3c4d", "x", EnumSet.allOf(Scope.class), "now", "test", -1, List.of());
    private CommandRunner runner;
    private LogRing ring;
    private Map<String, Route> routes;

    @BeforeEach
    void setUp() {
        ConsoleSettings.COMMANDS_ENABLED.key();
        Settings.useDefaults();
        Settings.override(ConsoleSettings.COMMANDS_GRACE_MS, 50);
        runner = new CommandRunner(engine, e -> { }, System::currentTimeMillis, Message::getRawText);
        ring = new LogRing(100);
        JsonArray list = new JsonArray();
        list.add("help");
        routes = new LinkedHashMap<>();
        for (Route r : new ConsoleRoutes(runner, ring, () -> list).routes()) {
            routes.put(r.method() + " " + r.pattern(), r);
        }
    }

    private Response call(String method, String pattern, String path, String body, Map<String, String> headers,
                          Map<String, String> params, String client) throws Exception {
        int q = path.indexOf('?');
        Map<String, List<String>> h = new LinkedHashMap<>();
        headers.forEach((k, v) -> h.put(k, List.of(v)));
        Request req = new Request(method, q < 0 ? path : path.substring(0, q), q < 0 ? null : path.substring(q + 1), h,
                body == null ? null : body.getBytes(StandardCharsets.UTF_8), client);
        Route r = routes.get(method + " " + pattern);
        assertNotNull(r, method + " " + pattern);
        try {
            return r.handler().handle(new Call(req, key, client, params));
        } catch (ApiException e) {
            return Response.error(e.status(), e.code(), e.getMessage());
        }
    }

    private Response post(String body, Map<String, String> headers) throws Exception {
        return call("POST", "/api/commands", "/api/commands", body, headers, Map.of(), "127.0.0.1");
    }

    private static JsonObject json(Response r) {
        return JsonParser.parseString(r.bodyText()).getAsJsonObject();
    }

    @Test
    void everyRouteSitsInTheConsoleGroupWithTheRightScope() {
        assertEquals(7, routes.size());
        for (Route r : routes.values()) {
            assertEquals("console", r.group());
            assertFalse(r.summary().contains(";"), r.summary());
        }
        assertEquals(Scope.COMMANDS, routes.get("POST /api/commands").scope());
        assertEquals(Access.DANGEROUS, routes.get("POST /api/commands").access());
        assertEquals(Access.DANGEROUS, routes.get("POST /api/commands/batch").access());
        assertEquals(Scope.READ, routes.get("GET /api/commands/list").scope());
        assertEquals(Scope.CONSOLE, routes.get("GET /api/console/stream").scope());
        assertEquals(2, new ConsoleRoutes(null, ring, JsonArray::new).routes().size());
        assertEquals(5, new ConsoleRoutes(runner, null, JsonArray::new).routes().size());
    }

    @Test
    void aCommandAnswersWithItsOutput() throws Exception {
        Response r = post("{\"command\": \"say hello\"}", Map.of());
        assertEquals(200, r.status());
        JsonObject o = json(r);
        assertEquals("done", o.get("outcome").getAsString());
        assertEquals("[RestAPI:admin] hello", o.getAsJsonArray("output").get(0).getAsString());
        assertFalse(o.get("replayed").getAsBoolean());

        Response kept = call("GET", "/api/commands/runs/{runId}", "/api/commands/runs/x", null, Map.of(),
                Map.of("runId", o.get("runId").getAsString()), "127.0.0.1");
        assertEquals(200, kept.status());
        assertEquals("say hello", json(kept).get("command").getAsString());
        assertEquals(404, call("GET", "/api/commands/runs/{runId}", "/x", null, Map.of(), Map.of("runId", "rnope"), "127.0.0.1").status());
    }

    @Test
    void aReplayIsMarkedAndRunsNothing() throws Exception {
        Map<String, String> h = Map.of("Idempotency-Key", "abc-123");
        JsonObject first = json(post("{\"command\": \"help\"}", h));
        JsonObject again = json(post("{\"command\": \"help\"}", h));
        assertTrue(again.get("replayed").getAsBoolean());
        assertEquals(first.get("runId"), again.get("runId"));
        assertEquals(1, engine.ran.size());
        Response conflict = post("{\"command\": \"say other\"}", h);
        assertEquals(409, conflict.status());
        assertEquals("idempotency_conflict", json(conflict).get("error").getAsString());
    }

    @Test
    void deniedTimedOutAndBadRequests() throws Exception {
        Response denied = post("{\"command\": \"stop\"}", Map.of());
        assertEquals(403, denied.status());
        assertEquals("command_denied", json(denied).get("error").getAsString());
        assertEquals("denied", json(denied).get("outcome").getAsString());

        assertEquals(202, post("{\"command\": \"slow\", \"timeoutMs\": 150}", Map.of()).status());
        assertEquals("invalid_timeout", json(post("{\"command\": \"help\", \"timeoutMs\": 5}", Map.of())).get("error").getAsString());
        assertEquals("invalid_timeout", json(post("{\"command\": \"help\", \"timeoutMs\": 999999999}", Map.of())).get("error").getAsString());
        assertEquals("invalid_command", json(post("{\"command\": \"\"}", Map.of())).get("error").getAsString());
        assertEquals("invalid_command", json(post("{\"cmd\": \"help\"}", Map.of())).get("error").getAsString());
        assertEquals("invalid_json", json(post("[1]", Map.of())).get("error").getAsString());
        assertEquals("invalid_idempotency_key", json(post("{\"command\": \"help\"}", Map.of("Idempotency-Key", "has space"))).get("error").getAsString());
        Settings.override(ConsoleSettings.COMMANDS_REQUIRE_IDEMPOTENCY, true);
        assertEquals("idempotency_key_required", json(post("{\"command\": \"help\"}", Map.of())).get("error").getAsString());

        Response far = call("POST", "/api/commands", "/api/commands", "{\"command\": \"help\"}", Map.of(), Map.of(), "203.0.113.9");
        assertEquals(403, far.status());
        assertEquals("address_not_allowed", json(far).get("error").getAsString());
    }

    @Test
    void aBatchRunsInOrderAndCanStopOnError() throws Exception {
        Response r = call("POST", "/api/commands/batch", "/api/commands/batch",
                "{\"commands\": [\"say one\", \"stop\", \"say three\"], \"stopOnError\": true}", Map.of("Idempotency-Key", "b1"),
                Map.of(), "127.0.0.1");
        JsonObject o = json(r);
        assertEquals(2, o.getAsJsonArray("results").size());
        assertTrue(o.get("stopped").getAsBoolean());
        assertEquals(List.of("say one"), engine.ran);
        Response all = call("POST", "/api/commands/batch", "/api/commands/batch",
                "{\"commands\": [\"say one\", \"stop\", \"say three\"]}", Map.of(), Map.of(), "127.0.0.1");
        assertEquals(3, json(all).getAsJsonArray("results").size());
        Settings.override(ConsoleSettings.COMMANDS_BATCH_MAX, 2);
        assertEquals("batch_too_long", json(call("POST", "/api/commands/batch", "/b", "{\"commands\": [\"a\", \"b\", \"c\"]}",
                Map.of(), Map.of(), "127.0.0.1")).get("error").getAsString());
        assertEquals("batch_empty", json(call("POST", "/api/commands/batch", "/b", "{\"commands\": []}",
                Map.of(), Map.of(), "127.0.0.1")).get("error").getAsString());
    }

    @Test
    void theDenyListAndTheCommandList() throws Exception {
        JsonObject deny = json(call("GET", "/api/commands/deny", "/x", null, Map.of(), Map.of(), "127.0.0.1"));
        assertTrue(deny.getAsJsonArray("fixed").toString().contains("stop"));
        assertEquals(5, deny.getAsJsonArray("configured").size());
        JsonObject list = json(call("GET", "/api/commands/list", "/x", null, Map.of(), Map.of(), "127.0.0.1"));
        assertEquals("help", list.getAsJsonArray("commands").get(0).getAsString());
    }

    @Test
    void theConsoleTailFiltersByLevelAndSince() throws Exception {
        ring.add(1_000, Level.INFO, "S", "booting", null);
        ring.add(2_000, Level.WARNING, "S", "careful", null);
        ring.add(3_000, Level.ALL, "S", "[RestAPI:admin] hi", null);
        JsonObject all = json(call("GET", "/api/console", "/api/console", null, Map.of(), Map.of(), "127.0.0.1"));
        assertEquals(3, all.getAsJsonArray("lines").size());
        assertEquals(3, all.get("lastId").getAsLong());
        JsonObject warn = json(call("GET", "/api/console", "/api/console?level=warning", null, Map.of(), Map.of(), "127.0.0.1"));
        assertEquals(1, warn.getAsJsonArray("lines").size());
        JsonObject since = json(call("GET", "/api/console", "/api/console?since=1&lines=1", null, Map.of(), Map.of(), "127.0.0.1"));
        assertEquals("RAW", since.getAsJsonArray("lines").get(0).getAsJsonObject().get("level").getAsString());
        JsonObject time = json(call("GET", "/api/console", "/api/console?since=1970-01-01T00:00:02Z", null, Map.of(), Map.of(), "127.0.0.1"));
        assertEquals(2, time.getAsJsonArray("lines").size());
        assertEquals("invalid_level", json(call("GET", "/api/console", "/api/console?level=loud", null, Map.of(), Map.of(), "127.0.0.1")).get("error").getAsString());
        assertEquals("invalid_since", json(call("GET", "/api/console", "/api/console?since=yesterday", null, Map.of(), Map.of(), "127.0.0.1")).get("error").getAsString());
        assertEquals("invalid_lines", json(call("GET", "/api/console", "/api/console?lines=0", null, Map.of(), Map.of(), "127.0.0.1")).get("error").getAsString());
    }

    /** A sink that records events and fails once the test says the client left. */
    static final class Recorder implements Sse.Sink {
        final List<String> events = new java.util.concurrent.CopyOnWriteArrayList<>();
        volatile int comments;

        @Override
        public void event(String name, String id, String data) {
            events.add(name + " " + id + " " + JsonParser.parseString(data).getAsJsonObject().get("message").getAsString());
        }

        @Override
        public void comment(String text) {
            comments++;
        }
    }

    @Test
    void theStreamSendsKeptLinesThenNewOnesAndEndsWhenTheRingCloses() throws Exception {
        Settings.override(ConsoleSettings.CONSOLE_KEEP_ALIVE_SECONDS, 1);
        ring.add(1, Level.INFO, "S", "booted", null);
        ring.add(2, Level.FINE, "S", "detail", null);
        Response r = call("GET", "/api/console/stream", "/api/console/stream?level=INFO&lines=10", null, Map.of(), Map.of(), "127.0.0.1");
        assertNotNull(r.stream());
        Recorder rec = new Recorder();
        CompletableFuture<Void> running = CompletableFuture.runAsync(() -> {
            try {
                r.stream().run(rec);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });
        Thread.sleep(100);
        ring.add(3, Level.INFO, "S", "[RestAPI:admin] new line", null);
        ring.add(4, Level.FINE, "S", "filtered", null);
        Thread.sleep(1_300);
        ring.close();
        running.get(3, TimeUnit.SECONDS);
        assertEquals(List.of("log 1 booted", "log 3 [RestAPI:admin] new line"), rec.events);
        assertTrue(rec.comments >= 1, "a keep alive was sent");
    }

    @Test
    void theStreamResumesAfterLastEventId() throws Exception {
        for (int i = 1; i <= 4; i++) {
            ring.add(i, Level.INFO, "S", "l" + i, null);
        }
        Response r = call("GET", "/api/console/stream", "/api/console/stream", null, Map.of("Last-Event-ID", "2"), Map.of(), "127.0.0.1");
        Recorder rec = new Recorder();
        ring.close();
        r.stream().run(rec);
        assertEquals(List.of(), rec.events, "a closed ring sends nothing");
        ring.open();
        CompletableFuture<Void> running = CompletableFuture.runAsync(() -> {
            try {
                r.stream().run(rec);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });
        Thread.sleep(150);
        ring.close();
        running.get(3, TimeUnit.SECONDS);
        assertEquals(List.of("log 3 l3", "log 4 l4"), rec.events);
    }

    @Test
    void aClientThatLeavesEndsTheStream() throws Exception {
        ring.add(1, Level.INFO, "S", "x", null);
        Response r = call("GET", "/api/console/stream", "/api/console/stream", null, Map.of(), Map.of(), "127.0.0.1");
        Sse.Sink gone = new Sse.Sink() {
            @Override
            public void event(String name, String id, String data) throws IOException {
                throw new IOException("closed");
            }

            @Override
            public void comment(String text) throws IOException {
                throw new IOException("closed");
            }
        };
        assertThrows(IOException.class, () -> r.stream().run(gone));
    }
}
