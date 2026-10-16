package dev.ryvrook.restapi.suite.chronicle;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import dev.ryvrook.chronicle.api.StatDef;
import dev.ryvrook.chronicle.api.Window;
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
import dev.ryvrook.restapi.suite.Names;
import dev.ryvrook.restapi.suite.SuiteText;
import dev.ryvrook.restapi.suite.Waits;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * The Chronicle Stats route group under /api/chronicle. Definitions and player totals come from ChronicleApi, the
 * all time boards, the server totals and the recent records from {@code export/stats.json}, which Chronicle writes
 * every exportSeconds for Atlas. The API has no board or server call yet.
 */
public final class ChronicleRoutes implements SiblingUnit {
    static final String GROUP = Sibling.CHRONICLE.group();
    static final Scope SCOPE = Sibling.CHRONICLE.scope();
    static final String MOD = "Chronicle Stats";

    @Override
    public List<Route> bind(RestApi api) {
        if (!ChronicleBridge.present()) {
            throw new IllegalStateException("Chronicle Stats has not published its API yet");
        }
        JsonFile export = new JsonFile(Folders.of(api, Sibling.CHRONICLE).resolve("export").resolve("stats.json"), MOD);
        return routes(new ChronicleBridge(), export, Limits.live());
    }

    static List<Route> routes(ChronicleSource chronicle, JsonFile export, Supplier<Limits> limits) {
        List<Route> out = new ArrayList<>();
        out.add(Route.of(GROUP, "GET", "/api/chronicle", SCOPE, Access.READ,
                "Chronicle Stats API version, how many stats it defines, and when it last wrote its export.",
                call -> summary(chronicle, export)));
        out.add(Route.of(GROUP, "GET", "/api/chronicle/stats", SCOPE, Access.READ,
                "Every stat definition with its category, unit, aggregation and whether it has boards per window.",
                call -> stats(chronicle, call)).doc(new RouteDoc(
                List.of(new RouteDoc.Param("category", "Only this category, such as combat or mining")), null,
                "{\"stats\":[{\"key\":\"blocks_broken\",\"label\":\"Blocks broken\",\"category\":\"mining\",\"unit\":\"count\",\"aggregation\":\"sum\",\"windowed\":true}]}",
                List.of())));
        out.add(Route.of(GROUP, "GET", "/api/chronicle/players/{player}", SCOPE, Access.READ,
                "One player's stats grouped by category. window=today, week or month reads the board stats of that window.",
                call -> player(chronicle, export, call, limits.get())).doc(new RouteDoc(
                List.of(new RouteDoc.Param("window", "all, today, week or month"),
                        new RouteDoc.Param("world", "With a window, only this world"),
                        new RouteDoc.Param("category", "Only this category")), null,
                "{\"uuid\":\"...\",\"window\":\"all\",\"tracked\":true,\"categories\":{\"mining\":{\"blocks_broken\":812}}}",
                List.of("player_not_found", "bad_window", "sibling_timeout"))));
        out.add(Route.of(GROUP, "GET", "/api/chronicle/players/{player}/{stat}", SCOPE, Access.READ,
                "One stat of one player. A stat that counts per id, such as blocks broken by block, lists the ids.",
                call -> one(chronicle, export, call, limits.get())).doc(new RouteDoc(
                List.of(new RouteDoc.Param("window", "all, today, week or month"),
                        new RouteDoc.Param("world", "With a window, only this world")), null, null,
                List.of("player_not_found", "stat_not_found", "bad_window"))));
        out.add(Route.of(GROUP, "GET", "/api/chronicle/top/{stat}", SCOPE, Access.READ,
                "The all time board of a stat from export/stats.json. Players who hide from boards show as hidden.",
                call -> top(export, call, limits.get())).doc(new RouteDoc(
                List.of(new RouteDoc.Param("limit", "Rows"), new RouteDoc.Param("window", "all, the only window exported")),
                null, null, List.of("stat_not_found", "not_exported", "file_missing"))));
        out.add(Route.of(GROUP, "GET", "/api/chronicle/server", SCOPE, Access.READ,
                "Server totals, players online and the newest records from export/stats.json.",
                call -> server(export)).doc(new RouteDoc(List.of(), null, null, List.of("file_missing", "file_broken"))));
        return out;
    }

    private static Response summary(ChronicleSource c, JsonFile export) {
        JsonObject o = new JsonObject();
        o.addProperty("apiVersion", c.apiVersion());
        o.addProperty("stats", c.stats().size());
        JsonObject doc = null;
        try {
            doc = export.readOrNull();
        } catch (ApiException e) {
            // Reported as no export below.
        }
        o.addProperty("exported", doc != null);
        if (doc != null) {
            copy(doc, o, "updatedAt");
            copy(doc, o, "online");
            copy(doc, o, "chronicleVersion");
        }
        return Response.ok(o);
    }

    private static void copy(JsonObject from, JsonObject to, String field) {
        if (from.has(field)) {
            to.add(field, from.get(field));
        }
    }

    private static Response stats(ChronicleSource c, Call call) {
        String category = call.query("category", null);
        JsonArray a = new JsonArray();
        for (StatDef d : c.stats()) {
            if (category != null && !category.equalsIgnoreCase(lower(d.category()))) {
                continue;
            }
            a.add(def(d));
        }
        JsonObject o = new JsonObject();
        o.add("stats", a);
        return Response.ok(o);
    }

    static JsonObject def(StatDef d) {
        JsonObject o = new JsonObject();
        o.addProperty("key", d.key());
        o.addProperty("prefix", d.isPrefix());
        o.addProperty("label", d.label());
        o.addProperty("description", d.description());
        o.addProperty("category", lower(d.category()));
        o.addProperty("unit", lower(d.unit()));
        o.addProperty("aggregation", lower(d.aggregation()));
        o.addProperty("scope", lower(d.scope()));
        o.addProperty("board", d.boardEligible());
        o.addProperty("windowed", d.windowed());
        o.addProperty("public", d.isPublic());
        o.addProperty("owner", d.owner());
        return o;
    }

    static Names names(JsonFile export) {
        return new Names(MOD, Names::online, name -> {
            JsonObject doc = export.readOrNull();
            if (doc == null || !doc.has("boards") || !doc.get("boards").isJsonObject()) {
                return Optional.empty();
            }
            for (Map.Entry<String, JsonElement> board : doc.getAsJsonObject("boards").entrySet()) {
                if (!board.getValue().isJsonArray()) {
                    continue;
                }
                for (JsonElement e : board.getValue().getAsJsonArray()) {
                    JsonObject row = e.getAsJsonObject();
                    if (row.has("name") && !row.get("name").isJsonNull() && name.equalsIgnoreCase(row.get("name").getAsString())
                            && row.has("uuid") && !row.get("uuid").isJsonNull()) {
                        return Optional.ofNullable(Json.uuid(row.get("uuid").getAsString()));
                    }
                }
            }
            return Optional.empty();
        });
    }

    static Window window(String raw) {
        if (raw == null || raw.isBlank()) {
            return Window.ALL;
        }
        try {
            return Window.valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw ApiException.badRequest("bad_window", SuiteText.get(SuiteText.BAD_WINDOW));
        }
    }

    /** The definition a counter key belongs to, its own or the prefix definition it falls under. */
    static StatDef defOf(List<StatDef> defs, String key) {
        StatDef best = null;
        for (StatDef d : defs) {
            if (d.key().equals(key)) {
                return d;
            }
            if (d.isPrefix() && key.startsWith(d.key()) && (best == null || d.key().length() > best.key().length())) {
                best = d;
            }
        }
        return best;
    }

    private static Response player(ChronicleSource c, JsonFile export, Call call, Limits limits) {
        UUID uuid = names(export).resolve(call.param("player"));
        Window w = window(call.query("window", null));
        String category = call.query("category", null);
        List<StatDef> defs = c.stats();
        Map<String, Map<String, Long>> grouped = new TreeMap<>();
        if (w == Window.ALL) {
            Map<String, Long> totals = Waits.await(c.totals(uuid), MOD, limits.waitMs());
            if (totals != null) {
                totals.forEach((key, value) -> {
                    StatDef d = defOf(defs, key);
                    String cat = d == null ? "other" : lower(d.category());
                    grouped.computeIfAbsent(cat, x -> new TreeMap<>()).put(key, value);
                });
            }
        } else {
            String worldKey = worldKey(c, call.query("world", null));
            for (StatDef d : defs) {
                if (d.isPrefix() || !d.windowed() || !d.player()) {
                    continue;
                }
                grouped.computeIfAbsent(lower(d.category()), x -> new TreeMap<>())
                        .put(d.key(), c.window(uuid, d.key(), w, worldKey));
            }
        }
        JsonObject cats = new JsonObject();
        grouped.forEach((cat, values) -> {
            if (category == null || category.equalsIgnoreCase(cat)) {
                cats.add(cat, Json.numbers(values));
            }
        });
        JsonObject o = new JsonObject();
        o.addProperty("uuid", uuid.toString());
        o.addProperty("window", lower(w));
        o.addProperty("tracked", c.isTracked(uuid));
        o.add("categories", cats);
        return Response.ok(o);
    }

    private static String worldKey(ChronicleSource c, String world) {
        if (world == null || world.isBlank()) {
            return null;
        }
        String key = c.worldKey(world);
        return key == null ? world : key;
    }

    private static Response one(ChronicleSource c, JsonFile export, Call call, Limits limits) {
        UUID uuid = names(export).resolve(call.param("player"));
        String stat = call.param("stat");
        List<StatDef> defs = c.stats();
        StatDef d = defOf(defs, stat);
        if (d == null) {
            throw ApiException.notFound("stat_not_found", SuiteText.get(SuiteText.STAT_NOT_FOUND, "stat", stat));
        }
        Window w = window(call.query("window", null));
        JsonObject o = new JsonObject();
        o.addProperty("uuid", uuid.toString());
        o.addProperty("stat", stat);
        o.addProperty("window", lower(w));
        o.add("definition", def(d));
        if (d.isPrefix() && d.key().equals(stat)) {
            o.add("ids", Json.numbers(new TreeMap<>(c.perId(uuid, stat))));
        } else if (w == Window.ALL) {
            Map<String, Long> totals = Waits.await(c.totals(uuid), MOD, limits.waitMs());
            o.addProperty("value", totals == null ? 0L : totals.getOrDefault(stat, 0L));
        } else {
            o.addProperty("value", c.window(uuid, stat, w, worldKey(c, call.query("world", null))));
        }
        return Response.ok(o);
    }

    private static Response top(JsonFile export, Call call, Limits limits) {
        String stat = call.param("stat");
        Window w = window(call.query("window", null));
        if (w != Window.ALL) {
            throw ApiException.badRequest("not_exported", SuiteText.get(SuiteText.NOT_EXPORTED, "stat", stat));
        }
        JsonObject doc = export.read();
        JsonObject boards = doc.has("boards") && doc.get("boards").isJsonObject() ? doc.getAsJsonObject("boards") : new JsonObject();
        if (!boards.has(stat) || !boards.get(stat).isJsonArray()) {
            throw ApiException.notFound("stat_not_found", SuiteText.get(SuiteText.STAT_NOT_FOUND, "stat", stat));
        }
        int limit = limits.limit(call);
        JsonArray rows = new JsonArray();
        int rank = 1;
        for (JsonElement e : boards.getAsJsonArray(stat)) {
            if (rows.size() >= limit) {
                break;
            }
            JsonObject row = e.getAsJsonObject().deepCopy();
            row.addProperty("rank", rank++);
            rows.add(row);
        }
        JsonObject o = new JsonObject();
        o.addProperty("stat", stat);
        o.addProperty("window", "all");
        copy(doc, o, "updatedAt");
        o.add("rows", rows);
        return Response.ok(o);
    }

    private static Response server(JsonFile export) {
        JsonObject doc = export.read();
        JsonObject o = new JsonObject();
        copy(doc, o, "updatedAt");
        copy(doc, o, "online");
        copy(doc, o, "chronicleVersion");
        copy(doc, o, "server");
        copy(doc, o, "recentRecords");
        return Response.ok(o);
    }

    static String lower(Enum<?> e) {
        return e == null ? null : e.name().toLowerCase(Locale.ROOT);
    }
}
