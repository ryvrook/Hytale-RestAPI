package dev.ryvrook.restapi.control;

import dev.ryvrook.restapi.audit.AuditEntry;
import dev.ryvrook.restapi.auth.KeyStore;
import dev.ryvrook.restapi.auth.Scope;
import dev.ryvrook.restapi.config.Settings;
import dev.ryvrook.restapi.http.Access;
import dev.ryvrook.restapi.http.Gate;
import dev.ryvrook.restapi.http.RateLimiter;
import dev.ryvrook.restapi.http.Request;
import dev.ryvrook.restapi.http.Response;
import dev.ryvrook.restapi.http.Route;
import dev.ryvrook.restapi.http.Router;
import dev.ryvrook.restapi.server.CoreModule;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Clock;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class ControlModuleTest {
    @TempDir
    Path dir;
    private final List<AuditEntry> audit = new ArrayList<>();
    private Gate gate;
    private String admin;
    private String reader;

    @BeforeEach
    void setUp() throws Exception {
        new ControlModule();
        Settings.useDefaults();
        KeyStore keys = new KeyStore(dir.resolve("keys.json"));
        admin = keys.create("admin", EnumSet.allOf(Scope.class), "test").token();
        reader = keys.create("dash", EnumSet.of(Scope.READ), "test").token();
        Router router = new Router();
        router.mount(CoreModule.GROUP, new CoreModule().routes(null));
        router.mount(ControlModule.GROUP, new ControlModule().routes(null));
        gate = new Gate(router, keys, audit::add, Clock.systemUTC(), new RateLimiter(), new RateLimiter(), (w, e) -> { });
    }

    @AfterEach
    void tearDown() {
        Settings.useDefaults();
    }

    private Response post(String path, String token, String body) {
        Request r = new Request("POST", path, null, java.util.Map.of("Authorization", List.of("Bearer " + token)),
                body == null ? null : body.getBytes(java.nio.charset.StandardCharsets.UTF_8), "127.0.0.1");
        return gate.handle(r);
    }

    @Test
    void theRoutesStayInTheirAreaAndLeaveTheCoreAlone() {
        List<Route> routes = new ControlModule().routes(null);
        assertEquals(22, routes.size());
        Set<String> seen = new HashSet<>();
        for (Route r : routes) {
            assertEquals(ControlModule.GROUP, r.group());
            assertTrue(seen.add(r.method() + " " + r.pattern()), r.pattern());
            assertTrue(r.pattern().startsWith("/api/plugins") || r.pattern().startsWith("/api/worlds")
                    || r.pattern().startsWith("/api/server/"), r.pattern());
            assertNotEquals("/api/health", r.pattern());
            if (r.access() != Access.READ) {
                assertNotEquals(Scope.READ, r.scope(), r.pattern() + " changes something and needs more than read");
            }
            assertFalse(r.summary().isBlank());
            assertFalse(r.summary().contains(";") || r.summary().contains("—"), r.summary());
        }
        assertTrue(routes.stream().anyMatch(r -> r.pattern().equals("/api/server/stop") && r.access() == Access.DANGEROUS));
    }

    @Test
    void offMountsNothing() {
        Settings.override(ControlSettings.ENABLED, false);
        assertTrue(new ControlModule().routes(null).isEmpty());
    }

    @Test
    void restApiIsRefusedEvenWithEveryScope() {
        for (String action : new String[]{"load", "unload", "reload"}) {
            Response r = post("/api/plugins/Ryvrook:RestAPI/" + action, admin, null);
            assertEquals(403, r.status(), action);
            assertTrue(r.bodyText().contains("self_refused"), r.bodyText());
            Response encoded = post("/api/plugins/ryvrook%3Arestapi/" + action, admin, null);
            assertEquals(403, encoded.status(), action);
            assertTrue(encoded.bodyText().contains("self_refused"), encoded.bodyText());
        }
        assertTrue(audit.stream().anyMatch(e -> e.action().equals("POST /api/plugins/Ryvrook:RestAPI/reload") && e.status() == 403),
                "the refusal is audited with the key name");
        assertEquals("admin", audit.get(0).key());
    }

    @Test
    void aReadKeyCannotTouchPlugins() {
        Response r = post("/api/plugins/Ryvrook:Lullaby/reload", reader, null);
        assertEquals(403, r.status());
        assertTrue(r.bodyText().contains("missing_scope"));
    }

    @Test
    void switchedOffWritesAnswerFeatureOff() {
        Settings.override(ControlSettings.ALLOW_PLUGIN_LOAD, false);
        Settings.override(ControlSettings.ALLOW_STOP, false);
        Settings.override(ControlSettings.ALLOW_CONFIG_EDIT, false);
        Settings.override(ControlSettings.ALLOW_WORLD_UNLOAD, false);
        for (String path : new String[]{"/api/plugins/Ryvrook:Lullaby/reload", "/api/server/stop", "/api/worlds/arena/unload"}) {
            Response r = post(path, admin, "{\"confirm\":\"stop\"}");
            assertEquals(403, r.status(), path);
            assertTrue(r.bodyText().contains("feature_off"), r.bodyText());
        }
        Request patch = new Request("PATCH", "/api/server/config", null,
                java.util.Map.of("Authorization", List.of("Bearer " + admin)), "{\"motd\":\"x\"}".getBytes(), "127.0.0.1");
        assertTrue(gate.handle(patch).bodyText().contains("control.allowConfigEdit"));
    }

    @Test
    void anEngineAssertionBecomesA500() {
        Route r = ControlModule.guarded(Route.of(ControlModule.GROUP, "GET", "/api/worlds/x", Scope.READ, Access.READ, "x", c -> {
            throw new AssertionError("Data can only be accessed from a store thread!");
        }));
        Router router = new Router();
        router.mount(ControlModule.GROUP, List.of(r));
        assertThrows(IllegalStateException.class, () -> r.handler().handle(null));
    }

    @Test
    void aBadPluginIdIs400() {
        Response r = post("/api/plugins/Lullaby/reload", admin, null);
        assertEquals(400, r.status());
        assertTrue(r.bodyText().contains("bad_plugin_id"));
    }
}
