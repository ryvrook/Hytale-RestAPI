package dev.ryvrook.restapi.suite.feats;

import com.google.gson.JsonObject;
import dev.ryvrook.feats.api.FeatInfo;
import dev.ryvrook.feats.api.FeatProgress;
import dev.ryvrook.feats.api.WorldRecord;
import dev.ryvrook.restapi.auth.Scope;
import dev.ryvrook.restapi.http.Response;
import dev.ryvrook.restapi.http.Route;
import dev.ryvrook.restapi.suite.Calls;
import dev.ryvrook.restapi.suite.JsonFile;
import dev.ryvrook.restapi.suite.Limits;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class FeatsRoutesTest {
    static final UUID STEVE = UUID.fromString("00000000-0000-0000-0000-00000000000a");

    /** Feats as 1.6.0 answered, with no top call, so the board comes from the export. */
    static class FakeFeats implements FeatsSource {
        @Override
        public List<FeatInfo> feats() {
            return List.of(new FeatInfo("first_steps", "First Steps", "Walk.", "Exploration", "Bronze", false, 5, "personal"),
                    new FeatInfo("miner", "Miner", "Break 100 blocks.", "Mining", "Silver", false, 10, "personal"),
                    new FeatInfo("secret", "Secret", "Hidden.", "Mining", "Gold", true, 25, "personal"));
        }

        @Override
        public int points(UUID player) {
            return STEVE.equals(player) ? 5 : 0;
        }

        @Override
        public Set<String> unlocked(UUID player) {
            return STEVE.equals(player) ? new LinkedHashSet<>(List.of("first_steps")) : Set.of();
        }

        @Override
        public FeatProgress progress(UUID player, String featId) {
            return "miner".equals(featId) ? new FeatProgress(40, 100) : new FeatProgress(0, 0);
        }

        @Override
        public String title(UUID player) {
            return "first_steps";
        }

        @Override
        public int rank(UUID player) {
            return 3;
        }

        @Override
        public Map<String, WorldRecord> milestones() {
            return Map.of("world_miner", new WorldRecord("world_miner", 9L, STEVE, "Steve"));
        }

        @Override
        public Map<String, WorldRecord> firsts() {
            return Map.of();
        }

        @Override
        public long stats(UUID player, String key) {
            return "blocks_broken".equals(key) ? 812 : 0;
        }
    }

    Path file;
    Calls calls;

    @BeforeEach
    void setUp(@TempDir Path dir) {
        file = dir.resolve("feats.json");
        calls = new Calls(FeatsRoutes.routes(new FakeFeats(), new JsonFile(file, "Feats"), () -> Limits.DEFAULTS));
    }

    void export() throws Exception {
        Files.writeString(file, "{\"totalFeats\": 3, \"online\": 1, \"players\": ["
                + "{\"uuid\": \"" + STEVE + "\", \"name\": \"Steve\", \"points\": 5, \"unlocked\": 1, \"lastUnlockAt\": 3},"
                + "{\"uuid\": \"00000000-0000-0000-0000-00000000000b\", \"name\": \"Alex\", \"points\": 0, \"unlocked\": 0, \"lastUnlockAt\": 0}],"
                + "\"recent\": [{\"at\": 3, \"uuid\": \"" + STEVE + "\", \"name\": \"Steve\", \"feat\": \"first_steps\"}],"
                + "\"rarity\": {\"first_steps\": 0.5}}");
    }

    @Test
    void routesStayUnderFeats() {
        for (Route r : FeatsRoutes.routes(new FakeFeats(), new JsonFile(file, "Feats"), () -> Limits.DEFAULTS)) {
            assertTrue(r.pattern().equals("/api/feats") || r.pattern().startsWith("/api/feats/"));
            assertEquals(Scope.FEATS, r.scope());
        }
    }

    @Test
    void theListFiltersAndAddsRarity() throws Exception {
        assertEquals(3, Calls.json(calls.get("/api/feats")).get("count").getAsInt());
        assertEquals(2, Calls.json(calls.get("/api/feats?hidden=false")).get("count").getAsInt());
        assertEquals(2, Calls.json(calls.get("/api/feats?category=mining")).get("count").getAsInt());
        export();
        JsonObject first = Calls.json(calls.get("/api/feats?tier=bronze")).getAsJsonArray("feats").get(0).getAsJsonObject();
        assertEquals(0.5, first.get("rarity").getAsDouble());
    }

    @Test
    void aPlayerWithProgress() {
        JsonObject o = Calls.json(calls.get("/api/feats/players/" + STEVE + "?progress=true"));
        assertEquals(5, o.get("points").getAsInt());
        assertEquals(3, o.get("rank").getAsInt());
        assertEquals("First Steps", o.getAsJsonArray("unlocked").get(0).getAsJsonObject().get("name").getAsString());
        assertEquals(1, o.getAsJsonArray("progress").size());
        assertEquals(40, o.getAsJsonArray("progress").get(0).getAsJsonObject().get("current").getAsLong());
        assertFalse(Calls.json(calls.get("/api/feats/players/" + STEVE)).has("progress"));
    }

    @Test
    void namesComeFromTheExportForOfflinePlayers() throws Exception {
        assertEquals(404, calls.get("/api/feats/players/Steve").status());
        export();
        assertEquals(STEVE.toString(), Calls.json(calls.get("/api/feats/players/steve")).get("uuid").getAsString());
        assertEquals(812, Calls.json(calls.get("/api/feats/players/Steve/stats/blocks_broken")).get("value").getAsLong());
    }

    @Test
    void records() {
        JsonObject o = Calls.json(calls.get("/api/feats/records"));
        assertEquals("Steve", o.getAsJsonArray("milestones").get(0).getAsJsonObject().get("byName").getAsString());
        assertEquals(0, o.getAsJsonArray("firsts").size());
    }

    @Test
    void anOlderFeatsGivesTheBoardAndRecentUnlocksFromTheExport() throws Exception {
        Response missing = calls.get("/api/feats/top");
        assertEquals(503, missing.status());
        assertEquals("file_missing", Calls.json(missing).get("error").getAsString());
        export();
        JsonObject top = Calls.json(calls.get("/api/feats/top?limit=1"));
        assertEquals(1, top.getAsJsonArray("players").size());
        assertEquals("Steve", top.getAsJsonArray("players").get(0).getAsJsonObject().get("name").getAsString());
        assertEquals(1, top.get("online").getAsInt());
        assertEquals("export", top.get("source").getAsString());
        assertEquals(1, Calls.json(calls.get("/api/feats/recent")).getAsJsonArray("unlocks").size());
    }

    @Test
    void theBoardComesFromFeatsTop() {
        int[] asked = {0};
        FeatsSource live = new FakeFeats() {
            @Override
            public List<dev.ryvrook.feats.api.LeaderboardEntry> top(int limit) {
                asked[0] = limit;
                return List.of(new dev.ryvrook.feats.api.LeaderboardEntry(1, STEVE, "Steve", 140, 31, 99L),
                        new dev.ryvrook.feats.api.LeaderboardEntry(2, null, "Alex", 90, 12, 50L));
            }
        };
        Calls calls = new Calls(FeatsRoutes.routes(live, new JsonFile(file, "Feats"), () -> Limits.DEFAULTS));
        JsonObject top = Calls.json(calls.get("/api/feats/top?limit=2"));
        assertEquals(2, asked[0]);
        assertEquals("api", top.get("source").getAsString());
        JsonObject first = top.getAsJsonArray("players").get(0).getAsJsonObject();
        assertEquals(140, first.get("points").getAsInt());
        assertEquals(31, first.get("unlocked").getAsInt());
        assertEquals(STEVE.toString(), first.get("uuid").getAsString());
        assertTrue(top.getAsJsonArray("players").get(1).getAsJsonObject().get("uuid").isJsonNull());
    }
}
