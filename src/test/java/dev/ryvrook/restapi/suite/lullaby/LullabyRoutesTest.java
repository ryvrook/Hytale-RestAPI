package dev.ryvrook.restapi.suite.lullaby;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.ryvrook.restapi.auth.Scope;
import dev.ryvrook.restapi.http.Route;
import dev.ryvrook.restapi.suite.Calls;
import dev.ryvrook.restapi.suite.JsonFile;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class LullabyRoutesTest {
    Path dir;
    Map<String, Integer> players;
    Calls calls;

    @BeforeEach
    void setUp(@TempDir Path dir) {
        this.dir = dir;
        players = new LinkedHashMap<>();
        players.put("default", 4);
        players.put("nether", 0);
        List<Route> routes = LullabyRoutes.routes(new JsonFile(dir.resolve("Lullaby.json"), "Lullaby"),
                new JsonFile(dir.resolve("stats.json"), "Lullaby"), new JsonFile(dir.resolve("ignored.json"), "Lullaby"), () -> players);
        calls = new Calls(routes);
    }

    @Test
    void routesStayUnderLullaby() {
        for (Route r : LullabyRoutes.routes(null, null, null, Map::of)) {
            assertTrue(r.pattern().equals("/api/lullaby") || r.pattern().startsWith("/api/lullaby/"));
            assertEquals(Scope.LULLABY, r.scope());
        }
    }

    @Test
    void withNoFilesTheDefaultsApply() {
        JsonObject o = Calls.json(calls.get("/api/lullaby"));
        assertFalse(o.get("configRead").getAsBoolean());
        assertEquals("percent", o.get("requirement").getAsString());
        assertEquals(0, o.get("nightsSkipped").getAsLong());
        JsonObject def = o.getAsJsonArray("worlds").get(0).getAsJsonObject();
        assertEquals("default", def.get("world").getAsString());
        assertEquals(2, def.get("neededOfOnline").getAsInt());
        assertFalse(o.get("sleepersKnown").getAsBoolean());
    }

    @Test
    void theFilesAreRead() throws Exception {
        Files.writeString(dir.resolve("Lullaby.json"), "{\"Mode\": \"manual\", \"Requirement\": \"count\", \"Count\": 3,"
                + " \"MinimumSleepers\": 1, \"Worlds\": {\"nether\": {\"Enabled\": false}, \"arena\": {\"Requirement\": \"percent\", \"Percent\": 100}}}");
        Files.writeString(dir.resolve("stats.json"), "{\"NightsSkipped\": {\"default\": 12, \"old\": {\"$numberLong\": \"3\"}}}");
        Files.writeString(dir.resolve("ignored.json"), "{\"Ignored\": [\"00000000-0000-0000-0000-00000000000a\"]}");
        JsonObject o = Calls.json(calls.get("/api/lullaby"));
        assertEquals("manual", o.get("mode").getAsString());
        assertEquals(1, o.get("ignoredPlayers").getAsInt());
        assertEquals(15, o.get("nightsSkipped").getAsLong());
        JsonArray worlds = o.getAsJsonArray("worlds");
        assertEquals(4, worlds.size());
        JsonObject def = Calls.json(calls.get("/api/lullaby/worlds/default"));
        assertEquals(3, def.get("neededOfOnline").getAsInt());
        assertEquals(12, def.get("nightsSkipped").getAsLong());
        assertFalse(Calls.json(calls.get("/api/lullaby/worlds/nether")).get("enabled").getAsBoolean());
        JsonObject arena = Calls.json(calls.get("/api/lullaby/worlds/arena"));
        assertEquals("percent", arena.get("requirement").getAsString());
        assertFalse(arena.get("loaded").getAsBoolean());
        assertEquals(404, calls.get("/api/lullaby/worlds/moon").status());
    }

    @Test
    void neededFollowsLullabysRule() {
        JsonObject cfg = JsonParser.parseString("{\"Requirement\": \"percent\", \"Percent\": 50, \"MinimumSleepers\": 1}").getAsJsonObject();
        SleepRule half = SleepRule.of(cfg, "default");
        assertEquals(1, half.needed(1));
        assertEquals(2, half.needed(3));
        assertEquals(1, half.needed(0));
        SleepRule count = SleepRule.of(JsonParser.parseString("{\"Requirement\": \"count\", \"Count\": 5}").getAsJsonObject(), "w");
        assertEquals(3, count.needed(3));
        SleepRule min = SleepRule.of(JsonParser.parseString("{\"Percent\": 10, \"MinimumSleepers\": 2}").getAsJsonObject(), "w");
        assertEquals(2, min.needed(10));
        SleepRule dynamic = SleepRule.of(JsonParser.parseString("{\"Requirement\": \"dynamic\"}").getAsJsonObject(), "w");
        assertEquals(1, dynamic.needed(8));
    }
}
