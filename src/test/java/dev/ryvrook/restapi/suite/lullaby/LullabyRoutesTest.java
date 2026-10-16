package dev.ryvrook.restapi.suite.lullaby;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import dev.ryvrook.lullaby.api.LullabyApi;
import dev.ryvrook.lullaby.api.SleepStatus;
import dev.ryvrook.restapi.auth.Scope;
import dev.ryvrook.restapi.http.Route;
import dev.ryvrook.restapi.suite.Calls;
import dev.ryvrook.restapi.suite.JsonFile;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class LullabyRoutesTest {
    /** Lullaby 1.1.0 with two worlds, one asleep halfway and one left to the engine. */
    static final class FakeLullaby extends LullabyApi {
        final List<SleepStatus> statuses = List.of(
                new SleepStatus("default", true, "percent", 4, 4, 1, 1, 2, 0.5, 0, 12),
                new SleepStatus("nether", false, "percent", 0, 0, 0, 0, 1, 0.0, 30, 3));

        @Override public int apiVersion() { return 1; }
        @Override public String version() { return "1.1.0"; }
        @Override public String mode() { return "native"; }
        @Override public List<String> worlds() { return List.of("default", "nether"); }
        @Override public SleepStatus status(String world) {
            return statuses.stream().filter(s -> s.world().equals(world)).findFirst().orElse(null);
        }
        @Override public List<SleepStatus> statuses() { return statuses; }
        @Override public int sleepers(String world) { return status(world).inBed(); }
        @Override public int needed(String world) { return status(world).needed(); }
        @Override public double progress(String world) { return status(world).progress(); }
        @Override public int nightsSkipped(String world) { return status(world).nightsSkipped(); }
        @Override public int nightsSkipped() { return 15; }
        @Override public Map<String, Integer> nightsSkippedByWorld() { return Map.of("default", 12, "nether", 3); }
    }

    Path dir;
    AtomicReference<LullabyApi> api;
    Calls calls;

    @BeforeEach
    void setUp(@TempDir Path dir) {
        this.dir = dir;
        api = new AtomicReference<>(new FakeLullaby());
        calls = new Calls(LullabyRoutes.routes(api::get, new JsonFile(dir.resolve("ignored.json"), "Lullaby")));
    }

    @Test
    void routesStayUnderLullaby() {
        for (Route r : LullabyRoutes.routes(() -> null, null)) {
            assertTrue(r.pattern().equals("/api/lullaby") || r.pattern().startsWith("/api/lullaby/"));
            assertEquals(Scope.LULLABY, r.scope());
        }
    }

    @Test
    void theSummaryComesFromTheApi() throws Exception {
        JsonObject o = Calls.json(calls.get("/api/lullaby"));
        assertEquals("native", o.get("mode").getAsString());
        assertEquals("1.1.0", o.get("version").getAsString());
        assertEquals(15, o.get("nightsSkipped").getAsInt());
        assertEquals(0, o.get("ignoredPlayers").getAsInt());
        assertTrue(o.get("sleepersKnown").getAsBoolean());
        JsonArray worlds = o.getAsJsonArray("worlds");
        assertEquals(2, worlds.size());
        JsonObject def = worlds.get(0).getAsJsonObject();
        assertEquals(1, def.get("inBed").getAsInt());
        assertEquals(2, def.get("needed").getAsInt());
        assertEquals(1, def.get("missing").getAsInt());
        assertEquals(0.5, def.get("progress").getAsDouble());
        Files.writeString(dir.resolve("ignored.json"), "{\"Ignored\": [\"00000000-0000-0000-0000-00000000000a\"]}");
        assertEquals(1, Calls.json(calls.get("/api/lullaby")).get("ignoredPlayers").getAsInt());
    }

    @Test
    void oneWorld() {
        JsonObject nether = Calls.json(calls.get("/api/lullaby/worlds/nether"));
        assertFalse(nether.get("enabled").getAsBoolean());
        assertEquals(30, nether.get("cooldownSeconds").getAsLong());
        assertEquals(3, nether.get("nightsSkipped").getAsInt());
        assertEquals(404, calls.get("/api/lullaby/worlds/moon").status());
    }

    @Test
    void aStoppedLullabyAnswers503() {
        api.set(null);
        assertEquals(503, calls.get("/api/lullaby").status());
    }
}
