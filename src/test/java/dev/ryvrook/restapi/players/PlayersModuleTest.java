package dev.ryvrook.restapi.players;

import dev.ryvrook.restapi.auth.Scope;
import dev.ryvrook.restapi.config.Settings;
import dev.ryvrook.restapi.http.Access;
import dev.ryvrook.restapi.http.ApiException;
import dev.ryvrook.restapi.http.Call;
import dev.ryvrook.restapi.http.Request;
import dev.ryvrook.restapi.http.Route;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class PlayersModuleTest {
    @TempDir
    Path dir;

    @AfterEach
    void defaults() {
        Settings.useDefaults();
    }

    private static Set<String> keys(List<Route> routes) {
        Set<String> out = new HashSet<>();
        for (Route r : routes) {
            assertTrue(out.add(r.method() + " " + r.pattern()), "twice " + r.method() + " " + r.pattern());
        }
        return out;
    }

    @Test
    void everyRouteOfThePlanIsThere() {
        PlayersModule m = new PlayersModule();
        List<Route> all = new java.util.ArrayList<>(m.reads());
        all.addAll(m.writes());
        Set<String> k = keys(all);
        for (String want : List.of("GET /api/players", "GET /api/players/{player}", "GET /api/players/{player}/inventory",
                "GET /api/players/offline", "POST /api/players/{player}/kick", "POST /api/players/{player}/message",
                "POST /api/players/{player}/title", "POST /api/players/{player}/teleport", "POST /api/players/{player}/give",
                "POST /api/players/{player}/clear", "PUT /api/players/{player}/gamemode", "POST /api/players/{player}/heal",
                "POST /api/players/{player}/kill", "POST /api/players/{player}/refer", "GET /api/bans", "POST /api/bans",
                "DELETE /api/bans/{player}", "GET /api/whitelist", "PUT /api/whitelist", "POST /api/whitelist",
                "DELETE /api/whitelist/{player}", "GET /api/permissions/groups", "GET /api/players/{player}/groups",
                "PUT /api/players/{player}/groups", "POST /api/players/{player}/groups/{group}",
                "DELETE /api/players/{player}/groups/{group}", "POST /api/broadcast", "POST /api/broadcast/title")) {
            assertTrue(k.contains(want), want);
        }
        assertEquals(28, all.size());
    }

    @Test
    void readsAndWritesHaveTheRightScopeAndKind() {
        PlayersModule m = new PlayersModule();
        for (Route r : m.reads()) {
            assertEquals("players", r.group());
            assertEquals(Access.READ, r.access());
            assertEquals("GET", r.method());
            assertTrue(r.summary().endsWith("."), r.pattern());
            assertFalse(r.doc().errors().isEmpty());
            if (!r.pattern().endsWith("/inventory")) {
                assertEquals(Scope.READ, r.scope(), r.pattern());
            }
        }
        for (Route r : m.writes()) {
            assertEquals(Scope.PLAYERS, r.scope(), r.pattern());
            assertNotEquals(Access.READ, r.access(), r.pattern());
            assertTrue(r.summary().endsWith("."), r.pattern());
            assertNotNull(r.doc().response(), r.pattern());
        }
        Set<String> dangerous = new HashSet<>();
        m.writes().stream().filter(r -> r.access() == Access.DANGEROUS).forEach(r -> dangerous.add(r.method() + " " + r.pattern()));
        assertTrue(dangerous.containsAll(List.of("POST /api/bans", "POST /api/players/{player}/kill",
                "POST /api/players/{player}/clear", "PUT /api/players/{player}/groups")));
    }

    @Test
    void killClearAndReferFollowTheirSwitches() throws Exception {
        Path f = dir.resolve("RestAPI.json");
        new PlayersModule();
        Files.writeString(f, "{\"players\": {\"allowKill\": false, \"allowClear\": false, \"allowRefer\": false}}");
        assertTrue(Settings.load(f).ok());
        Set<String> k = keys(new PlayersModule().writes());
        assertFalse(k.contains("POST /api/players/{player}/kill"));
        assertFalse(k.contains("POST /api/players/{player}/clear"));
        assertFalse(k.contains("POST /api/players/{player}/refer"));
        assertTrue(k.contains("POST /api/players/{player}/kick"));
    }

    @Test
    void examplesAreValidJson() {
        PlayersModule m = new PlayersModule();
        List<Route> all = new java.util.ArrayList<>(m.reads());
        all.addAll(m.writes());
        for (Route r : all) {
            if (r.doc().request() != null) {
                assertDoesNotThrow(() -> com.google.gson.JsonParser.parseString(r.doc().request()), r.pattern());
            }
            if (r.doc().response() != null) {
                assertTrue(com.google.gson.JsonParser.parseString(r.doc().response()).isJsonObject(), r.pattern());
            }
        }
    }

    @Test
    void theRoutesMountAndMatchTheRightHandlers() {
        PlayersModule m = new PlayersModule();
        List<Route> all = new java.util.ArrayList<>(m.reads());
        all.addAll(m.writes());
        dev.ryvrook.restapi.http.Router router = new dev.ryvrook.restapi.http.Router();
        router.mount("players", all);
        var offline = (dev.ryvrook.restapi.http.Router.Found) router.match("GET", "/api/players/offline");
        assertEquals("/api/players/offline", offline.route().pattern());
        var group = (dev.ryvrook.restapi.http.Router.Found) router.match("POST", "/api/players/Steve/groups/hytale%3ABuilder");
        assertEquals("/api/players/{player}/groups/{group}", group.route().pattern());
        Call c = new Call(Request.of("POST", "/x", "127.0.0.1"), null, "127.0.0.1", group.params());
        assertEquals("hytale:Builder", c.param("group"));
        assertInstanceOf(dev.ryvrook.restapi.http.Router.WrongMethod.class, router.match("PATCH", "/api/players/Steve/kick"));
    }

    @Test
    void pagingIsCheckedAgainstTheLimits() {
        new PlayersModule();
        Call none = new Call(Request.of("GET", "/api/bans", "127.0.0.1"), null, "127.0.0.1", Map.of());
        assertArrayEquals(new int[]{100, 0}, PlayersModule.page(none));
        Call some = new Call(Request.of("GET", "/api/bans?limit=5&offset=10", "127.0.0.1"), null, "127.0.0.1", Map.of());
        assertArrayEquals(new int[]{5, 10}, PlayersModule.page(some));
        Call big = new Call(Request.of("GET", "/api/bans?limit=5000", "127.0.0.1"), null, "127.0.0.1", Map.of());
        assertEquals("invalid_field", assertThrows(ApiException.class, () -> PlayersModule.page(big)).code());
        Call word = new Call(Request.of("GET", "/api/bans?offset=x", "127.0.0.1"), null, "127.0.0.1", Map.of());
        assertThrows(ApiException.class, () -> PlayersModule.page(word));
    }
}
