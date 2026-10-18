package dev.ryvrook.restapi.dashboard;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.ryvrook.restapi.audit.AuditEntry;
import dev.ryvrook.restapi.auth.KeyStore;
import dev.ryvrook.restapi.config.CoreSettings;
import dev.ryvrook.restapi.config.Settings;
import dev.ryvrook.restapi.http.Gate;
import dev.ryvrook.restapi.http.RateLimiter;
import dev.ryvrook.restapi.http.Request;
import dev.ryvrook.restapi.http.Response;
import dev.ryvrook.restapi.http.Router;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.*;

/** The dashboard routes through the gate, over a small Assets.zip and a Chronicle that answers from memory. */
class DashboardRoutesTest {
    @TempDir
    Path dir;
    private final AtomicLong now = new AtomicLong(1_000_000);
    private final List<AuditEntry> audit = new ArrayList<>();
    private final List<String> upstream = new ArrayList<>();
    private SessionStore sessions;
    private AssetZip zip;
    private Gate gate;
    private String chronicleBase = "http://127.0.0.1:8124/";

    static final byte[] PNG = {(byte) 0x89, 'P', 'N', 'G', 1, 2, 3};

    static Path makeZip(Path file) throws IOException {
        try (ZipOutputStream out = new ZipOutputStream(Files.newOutputStream(file))) {
            for (String name : List.of("Common/Icons/ItemsGenerated/Ore_Iron.png", "Common/UI/WorldMap/MapMarkers/Home.png",
                    "Common/Icons/ModelsGenerated/Boar.png", "Common/Icons/ItemsGenerated/sub/Nested.png",
                    "Common/Icons/ItemsGenerated/bad name.png", "Common/Icons/ItemsGenerated/Readme.txt",
                    "Server/Item/Items/Ore_Iron.json")) {
                out.putNextEntry(new ZipEntry(name));
                out.write(name.endsWith(".png") ? PNG : "{}".getBytes(StandardCharsets.UTF_8));
                out.closeEntry();
            }
        }
        return file;
    }

    @BeforeEach
    void setUp() throws Exception {
        CoreSettings.HTTP_ENABLED.key();
        DashboardSettings.ENABLED.key();
        Settings.useDefaults();
        zip = AssetZip.open(makeZip(dir.resolve("Assets.zip")));
        sessions = new SessionStore(now::get, () -> 3600_000L, () -> 600_000L, () -> false, () -> 0);
        ChroniclePass.Upstream fake = (url, inm, asset) -> {
            upstream.add(url + (inm == null ? "" : " if " + inm));
            if (url.endsWith("/api/avatar/Nobody")) {
                return new ChroniclePass.Answer(404, Response.JSON, null, "{\"error\":\"no\"}".getBytes(StandardCharsets.UTF_8));
            }
            if (inm != null) {
                return new ChroniclePass.Answer(304, null, inm, new byte[0]);
            }
            return new ChroniclePass.Answer(200, asset ? "application/json" : Response.JSON, "\"c-1\"",
                    "{\"parts\":[1]}".getBytes(StandardCharsets.UTF_8));
        };
        ChroniclePass pass = new ChroniclePass(() -> chronicleBase, fake, now::get);
        Router router = new Router();
        gate = new Gate(router, new KeyStore(dir.resolve("keys.json")), audit::add, Clock.systemUTC(), new RateLimiter(now::get),
                new RateLimiter(now::get), (w, e) -> { });
        DashboardRoutes routes = new DashboardRoutes(PageFiles.load(getClass().getClassLoader()), sessions, () -> zip, pass,
                gate, audit::add, Clock.systemUTC(), () -> "http://127.0.0.1:8123", "0.3.0");
        router.mount(DashboardRoutes.GROUP, routes.routes());
        gate.credentials(sessions);
    }

    @AfterEach
    void close() throws IOException {
        zip.close();
        Settings.useDefaults();
    }

    private Request get(String path) {
        int q = path.indexOf('?');
        return new Request("GET", q < 0 ? path : path.substring(0, q), q < 0 ? null : path.substring(q + 1), Map.of(), null, "127.0.0.1")
                .with("Host", "127.0.0.1:8125").with("Sec-Fetch-Site", "same-origin");
    }

    private String login() {
        Response r = gate.handle(get("/dashboard/login?t=" + sessions.issue("Steve")));
        assertEquals(303, r.status());
        String set = r.headers().get("Set-Cookie");
        return set.substring(set.indexOf('=') + 1, set.indexOf(';'));
    }

    private Request withSession(Request r, String cookie) {
        return r.with("Cookie", "restapi_session=" + cookie);
    }

    // ---- the page --------------------------------------------------------------------------------------

    @Test
    void thePageIsOpenWithAStrictPolicyAndDashboardWithoutASlashMovesOn() {
        Response moved = gate.handle(get("/dashboard"));
        assertEquals(308, moved.status());
        assertEquals("dashboard/", moved.headers().get("Location"));
        Response page = gate.handle(get("/dashboard/"));
        assertEquals(200, page.status());
        assertTrue(page.bodyText().contains("js/app.js"));
        String csp = page.headers().get("Content-Security-Policy");
        assertTrue(csp.startsWith("default-src 'none'; script-src 'self'; style-src 'self'; img-src 'self' http://127.0.0.1:8123;"), csp);
        assertFalse(csp.contains("unsafe"), csp);
        assertEquals("no-store", page.headers().get("Cache-Control"));
        assertEquals("DENY", page.headers().get("X-Frame-Options"));
        Response js = gate.handle(get("/dashboard/js/panels/settings.js"));
        assertEquals(200, js.status());
        assertEquals("text/javascript; charset=utf-8", js.contentType());
        assertEquals(200, gate.handle(get("/dashboard/vendor/cinzel-latin-600-normal.woff2")).status());
    }

    @Test
    void onlyListedPageFilesAreServed() {
        for (String p : List.of("/dashboard/server.js", "/dashboard/lib/auth.js", "/dashboard/files.txt", "/dashboard/VERSION",
                "/dashboard/..%2Fmanifest.json", "/dashboard/js/../index.html", "/dashboard/mock/data/server.json",
                "/dashboard/js/app.js%00", "/dashboard/restapi-docs/docs.js")) {
            assertEquals(404, gate.handle(get(p)).status(), p);
        }
    }

    @Test
    void theJarCarriesThePageWithItsVersion() throws Exception {
        PageFiles p = PageFiles.load(getClass().getClassLoader());
        assertNotNull(p.get("index.html"));
        assertNotNull(p.get("login.html"));
        assertNotNull(p.get("js/app.js"));
        assertNotNull(p.get("css/style.css"));
        assertNotNull(p.get("vendor/cinzel-latin-400-normal.woff2"));
        assertTrue(p.version().matches("\\d+\\.\\d+\\.\\d+"), p.version());
        assertNotEquals("unknown", p.commit());
        String app = new String(p.get("js/app.js"), StandardCharsets.UTF_8);
        assertTrue(app.contains("DASHBOARD_VERSION = '" + p.version() + "'"), "VERSION matches js/app.js");
        assertTrue(p.bytes() > 300_000 && p.bytes() < 2_000_000, p.bytes() + " bytes");
        for (String name : List.of("server.js", "lib/auth.js", "mock/data/server.json", "tools/cdp.mjs")) {
            assertNull(p.get(name), name);
        }
    }

    // ---- login, capabilities, logout -------------------------------------------------------------------

    @Test
    void capabilitiesSayOnlyThatALoginIsNeededWithoutASession() {
        Response r = gate.handle(get("/dashboard/capabilities"));
        assertEquals(200, r.status());
        assertEquals("{\"auth\":\"session\"}", r.bodyText().trim());
        String cookie = login();
        JsonObject o = JsonParser.parseString(gate.handle(withSession(get("/dashboard/capabilities"), cookie)).bodyText()).getAsJsonObject();
        assertEquals("session", o.get("auth").getAsString());
        assertTrue(o.get("session").getAsBoolean());
        assertEquals("live", o.get("mode").getAsString());
        assertFalse(o.get("writes").getAsBoolean());
        assertTrue(o.get("assets").getAsBoolean());
        assertTrue(o.get("chronicle").getAsBoolean());
        assertEquals("Steve", o.get("who").getAsString());
        assertEquals("http://127.0.0.1:8125", o.get("upstream").getAsString());
        assertEquals("127.0.0.1:8125", o.get("listen").getAsString());
    }

    @Test
    void aLoginLinkOpensASessionOnceAndABadOneCountsAsAFailedLogin() {
        String token = sessions.issue("Steve");
        Response r = gate.handle(get("/dashboard/login?t=" + token));
        assertEquals(303, r.status());
        assertEquals("./", r.headers().get("Location"));
        String set = r.headers().get("Set-Cookie");
        assertTrue(set.matches("restapi_session=[A-Za-z0-9_-]{43}; Path=/; HttpOnly; SameSite=Strict; Max-Age=604800"), set);
        assertTrue(audit.stream().anyMatch(e -> e.key().equals("dashboard:Steve") && e.action().equals("dashboard login")));
        assertTrue(audit.stream().noneMatch(e -> e.toString().contains(token.substring(4))), "the token never reaches the audit log");

        Response again = gate.handle(get("/dashboard/login?t=" + token));
        assertEquals(303, again.status());
        assertEquals("login?link=used", again.headers().get("Location"));
        assertNull(again.headers().get("Set-Cookie"));
        for (int i = 0; i < 9; i++) {
            gate.handle(get("/dashboard/login?t=rdl_" + "x".repeat(43)));
        }
        Response waited = gate.handle(get("/dashboard/login?t=rdl_" + "y".repeat(43)));
        assertEquals("login?link=wait", waited.headers().get("Location"));
        assertNotNull(waited.headers().get("Retry-After"));
    }

    @Test
    void loginWithoutATokenShowsTheLoginPageOrSendsASessionOn() {
        Response view = gate.handle(get("/dashboard/login"));
        assertEquals(200, view.status());
        assertTrue(view.bodyText().contains("/restapi dashboard login"));
        assertNotNull(view.headers().get("Content-Security-Policy"));
        String cookie = login();
        Response on = gate.handle(withSession(get("/dashboard/login"), cookie));
        assertEquals(303, on.status());
        assertEquals("./", on.headers().get("Location"));
    }

    @Test
    void theCookieFollowsTheBasePathAndASecureProxy() {
        Settings.override(CoreSettings.HTTP_BASE_PATH, "/restapi");
        Settings.override(CoreSettings.HTTP_TRUSTED_PROXIES, List.of("127.0.0.1"));
        Request r = new Request("GET", "/restapi/dashboard/login", "t=" + sessions.issue("Steve"), Map.of(), null, "127.0.0.1")
                .with("Host", "play.example.com").with("Sec-Fetch-Site", "none").with("X-Forwarded-Proto", "https");
        String set = gate.handle(r).headers().get("Set-Cookie");
        assertTrue(set.contains("; Path=/restapi;") && set.endsWith("; Secure"), set);
        Request page = new Request("GET", "/restapi/dashboard", null, Map.of(), null, "127.0.0.1");
        assertEquals("dashboard/", gate.handle(page).headers().get("Location"));
    }

    @Test
    void logoutEndsTheSessionAndClearsTheCookie() {
        String cookie = login();
        Request out = new Request("POST", "/dashboard/logout", null, Map.of(), null, "127.0.0.1")
                .with("Host", "127.0.0.1:8125").with("Origin", "http://127.0.0.1:8125").with("Cookie", "restapi_session=" + cookie);
        Response r = gate.handle(out);
        assertEquals(200, r.status());
        assertTrue(r.headers().get("Set-Cookie").contains("Max-Age=0"));
        assertEquals(0, sessions.count());
        assertEquals(401, gate.handle(withSession(get("/dashboard/assets/index.json"), cookie)).status());
    }

    // ---- game icons --------------------------------------------------------------------------------------

    @Test
    void theIndexHoldsTheThreeFoldersAndNothingElse() {
        assertEquals(3, zip.count());
        String cookie = login();
        Response r = gate.handle(withSession(get("/dashboard/assets/index.json"), cookie));
        assertEquals(200, r.status());
        assertEquals("{\"items\":[\"Ore_Iron\"],\"markers\":[\"Home\"],\"models\":[\"Boar\"]}", r.bodyText().trim());
        assertEquals("public, max-age=86400", r.headers().get("Cache-Control"), "the gate keeps the route's own cache rule");
        String tag = r.headers().get("ETag");
        assertEquals(304, gate.handle(withSession(get("/dashboard/assets/index.json"), cookie).with("If-None-Match", tag)).status());
        Response gz = gate.handle(withSession(get("/dashboard/assets/index.json"), cookie).with("Accept-Encoding", "gzip, br"));
        assertEquals("gzip", gz.headers().get("Content-Encoding"));
    }

    @Test
    void iconsComeWithTheirCrcAsETagAndALongCache() {
        String cookie = login();
        Response r = gate.handle(withSession(get("/dashboard/assets/items/Ore_Iron.png"), cookie));
        assertEquals(200, r.status());
        assertEquals("image/png", r.contentType());
        assertArrayEquals(PNG, r.body());
        java.util.zip.CRC32 crc = new java.util.zip.CRC32();
        crc.update(PNG);
        assertEquals("\"" + Long.toHexString(crc.getValue()) + "\"", r.headers().get("ETag"));
        assertTrue(r.headers().get("Cache-Control").startsWith("public, max-age="));
        Response again = gate.handle(withSession(get("/dashboard/assets/items/Ore_Iron.png"), cookie).with("If-None-Match", r.headers().get("ETag")));
        assertEquals(304, again.status());
        assertEquals(0, again.body().length);
        assertEquals(200, gate.handle(withSession(get("/dashboard/assets/markers/Home.png"), cookie)).status());
        assertEquals(200, gate.handle(withSession(get("/dashboard/assets/models/Boar.png"), cookie)).status());
    }

    @Test
    void namesWithASeparatorDotSegmentOrEscapeAreRefused() {
        String cookie = login();
        for (String p : List.of("/dashboard/assets/items/Nested.png", "/dashboard/assets/items/sub%2FNested.png",
                "/dashboard/assets/items/..%2F..%2FServer.png", "/dashboard/assets/items/bad%20name.png",
                "/dashboard/assets/items/Ore_Iron", "/dashboard/assets/items/Ore_Iron.json", "/dashboard/assets/sounds/Ore_Iron.png",
                "/dashboard/assets/items/..png", "/dashboard/assets/items/Not_There.png", "/dashboard/assets/items/sub/Nested.png")) {
            Response r = gate.handle(withSession(get(p), cookie));
            assertEquals(404, r.status(), p);
        }
        assertFalse(AssetZip.valid("items", "../x"));
        assertFalse(AssetZip.valid("items", "a.b"));
        assertFalse(AssetZip.valid("items", "a%2Fb"));
        assertTrue(AssetZip.valid("models", "Boar"));
    }

    @Test
    void iconsNeedASessionOrAKey() {
        assertEquals(401, gate.handle(get("/dashboard/assets/items/Ore_Iron.png")).status());
        assertEquals(401, gate.handle(get("/dashboard/chronicle/api/avatar/Steve")).status());
    }

    @Test
    void autoFindsTheArchiveNextToTheServerOrAboveIt() throws Exception {
        Path server = Files.createDirectories(dir.resolve("hytale/Server"));
        assertNull(AssetZip.find("", server, () -> null));
        Path above = makeZip(dir.resolve("hytale/Assets.zip"));
        assertEquals(above, AssetZip.find("", server, () -> null));
        Path here = makeZip(server.resolve("Assets.zip"));
        assertEquals(here, AssetZip.find("", server, () -> null));
        Path engine = makeZip(dir.resolve("engine.zip"));
        assertEquals(engine, AssetZip.find("", server, () -> engine));
        assertEquals(above, AssetZip.find("../Assets.zip", server, () -> engine));
        assertNull(AssetZip.find("missing.zip", server, () -> engine));
        assertEquals(here, AssetZip.find("", server, () -> {
            throw new IllegalStateException("no engine here");
        }));
    }

    // ---- Chronicle Stats -------------------------------------------------------------------------------

    @Test
    void thePassThroughKeepsAvatarsAMinuteAndRevalidatesByETag() {
        String cookie = login();
        Response a = gate.handle(withSession(get("/dashboard/chronicle/api/avatar/Steve"), cookie));
        assertEquals(200, a.status());
        assertEquals("private, max-age=60", a.headers().get("Cache-Control"));
        gate.handle(withSession(get("/dashboard/chronicle/api/avatar/Steve"), cookie));
        assertEquals(List.of("http://127.0.0.1:8124/api/avatar/Steve"), upstream);
        now.addAndGet(61_000);
        Response b = gate.handle(withSession(get("/dashboard/chronicle/api/avatar/Steve"), cookie));
        assertEquals(200, b.status());
        assertEquals("http://127.0.0.1:8124/api/avatar/Steve if \"c-1\"", upstream.get(1));
        assertEquals(304, gate.handle(withSession(get("/dashboard/chronicle/api/avatar/Steve"), cookie).with("If-None-Match", "\"c-1\"")).status());
        Response asset = gate.handle(withSession(get("/dashboard/chronicle/api/asset/Characters/Player.blockymodel"), cookie));
        assertEquals(200, asset.status());
        assertTrue(asset.headers().get("Cache-Control").contains("immutable"));
        assertEquals(404, gate.handle(withSession(get("/dashboard/chronicle/api/avatar/Nobody"), cookie)).status(),
                "Chronicle's own 404 passes");
    }

    @Test
    void thePassThroughRefusesEveryOtherShape() {
        String cookie = login();
        int before = upstream.size();
        for (String p : List.of("/dashboard/chronicle/api/asset/Player.blockymodel", "/dashboard/chronicle/api/asset/Characters/../secret",
                "/dashboard/chronicle/api/asset/Characters/%2e%2e/x", "/dashboard/chronicle/api/asset/a%2Fb/c",
                "/dashboard/chronicle/api/asset/a/b%5Cc", "/dashboard/chronicle/api/asset/a/b%00", "/dashboard/chronicle/api/avatar/a%2Fb",
                "/dashboard/chronicle/api/avatar/..", "/dashboard/chronicle/api/player/Steve", "/dashboard/chronicle/admin/x")) {
            assertEquals(404, gate.handle(withSession(get(p), cookie)).status(), p);
        }
        Request post = new Request("POST", "/dashboard/chronicle/api/avatar/Steve", null, Map.of(), null, "127.0.0.1")
                .with("Sec-Fetch-Site", "same-origin").with("Cookie", "restapi_session=" + cookie);
        assertTrue(gate.handle(post).status() >= 403);
        assertEquals(before, upstream.size(), "nothing of those reached Chronicle");

        chronicleBase = null;
        Response off = gate.handle(withSession(get("/dashboard/chronicle/api/avatar/Steve"), cookie));
        assertEquals(404, off.status());
        assertTrue(off.bodyText().contains("no_chronicle"));
    }
}
