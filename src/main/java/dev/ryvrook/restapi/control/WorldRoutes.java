package dev.ryvrook.restapi.control;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.hypixel.hytale.builtin.weather.resources.WeatherResource;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.HytaleServer;
import com.hypixel.hytale.server.core.HytaleServerConfig;
import com.hypixel.hytale.server.core.asset.type.weather.config.Weather;
import com.hypixel.hytale.server.core.modules.time.WorldTimeResource;
import com.hypixel.hytale.server.core.universe.Universe;
import com.hypixel.hytale.server.core.universe.system.WorldConfigSaveSystem;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.WorldConfig;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.core.universe.world.storage.component.ChunkSavingSystems;
import dev.ryvrook.restapi.RestApi;
import dev.ryvrook.restapi.auth.Scope;
import dev.ryvrook.restapi.config.CoreSettings;
import dev.ryvrook.restapi.http.Access;
import dev.ryvrook.restapi.http.ApiException;
import dev.ryvrook.restapi.http.Call;
import dev.ryvrook.restapi.http.Response;
import dev.ryvrook.restapi.http.Route;
import dev.ryvrook.restapi.http.RouteDoc;
import dev.ryvrook.restapi.server.Health;
import dev.ryvrook.restapi.server.WorldSample;
import dev.ryvrook.restapi.text.Messages;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.TreeSet;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * The /api/worlds routes. Lists read the samples the core module takes on each world thread every
 * server.sampleSeconds, so a list never waits on a world. Time, weather, ticking and pause are read and written
 * on the world's own thread through {@link OnWorld}, with control.worldTimeoutMs as the limit.
 */
final class WorldRoutes {
    private WorldRoutes() {
    }

    private static final String G = ControlModule.GROUP;
    private static final String TIME = "{\"world\":\"default\",\"hour\":12,\"dayProgress\":0.5,\"moonPhase\":0,\"gameTime\":\"0001-01-01T12:00:00Z\",\"timePaused\":false}";

    static List<Route> routes(ControlModule m, RestApi api) {
        Replays r = m.replays();
        return List.of(
                Route.of(G, "GET", "/api/worlds", Scope.READ, Access.READ,
                        "Every loaded world with players, measured TPS, tick times, chunks, entities and whether it ticks or is paused.",
                        call -> list(api)).doc(new RouteDoc(List.of(), null,
                        "{\"worlds\":[{\"name\":\"default\",\"default\":true,\"players\":2,\"targetTps\":30,\"tps\":30.0,\"tickAvgMs\":4.1,"
                                + "\"tickMaxMs\":9.8,\"chunks\":441,\"entities\":312,\"sampledAgoMs\":1200,\"stalled\":false,\"ticking\":true,\"paused\":false}]}",
                        List.of())),
                Route.of(G, "GET", "/api/worlds/{world}", Scope.READ, Access.READ,
                        "One world with the list fields plus time of day, moon phase, forced weather, seed, save path and day and night lengths.",
                        call -> detail(api, call)).doc(new RouteDoc(List.of(), null,
                        "{\"name\":\"default\",\"hour\":12,\"dayProgress\":0.5,\"moonPhase\":0,\"weather\":null,\"seed\":1234,"
                                + "\"daytimeSeconds\":1440,\"nighttimeSeconds\":480,\"savePath\":\"universe/worlds/default\"}",
                        List.of("world_not_found", "world_timeout"))),
                Route.of(G, "PUT", "/api/worlds/{world}/time", Scope.SERVER, Access.WRITE,
                        "Sets the time of day with hour from 0 to 24 or dayProgress from 0 to 1, as /time does.",
                        call -> time(call)).doc(new RouteDoc(List.of(), "{\"hour\":6}", TIME,
                        List.of("world_not_found", "world_timeout", "bad_value"))),
                Route.of(G, "PUT", "/api/worlds/{world}/weather", Scope.SERVER, Access.WRITE,
                        "Forces a weather by its asset name, or clears the forced weather with null, as /weather set and /weather reset do.",
                        call -> weather(call)).doc(new RouteDoc(List.of(), "{\"weather\":\"Zone1_Rain\"}",
                        "{\"world\":\"default\",\"weather\":\"Zone1_Rain\"}",
                        List.of("world_not_found", "world_timeout", "bad_value", "weather_unknown"))),
                Route.of(G, "POST", "/api/worlds/{world}/save", Scope.SERVER, Access.WRITE,
                        "Saves the world's chunks, config and resources, as /world save does. 202 when it takes longer than control.saveTimeoutSeconds.",
                        call -> save(call)).doc(new RouteDoc(List.of(), null,
                        "{\"world\":\"default\",\"state\":\"saved\",\"millis\":184}", List.of("world_not_found"))),
                Route.of(G, "PUT", "/api/worlds/{world}/ticking", Scope.SERVER, Access.DANGEROUS,
                        "Stops or starts the world's tick with ticking, or pauses it with paused as /pause does. Both may be sent.",
                        r.wrap(WorldRoutes::ticking)).doc(new RouteDoc(List.of(), "{\"paused\":true}",
                        "{\"world\":\"default\",\"ticking\":true,\"paused\":true}",
                        List.of("world_not_found", "world_timeout", "bad_value"))),
                Route.of(G, "POST", "/api/worlds/{world}/load", Scope.SERVER, Access.DANGEROUS,
                        "Loads a saved world from the universe folder. 202 while it is still loading after control.worldTimeoutMs.",
                        r.wrap(WorldRoutes::load)).doc(new RouteDoc(List.of(), null,
                        "{\"world\":\"arena\",\"state\":\"loaded\"}",
                        List.of("world_loaded", "world_not_found", "world_load_failed"))),
                Route.of(G, "POST", "/api/worlds/{world}/unload", Scope.SERVER, Access.DANGEROUS,
                        "Removes a loaded world. Needs control.allowWorldUnload. The default world and the last world are refused.",
                        r.wrap(WorldRoutes::unload)).doc(new RouteDoc(List.of(), null,
                        "{\"world\":\"arena\",\"state\":\"unloading\"}",
                        List.of("world_not_found", "world_default", "world_last", "world_refused", "feature_off"))));
    }

    private static Universe universe() {
        Universe u = Universe.get();
        if (u == null) {
            throw new ApiException(503, "booting", Messages.get(ControlText.BOOTING));
        }
        return u;
    }

    /** The loaded world, matched exactly first and then without regard to case. */
    static World world(String name) {
        Universe u = universe();
        World w = name == null ? null : u.getWorld(name);
        if (w == null && name != null) {
            for (World x : u.getWorlds().values()) {
                if (x != null && x.getName().equalsIgnoreCase(name)) {
                    w = x;
                    break;
                }
            }
        }
        if (w == null || !w.isAlive()) {
            throw ApiException.notFound("world_not_found", Messages.get(ControlText.WORLD_NOT_FOUND, "world", name));
        }
        return w;
    }

    static String defaultWorld() {
        HytaleServer s = HytaleServer.get();
        HytaleServerConfig c = s == null ? null : s.getConfig();
        return c == null || c.getDefaults() == null ? null : c.getDefaults().getWorld();
    }

    private static long timeout() {
        return ControlSettings.WORLD_TIMEOUT_MS.get();
    }

    private static long stallLimitMs() {
        return Math.max(15_000L, 3_000L * CoreSettings.SAMPLE_SECONDS.get());
    }

    private static JsonObject summary(RestApi api, World w, String def) {
        long now = System.currentTimeMillis();
        WorldSample sample = null;
        for (WorldSample s : api.worlds().all()) {
            if (s.name().equals(w.getName())) {
                sample = s;
                break;
            }
        }
        JsonObject o = sample == null ? new JsonObject() : Health.world(sample, now, stallLimitMs());
        o.addProperty("name", w.getName());
        o.addProperty("default", w.getName().equalsIgnoreCase(String.valueOf(def)));
        o.addProperty("players", w.getPlayerCount());
        o.addProperty("ticking", w.isTicking());
        o.addProperty("paused", w.isPaused());
        return o;
    }

    private static Response list(RestApi api) {
        List<World> worlds = new ArrayList<>(universe().getWorlds().values());
        worlds.removeIf(w -> w == null || !w.isAlive());
        worlds.sort(Comparator.comparing(World::getName));
        String def = defaultWorld();
        JsonArray a = new JsonArray();
        for (World w : worlds) {
            a.add(summary(api, w, def));
        }
        JsonObject o = new JsonObject();
        o.addProperty("count", a.size());
        o.addProperty("defaultWorld", def);
        o.add("worlds", a);
        return Response.ok(o);
    }

    private static Response detail(RestApi api, Call call) {
        World w = world(call.param("world"));
        JsonObject o = summary(api, w, defaultWorld());
        WorldConfig c = w.getWorldConfig();
        if (c != null) {
            o.addProperty("displayName", c.getDisplayName());
            o.addProperty("seed", c.getSeed());
            o.addProperty("pvp", c.isPvpEnabled());
            o.addProperty("fallDamage", c.isFallDamageEnabled());
            o.addProperty("gameMode", c.getGameMode() == null ? null : c.getGameMode().name());
            o.addProperty("timePaused", c.isGameTimePaused());
        }
        o.addProperty("daytimeSeconds", w.getDaytimeDurationSeconds());
        o.addProperty("nighttimeSeconds", w.getNighttimeDurationSeconds());
        o.addProperty("savePath", w.getSavePath() == null ? null : w.getSavePath().toString());
        o.addProperty("tick", w.getTick());
        JsonObject onThread = OnWorld.call(w, w.getName(), timeout(), () -> {
            JsonObject t = timeOf(w);
            t.addProperty("weather", forcedWeather(w));
            return t;
        });
        for (String k : onThread.keySet()) {
            o.add(k, onThread.get(k));
        }
        JsonArray names = new JsonArray();
        weatherNames().forEach(names::add);
        o.add("weatherNames", names);
        return Response.ok(o);
    }

    /** World thread. */
    private static JsonObject timeOf(World w) {
        Store<EntityStore> store = w.getEntityStore().getStore();
        WorldTimeResource t = store.getResource(WorldTimeResource.getResourceType());
        JsonObject o = new JsonObject();
        o.addProperty("world", w.getName());
        if (t != null) {
            o.addProperty("hour", t.getCurrentHour());
            o.addProperty("dayProgress", Math.round(t.getDayProgress() * 10_000) / 10_000.0);
            o.addProperty("moonPhase", t.getMoonPhase());
            o.addProperty("gameTime", t.getGameTime() == null ? null : t.getGameTime().toString());
        }
        return o;
    }

    /** World thread. The forced weather name, or null when the weather follows the environment. */
    private static String forcedWeather(World w) {
        try {
            WeatherResource res = w.getEntityStore().getStore().getResource(WeatherResource.getResourceType());
            if (res == null) {
                return null;
            }
            int i = res.getForcedWeatherIndex();
            if (i == 0) {
                return null;
            }
            Weather weather = Weather.getAssetMap().getAsset(i);
            return weather == null ? null : weather.getId();
        } catch (RuntimeException | LinkageError e) {
            return null;
        }
    }

    private static List<String> weatherNames() {
        try {
            return new ArrayList<>(new TreeSet<>(Weather.getAssetMap().getAssetMap().keySet()));
        } catch (RuntimeException | LinkageError e) {
            return List.of();
        }
    }

    private static Response time(Call call) {
        World w = world(call.param("world"));
        double progress = TimeBody.dayProgress(call.jsonBody());
        JsonObject o = OnWorld.call(w, w.getName(), timeout(), () -> {
            Store<EntityStore> store = w.getEntityStore().getStore();
            WorldTimeResource t = store.getResource(WorldTimeResource.getResourceType());
            t.setDayTime(progress, w, store);
            return timeOf(w);
        });
        return Response.ok(o);
    }

    private static Response weather(Call call) {
        World w = world(call.param("world"));
        JsonObject body = call.jsonBody();
        if (!body.has("weather")) {
            throw Bodies.bad("weather", Messages.get(ControlText.MUST_TEXT, "max", 128));
        }
        String asked = Bodies.optText(body, "weather", 128);
        String id = asked == null || asked.isBlank() ? null : weatherId(asked.trim(), w.getName());
        String now = OnWorld.call(w, w.getName(), timeout(), () -> {
            WeatherResource res = w.getEntityStore().getStore().getResource(WeatherResource.getResourceType());
            res.setForcedWeather(id);
            WorldConfig c = w.getWorldConfig();
            c.setForcedWeather(id);
            c.markChanged();
            return forcedWeather(w);
        });
        JsonObject o = new JsonObject();
        o.addProperty("world", w.getName());
        o.addProperty("weather", now);
        return Response.ok(o);
    }

    /** The asset name as the engine spells it, or 400 weather_unknown. */
    private static String weatherId(String asked, String world) {
        if (Weather.getAssetMap().getAsset(asked) != null) {
            return asked;
        }
        for (String k : weatherNames()) {
            if (k.equalsIgnoreCase(asked)) {
                return k;
            }
        }
        throw ApiException.badRequest("weather_unknown", Messages.get(ControlText.WEATHER_UNKNOWN, "weather", asked, "world", world));
    }

    /** Saves one world as the engine's /world save does. The chunk save starts on the world thread. */
    static CompletableFuture<Void> saveWorld(World w) {
        CompletableFuture<Void> chunks = CompletableFuture
                .supplyAsync(() -> ChunkSavingSystems.saveChunksInWorld(w.getChunkStore().getStore(), w), w)
                .thenCompose(f -> f);
        return CompletableFuture.allOf(chunks, WorldConfigSaveSystem.saveWorldConfigAndResources(w));
    }

    /** Waits for a save up to control.saveTimeoutSeconds and says saved, running or failed. */
    static JsonObject awaitSave(String name, CompletableFuture<Void> f, long start, long deadline) {
        JsonObject o = new JsonObject();
        o.addProperty("world", name);
        try {
            f.get(Math.max(0, deadline - System.currentTimeMillis()), TimeUnit.MILLISECONDS);
            o.addProperty("state", "saved");
        } catch (TimeoutException e) {
            o.addProperty("state", "running");
        } catch (ExecutionException e) {
            o.addProperty("state", "failed");
            o.addProperty("error", String.valueOf(e.getCause() == null ? e.getMessage() : e.getCause().getMessage()));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            o.addProperty("state", "running");
        }
        o.addProperty("millis", System.currentTimeMillis() - start);
        return o;
    }

    private static Response save(Call call) {
        World w = world(call.param("world"));
        long start = System.currentTimeMillis();
        JsonObject o = awaitSave(w.getName(), saveWorld(w), start,
                start + TimeUnit.SECONDS.toMillis(ControlSettings.SAVE_TIMEOUT_SECONDS.get()));
        String state = o.get("state").getAsString();
        return Response.json("saved".equals(state) ? 200 : "running".equals(state) ? 202 : 500, o);
    }

    private static Response ticking(Call call) {
        World w = world(call.param("world"));
        JsonObject body = call.jsonBody();
        Boolean tick = Bodies.optBool(body, "ticking");
        Boolean pause = Bodies.optBool(body, "paused");
        if (tick == null && pause == null) {
            throw ApiException.badRequest("bad_value", Messages.get(ControlText.NOTHING, "fields", "ticking, paused"));
        }
        JsonObject o = OnWorld.call(w, w.getName(), timeout(), () -> {
            if (tick != null) {
                w.setTicking(tick);
            }
            if (pause != null) {
                w.setPaused(pause);
            }
            JsonObject x = new JsonObject();
            x.addProperty("world", w.getName());
            x.addProperty("ticking", w.isTicking());
            x.addProperty("paused", w.isPaused());
            return x;
        });
        return Response.ok(o);
    }

    private static Response load(Call call) {
        String name = call.param("world");
        Universe u = universe();
        if (u.getWorld(name) != null) {
            throw new ApiException(409, "world_loaded", Messages.get(ControlText.WORLD_LOADED, "world", name));
        }
        if (!u.isWorldLoadable(name)) {
            throw ApiException.notFound("world_not_found", Messages.get(ControlText.WORLD_NOT_LOADABLE, "world", name));
        }
        CompletableFuture<World> f = u.loadWorld(name);
        JsonObject o = new JsonObject();
        o.addProperty("world", name);
        try {
            World w = f.get(timeout(), TimeUnit.MILLISECONDS);
            o.addProperty("state", "loaded");
            o.addProperty("world", w == null ? name : w.getName());
            return Response.ok(o);
        } catch (TimeoutException e) {
            o.addProperty("state", "loading");
            return Response.json(202, o);
        } catch (ExecutionException e) {
            Throwable c = e.getCause() == null ? e : e.getCause();
            throw new ApiException(409, "world_load_failed",
                    Messages.get(ControlText.WORLD_LOAD_FAILED, "world", name, "reason", String.valueOf(c.getMessage())));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            o.addProperty("state", "loading");
            return Response.json(202, o);
        }
    }

    private static Response unload(Call call) {
        ControlModule.require(ControlSettings.ALLOW_WORLD_UNLOAD);
        World w = world(call.param("world"));
        Universe u = universe();
        String def = defaultWorld();
        if (def != null && def.equalsIgnoreCase(w.getName())) {
            throw new ApiException(409, "world_default", Messages.get(ControlText.WORLD_DEFAULT, "world", w.getName()));
        }
        if (u.getWorlds().size() <= 1) {
            throw new ApiException(409, "world_last", Messages.get(ControlText.WORLD_LAST, "world", w.getName()));
        }
        if (!u.removeWorld(w.getName())) {
            throw new ApiException(409, "world_refused", Messages.get(ControlText.WORLD_REMOVE_FAILED, "world", w.getName()));
        }
        JsonObject o = new JsonObject();
        o.addProperty("world", w.getName());
        o.addProperty("state", "unloading");
        return Response.json(202, o);
    }
}
