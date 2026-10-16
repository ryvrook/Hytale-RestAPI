package dev.ryvrook.restapi.docs;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.ryvrook.restapi.auth.Scope;
import dev.ryvrook.restapi.config.Settings;
import dev.ryvrook.restapi.http.Access;
import dev.ryvrook.restapi.http.Response;
import dev.ryvrook.restapi.http.Route;
import dev.ryvrook.restapi.http.RouteDoc;
import dev.ryvrook.restapi.http.Router;
import dev.ryvrook.restapi.siblings.Sibling;
import dev.ryvrook.restapi.text.Messages;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;

class OpenApiTest {
    private static final OpenApi.Meta META = new OpenApi.Meta("RestAPI", "1.0.0", "/", true);
    private Router router;

    private static Route route(String group, String method, String pattern, Scope scope, Access access) {
        return Route.of(group, method, pattern, scope, access, "Does " + pattern + ".", c -> Response.noContent());
    }

    @BeforeEach
    void setUp() {
        DocsSettings.PUBLIC.key();
        Settings.useDefaults();
        Messages.reset();
        router = new Router();
        router.mount("core", List.of(
                new Route("core", "GET", "/api/health", Scope.READ, Access.READ, () -> true, "Health.", c -> Response.noContent()),
                route("core", "GET", "/api/server", Scope.READ, Access.READ)));
        router.mount("players", List.of(
                route("players", "GET", "/api/players", Scope.READ, Access.READ).doc(new RouteDoc(
                        List.of(new RouteDoc.Param("world", "Only players in this world.")), null,
                        "{\"players\":[{\"name\":\"Steve\",\"x\":1.5,\"ping\":12,\"op\":false,\"world\":null}]}", List.of())),
                route("players", "GET", "/api/players/{player}", Scope.READ, Access.READ)
                        .doc(new RouteDoc(List.of(), null, null, List.of("player_not_found"))),
                route("players", "POST", "/api/players/{player}/kick", Scope.PLAYERS, Access.WRITE)
                        .doc(new RouteDoc(List.of(), "{\"reason\":\"Spam\"}", "{\"closed\":1}", List.of("player_not_found"))),
                route("players", "DELETE", "/api/bans/{player}", Scope.PLAYERS, Access.WRITE),
                route("players", "POST", "/api/bans", Scope.PLAYERS, Access.DANGEROUS)));
        router.mount("console", List.of(
                route("console", "GET", "/api/console/stream", Scope.CONSOLE, Access.READ)
                        .doc(new RouteDoc(List.of(), null, "event: log\ndata: [INFO] Booted\n\n", List.of())),
                route("console", "GET", "/metrics", Scope.METRICS, Access.READ)
                        .doc(new RouteDoc(List.of(), null, "hytale_players_online 3\n", List.of()))));
    }

    private JsonObject build() {
        return OpenApi.build(router.routes(), META);
    }

    @Test
    void theDocumentHasTheOpenApi31Shape() {
        JsonObject doc = build();
        assertEquals("3.1.0", doc.get("openapi").getAsString());
        assertEquals("RestAPI", doc.getAsJsonObject("info").get("title").getAsString());
        assertEquals("1.0.0", doc.getAsJsonObject("info").get("version").getAsString());
        assertEquals("/", doc.getAsJsonArray("servers").get(0).getAsJsonObject().get("url").getAsString());
        JsonObject schemes = doc.getAsJsonObject("components").getAsJsonObject("securitySchemes");
        assertEquals("http", schemes.getAsJsonObject("bearer").get("type").getAsString());
        assertEquals("bearer", schemes.getAsJsonObject("bearer").get("scheme").getAsString());
        assertEquals("X-API-Key", schemes.getAsJsonObject("apiKey").get("name").getAsString());
        assertEquals(2, doc.getAsJsonArray("security").size());
        validate(doc);
    }

    @Test
    void withoutXApiKeyOnlyBearerIsListed() {
        JsonObject doc = OpenApi.build(router.routes(), new OpenApi.Meta("RestAPI", "1.0.0", "/", false));
        assertFalse(doc.getAsJsonObject("components").getAsJsonObject("securitySchemes").has("apiKey"));
        assertEquals(1, doc.getAsJsonArray("security").size());
        validate(doc);
    }

    @Test
    void everyMountedRouteAppearsOnceWithItsScope() {
        JsonObject paths = build().getAsJsonObject("paths");
        int ops = 0;
        for (Route r : router.routes()) {
            JsonObject item = paths.getAsJsonObject(r.pattern());
            assertNotNull(item, r.pattern());
            JsonObject op = item.getAsJsonObject(r.method().toLowerCase(Locale.ROOT));
            assertNotNull(op, r.method() + " " + r.pattern());
            assertEquals(r.scope().id(), op.get("x-scope").getAsString());
            assertEquals(r.access().name().toLowerCase(Locale.ROOT), op.get("x-access").getAsString());
            assertEquals(r.group(), op.getAsJsonArray("tags").get(0).getAsString());
        }
        for (Map.Entry<String, JsonElement> e : paths.entrySet()) {
            ops += e.getValue().getAsJsonObject().size();
        }
        assertEquals(router.routes().size(), ops);
    }

    @Test
    void oneTagPerGroupInMountOrder() {
        JsonArray tags = build().getAsJsonArray("tags");
        List<String> names = new ArrayList<>();
        tags.forEach(t -> names.add(t.getAsJsonObject().get("name").getAsString()));
        assertEquals(List.of("core", "players", "console"), names);
        assertTrue(tags.get(1).getAsJsonObject().has("description"));
    }

    @Test
    void aSuiteGroupAppearsWhileMountedAndLeavesWithIt() {
        Sibling s = Sibling.DOMINION;
        router.mount(s.group(), List.of(route(s.group(), "GET", "/api/dominion/factions", s.scope(), Access.READ)));
        JsonObject doc = build();
        assertTrue(doc.getAsJsonObject("paths").has("/api/dominion/factions"));
        JsonObject tag = doc.getAsJsonArray("tags").get(3).getAsJsonObject();
        assertEquals("suite.dominion", tag.get("name").getAsString());
        assertTrue(tag.get("description").getAsString().contains("Dominion"));
        router.unmount(s.group());
        assertFalse(build().getAsJsonObject("paths").has("/api/dominion/factions"));
    }

    @Test
    void pathParametersAreDeclaredAndRequired() {
        JsonObject op = build().getAsJsonObject("paths").getAsJsonObject("/api/players/{player}/kick").getAsJsonObject("post");
        JsonObject p = op.getAsJsonArray("parameters").get(0).getAsJsonObject();
        assertEquals("player", p.get("name").getAsString());
        assertEquals("path", p.get("in").getAsString());
        assertTrue(p.get("required").getAsBoolean());
        assertEquals("A player name or UUID.", p.get("description").getAsString());
    }

    @Test
    void aRouteDocCanNameItsMediaTypeAndDescribeItsPathParameters() {
        router.mount("events", List.of(
                route("events", "GET", "/api/webhooks/{id}/feed", Scope.EVENTS, Access.READ)
                        .doc(new RouteDoc(List.of(), null, "hello", List.of())
                                .produces("text/event-stream")
                                .path(new RouteDoc.Param("id", "The webhook id from GET /api/webhooks.")))));
        JsonObject op = build().getAsJsonObject("paths").getAsJsonObject("/api/webhooks/{id}/feed").getAsJsonObject("get");
        assertEquals("The webhook id from GET /api/webhooks.",
                op.getAsJsonArray("parameters").get(0).getAsJsonObject().get("description").getAsString());
        assertTrue(op.getAsJsonObject("responses").getAsJsonObject("200").getAsJsonObject("content")
                .has("text/event-stream"));
    }

    @Test
    void queryParametersComeFromTheRouteDoc() {
        JsonObject op = build().getAsJsonObject("paths").getAsJsonObject("/api/players").getAsJsonObject("get");
        JsonObject p = op.getAsJsonArray("parameters").get(0).getAsJsonObject();
        assertEquals("world", p.get("name").getAsString());
        assertEquals("query", p.get("in").getAsString());
        assertFalse(p.get("required").getAsBoolean());
    }

    @Test
    void examplesBecomeSchemas() {
        JsonObject op = build().getAsJsonObject("paths").getAsJsonObject("/api/players").getAsJsonObject("get");
        JsonObject media = op.getAsJsonObject("responses").getAsJsonObject("200").getAsJsonObject("content")
                .getAsJsonObject("application/json");
        JsonObject item = media.getAsJsonObject("schema").getAsJsonObject("properties").getAsJsonObject("players")
                .getAsJsonObject("items").getAsJsonObject("properties");
        assertEquals("string", item.getAsJsonObject("name").get("type").getAsString());
        assertEquals("number", item.getAsJsonObject("x").get("type").getAsString());
        assertEquals("integer", item.getAsJsonObject("ping").get("type").getAsString());
        assertEquals("boolean", item.getAsJsonObject("op").get("type").getAsString());
        assertEquals("null", item.getAsJsonObject("world").get("type").getAsString());
        assertEquals("Steve", media.getAsJsonObject("example").getAsJsonArray("players").get(0).getAsJsonObject()
                .get("name").getAsString());

        JsonObject kick = build().getAsJsonObject("paths").getAsJsonObject("/api/players/{player}/kick").getAsJsonObject("post");
        assertEquals("string", kick.getAsJsonObject("requestBody").getAsJsonObject("content").getAsJsonObject("application/json")
                .getAsJsonObject("schema").getAsJsonObject("properties").getAsJsonObject("reason").get("type").getAsString());
    }

    @Test
    void textExamplesKeepTheirMediaType() {
        JsonObject paths = build().getAsJsonObject("paths");
        JsonObject stream = paths.getAsJsonObject("/api/console/stream").getAsJsonObject("get")
                .getAsJsonObject("responses").getAsJsonObject("200").getAsJsonObject("content");
        assertTrue(stream.has("text/event-stream"), stream.toString());
        JsonObject metrics = paths.getAsJsonObject("/metrics").getAsJsonObject("get")
                .getAsJsonObject("responses").getAsJsonObject("200").getAsJsonObject("content");
        assertTrue(metrics.has("text/plain"), metrics.toString());
        assertTrue(OpenApi.content("<!doctype html>").has("text/html"));
        assertTrue(OpenApi.content("{not json").has("text/plain"));
    }

    @Test
    void errorCodesAreListed() {
        JsonObject op = build().getAsJsonObject("paths").getAsJsonObject("/api/players/{player}").getAsJsonObject("get");
        assertEquals("player_not_found", op.getAsJsonArray("x-errors").get(0).getAsString());
        assertTrue(op.getAsJsonObject("responses").getAsJsonObject("4XX").get("description").getAsString()
                .contains("player_not_found"));
    }

    @Test
    void openRoutesMakeTheKeyOptional() {
        JsonObject op = build().getAsJsonObject("paths").getAsJsonObject("/api/health").getAsJsonObject("get");
        assertTrue(op.get("x-open").getAsBoolean());
        assertEquals(0, op.getAsJsonArray("security").get(0).getAsJsonObject().size());
        assertFalse(op.getAsJsonObject("responses").has("403"));
        JsonObject closed = build().getAsJsonObject("paths").getAsJsonObject("/api/server").getAsJsonObject("get");
        assertFalse(closed.has("x-open"));
        assertFalse(closed.has("security"));
    }

    @Test
    void writeAndDangerousRoutesSaySo() {
        JsonObject paths = build().getAsJsonObject("paths");
        assertTrue(paths.getAsJsonObject("/api/bans").getAsJsonObject("post").get("description").getAsString()
                .contains(Messages.get(DocsText.ACCESS_DANGEROUS)));
        JsonObject delete = paths.getAsJsonObject("/api/bans/{player}").getAsJsonObject("delete");
        assertTrue(delete.getAsJsonObject("responses").has("204"));
    }

    @Test
    void operationIdsAreReadableAndUnique() {
        Set<String> taken = new HashSet<>();
        assertEquals("postPlayersByPlayerKick",
                OpenApi.operationId(route("p", "POST", "/api/players/{player}/kick", Scope.PLAYERS, Access.WRITE), taken));
        assertEquals("getOpenapiJson", OpenApi.operationId(route("d", "GET", "/openapi.json", Scope.READ, Access.READ), taken));
        assertEquals("getApi", OpenApi.operationId(route("d", "GET", "/api", Scope.READ, Access.READ), taken));
        assertEquals("getMetrics", OpenApi.operationId(route("d", "GET", "/metrics", Scope.READ, Access.READ), taken));
        assertEquals("getMetrics2", OpenApi.operationId(route("d", "GET", "/api/metrics", Scope.READ, Access.READ), taken));
    }

    @Test
    void theTitleAndServerFollowTheMeta() {
        JsonObject doc = OpenApi.build(List.of(), new OpenApi.Meta("Mesa SMP", "1.0.0", "https://play.example.com/restapi", true));
        assertEquals("Mesa SMP", doc.getAsJsonObject("info").get("title").getAsString());
        assertEquals("https://play.example.com/restapi",
                doc.getAsJsonArray("servers").get(0).getAsJsonObject().get("url").getAsString());
        assertEquals(0, doc.getAsJsonObject("paths").size());
        validate(doc);
    }

    @Test
    void theDocumentSurvivesARoundTripThroughJson() {
        JsonObject doc = build();
        assertEquals(doc, JsonParser.parseString(doc.toString()));
    }

    @Test
    void docsTextFollowsTheWritingRules() {
        DocsText.INFO.length();
        Pattern colonReveal = Pattern.compile("^[A-Z][a-z ]+: ");
        for (String id : Messages.ids()) {
            if (!id.startsWith("docs.")) {
                continue;
            }
            String t = Messages.defaultOf(id);
            assertFalse(t.contains("\u2014"), id);
            assertFalse(t.contains(";"), id);
            assertFalse(t.contains("!"), id);
            assertFalse(colonReveal.matcher(t).find(), id);
            assertTrue(t.endsWith("."), id + " should end with a full stop");
        }
    }

    /**
     * The checks a strict validator makes that matter here. Every path template parameter is declared in the
     * path, every operation has responses with a description, operation ids are unique, and every $ref resolves.
     */
    static void validate(JsonObject doc) {
        assertTrue(doc.has("openapi") && doc.has("info") && doc.has("paths"));
        Set<String> ids = new HashSet<>();
        Pattern param = Pattern.compile("\\{([^/}]+)}");
        for (Map.Entry<String, JsonElement> p : doc.getAsJsonObject("paths").entrySet()) {
            assertTrue(p.getKey().startsWith("/"), p.getKey());
            Set<String> template = new HashSet<>();
            var m = param.matcher(p.getKey());
            while (m.find()) {
                template.add(m.group(1));
            }
            for (Map.Entry<String, JsonElement> o : p.getValue().getAsJsonObject().entrySet()) {
                assertTrue(Set.of("get", "put", "post", "delete", "patch", "head", "options").contains(o.getKey()), o.getKey());
                JsonObject op = o.getValue().getAsJsonObject();
                assertTrue(ids.add(op.get("operationId").getAsString()), "repeated operationId");
                Set<String> declared = new HashSet<>();
                if (op.has("parameters")) {
                    for (JsonElement x : op.getAsJsonArray("parameters")) {
                        JsonObject po = x.getAsJsonObject();
                        assertTrue(po.has("schema"));
                        if (po.get("in").getAsString().equals("path")) {
                            assertTrue(po.get("required").getAsBoolean());
                            declared.add(po.get("name").getAsString());
                        }
                    }
                }
                assertEquals(template, declared, p.getKey());
                JsonObject responses = op.getAsJsonObject("responses");
                assertFalse(responses.isEmpty());
                for (Map.Entry<String, JsonElement> r : responses.entrySet()) {
                    assertTrue(r.getKey().matches("[1-5](\\d\\d|XX)|default"), r.getKey());
                    JsonObject ro = r.getValue().getAsJsonObject();
                    assertTrue(ro.has("$ref") || ro.has("description"), r.getKey());
                }
            }
        }
        checkRefs(doc, doc);
    }

    private static void checkRefs(JsonObject root, JsonElement at) {
        if (at.isJsonObject()) {
            for (Map.Entry<String, JsonElement> e : at.getAsJsonObject().entrySet()) {
                if (e.getKey().equals("$ref")) {
                    JsonElement target = root;
                    for (String part : e.getValue().getAsString().substring(2).split("/")) {
                        assertTrue(target.isJsonObject() && target.getAsJsonObject().has(part), e.getValue().getAsString());
                        target = target.getAsJsonObject().get(part);
                    }
                } else {
                    checkRefs(root, e.getValue());
                }
            }
        } else if (at.isJsonArray()) {
            at.getAsJsonArray().forEach(x -> checkRefs(root, x));
        }
    }
}
