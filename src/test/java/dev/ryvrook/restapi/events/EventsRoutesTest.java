package dev.ryvrook.restapi.events;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.ryvrook.restapi.auth.ApiKey;
import dev.ryvrook.restapi.auth.Scope;
import dev.ryvrook.restapi.config.Settings;
import dev.ryvrook.restapi.http.ApiException;
import dev.ryvrook.restapi.http.Call;
import dev.ryvrook.restapi.http.Request;
import dev.ryvrook.restapi.http.Response;
import dev.ryvrook.restapi.http.Route;
import dev.ryvrook.restapi.http.Sse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class EventsRoutesTest {
    static final long BASE = EventRing.idBase(1_760_600_000_000L);
    static final ApiKey KEY = new ApiKey("ops", "0a0b0c0d", "x", Set.of(Scope.EVENTS), "now", "test", -1, List.of());

    @TempDir
    Path dir;
    EventRing ring;

    @BeforeEach
    void setUp() {
        EventsSettings.ENABLED.key();
        Settings.useDefaults();
        ring = new EventRing(100, BASE, e -> fail(e));
    }

    static Route route(List<Route> routes, String method, String pattern) {
        return routes.stream().filter(r -> r.method().equals(method) && r.pattern().equals(pattern)).findFirst().orElseThrow();
    }

    static Call call(Request req, Map<String, String> params) {
        return new Call(req, KEY, "127.0.0.1", params);
    }

    static Request post(String path, String body) {
        return new Request("POST", path, null, Map.of(), body.getBytes(StandardCharsets.UTF_8), "127.0.0.1");
    }

    static String code(Executable e) {
        ApiException ex = assertThrows(ApiException.class, e::run);
        return ex.code();
    }

    interface Executable {
        void run() throws Exception;
    }

    @Test
    void recentFiltersLimitsAndChecksItsInput() throws Exception {
        ring.publish("join", null, 0, EngineEvents.player("Steve", null));
        ring.publish("chat", null, 0, null);
        ring.publish("join", null, 0, EngineEvents.player("Alex", null));
        Route r = route(new EventRoutes(ring).routes(), "GET", "/api/events/recent");
        JsonObject o = JsonParser.parseString(r.handler().handle(call(Request.of("GET", "/api/events/recent?types=join&limit=1", "x"), Map.of()))
                .bodyText()).getAsJsonObject();
        assertEquals(1, o.getAsJsonArray("events").size());
        assertEquals("Alex", o.getAsJsonArray("events").get(0).getAsJsonObject().get("player").getAsString());
        assertEquals(BASE + 3, o.get("nextId").getAsLong());

        JsonObject since = JsonParser.parseString(r.handler().handle(call(Request.of("GET", "/api/events/recent?since=" + BASE, "x"), Map.of()))
                .bodyText()).getAsJsonObject();
        assertEquals(2, since.getAsJsonArray("events").size());
        assertFalse(since.get("gap").getAsBoolean());

        assertEquals("bad_limit", code(() -> r.handler().handle(call(Request.of("GET", "/api/events/recent?limit=0", "x"), Map.of()))));
        assertEquals("bad_limit", code(() -> r.handler().handle(call(Request.of("GET", "/api/events/recent?limit=lots", "x"), Map.of()))));
        assertEquals("unknown_type", code(() -> r.handler().handle(call(Request.of("GET", "/api/events/recent?types=jion", "x"), Map.of()))));
        assertEquals("bad_since", code(() -> r.handler().handle(call(Request.of("GET", "/api/events/recent?since=x", "x"), Map.of()))));
    }

    @Test
    void theStreamResumesFollowsAndEndsWhenTheModuleStops() throws Exception {
        ring.publish("join", null, 0, null);
        ring.publish("chat", null, 0, null);
        Route r = route(new EventRoutes(ring).routes(), "GET", "/api/events");
        Request req = Request.of("GET", "/api/events?types=chat,leave", "x").with("Last-Event-ID", Long.toString(BASE - 5));
        Response resp = r.handler().handle(call(req, Map.of()));
        assertNotNull(resp.stream());
        List<String> seen = Collections.synchronizedList(new ArrayList<>());
        Thread t = Thread.ofVirtual().start(() -> {
            try {
                resp.stream().run(new Sse.Sink() {
                    @Override
                    public void event(String name, String id, String data) {
                        seen.add(name + "|" + id);
                    }

                    @Override
                    public void comment(String text) {
                        seen.add(":");
                    }
                });
            } catch (Exception e) {
                seen.add("failed " + e);
            }
        });
        WebhooksTest.waitFor(() -> ring.streams() == 1);
        ring.publish("join", null, 0, null);
        ring.publish("leave", null, 0, null);
        WebhooksTest.waitFor(() -> seen.contains("leave|" + (BASE + 3)));
        ring.closeAll();
        t.join(5000);
        assertFalse(t.isAlive());
        assertEquals(List.of(":", "gap|null", "chat|" + (BASE + 1), "leave|" + (BASE + 3)), seen);
    }

    private Webhooks hooks(byte[] address) {
        return new Webhooks(dir.resolve("webhooks.json"), (u, h, b, t) -> new Webhooks.Result(204, null, 3),
                host -> new InetAddress[]{InetAddress.getByAddress(host, address)}, ms -> { }, (a, b) -> { });
    }

    @Test
    void webhooksAreMadeListedTestedAndRemoved() throws Exception {
        Webhooks w = hooks(new byte[]{93, (byte) 184, (byte) 215, 14});
        List<Route> routes = new WebhookRoutes(w, (a, b) -> { }).routes();
        Route create = route(routes, "POST", "/api/webhooks");
        Response made = create.handler().handle(call(post("/api/webhooks",
                "{\"url\":\"https://discord.com/api/webhooks/123456789012345678901/abcdefghijklmnopqrstuvwxyz\",\"types\":[\"join\"],\"format\":\"discord\"}"),
                Map.of()));
        assertEquals(201, made.status());
        JsonObject m = JsonParser.parseString(made.bodyText()).getAsJsonObject();
        String id = m.get("id").getAsString();
        assertEquals(64, m.get("secret").getAsString().length(), "a secret is made and shown once");
        assertTrue(Files.readString(dir.resolve("webhooks.json")).contains(m.get("secret").getAsString()));

        String listed = route(routes, "GET", "/api/webhooks").handler().handle(call(Request.of("GET", "/api/webhooks", "x"), Map.of())).bodyText();
        assertFalse(listed.contains(m.get("secret").getAsString()));
        assertTrue(listed.contains("https://discord.com/api/webhooks/***/***"));
        assertTrue(listed.contains("\"secretSet\":true"));

        JsonObject tested = JsonParser.parseString(route(routes, "POST", "/api/webhooks/{id}/test").handler()
                .handle(call(post("/x", ""), Map.of("id", id))).bodyText()).getAsJsonObject();
        assertTrue(tested.get("ok").getAsBoolean());
        assertEquals(204, tested.get("status").getAsInt());

        Route delete = route(routes, "DELETE", "/api/webhooks/{id}");
        assertEquals(204, delete.handler().handle(call(Request.of("DELETE", "/x", "x"), Map.of("id", id))).status());
        assertEquals("webhook_not_found", code(() -> delete.handler().handle(call(Request.of("DELETE", "/x", "x"), Map.of("id", id)))));
    }

    @Test
    void webhookInputIsChecked() {
        Route create = route(new WebhookRoutes(hooks(new byte[]{93, (byte) 184, (byte) 215, 14}), (a, b) -> { }).routes(),
                "POST", "/api/webhooks");
        assertEquals("invalid_url", code(() -> create.handler().handle(call(post("/", "{\"url\":\"ftp://x/y\"}"), Map.of()))));
        assertEquals("invalid_url", code(() -> create.handler().handle(call(post("/", "{}"), Map.of()))));
        assertEquals("unknown_type", code(() -> create.handler().handle(call(post("/",
                "{\"url\":\"https://example.com\",\"types\":[\"jion\"]}"), Map.of()))));
        assertEquals("invalid_format", code(() -> create.handler().handle(call(post("/",
                "{\"url\":\"https://example.com\",\"format\":\"slack\"}"), Map.of()))));
        assertEquals("invalid_secret", code(() -> create.handler().handle(call(post("/",
                "{\"url\":\"https://example.com\",\"secret\":\"short\"}"), Map.of()))));
        assertEquals("invalid_json", code(() -> create.handler().handle(call(post("/", "[1]"), Map.of()))));

        Route lan = route(new WebhookRoutes(hooks(new byte[]{10, 0, 0, 7}), (a, b) -> { }).routes(), "POST", "/api/webhooks");
        assertEquals("private_target", code(() -> lan.handler().handle(call(post("/", "{\"url\":\"http://nas.local/hook\"}"), Map.of()))));
    }

    @Test
    void theWebhookCapHolds() throws Exception {
        Settings.override(EventsSettings.WEBHOOKS_MAX, 1);
        Route create = route(new WebhookRoutes(hooks(new byte[]{93, (byte) 184, (byte) 215, 14}), (a, b) -> { }).routes(),
                "POST", "/api/webhooks");
        JsonObject ok = JsonParser.parseString(create.handler().handle(call(post("/",
                "{\"url\":\"https://example.com/a\",\"secret\":null}"), Map.of())).bodyText()).getAsJsonObject();
        assertFalse(ok.has("secret"));
        assertFalse(ok.get("secretSet").getAsBoolean());
        ApiException e = assertThrows(ApiException.class, () -> create.handler().handle(call(post("/",
                "{\"url\":\"https://example.com/b\"}"), Map.of())));
        assertEquals(409, e.status());
        assertEquals("too_many_webhooks", e.code());
    }
}
