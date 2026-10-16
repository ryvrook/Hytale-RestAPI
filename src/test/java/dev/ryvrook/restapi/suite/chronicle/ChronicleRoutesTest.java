package dev.ryvrook.restapi.suite.chronicle;

import com.google.gson.JsonObject;
import dev.ryvrook.chronicle.api.Category;
import dev.ryvrook.chronicle.api.StatDef;
import dev.ryvrook.chronicle.api.Window;
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
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.*;

class ChronicleRoutesTest {
    static final UUID STEVE = UUID.fromString("00000000-0000-0000-0000-00000000000a");

    static final List<StatDef> DEFS = List.of(
            StatDef.builder("blocks_broken").label("Blocks broken").category(Category.MINING).boardEligible(true).aggregation(dev.ryvrook.chronicle.api.Aggregation.SUM).windowed(true).build(),
            StatDef.builder("blocks_broken_by:").label("Blocks broken by block").category(Category.MINING).build(),
            StatDef.builder("deaths").label("Deaths").category(Category.COMBAT).boardEligible(true).aggregation(dev.ryvrook.chronicle.api.Aggregation.SUM).windowed(true).build());

    static final class FakeChronicle implements ChronicleSource {
        Window lastWindow;
        String lastWorld;

        @Override
        public int apiVersion() {
            return 1;
        }

        @Override
        public List<StatDef> stats() {
            return DEFS;
        }

        @Override
        public CompletableFuture<Map<String, Long>> totals(UUID player) {
            return CompletableFuture.completedFuture(Map.of("blocks_broken", 812L, "blocks_broken_by:Rock", 700L,
                    "deaths", 3L, "mystery", 1L));
        }

        @Override
        public long window(UUID player, String key, Window window, String worldKey) {
            lastWindow = window;
            lastWorld = worldKey;
            return "deaths".equals(key) ? 1 : 20;
        }

        @Override
        public Map<String, Long> perId(UUID player, String prefix) {
            return Map.of("Rock", 700L, "Dirt", 112L);
        }

        @Override
        public String worldKey(String world) {
            return "w_" + world;
        }

        @Override
        public boolean isTracked(UUID player) {
            return true;
        }
    }

    Path file;
    FakeChronicle chronicle;
    Calls calls;

    @BeforeEach
    void setUp(@TempDir Path dir) {
        file = dir.resolve("stats.json");
        chronicle = new FakeChronicle();
        calls = new Calls(ChronicleRoutes.routes(chronicle, new JsonFile(file, "Chronicle Stats"), () -> Limits.DEFAULTS));
    }

    void export() throws Exception {
        Files.writeString(file, "{\"Version\": 1, \"updatedAt\": 77, \"online\": 2, \"chronicleVersion\": \"1.4.0\","
                + "\"server\": {\"blocks_broken\": 9000}, \"boards\": {\"blocks_broken\": ["
                + "{\"uuid\": \"" + STEVE + "\", \"name\": \"Steve\", \"value\": 812},"
                + "{\"uuid\": null, \"name\": \"Hidden\", \"value\": 500}]}, \"recentRecords\": []}");
    }

    @Test
    void routesStayUnderChronicle() {
        for (Route r : ChronicleRoutes.routes(chronicle, new JsonFile(file, "x"), () -> Limits.DEFAULTS)) {
            assertTrue(r.pattern().equals("/api/chronicle") || r.pattern().startsWith("/api/chronicle/"));
            assertEquals(Scope.CHRONICLE, r.scope());
        }
    }

    @Test
    void definitionsByCategory() {
        assertEquals(3, Calls.json(calls.get("/api/chronicle/stats")).getAsJsonArray("stats").size());
        JsonObject d = Calls.json(calls.get("/api/chronicle/stats?category=combat")).getAsJsonArray("stats").get(0).getAsJsonObject();
        assertEquals("deaths", d.get("key").getAsString());
        assertTrue(d.get("windowed").getAsBoolean());
    }

    @Test
    void allTimeTotalsGroupedByCategory() {
        JsonObject o = Calls.json(calls.get("/api/chronicle/players/" + STEVE));
        JsonObject cats = o.getAsJsonObject("categories");
        assertEquals(812, cats.getAsJsonObject("mining").get("blocks_broken").getAsLong());
        assertEquals(700, cats.getAsJsonObject("mining").get("blocks_broken_by:Rock").getAsLong());
        assertEquals(3, cats.getAsJsonObject("combat").get("deaths").getAsLong());
        assertEquals(1, cats.getAsJsonObject("other").get("mystery").getAsLong());
        assertEquals(1, Calls.json(calls.get("/api/chronicle/players/" + STEVE + "?category=combat")).getAsJsonObject("categories").size());
    }

    @Test
    void aWindowReadsTheWindowedStatsInOneWorld() {
        JsonObject o = Calls.json(calls.get("/api/chronicle/players/" + STEVE + "?window=week&world=default"));
        assertEquals("week", o.get("window").getAsString());
        assertEquals(Window.WEEK, chronicle.lastWindow);
        assertEquals("w_default", chronicle.lastWorld);
        assertEquals(20, o.getAsJsonObject("categories").getAsJsonObject("mining").get("blocks_broken").getAsLong());
        assertFalse(o.getAsJsonObject("categories").getAsJsonObject("mining").has("blocks_broken_by:"));
        assertEquals(400, calls.get("/api/chronicle/players/" + STEVE + "?window=year").status());
    }

    @Test
    void oneStatAndAPrefixStat() {
        assertEquals(812, Calls.json(calls.get("/api/chronicle/players/" + STEVE + "/blocks_broken")).get("value").getAsLong());
        JsonObject ids = Calls.json(calls.get("/api/chronicle/players/" + STEVE + "/blocks_broken_by:")).getAsJsonObject("ids");
        assertEquals(112, ids.get("Dirt").getAsLong());
        assertEquals(1, Calls.json(calls.get("/api/chronicle/players/" + STEVE + "/deaths?window=today")).get("value").getAsLong());
        assertEquals(404, calls.get("/api/chronicle/players/" + STEVE + "/nothing").status());
    }

    @Test
    void boardsAndServerComeFromTheExport() throws Exception {
        assertEquals(503, calls.get("/api/chronicle/server").status());
        export();
        JsonObject top = Calls.json(calls.get("/api/chronicle/top/blocks_broken?limit=5"));
        assertEquals(2, top.getAsJsonArray("rows").size());
        assertEquals(2, top.getAsJsonArray("rows").get(1).getAsJsonObject().get("rank").getAsInt());
        Response week = calls.get("/api/chronicle/top/blocks_broken?window=week");
        assertEquals(400, week.status());
        assertEquals("not_exported", Calls.json(week).get("error").getAsString());
        assertEquals(404, calls.get("/api/chronicle/top/nothing").status());
        JsonObject server = Calls.json(calls.get("/api/chronicle/server"));
        assertEquals(9000, server.getAsJsonObject("server").get("blocks_broken").getAsLong());
        assertEquals(2, server.get("online").getAsInt());
        JsonObject summary = Calls.json(calls.get("/api/chronicle"));
        assertTrue(summary.get("exported").getAsBoolean());
        assertEquals(3, summary.get("stats").getAsInt());
        assertEquals(STEVE.toString(), Calls.json(calls.get("/api/chronicle/players/steve")).get("uuid").getAsString());
    }

    @Test
    void aPrefixDefinitionOwnsItsKeys() {
        assertEquals("blocks_broken_by:", ChronicleRoutes.defOf(DEFS, "blocks_broken_by:Rock").key());
        assertEquals("blocks_broken", ChronicleRoutes.defOf(DEFS, "blocks_broken").key());
        assertNull(ChronicleRoutes.defOf(DEFS, "jumps"));
    }
}
