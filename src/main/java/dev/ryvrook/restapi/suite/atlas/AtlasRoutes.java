package dev.ryvrook.restapi.suite.atlas;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import dev.ryvrook.atlas.api.AtlasApi;
import dev.ryvrook.atlas.api.CircleMarker;
import dev.ryvrook.atlas.api.Marker;
import dev.ryvrook.atlas.api.MarkerLayer;
import dev.ryvrook.atlas.api.PointMarker;
import dev.ryvrook.atlas.api.PolygonMarker;
import dev.ryvrook.atlas.api.PolylineMarker;
import dev.ryvrook.atlas.api.RenderStatus;
import dev.ryvrook.atlas.api.SessionApi;
import dev.ryvrook.atlas.api.ShapeMarker;
import dev.ryvrook.atlas.api.WorldStatus;
import dev.ryvrook.restapi.RestApi;
import dev.ryvrook.restapi.auth.Scope;
import dev.ryvrook.restapi.http.Access;
import dev.ryvrook.restapi.http.ApiException;
import dev.ryvrook.restapi.http.Call;
import dev.ryvrook.restapi.http.Response;
import dev.ryvrook.restapi.http.Route;
import dev.ryvrook.restapi.http.RouteDoc;
import dev.ryvrook.restapi.siblings.Sibling;
import dev.ryvrook.restapi.siblings.SiblingUnit;
import dev.ryvrook.restapi.suite.Folders;
import dev.ryvrook.restapi.suite.Json;
import dev.ryvrook.restapi.suite.JsonFile;
import dev.ryvrook.restapi.suite.Limits;
import dev.ryvrook.restapi.suite.SuiteSettings;
import dev.ryvrook.restapi.suite.SuiteText;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * The Atlas route group under /api/atlas, bound through AtlasApi's onEnable and onDisable hooks. Marker layers,
 * the map address players open, the render queue and the worlds come from AtlasApi. The web server's bind, port and
 * basePath for the health probe come from {@code atlas.json}, of which nothing else is read, never the admin token.
 * An older Atlas bound under always gives the address from atlas.json too and has no render or worlds route. The
 * players hidden from the map come from SessionApi when Atlas has one.
 */
public final class AtlasRoutes implements SiblingUnit {
    static final String GROUP = Sibling.ATLAS.group();
    static final Scope SCOPE = Sibling.ATLAS.scope();
    static final String MOD = "Atlas";

    /** What the summary route asks the Atlas web server, swapped in the tests. */
    interface Prober {
        JsonObject health(String bind, int port, String basePath);
    }

    private volatile AtlasApi current;
    private Consumer<AtlasApi> enableHook;
    private Runnable disableHook;

    @Override
    public List<Route> bind(RestApi api) {
        enableHook = a -> current = a;
        disableHook = () -> current = null;
        AtlasApi.onEnable(enableHook);
        AtlasApi.onDisable(disableHook);
        if (current == null) {
            AtlasApi.removeHooks(enableHook, disableHook);
            throw new IllegalStateException("Atlas has not handed out its API yet");
        }
        JsonFile config = new JsonFile(Folders.of(api, Sibling.ATLAS).resolve("atlas.json"), MOD);
        return routes(() -> current, config, AtlasRoutes::hidden, SuiteSettings.ATLAS_PROBE::get, AtlasProbe::health,
                Limits.live());
    }

    @Override
    public void unbind() {
        if (enableHook != null) {
            AtlasApi.removeHooks(enableHook, disableHook);
        }
        current = null;
    }

    private static Set<UUID> hidden() {
        SessionApi s = SessionApi.get();
        return s == null ? null : s.hidden();
    }

    static List<Route> routes(Supplier<AtlasApi> source, JsonFile config, Supplier<Set<UUID>> hidden,
                              BooleanSupplier probe, Prober prober, Supplier<Limits> limits) {
        Supplier<AtlasApi> api = () -> {
            AtlasApi a = source.get();
            if (a == null) {
                throw new ApiException(503, "sibling_gone", SuiteText.get(SuiteText.SIBLING_GONE, "mod", MOD));
            }
            return a;
        };
        List<Route> out = new ArrayList<>();
        out.add(Route.of(GROUP, "GET", "/api/atlas", SCOPE, Access.READ,
                "The map address players open, the web server bind and port, the marker layers and, with suite.atlas.probe, the map's own health answer.",
                call -> summary(api.get(), config, probe.getAsBoolean() ? prober : null)).doc(new RouteDoc(List.of(), null,
                "{\"url\":\"https://map.example.com/\",\"publicUrlSet\":true,\"bind\":\"127.0.0.1\",\"port\":8123,\"layers\":4,\"markers\":37,\"health\":{\"reachable\":true,\"httpStatus\":200,\"ok\":true,\"version\":\"1.8.0\"}}",
                List.of("sibling_gone"))));
        out.add(Route.of(GROUP, "GET", "/api/atlas/render", SCOPE, Access.READ,
                "The tile render queue, tiles running and done, whether rendering is paused and the budget left this minute.",
                call -> render(api.get())).doc(new RouteDoc(List.of(), null,
                "{\"queued\":120,\"running\":2,\"done\":40311,\"paused\":false,\"queuedPerWorld\":{\"default\":120},\"budgetLeft\":-1,\"budgetPerMinute\":0}",
                List.of("not_supported"))));
        out.add(Route.of(GROUP, "GET", "/api/atlas/worlds", SCOPE, Access.READ,
                "The worlds Atlas renders, loaded or touched since start, with players, explored chunks, queued tiles and whether the public map shows them.",
                call -> worlds(api.get())).doc(new RouteDoc(List.of(), null,
                "{\"worlds\":[{\"name\":\"default\",\"loaded\":true,\"players\":3,\"exploredChunks\":5120,\"queuedTiles\":12,\"publicVisible\":true}]}",
                List.of("not_supported"))));
        out.add(Route.of(GROUP, "GET", "/api/atlas/layers", SCOPE, Access.READ,
                "Every marker layer other plugins put on the map, with its tier, size and worlds.",
                call -> layers(api.get())));
        out.add(Route.of(GROUP, "GET", "/api/atlas/markers", SCOPE, Access.READ,
                "The markers of every layer, or of one, in one world or all of them.",
                call -> markers(api.get(), call, limits.get())).doc(new RouteDoc(
                List.of(new RouteDoc.Param("world", "One world"), new RouteDoc.Param("layer", "One layer as plugin:id, such as Ryvrook:Dominion:sieges"),
                        new RouteDoc.Param("limit", "Markers per layer")), null,
                "{\"layers\":[{\"key\":\"Ryvrook:Dominion:sieges\",\"markers\":[{\"id\":\"siege:...\",\"kind\":\"point\",\"world\":\"default\",\"x\":120.0,\"z\":-40.0,\"label\":\"NRT against SOU\"}]}]}",
                List.of("unknown_layer"))));
        out.add(Route.of(GROUP, "GET", "/api/atlas/hidden", SCOPE, Access.READ,
                "The players hidden from the map, by their own choice or by staff.",
                call -> hiddenList(api.get(), hidden.get())));
        return out;
    }

    private static Response summary(AtlasApi a, JsonFile config, Prober prober) {
        JsonObject cfg = null;
        try {
            cfg = config.readOrNull();
        } catch (ApiException e) {
            // A broken atlas.json keeps the Atlas web server down too, reported below.
        }
        String bind = text(cfg, "bind", "0.0.0.0");
        int port = number(cfg, "port", 8123);
        String basePath = text(cfg, "basePath", "");
        String publicUrl = text(cfg, "publicUrl", "");
        String url;
        boolean urlSet;
        try {
            url = a.publicUrl();
            urlSet = a.publicUrlSet();
        } catch (AbstractMethodError | NoSuchMethodError e) {
            // Atlas before 1.8.0.
            url = publicUrl.isBlank() ? AtlasProbe.base(bind, port, basePath) + "/" : publicUrl;
            urlSet = !publicUrl.isBlank();
        }
        JsonObject o = new JsonObject();
        o.addProperty("configRead", cfg != null);
        o.addProperty("url", url);
        o.addProperty("publicUrlSet", urlSet);
        o.addProperty("bind", bind);
        o.addProperty("port", port);
        o.addProperty("basePath", basePath);
        List<MarkerLayer> layers = a.layers();
        int markers = 0;
        for (MarkerLayer l : layers) {
            markers += l.size();
        }
        o.addProperty("layers", layers.size());
        o.addProperty("markers", markers);
        if (prober != null) {
            o.add("health", prober.health(bind, port, basePath));
        }
        return Response.ok(o);
    }

    private static String text(JsonObject cfg, String key, String fallback) {
        if (cfg == null || !cfg.has(key) || !cfg.get(key).isJsonPrimitive()) {
            return fallback;
        }
        return cfg.get(key).getAsString();
    }

    private static int number(JsonObject cfg, String key, int fallback) {
        try {
            return cfg == null || !cfg.has(key) ? fallback : cfg.get(key).getAsInt();
        } catch (RuntimeException e) {
            return fallback;
        }
    }

    static JsonObject layer(MarkerLayer l) {
        JsonObject o = new JsonObject();
        o.addProperty("key", l.key());
        o.addProperty("plugin", l.pluginId());
        o.addProperty("id", l.id());
        o.addProperty("label", l.label());
        o.addProperty("tier", l.tier() == null ? null : l.tier().id());
        o.addProperty("defaultHidden", l.defaultHidden());
        o.addProperty("priority", l.priority());
        o.addProperty("size", l.size());
        o.add("worlds", Json.strings(new java.util.TreeSet<>(l.worlds())));
        o.addProperty("updatedAt", l.updatedAt());
        return o;
    }

    private static Response layers(AtlasApi a) {
        JsonArray arr = new JsonArray();
        for (MarkerLayer l : a.layers()) {
            arr.add(layer(l));
        }
        JsonObject o = new JsonObject();
        o.add("layers", arr);
        return Response.ok(o);
    }

    private static Response markers(AtlasApi a, Call call, Limits limits) {
        String world = call.query("world", null);
        String key = call.query("layer", null);
        int limit = limits.limit(call);
        List<MarkerLayer> layers = new ArrayList<>(a.layers());
        if (key != null && !key.isBlank()) {
            layers.removeIf(l -> !key.equals(l.key()));
            if (layers.isEmpty()) {
                throw ApiException.notFound("unknown_layer", SuiteText.get(SuiteText.UNKNOWN_LAYER, "layer", key));
            }
        }
        JsonArray arr = new JsonArray();
        for (MarkerLayer l : layers) {
            JsonObject o = layer(l);
            JsonArray ms = new JsonArray();
            List<String> worlds = world == null || world.isBlank() ? new ArrayList<>(l.worlds()) : List.of(world);
            for (String w : worlds) {
                for (Marker<?> m : l.markers(w)) {
                    if (ms.size() >= limit) {
                        break;
                    }
                    ms.add(marker(m));
                }
            }
            o.add("markers", ms);
            arr.add(o);
        }
        JsonObject o = new JsonObject();
        o.add("layers", arr);
        return Response.ok(o);
    }

    static JsonObject marker(Marker<?> m) {
        JsonObject o = new JsonObject();
        o.addProperty("id", m.id());
        o.addProperty("kind", m.kind());
        o.addProperty("world", m.world());
        o.addProperty("label", m.label());
        o.addProperty("popup", m.popup());
        o.addProperty("tint", m.tint());
        o.addProperty("faction", m.faction());
        if (m instanceof PointMarker p) {
            o.addProperty("x", p.x());
            o.add("y", p.y() == null ? null : Json.of(p.y()));
            o.addProperty("z", p.z());
            o.addProperty("icon", p.icon());
        } else if (m instanceof CircleMarker c) {
            o.addProperty("x", c.x());
            o.addProperty("z", c.z());
            o.addProperty("radius", c.radius());
        } else if (m instanceof PolygonMarker p) {
            o.add("points", points(p.points()));
        } else if (m instanceof PolylineMarker p) {
            o.add("points", points(p.points()));
        }
        if (m instanceof ShapeMarker<?> s) {
            o.addProperty("fill", s.fill());
            o.add("weight", s.weight() == null ? null : Json.of(s.weight()));
        }
        return o;
    }

    private static JsonArray points(List<double[]> pts) {
        JsonArray a = new JsonArray();
        if (pts != null) {
            for (double[] p : pts) {
                a.add((JsonElement) Json.of(p));
            }
        }
        return a;
    }

    private static Response render(AtlasApi a) {
        RenderStatus r;
        try {
            r = a.renderStatus();
        } catch (AbstractMethodError | NoSuchMethodError | NoClassDefFoundError e) {
            throw new ApiException(501, "not_supported", SuiteText.get(SuiteText.ATLAS_OLD));
        }
        JsonObject o = new JsonObject();
        o.addProperty("queued", r.queued());
        o.addProperty("running", r.running());
        o.addProperty("done", r.done());
        o.addProperty("paused", r.paused());
        o.add("queuedPerWorld", Json.numbers(new java.util.TreeMap<>(r.queuedPerWorld())));
        o.addProperty("budgetLeft", r.budgetLeft());
        o.addProperty("budgetPerMinute", r.budgetPerMinute());
        return Response.ok(o);
    }

    private static Response worlds(AtlasApi a) {
        List<WorldStatus> worlds;
        try {
            worlds = a.worlds();
        } catch (AbstractMethodError | NoSuchMethodError | NoClassDefFoundError e) {
            throw new ApiException(501, "not_supported", SuiteText.get(SuiteText.ATLAS_OLD));
        }
        JsonArray arr = new JsonArray();
        for (WorldStatus w : worlds) {
            JsonObject o = new JsonObject();
            o.addProperty("name", w.name());
            o.addProperty("loaded", w.loaded());
            o.addProperty("players", w.players());
            o.addProperty("exploredChunks", w.exploredChunks());
            o.addProperty("queuedTiles", w.queuedTiles());
            o.addProperty("publicVisible", w.publicVisible());
            arr.add(o);
        }
        JsonObject o = new JsonObject();
        o.add("worlds", arr);
        return Response.ok(o);
    }

    private static Response hiddenList(AtlasApi a, Set<UUID> hidden) {
        JsonObject o = new JsonObject();
        o.addProperty("known", hidden != null);
        o.add("players", Json.strings(hidden == null ? List.of() : new java.util.TreeSet<>(hidden)));
        return Response.ok(o);
    }

}
