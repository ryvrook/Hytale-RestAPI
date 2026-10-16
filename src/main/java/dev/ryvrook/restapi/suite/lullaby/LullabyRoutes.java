package dev.ryvrook.restapi.suite.lullaby;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import dev.ryvrook.restapi.RestApi;
import dev.ryvrook.restapi.auth.Scope;
import dev.ryvrook.restapi.http.Access;
import dev.ryvrook.restapi.http.ApiException;
import dev.ryvrook.restapi.http.Call;
import dev.ryvrook.restapi.http.Response;
import dev.ryvrook.restapi.http.Route;
import dev.ryvrook.restapi.http.RouteDoc;
import dev.ryvrook.restapi.server.WorldSample;
import dev.ryvrook.restapi.siblings.Sibling;
import dev.ryvrook.restapi.siblings.SiblingUnit;
import dev.ryvrook.restapi.suite.Folders;
import dev.ryvrook.restapi.suite.JsonFile;
import dev.ryvrook.restapi.suite.SuiteText;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.function.Supplier;

/**
 * The Lullaby route group under /api/lullaby. Lullaby has no API, so this reads its three files, Lullaby.json for
 * the rules, stats.json for the nights skipped per world and ignored.json for the players who opted out, and
 * takes the players per world from RestAPI's own world samples. Who sleeps right now is not in any file, that
 * waits on a Lullaby API. Touches no Lullaby class.
 */
public final class LullabyRoutes implements SiblingUnit {
    static final String GROUP = Sibling.LULLABY.group();
    static final Scope SCOPE = Sibling.LULLABY.scope();
    static final String MOD = "Lullaby";

    @Override
    public List<Route> bind(RestApi api) {
        Path dir = Folders.of(api, Sibling.LULLABY);
        return routes(new JsonFile(dir.resolve("Lullaby.json"), MOD), new JsonFile(dir.resolve("stats.json"), MOD),
                new JsonFile(dir.resolve("ignored.json"), MOD), () -> {
                    Map<String, Integer> out = new LinkedHashMap<>();
                    for (WorldSample w : api.worlds().all()) {
                        out.put(w.name(), w.players());
                    }
                    return out;
                });
    }

    static List<Route> routes(JsonFile config, JsonFile stats, JsonFile ignored, Supplier<Map<String, Integer>> players) {
        List<Route> out = new ArrayList<>();
        out.add(Route.of(GROUP, "GET", "/api/lullaby", SCOPE, Access.READ,
                "Lullaby's mode and rules, how many players opted out, and per world the nights skipped, the players online and how many of them would need to sleep.",
                call -> summary(config, stats, ignored, players.get())).doc(new RouteDoc(List.of(), null,
                "{\"mode\":\"auto\",\"requirement\":\"percent\",\"percent\":50,\"ignoredPlayers\":1,\"nightsSkipped\":14,\"worlds\":[{\"world\":\"default\",\"enabled\":true,\"requirement\":\"percent\",\"nightsSkipped\":14,\"playersOnline\":4,\"neededOfOnline\":2}],\"sleepersKnown\":false}",
                List.of("file_broken"))));
        out.add(Route.of(GROUP, "GET", "/api/lullaby/worlds/{world}", SCOPE, Access.READ,
                "One world's sleep rule, nights skipped, players online and sleepers needed.",
                call -> world(config, stats, players.get(), call)).doc(new RouteDoc(List.of(), null, null,
                List.of("unknown_world"))));
        return out;
    }

    private static JsonObject read(JsonFile f) {
        JsonObject o = f.readOrNull();
        return o == null ? new JsonObject() : o;
    }

    private static Response summary(JsonFile config, JsonFile stats, JsonFile ignored, Map<String, Integer> players) {
        JsonObject cfg = read(config);
        JsonObject nights = nights(read(stats));
        JsonObject o = new JsonObject();
        o.addProperty("configRead", config.readOrNull() != null);
        o.addProperty("mode", text(cfg, "Mode", "auto"));
        o.addProperty("requirement", text(cfg, "Requirement", "percent"));
        copy(cfg, o, "Percent", "percent");
        copy(cfg, o, "Count", "count");
        copy(cfg, o, "MinimumSleepers", "minimumSleepers");
        copy(cfg, o, "ExcludeCreative", "excludeCreative");
        copy(cfg, o, "AfkSeconds", "afkSeconds");
        copy(cfg, o, "SkipSeconds", "skipSeconds");
        copy(cfg, o, "CooldownSeconds", "cooldownSeconds");
        copy(cfg, o, "ClearStorms", "clearStorms");
        JsonObject ign = read(ignored);
        o.addProperty("ignoredPlayers", ign.has("Ignored") && ign.get("Ignored").isJsonArray() ? ign.getAsJsonArray("Ignored").size() : 0);
        long total = 0;
        for (Map.Entry<String, JsonElement> e : nights.entrySet()) {
            total += number(e.getValue());
        }
        o.addProperty("nightsSkipped", total);
        TreeSet<String> names = new TreeSet<>(players.keySet());
        names.addAll(nights.keySet());
        if (cfg.has("Worlds") && cfg.get("Worlds").isJsonObject()) {
            names.addAll(cfg.getAsJsonObject("Worlds").keySet());
        }
        JsonArray worlds = new JsonArray();
        for (String w : names) {
            worlds.add(world(cfg, nights, players, w));
        }
        o.add("worlds", worlds);
        o.addProperty("sleepersKnown", false);
        return Response.ok(o);
    }

    private static Response world(JsonFile config, JsonFile stats, Map<String, Integer> players, Call call) {
        String w = call.param("world");
        JsonObject cfg = read(config);
        JsonObject nights = nights(read(stats));
        boolean known = players.containsKey(w) || nights.has(w)
                || cfg.has("Worlds") && cfg.get("Worlds").isJsonObject() && cfg.getAsJsonObject("Worlds").has(w);
        if (!known) {
            throw ApiException.notFound("unknown_world", SuiteText.get(SuiteText.UNKNOWN_WORLD, "world", w));
        }
        return Response.ok(world(cfg, nights, players, w));
    }

    static JsonObject world(JsonObject cfg, JsonObject nights, Map<String, Integer> players, String w) {
        SleepRule rule = SleepRule.of(cfg, w);
        JsonObject o = new JsonObject();
        o.addProperty("world", w);
        o.addProperty("loaded", players.containsKey(w));
        o.addProperty("enabled", rule.enabled());
        o.addProperty("requirement", rule.requirement());
        o.addProperty("percent", rule.percent());
        o.addProperty("count", rule.count());
        o.addProperty("nightsSkipped", nights.has(w) ? number(nights.get(w)) : 0);
        Integer online = players.get(w);
        o.addProperty("playersOnline", online == null ? 0 : online);
        o.addProperty("neededOfOnline", online == null || online == 0 ? 0 : rule.needed(online));
        return o;
    }

    private static JsonObject nights(JsonObject stats) {
        return stats.has("NightsSkipped") && stats.get("NightsSkipped").isJsonObject()
                ? stats.getAsJsonObject("NightsSkipped") : new JsonObject();
    }

    private static long number(JsonElement e) {
        try {
            if (e.isJsonObject()) {
                // Extended JSON such as {"$numberLong": "3"}.
                for (Map.Entry<String, JsonElement> x : e.getAsJsonObject().entrySet()) {
                    return Long.parseLong(x.getValue().getAsString());
                }
                return 0;
            }
            return e.getAsLong();
        } catch (RuntimeException ex) {
            return 0;
        }
    }

    private static String text(JsonObject o, String key, String fallback) {
        return o.has(key) && o.get(key).isJsonPrimitive() ? o.get(key).getAsString() : fallback;
    }

    private static void copy(JsonObject from, JsonObject to, String key, String as) {
        if (from.has(key)) {
            to.add(as, from.get(key));
        }
    }
}
