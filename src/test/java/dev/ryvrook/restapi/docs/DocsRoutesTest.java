package dev.ryvrook.restapi.docs;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
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
import dev.ryvrook.restapi.siblings.Sibling;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Clock;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;

class DocsRoutesTest {
    @TempDir
    Path dir;
    private Router router;
    private DocsRoutes docs;
    private Gate gate;
    private String reader;
    private String metricsOnly;
    private final List<AuditEntry> audit = new ArrayList<>();

    @BeforeEach
    void setUp() throws Exception {
        DocsSettings.PUBLIC.key();
        CoreSettings.HTTP_ENABLED.key();
        Settings.useDefaults();
        KeyStore keys = new KeyStore(dir.resolve("keys.json"));
        reader = keys.create("dash", EnumSet.of(Scope.READ), "test").token();
        metricsOnly = keys.create("prom", EnumSet.of(Scope.METRICS), "test").token();
        router = new Router();
        router.mount("core", List.of(Route.of("core", "GET", "/api/server", Scope.READ, Access.READ, "Server.",
                c -> Response.noContent())));
        docs = new DocsRoutes(router::routes, "1.0.0", getClass().getClassLoader());
        router.mount(DocsRoutes.GROUP, docs.routes());
        gate = new Gate(router, keys, audit::add, Clock.systemUTC(), new RateLimiter(), new RateLimiter(), (w, e) -> { });
    }

    @AfterEach
    void reset() {
        Settings.useDefaults();
    }

    private Response get(String path, String token) {
        Request r = Request.of("GET", path, "127.0.0.1");
        return gate.handle(token == null ? r : r.with("Authorization", "Bearer " + token));
    }

    private JsonObject spec(String token) {
        Response r = get("/openapi.json", token);
        assertEquals(200, r.status(), r.bodyText());
        return JsonParser.parseString(r.bodyText()).getAsJsonObject();
    }

    @Test
    void theDocumentIsOpenByDefaultAndListsEveryMountedRoute() {
        JsonObject doc = spec(null);
        OpenApiTest.validate(doc);
        for (Route r : router.routes()) {
            assertTrue(doc.getAsJsonObject("paths").has(r.pattern()), r.pattern());
        }
        assertTrue(doc.getAsJsonObject("paths").has("/openapi.json"));
        assertTrue(doc.getAsJsonObject("paths").has("/docs"));
    }

    @Test
    void docsPublicOffAsksForAKeyWithRead() {
        Settings.override(DocsSettings.PUBLIC, false);
        assertEquals(401, get("/openapi.json", null).status());
        assertEquals(403, get("/openapi.json", metricsOnly).status());
        assertEquals(200, get("/openapi.json", reader).status());
        assertEquals(200, get("/docs", null).status(), "the page itself holds no routes and stays open");
    }

    @Test
    void theDocumentFollowsMountsAndUnmounts() {
        assertFalse(spec(null).getAsJsonObject("paths").has("/api/lullaby"));
        Sibling s = Sibling.LULLABY;
        router.mount(s.group(), List.of(Route.of(s.group(), "GET", "/api/lullaby", s.scope(), Access.READ, "Sleep.",
                c -> Response.noContent())));
        assertTrue(spec(null).getAsJsonObject("paths").has("/api/lullaby"));
        router.unmount(s.group());
        assertFalse(spec(null).getAsJsonObject("paths").has("/api/lullaby"));
    }

    @Test
    void theDocumentIsKeptUntilSomethingChanges() {
        byte[] a = docs.specBytes();
        assertSame(a, docs.specBytes());
        docs.invalidate();
        byte[] b = docs.specBytes();
        assertNotSame(a, b);
        assertArrayEquals(a, b);
        Settings.override(DocsSettings.TITLE, "Mesa SMP");
        assertTrue(new String(docs.specBytes(), StandardCharsets.UTF_8).contains("Mesa SMP"));
    }

    @Test
    void theServerEntryFollowsTheBasePathUnlessSet() {
        Settings.override(CoreSettings.HTTP_BASE_PATH, "/restapi");
        String json = new String(docs.specBytes(), StandardCharsets.UTF_8);
        assertTrue(json.contains("\"url\":\"/restapi\""), json);
        Settings.override(DocsSettings.SERVER_URL, "https://play.example.com/api");
        json = new String(docs.specBytes(), StandardCharsets.UTF_8);
        assertTrue(json.contains("\"url\":\"https://play.example.com/api\""), json);
    }

    @Test
    void thePageCarriesAStrictContentSecurityPolicy() {
        Response page = get("/docs", null);
        assertEquals(200, page.status());
        assertTrue(page.contentType().startsWith("text/html"));
        assertTrue(page.headers().get("Content-Security-Policy").startsWith("default-src 'self'"));
        assertEquals("no-referrer", page.headers().get("Referrer-Policy"));
        assertEquals("nosniff", page.headers().get("X-Content-Type-Options"));
        Response js = get("/docs/docs.js", null);
        assertEquals(200, js.status());
        assertTrue(js.contentType().startsWith("text/javascript"));
        assertEquals(DocsRoutes.CSP, js.headers().get("Content-Security-Policy"));
        Response css = get("/docs/docs.css", null);
        assertTrue(css.contentType().startsWith("text/css"));
    }

    @Test
    void anUnknownFileIs404() {
        Response r = get("/docs/secrets.txt", null);
        assertEquals(404, r.status());
        assertTrue(r.bodyText().contains("file_not_found"));
        assertEquals(404, get("/docs/..%2Fkeys.json", null).status());
    }

    @Test
    void aTrailingSlashGoesBackToTheCanonicalPage() {
        Response r = get("/docs/", null);
        assertEquals(308, r.status());
        assertEquals("../docs", r.headers().get("Location"));
    }

    @Test
    void tryItCanBeSwitchedOff() {
        assertTrue(get("/docs", null).bodyText().contains("data-try-it=\"true\""));
        Settings.override(DocsSettings.TRY_IT, false);
        assertTrue(get("/docs", null).bodyText().contains("data-try-it=\"false\""));
    }

    @Test
    void docsPageOffLeavesOnlyTheDocument() {
        Settings.override(DocsSettings.PAGE, false);
        List<Route> rs = docs.routes();
        assertEquals(1, rs.size());
        assertEquals("/openapi.json", rs.get(0).pattern());
    }

    @Test
    void thePageFilesStayUnder60KbAndHoldNoInlineCode() {
        assertTrue(docs.pageBytes() < 60 * 1024, docs.pageBytes() + " bytes");
        String html = get("/docs", null).bodyText();
        assertTrue(html.contains("src=\"docs/docs.js\""));
        assertTrue(html.contains("href=\"docs/docs.css\""));
        assertFalse(Pattern.compile("<script(?![^>]*\\bsrc=)").matcher(html).find(), "inline script");
        assertFalse(Pattern.compile("<style|\\sstyle=|\\son[a-z]+=", Pattern.CASE_INSENSITIVE).matcher(html).find(),
                "inline style or handler");
        assertFalse(Pattern.compile("https?://").matcher(html + get("/docs/docs.js", null).bodyText()
                + get("/docs/docs.css", null).bodyText()).find(), "a link to another host");
    }

    @Test
    void thePageHasNoEmDashAndNeverStoresTheKey() {
        for (String f : List.of("/docs", "/docs/docs.js", "/docs/docs.css")) {
            String text = get(f, null).bodyText();
            assertFalse(text.contains("\u2014"), f + " has an em dash");
            assertFalse(text.contains("localStorage") || text.contains("sessionStorage") || text.contains("document.cookie"),
                    f + " keeps the key somewhere other than memory");
        }
    }
}
