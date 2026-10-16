package dev.ryvrook.restapi.http;

import dev.ryvrook.restapi.audit.AuditEntry;
import dev.ryvrook.restapi.auth.KeyStore;
import dev.ryvrook.restapi.auth.Scope;
import dev.ryvrook.restapi.config.CoreSettings;
import dev.ryvrook.restapi.config.Settings;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Clock;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

class GateTest {
    @TempDir
    Path dir;
    private KeyStore keys;
    private Router router;
    private Gate gate;
    private final List<AuditEntry> audit = new ArrayList<>();
    private final AtomicLong now = new AtomicLong(0);
    private String reader;
    private String admin;

    @BeforeEach
    void setUp() throws Exception {
        CoreSettings.HTTP_ENABLED.key();
        Settings.useDefaults();
        keys = new KeyStore(dir.resolve("keys.json"));
        reader = keys.create("dash", EnumSet.of(Scope.READ), "test").token();
        admin = keys.create("admin", EnumSet.allOf(Scope.class), "test").token();
        router = new Router();
        router.mount("core", List.of(
                new Route("core", "GET", "/api/health", Scope.READ, Access.READ, () -> true, "health",
                        c -> Response.text(200, c.key() == null ? "anon" : c.key().name())),
                Route.of("core", "GET", "/api/server", Scope.READ, Access.READ, "server", c -> Response.text(200, "server")),
                Route.of("core", "POST", "/api/players/{player}/kick", Scope.PLAYERS, Access.WRITE, "kick",
                        c -> Response.text(200, "kicked " + c.param("player"))),
                Route.of("core", "GET", "/api/boom", Scope.READ, Access.READ, "boom", c -> {
                    throw new IllegalStateException("boom");
                }),
                Route.of("core", "GET", "/api/missing", Scope.READ, Access.READ, "missing", c -> {
                    throw ApiException.notFound("player_not_found", "No such player");
                })));
        gate = new Gate(router, keys, audit::add, Clock.systemUTC(), new RateLimiter(now::get), new RateLimiter(now::get),
                (w, e) -> { });
    }

    private Response get(String path, String token) {
        Request r = Request.of("GET", path, "127.0.0.1");
        return gate.handle(token == null ? r : r.with("Authorization", "Bearer " + token));
    }

    @Test
    void noKeyIs401AndRevealsNoRoutes() {
        Response known = get("/api/server", null);
        Response unknown = get("/api/does-not-exist", null);
        assertEquals(401, known.status());
        assertEquals(401, unknown.status());
        assertEquals(known.bodyText(), unknown.bodyText());
        assertEquals("Bearer realm=\"RestAPI\"", known.headers().get("WWW-Authenticate"));
    }

    @Test
    void theObserverHearsEachAnswerWithItsPatternAndEachKeyFailure() {
        List<String> seen = new ArrayList<>();
        gate.observe(new Gate.Observer() {
            @Override
            public void answered(String method, String pattern, int status) {
                seen.add(method + " " + pattern + " " + status);
            }

            @Override
            public void authFailed() {
                seen.add("auth failed");
            }
        });
        Request kick = Request.of("POST", "/api/players/Steve/kick", "127.0.0.1").with("Authorization", "Bearer " + admin);
        gate.handle(kick);
        get("/api/nowhere", reader);
        get("/api/server", null);
        assertEquals(List.of("POST /api/players/{player}/kick 200", "GET null 404", "auth failed",
                "GET /api/server 401"), seen);
        gate.observe(null);
        get("/api/server", reader);
        assertEquals(4, seen.size());
    }

    @Test
    void aValidKeyReachesTheHandler() {
        Response r = get("/api/server", reader);
        assertEquals(200, r.status());
        assertEquals("server", r.bodyText());
        assertEquals("no-store", r.headers().get("Cache-Control"));
    }

    @Test
    void xApiKeyWorksUnlessTurnedOff() {
        Request r = Request.of("GET", "/api/server", "127.0.0.1").with("X-API-Key", reader);
        assertEquals(200, gate.handle(r).status());
        Settings.override(CoreSettings.KEYS_ACCEPT_X_API_KEY, false);
        assertEquals(401, gate.handle(r).status());
    }

    @Test
    void openRoutesAnswerWithoutAKeyAndStillSeeOne() {
        assertEquals("anon", get("/api/health", null).bodyText());
        assertEquals("dash", get("/api/health", reader).bodyText());
        assertEquals(401, get("/api/health", "rapi_00000000_" + "a".repeat(43)).status());
    }

    @Test
    void aMissingScopeIs403AndAudited() {
        Request r = Request.of("POST", "/api/players/Steve/kick", "127.0.0.1").with("Authorization", "Bearer " + reader);
        Response res = gate.handle(r);
        assertEquals(403, res.status());
        assertTrue(res.bodyText().contains("missing_scope"));
        assertEquals("missing scope players", audit.getLast().detail());
    }

    @Test
    void writesAreAuditedWithTheKeyName() {
        Request r = Request.of("POST", "/api/players/Steve/kick", "10.1.2.3").with("Authorization", "Bearer " + admin);
        assertEquals("kicked Steve", gate.handle(r).bodyText());
        AuditEntry e = audit.getLast();
        assertEquals("admin", e.key());
        assertEquals("10.1.2.3", e.client());
        assertEquals("POST /api/players/Steve/kick", e.action());
        assertEquals(200, e.status());
    }

    @Test
    void readsAreNotAuditedUnlessAsked() {
        get("/api/server", reader);
        assertTrue(audit.isEmpty());
        Settings.override(CoreSettings.AUDIT_READS, true);
        get("/api/server", reader);
        assertEquals(1, audit.size());
    }

    @Test
    void wrongKeysAreLimitedPerAddress() {
        Settings.override(CoreSettings.LIMITS_AUTH_FAILURES, 3);
        String wrong = "rapi_12345678_" + "b".repeat(43);
        for (int i = 0; i < 3; i++) {
            assertEquals(401, get("/api/server", wrong).status());
        }
        Response limited = get("/api/server", wrong);
        assertEquals(429, limited.status());
        assertNotNull(limited.headers().get("Retry-After"));
        assertEquals("wrong key", audit.getLast().detail());
    }

    @Test
    void eachKeyHasItsOwnRequestLimit() {
        Settings.override(CoreSettings.LIMITS_PER_MINUTE, 2);
        assertEquals(200, get("/api/server", reader).status());
        assertEquals(200, get("/api/server", reader).status());
        assertEquals(429, get("/api/server", reader).status());
        assertEquals(200, get("/api/server", admin).status());
    }

    @Test
    void handlerErrorsBecomeJsonAnswers() {
        Response boom = get("/api/boom", reader);
        assertEquals(500, boom.status());
        assertFalse(boom.bodyText().contains("boom"), "no exception text reaches the client");
        Response missing = get("/api/missing", reader);
        assertEquals(404, missing.status());
        assertTrue(missing.bodyText().contains("player_not_found"));
    }

    @Test
    void theBasePathIsStripped() {
        Settings.override(CoreSettings.HTTP_BASE_PATH, "/restapi/");
        assertEquals(200, get("/restapi/api/server", reader).status());
        assertEquals(404, get("/api/server", reader).status());
        assertEquals("/", Gate.stripBase("/restapi", "/restapi"));
        assertNull(Gate.stripBase("/restapiX/api", "/restapi"));
    }

    @Test
    void wrongMethodIs405OnlyForAKey() {
        Response r = gate.handle(Request.of("DELETE", "/api/server", "127.0.0.1").with("Authorization", "Bearer " + reader));
        assertEquals(405, r.status());
        assertEquals("GET", r.headers().get("Allow"));
    }

    @Test
    void forwardedForIsBelievedOnlyFromTrustedProxies() {
        Request r = Request.of("POST", "/api/players/Steve/kick", "127.0.0.1")
                .with("Authorization", "Bearer " + admin).with("X-Forwarded-For", "203.0.113.9, 10.0.0.5");
        gate.handle(r);
        assertEquals("127.0.0.1", audit.getLast().client());
        Settings.override(CoreSettings.HTTP_TRUSTED_PROXIES, List.of("127.0.0.1", "10.0.0.0/8"));
        gate.handle(r);
        assertEquals("203.0.113.9", audit.getLast().client());
    }

    @Test
    void allowFromLimitsWhereAKeyWorks() throws Exception {
        String json = java.nio.file.Files.readString(dir.resolve("keys.json"))
                .replaceFirst("\"allowFrom\": \\[]", "\"allowFrom\": [\"192.168.1.0/24\"]");
        java.nio.file.Files.writeString(dir.resolve("keys.json"), json);
        keys.load();
        String first = keys.list().stream().filter(k -> !k.allowFrom().isEmpty()).findFirst().orElseThrow().name();
        String token = first.equals("dash") ? reader : admin;
        Request r = Request.of("GET", "/api/server", "192.168.1.20").with("Authorization", "Bearer " + token);
        assertEquals(200, gate.handle(r).status());
        Request away = Request.of("GET", "/api/server", "192.168.2.20").with("Authorization", "Bearer " + token);
        assertEquals(403, gate.handle(away).status());
    }

    @Test
    void corsHeadersOnlyForListedOrigins() {
        Request pre = Request.of("OPTIONS", "/api/server", "127.0.0.1").with("Origin", "https://panel.example")
                .with("Access-Control-Request-Method", "GET");
        assertEquals(403, gate.handle(pre).status());
        Settings.override(CoreSettings.HTTP_CORS_ORIGINS, List.of("https://panel.example"));
        Response ok = gate.handle(pre);
        assertEquals(204, ok.status());
        assertEquals("https://panel.example", ok.headers().get("Access-Control-Allow-Origin"));
        Response plain = get("/api/server", reader);
        assertNull(plain.headers().get("Access-Control-Allow-Origin"));
    }

    @Test
    void clientWalksForwardedForFromTheRight() {
        var trusted = dev.ryvrook.restapi.util.AddressRanges.parse(List.of("10.0.0.0/8"));
        assertEquals("198.51.100.7", Gate.client(trusted, "10.0.0.1", List.of("198.51.100.7, 10.0.0.2")));
        assertEquals("10.0.0.2", Gate.client(trusted, "10.0.0.1", List.of("10.0.0.2")));
        assertEquals("203.0.113.1", Gate.client(trusted, "203.0.113.1", List.of("1.2.3.4")));
        assertEquals("10.0.0.2", Gate.client(trusted, "10.0.0.1", List.of("not-an-ip, 10.0.0.2")));
    }
}
