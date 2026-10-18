package dev.ryvrook.restapi;

import com.hypixel.hytale.logger.HytaleLogger;
import dev.ryvrook.restapi.audit.AuditLog;
import dev.ryvrook.restapi.auth.KeyStore;
import dev.ryvrook.restapi.config.CoreSettings;
import dev.ryvrook.restapi.config.Settings;
import dev.ryvrook.restapi.dashboard.DashboardModule;
import dev.ryvrook.restapi.dashboard.DashboardSettings;
import dev.ryvrook.restapi.http.Gate;
import dev.ryvrook.restapi.http.RateLimiter;
import dev.ryvrook.restapi.http.Request;
import dev.ryvrook.restapi.http.Router;
import dev.ryvrook.restapi.server.WorldSampler;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ScheduledThreadPoolExecutor;

import static org.junit.jupiter.api.Assertions.*;

/** The dashboard module as the plugin drives it: off by default, on and off by command, kept across a reload. */
class DashboardMountTest {
    @TempDir
    Path dir;
    private Router router;
    private Gate gate;
    private RestApi api;
    private DashboardModule module;
    private AuditLog audit;
    private ScheduledThreadPoolExecutor scheduler;

    @BeforeEach
    void setUp() throws Exception {
        module = new DashboardModule();
        CoreSettings.HTTP_ENABLED.key();
        Settings.useDefaults();
        // No Assets.zip in a test, so the module never looks at the engine or the project folder.
        Settings.override(DashboardSettings.ASSETS, dir.resolve("none.zip").toString());
        KeyStore keys = new KeyStore(dir.resolve("keys.json"));
        audit = new AuditLog(dir.resolve("audit"), Clock.systemUTC(), m -> { });
        router = new Router();
        gate = new Gate(router, keys, audit, Clock.systemUTC(), new RateLimiter(), new RateLimiter(), (w, e) -> { });
        scheduler = new ScheduledThreadPoolExecutor(1);
        api = new RestApi("0.3.0", dir, HytaleLogger.get("RestAPI-test"), keys, audit, router, gate, scheduler,
                new WorldSampler(), System.currentTimeMillis());
        mount();
    }

    @AfterEach
    void tearDown() {
        module.stop();
        scheduler.shutdownNow();
        audit.close();
        Settings.useDefaults();
    }

    /** What the plugin does at start, on a reload and in remount. */
    private void mount() throws Exception {
        module.stop();
        router.mount(module.group(), module.routes(api));
        module.start(api);
    }

    private int dashboardRoutes() {
        return (int) router.routes().stream().filter(r -> r.group().equals("dashboard")).count();
    }

    private int status(String path) {
        return gate.handle(new Request("GET", path, null, Map.of(), null, "127.0.0.1")).status();
    }

    @Test
    void offByDefaultMountsNothing() {
        assertFalse(DashboardSettings.ENABLED.get());
        assertEquals(0, dashboardRoutes());
        assertEquals(401, status("/dashboard/"), "an unknown path without a key, as for any path");
        assertEquals(401, status("/dashboard/capabilities"));
    }

    @Test
    void onMountsTheRoutesWithoutAReloadAndWritesTheFile() throws Exception {
        List<?> reply = module.act("on", "console", () -> {
            try {
                mount();
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        });
        assertFalse(reply.isEmpty());
        assertTrue(DashboardSettings.ENABLED.get());
        assertEquals(9, dashboardRoutes());
        assertEquals(200, status("/dashboard/"));
        assertEquals(200, status("/dashboard/capabilities"));
        String file = Files.readString(dir.resolve("RestAPI.json"));
        assertTrue(file.replaceAll("\\s", "").contains("\"dashboard\":{\"enabled\":true}"), file);
        assertTrue(module.status().get(0).startsWith("Dashboard on at http://127.0.0.1:8125/dashboard/"), module.status().get(0));

        module.act("off", "console", () -> {
            try {
                mount();
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        });
        assertEquals(0, dashboardRoutes());
        assertEquals(401, status("/dashboard/"));
        assertTrue(Files.readString(dir.resolve("RestAPI.json")).replaceAll("\\s", "").contains("\"enabled\":false"));
        assertTrue(audit.tail(10).stream().anyMatch(e -> e.action().equals("dashboard on") && e.key().equals("console")));
        assertTrue(audit.tail(10).stream().anyMatch(e -> e.action().equals("dashboard off")));
    }

    @Test
    void aReloadKeepsTheSessionsAndOffThenOnDropsThem() throws Exception {
        Settings.override(DashboardSettings.ENABLED, true);
        mount();
        List<?> link = module.act("login", "Steve", () -> { });
        assertEquals(2, link.size(), "a sentence and the link");
        String url = String.valueOf(((com.hypixel.hytale.server.core.Message) link.get(1)).getRawText());
        assertTrue(url.startsWith("http://127.0.0.1:8125/dashboard/login?t=rdl_"), url);
        String token = url.substring(url.indexOf("t=") + 2);
        var r = gate.handle(new Request("GET", "/dashboard/login", "t=" + token, Map.of(), null, "127.0.0.1").with("Sec-Fetch-Site", "none"));
        String set = r.headers().get("Set-Cookie");
        String cookie = set.substring(set.indexOf('=') + 1, set.indexOf(';'));
        Request asked = new Request("GET", "/dashboard/capabilities", null, Map.of(), null, "127.0.0.1")
                .with("Cookie", "restapi_session=" + cookie).with("Sec-Fetch-Site", "same-origin");
        assertTrue(gate.handle(asked).bodyText().contains("\"session\":true"));

        mount();
        assertTrue(gate.handle(asked).bodyText().contains("\"session\":true"), "a reload keeps the session");

        Settings.override(DashboardSettings.ENABLED, false);
        mount();
        Settings.override(DashboardSettings.ENABLED, true);
        mount();
        assertEquals("{\"auth\":\"session\"}", gate.handle(asked).bodyText().trim(), "off then on ends it");
        assertTrue(audit.tail(20).stream().noneMatch(e -> e.toString().contains(token.substring(4))));
    }

    @Test
    void writesOnAndOffArePersistedAndAudited() throws Exception {
        module.act("writes on", "Steve", () -> { });
        assertTrue(DashboardSettings.WRITES.get());
        assertTrue(Files.readString(dir.resolve("RestAPI.json")).replaceAll("\\s", "").contains("\"writes\":true"));
        module.act("writes off", "Steve", () -> { });
        assertFalse(DashboardSettings.WRITES.get());
        assertTrue(audit.tail(10).stream().anyMatch(e -> e.action().equals("dashboard writes on") && e.key().equals("Steve")));
    }

    @Test
    void loginWhileOffGivesNoLink() {
        List<?> reply = module.act("login", "Steve", () -> { });
        assertEquals(1, reply.size());
    }
}
