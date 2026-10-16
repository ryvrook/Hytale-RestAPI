package dev.ryvrook.restapi.suite.atlas;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import dev.ryvrook.atlas.api.AtlasApi;
import dev.ryvrook.atlas.api.MarkerLayer;
import dev.ryvrook.atlas.api.Tier;
import dev.ryvrook.restapi.auth.Scope;
import dev.ryvrook.restapi.http.Route;
import dev.ryvrook.restapi.suite.Calls;
import dev.ryvrook.restapi.suite.JsonFile;
import dev.ryvrook.restapi.suite.Limits;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class AtlasRoutesTest {
    /** AtlasApi with layers made the way Atlas makes them. */
    static final class FakeAtlas extends AtlasApi {
        final List<MarkerLayer> layers = new ArrayList<>();

        MarkerLayer add(String plugin, String id) {
            MarkerLayer l = newLayer(plugin, id, (layer, world) -> { });
            layers.add(l);
            return l;
        }

        @Override
        public MarkerLayer layer(String pluginId, String id) {
            return add(pluginId, id);
        }

        @Override
        public List<MarkerLayer> layers() {
            return layers;
        }

        @Override
        public List<MarkerLayer> layers(String pluginId) {
            return layers.stream().filter(l -> l.pluginId().equals(pluginId)).toList();
        }

        @Override
        public boolean removeLayer(String pluginId, String id) {
            return false;
        }

        @Override
        public void setHidden(UUID player, boolean hidden, String by) {
        }

        @Override
        public boolean isHidden(UUID player) {
            return false;
        }
    }

    FakeAtlas atlas;
    Path config;
    AtomicBoolean probe;
    AtomicReference<String> probed;
    Calls calls;
    final UUID hiddenPlayer = UUID.fromString("00000000-0000-0000-0000-00000000000a");

    @BeforeEach
    void setUp(@TempDir Path dir) {
        atlas = new FakeAtlas();
        MarkerLayer sieges = atlas.add("Ryvrook:Dominion", "sieges").label("Sieges").tier(Tier.PUBLIC);
        sieges.point("default", "siege:1", 120, -40).label("NRT against SOU").icon("Siege");
        sieges.circle("default", "zone:1", 10, 10, 32).fill("#ff0000");
        atlas.add("Ryvrook:Feats", "firsts").polygon("nether", "area", List.of(new double[]{0, 0}, new double[]{0, 5}, new double[]{5, 5}));
        config = dir.resolve("atlas.json");
        probe = new AtomicBoolean(true);
        probed = new AtomicReference<>();
        calls = new Calls(AtlasRoutes.routes(() -> atlas, new JsonFile(config, "Atlas"), () -> Set.of(hiddenPlayer),
                probe::get, (bind, port, base) -> {
                    probed.set(bind + " " + port + " " + base);
                    JsonObject o = new JsonObject();
                    o.addProperty("reachable", true);
                    return o;
                }, () -> Limits.DEFAULTS));
    }

    @Test
    void routesStayUnderAtlas() {
        for (Route r : AtlasRoutes.routes(() -> atlas, new JsonFile(config, "Atlas"), Set::of, () -> false, null, () -> Limits.DEFAULTS)) {
            assertTrue(r.pattern().equals("/api/atlas") || r.pattern().startsWith("/api/atlas/"));
            assertEquals(Scope.ATLAS, r.scope());
        }
    }

    @Test
    void theSummaryReadsTheAddressButNeverTheToken() throws Exception {
        Files.writeString(config, "{\"bind\": \"127.0.0.1\", \"port\": 8190, \"basePath\": \"/map\", \"publicUrl\": \"\", \"adminToken\": \"secret\"}");
        JsonObject o = Calls.json(calls.get("/api/atlas"));
        assertEquals("http://127.0.0.1:8190/map/", o.get("url").getAsString());
        assertFalse(o.get("publicUrlSet").getAsBoolean());
        assertEquals(2, o.get("layers").getAsInt());
        assertEquals(3, o.get("markers").getAsInt());
        assertTrue(o.getAsJsonObject("health").get("reachable").getAsBoolean());
        assertEquals("127.0.0.1 8190 /map", probed.get());
        assertFalse(calls.get("/api/atlas").bodyText().contains("secret"));
        probe.set(false);
        assertFalse(Calls.json(calls.get("/api/atlas")).has("health"));
    }

    @Test
    void withoutAConfigTheDefaultsApply() {
        JsonObject o = Calls.json(calls.get("/api/atlas"));
        assertFalse(o.get("configRead").getAsBoolean());
        assertEquals(8123, o.get("port").getAsInt());
        assertEquals("http://127.0.0.1:8123/", o.get("url").getAsString());
    }

    @Test
    void layersAndMarkers() {
        JsonArray layers = Calls.json(calls.get("/api/atlas/layers")).getAsJsonArray("layers");
        assertEquals(2, layers.size());
        assertEquals("Sieges", layers.get(0).getAsJsonObject().get("label").getAsString());
        JsonObject one = Calls.json(calls.get("/api/atlas/markers?layer=" + layers.get(0).getAsJsonObject().get("key").getAsString()))
                .getAsJsonArray("layers").get(0).getAsJsonObject();
        JsonArray markers = one.getAsJsonArray("markers");
        assertEquals(2, markers.size());
        JsonObject point = markers.asList().stream().map(e -> e.getAsJsonObject())
                .filter(m -> m.get("kind").getAsString().equals("point")).findFirst().orElseThrow();
        assertEquals(120.0, point.get("x").getAsDouble());
        assertEquals("Siege", point.get("icon").getAsString());
        JsonArray nether = Calls.json(calls.get("/api/atlas/markers?world=nether")).getAsJsonArray("layers");
        assertEquals(0, nether.get(0).getAsJsonObject().getAsJsonArray("markers").size());
        assertEquals(3, nether.get(1).getAsJsonObject().getAsJsonArray("markers").get(0).getAsJsonObject().getAsJsonArray("points").size());
        assertEquals(404, calls.get("/api/atlas/markers?layer=nope").status());
    }

    @Test
    void hiddenPlayers() {
        JsonObject o = Calls.json(calls.get("/api/atlas/hidden"));
        assertTrue(o.get("known").getAsBoolean());
        assertEquals(hiddenPlayer.toString(), o.getAsJsonArray("players").get(0).getAsString());
    }

    @Test
    void probeAddresses() {
        assertEquals("http://127.0.0.1:8123", AtlasProbe.base("0.0.0.0", 8123, ""));
        assertEquals("http://10.0.0.2:80/map", AtlasProbe.base("10.0.0.2", 80, "map/"));
        assertEquals("http://[::1]:8123", AtlasProbe.base("::1", 8123, null));
    }
}
