package dev.ryvrook.restapi;

import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.server.core.Message;
import com.sun.net.httpserver.HttpServer;
import dev.ryvrook.restapi.audit.AuditEntry;
import dev.ryvrook.restapi.audit.AuditLog;
import dev.ryvrook.restapi.auth.KeyStore;
import dev.ryvrook.restapi.auth.Scope;
import dev.ryvrook.restapi.config.CoreSettings;
import dev.ryvrook.restapi.config.Settings;
import dev.ryvrook.restapi.dashboard.DashboardModule;
import dev.ryvrook.restapi.dashboard.DashboardSettings;
import dev.ryvrook.restapi.http.Access;
import dev.ryvrook.restapi.http.Gate;
import dev.ryvrook.restapi.http.HttpHost;
import dev.ryvrook.restapi.http.RateLimiter;
import dev.ryvrook.restapi.http.Response;
import dev.ryvrook.restapi.http.Route;
import dev.ryvrook.restapi.http.Router;
import dev.ryvrook.restapi.server.WorldSampler;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The dashboard in headless Chrome, with no engine. Starts HttpHost with the gate, the dashboard module and a stub
 * router that answers /api/health and /api/server with fixed JSON, then runs src/test/browser/dashboard-check.mjs,
 * which drives Chrome over the DevTools protocol through the login link, the page, the view switch, logout and a
 * POST from another origin, then the dashboard's tools/layout-scan.mjs over the login view. Runs only with
 * RESTAPI_BROWSER_CHECK=1, see docs/DEVELOPMENT.md. Ports 8310 (RestAPI),
 * 8311 (the other origin) and 8312 (Chrome's debugging port), or RESTAPI_BROWSER_PORT and the two after it.
 */
@EnabledIfEnvironmentVariable(named = "RESTAPI_BROWSER_CHECK", matches = "1")
class DashboardBrowserCheck {
    @TempDir
    Path dir;

    @Test
    void thePageWorksInARealBrowser() throws Exception {
        int port = Integer.parseInt(System.getenv().getOrDefault("RESTAPI_BROWSER_PORT", "8310"));
        Path tools = Path.of(System.getenv().getOrDefault("DASHBOARD_TOOLS", "../restapi-dashboard/tools")).toAbsolutePath().normalize();
        Path out = Path.of(System.getenv().getOrDefault("RESTAPI_BROWSER_OUT", dir.resolve("shots").toString()));
        Files.createDirectories(out);

        DashboardModule module = new DashboardModule();
        CoreSettings.HTTP_ENABLED.key();
        Settings.useDefaults();
        Settings.override(CoreSettings.HTTP_PORT, port);
        Settings.override(DashboardSettings.ENABLED, true);
        Settings.override(DashboardSettings.ASSETS, iconZip(dir.resolve("Assets.zip"), tools.getParent()).toString());

        KeyStore keys = new KeyStore(dir.resolve("keys.json"));
        AuditLog audit = new AuditLog(dir.resolve("audit"), Clock.systemUTC(), m -> { });
        Router router = new Router();
        List<String> kicked = Collections.synchronizedList(new ArrayList<>());
        byte[] server;
        try (InputStream in = getClass().getResourceAsStream("/dashboard-check/server.json")) {
            server = in.readAllBytes();
        }
        router.mount("core", List.of(
                new Route("core", "GET", "/api/health", Scope.READ, Access.READ, () -> true, "health",
                        c -> new Response(200, Response.JSON, "{\"ok\":true,\"status\":\"ok\",\"restapi\":\"0.3.0\",\"uptimeSeconds\":120}"
                                .getBytes(StandardCharsets.UTF_8), Map.of())),
                Route.of("core", "GET", "/api/server", Scope.READ, Access.READ, "server",
                        c -> new Response(200, Response.JSON, server, Map.of())),
                Route.of("core", "POST", "/api/players/{player}/kick", Scope.PLAYERS, Access.WRITE, "kick", c -> {
                    kicked.add(c.param("player") + " by " + c.keyName());
                    return Response.text(200, "kicked");
                })));
        Gate gate = new Gate(router, keys, audit, Clock.systemUTC(), new RateLimiter(), new RateLimiter(), (w, e) -> e.printStackTrace());
        ScheduledThreadPoolExecutor scheduler = new ScheduledThreadPoolExecutor(1);
        RestApi api = new RestApi("0.3.0", dir, HytaleLogger.get("RestAPI-browser"), keys, audit, router, gate, scheduler,
                new WorldSampler(), System.currentTimeMillis());
        router.mount(module.group(), module.routes(api));
        module.start(api);
        HttpHost host = new HttpHost(gate);
        host.start("127.0.0.1", port);

        // The other origin, the same site on another port, so the browser sends the SameSite Strict cookie and
        // only the gate stands between the page and the API.
        HttpServer other = HttpServer.create(new InetSocketAddress("127.0.0.1", port + 1), 8);
        String evil = "<!doctype html><title>other</title><p id=done>sending</p><script>"
                + "Promise.allSettled([fetch('http://127.0.0.1:" + port + "/api/players/Steve/kick',{method:'POST',mode:'no-cors',credentials:'include'}),"
                + "fetch('http://127.0.0.1:" + port + "/api/server',{mode:'no-cors',credentials:'include'})])"
                + ".then(()=>{document.getElementById('done').textContent='sent'})</script>";
        other.createContext("/", ex -> {
            byte[] b = evil.getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().set("Content-Type", "text/html; charset=utf-8");
            ex.sendResponseHeaders(200, b.length);
            try (OutputStream os = ex.getResponseBody()) {
                os.write(b);
            }
        });
        other.start();
        try {
            List<?> reply = module.act("login", "browser-check", () -> { });
            String link = ((Message) reply.get(1)).getRawText();
            ProcessBuilder pb = new ProcessBuilder("node", Path.of("src/test/browser/dashboard-check.mjs").toAbsolutePath().toString())
                    .redirectErrorStream(true);
            pb.environment().put("DASH_LINK", link);
            pb.environment().put("OTHER_URL", "http://127.0.0.1:" + (port + 1) + "/");
            pb.environment().put("CDP_PORT", Integer.toString(port + 2));
            pb.environment().put("DASHBOARD_TOOLS", tools.toString());
            pb.environment().put("OUT", out.toString());
            Process p = pb.start();
            String log = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            assertTrue(p.waitFor(180, TimeUnit.SECONDS));
            System.out.println(log);
            Files.writeString(out.resolve("dashboard-check.log"), log);
            assertEquals(0, p.exitValue(), log);

            // The dashboard's own layout scan over the login view RestAPI serves, logged out, at four widths.
            ProcessBuilder scan = new ProcessBuilder("node", tools.resolve("layout-scan.mjs").toString(),
                    out.resolve("layout").toString(), "http://127.0.0.1:" + port).redirectErrorStream(true);
            scan.directory(tools.getParent().toFile());
            scan.environment().put("SECTIONS", "/dashboard/login");
            scan.environment().put("CDP_PORT", Integer.toString(port + 2));
            Process s = scan.start();
            String scanLog = new String(s.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            assertTrue(s.waitFor(180, TimeUnit.SECONDS));
            System.out.println(scanLog);
            Files.writeString(out.resolve("layout-scan.log"), scanLog);
            assertEquals(0, s.exitValue(), scanLog);

            assertTrue(kicked.isEmpty(), "nothing got through to the kick route: " + kicked);
            List<AuditEntry> lines = audit.tail(50);
            assertTrue(lines.stream().anyMatch(e -> e.status() == 403 && e.key().equals("dashboard:browser-check")
                    && e.action().equals("POST /api/players/Steve/kick")), lines.toString());
            assertTrue(lines.stream().anyMatch(e -> e.action().equals("dashboard login") && e.key().equals("dashboard:browser-check")));
            assertTrue(lines.stream().noneMatch(e -> e.toString().contains(link.substring(link.indexOf("rdl_") + 4))));
        } finally {
            other.stop(0);
            host.stop();
            module.stop();
            scheduler.shutdownNow();
            audit.close();
            Settings.useDefaults();
        }
    }

    /** An Assets.zip with a few real PNG files under the icon folders, taken from the dashboard's own images. */
    private static Path iconZip(Path file, Path dashboard) throws Exception {
        Path img = dashboard.resolve("img/mods");
        try (ZipOutputStream z = new ZipOutputStream(Files.newOutputStream(file))) {
            String[][] icons = {{"Common/Icons/ItemsGenerated/Ore_Iron.png", "coinage.png"},
                    {"Common/Icons/ModelsGenerated/Boar.png", "feats.png"},
                    {"Common/UI/WorldMap/MapMarkers/Home.png", "atlas.png"}};
            for (String[] i : icons) {
                z.putNextEntry(new ZipEntry(i[0]));
                z.write(Files.readAllBytes(img.resolve(i[1])));
                z.closeEntry();
            }
        }
        return file;
    }
}
