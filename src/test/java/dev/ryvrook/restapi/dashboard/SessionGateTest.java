package dev.ryvrook.restapi.dashboard;

import dev.ryvrook.restapi.audit.AuditEntry;
import dev.ryvrook.restapi.auth.KeyStore;
import dev.ryvrook.restapi.auth.Scope;
import dev.ryvrook.restapi.config.CoreSettings;
import dev.ryvrook.restapi.config.Settings;
import dev.ryvrook.restapi.http.Access;
import dev.ryvrook.restapi.http.Gate;
import dev.ryvrook.restapi.http.RateLimiter;
import dev.ryvrook.restapi.http.Request;
import dev.ryvrook.restapi.http.Response;
import dev.ryvrook.restapi.http.Route;
import dev.ryvrook.restapi.http.Router;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Clock;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

/** The session cookie as credentials in the gate, next to keys. */
class SessionGateTest {
    @TempDir
    Path dir;
    private final AtomicLong now = new AtomicLong(1_000_000);
    private final AtomicBoolean writes = new AtomicBoolean(false);
    private final java.util.concurrent.atomic.AtomicInteger perMinute = new java.util.concurrent.atomic.AtomicInteger(0);
    private final List<AuditEntry> audit = new ArrayList<>();
    private final List<String> kicked = new ArrayList<>();
    private SessionStore sessions;
    private Gate gate;
    private String reader;
    private String cookie;

    @BeforeEach
    void setUp() throws Exception {
        CoreSettings.HTTP_ENABLED.key();
        DashboardSettings.ENABLED.key();
        Settings.useDefaults();
        KeyStore keys = new KeyStore(dir.resolve("keys.json"));
        reader = keys.create("grafana", EnumSet.of(Scope.READ), "test").token();
        Router router = new Router();
        router.mount("core", List.of(
                new Route("core", "GET", "/api/health", Scope.READ, Access.READ, () -> true, "health",
                        c -> Response.text(200, c.key() == null ? "anon" : c.key().name())),
                Route.of("core", "GET", "/api/server", Scope.READ, Access.READ, "server",
                        c -> Response.text(200, c.keyName())),
                Route.of("core", "POST", "/api/players/{player}/kick", Scope.PLAYERS, Access.WRITE, "kick", c -> {
                    kicked.add(c.param("player") + " by " + c.keyName());
                    return Response.text(200, "kicked");
                }),
                new Route("core", "POST", "/dashboard/logout", Scope.READ, Access.WRITE, () -> true, "logout",
                        c -> Response.text(200, "bye " + c.keyName()))));
        sessions = new SessionStore(now::get, () -> 3600_000L, () -> 600_000L, writes::get, perMinute::get);
        gate = new Gate(router, keys, audit::add, Clock.systemUTC(), new RateLimiter(now::get), new RateLimiter(now::get),
                (w, e) -> { });
        gate.credentials(sessions);
        cookie = sessions.redeem(sessions.issue("Steve")).cookie();
    }

    private Request req(String method, String path) {
        return new Request(method, path, null, java.util.Map.of(), null, "127.0.0.1")
                .with("Host", "127.0.0.1:8125")
                .with("Cookie", "restapi_session=" + cookie);
    }

    @Test
    void sameSiteRequestsCarryTheSessionUnderItsOwnName() {
        assertEquals("dashboard:Steve", gate.handle(req("GET", "/api/server").with("Sec-Fetch-Site", "same-origin")).bodyText());
        assertEquals("dashboard:Steve", gate.handle(req("GET", "/api/server").with("Sec-Fetch-Site", "none")).bodyText());
        assertEquals("dashboard:Steve", gate.handle(req("GET", "/api/server").with("Origin", "http://127.0.0.1:8125")).bodyText());
        assertEquals("dashboard:Steve", gate.handle(req("GET", "/api/server")
                .with("Referer", "http://127.0.0.1:8125/dashboard/#/overview")).bodyText());
    }

    @Test
    void crossSiteRequestsWithTheCookieAre403EvenForGet() {
        for (Request r : List.of(
                req("GET", "/api/server").with("Sec-Fetch-Site", "cross-site"),
                req("GET", "/api/server").with("Sec-Fetch-Site", "same-site"),
                req("GET", "/api/server").with("Origin", "http://evil.example"),
                req("GET", "/api/server").with("Origin", "null"),
                req("GET", "/api/server").with("Referer", "http://127.0.0.1:9999/x"),
                req("GET", "/api/server"),
                req("POST", "/api/players/Steve/kick").with("Sec-Fetch-Site", "cross-site"),
                req("GET", "/api/health").with("Sec-Fetch-Site", "cross-site"))) {
            Response res = gate.handle(r);
            assertEquals(403, res.status(), r.toString());
            assertTrue(res.bodyText().contains("cross_site"));
        }
        assertTrue(kicked.isEmpty());
        assertTrue(audit.stream().anyMatch(e -> e.status() == 403 && e.key().equals("dashboard:Steve")
                && e.detail().contains("another site")));
    }

    @Test
    void withoutWritesASessionMayOnlyGet() {
        Response r = gate.handle(req("POST", "/api/players/Alex/kick").with("Sec-Fetch-Site", "same-origin"));
        assertEquals(403, r.status());
        assertTrue(r.bodyText().contains("read_only"));
        assertTrue(kicked.isEmpty());
        assertEquals(403, gate.handle(req("PUT", "/api/server").with("Sec-Fetch-Site", "same-origin")).status(), "PUT is refused too");
        assertEquals(403, gate.handle(req("DELETE", "/api/server").with("Sec-Fetch-Site", "same-origin")).status());
        // Logout is open and stays possible while read only.
        assertEquals("bye dashboard:Steve", gate.handle(req("POST", "/dashboard/logout").with("Sec-Fetch-Site", "same-origin")).bodyText());

        writes.set(true);
        assertEquals(200, gate.handle(req("POST", "/api/players/Alex/kick").with("Sec-Fetch-Site", "same-origin")).status());
        assertEquals(List.of("Alex by dashboard:Steve"), kicked);
        assertTrue(audit.stream().anyMatch(e -> e.key().equals("dashboard:Steve") && e.action().equals("POST /api/players/Alex/kick")
                && e.status() == 200));
    }

    @Test
    void aBearerKeyInTheSameRequestWinsOverTheCookie() {
        Request r = req("GET", "/api/server").with("Authorization", "Bearer " + reader).with("Sec-Fetch-Site", "cross-site");
        assertEquals("grafana", gate.handle(r).bodyText());
        Request w = req("POST", "/api/players/Alex/kick").with("Authorization", "Bearer " + reader);
        Response res = gate.handle(w);
        assertEquals(403, res.status());
        assertTrue(res.bodyText().contains("missing_scope"), "the key decides, not the session");
    }

    @Test
    void anEndedSessionAsksForALoginAndCountsAsAFailure() {
        sessions.end(cookie);
        Response r = gate.handle(req("GET", "/api/server").with("Sec-Fetch-Site", "same-origin"));
        assertEquals(401, r.status());
        assertTrue(r.bodyText().contains("login_required"));
        // Open routes still answer, without the session.
        assertEquals("anon", gate.handle(req("GET", "/api/health").with("Sec-Fetch-Site", "same-origin")).bodyText());
        for (int i = 0; i < 9; i++) {
            gate.handle(req("GET", "/api/server").with("Sec-Fetch-Site", "same-origin"));
        }
        assertEquals(429, gate.handle(req("GET", "/api/server").with("Sec-Fetch-Site", "same-origin")).status());
    }

    @Test
    void theRequestLimitAppliesPerSession() {
        perMinute.set(3);
        for (int i = 0; i < 3; i++) {
            assertEquals(200, gate.handle(req("GET", "/api/server").with("Sec-Fetch-Site", "same-origin")).status());
        }
        Response r = gate.handle(req("GET", "/api/server").with("Sec-Fetch-Site", "same-origin"));
        assertEquals(429, r.status());
        String other = sessions.redeem(sessions.issue("Alex")).cookie();
        Request fresh = new Request("GET", "/api/server", null, java.util.Map.of(), null, "127.0.0.1")
                .with("Cookie", "restapi_session=" + other).with("Sec-Fetch-Site", "same-origin");
        assertEquals("dashboard:Alex", gate.handle(fresh).bodyText(), "another session has its own bucket");
    }

    @Test
    void withoutCredentialsTheGateIsAsBefore() {
        gate.credentials(null);
        assertEquals(401, gate.handle(req("GET", "/api/server").with("Sec-Fetch-Site", "same-origin")).status());
        assertEquals(200, gate.handle(req("GET", "/api/server").with("Authorization", "Bearer " + reader)).status());
    }
}
