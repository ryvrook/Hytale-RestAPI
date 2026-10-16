package dev.ryvrook.restapi.suite.chronicle;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import dev.ryvrook.chronicle.api.Board;
import dev.ryvrook.chronicle.api.RecordBroken;
import dev.ryvrook.chronicle.api.Resolution;
import dev.ryvrook.chronicle.api.Series;
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
import dev.ryvrook.restapi.suite.SuiteSettings;
import dev.ryvrook.restapi.suite.SuiteText;
import dev.ryvrook.restapi.suite.Waits;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.BooleanSupplier;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

/**
 * The Chronicle Stats route group under /api/chronicle, bound through ChronicleApi's enable and disable hooks.
 * Definitions, player totals, boards per window and world, server totals, the newest records and the server
 * series all come from ChronicleApi. Boards and records carry Chronicle's public privacy view, the series are raw,
 * so the performance series (everything outside players.* and server.*) stay away while
 * suite.chronicle.performanceSeries is off. An older Chronicle bound under always gives the all time boards and
 * the server totals from {@code export/stats.json}, which Chronicle writes every exportSeconds for Atlas.
 */
public final class ChronicleRoutes implements SiblingUnit {
    static final String GROUP = Sibling.CHRONICLE.group();
    static final Scope SCOPE = Sibling.CHRONICLE.scope();
    static final String MOD = "Chronicle Stats";

    /** Chronicle's own most rows per board (pageSizeMax). */
    static final int BOARD_MAX = 100;

    private ChronicleBridge bridge;

    @Override
    public List<Route> bind(RestApi api) {
        ChronicleBridge b = new ChronicleBridge();
        b.bind();
        bridge = b;
        JsonFile export = new JsonFile(Folders.of(api, Sibling.CHRONICLE).resolve("export").resolve("stats.json"), MOD);
        return routes(b, export, Limits.live(), SuiteSettings.CHRONICLE_PERFORMANCE::get, System::currentTimeMillis);
    }

    @Override
    public void unbind() {
        if (bridge != null) {
            bridge.unbind();
            bridge = null;
        }
    }

    static List<Route> routes(ChronicleSource chronicle, JsonFile export, Supplier<Limits> limits) {
        return routes(chronicle, export, limits, () -> true, System::currentTimeMillis);
    }

    static List<Route> routes(ChronicleSource chronicle, JsonFile export, Supplier<Limits> limits,
                              BooleanSupplier performance, LongSupplier clock) {
        List<Route> out = new ArrayList<>();
        out.add(Route.of(GROUP, "GET", "/api/chronicle", SCOPE, Access.READ,
                "Chronicle Stats version and API version, how many stats, boards and series it keeps, players online and when it last wrote its export.",
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
        out.add(Route.of(GROUP, "GET", "/api/chronicle/boards", SCOPE, Access.READ,
                "The stats Chronicle keeps a board for.",
                call -> boards(chronicle, export)));
        out.add(Route.of(GROUP, "GET", "/api/chronicle/top/{stat}", SCOPE, Access.READ,
                "The board of a stat for a window and world, with each row's movement since the last build. Players who hide from boards show as hidden.",
                call -> top(chronicle, export, call, limits.get())).doc(new RouteDoc(
                List.of(new RouteDoc.Param("limit", "Rows, at most 100"), new RouteDoc.Param("window", "all, today, week or month"),
                        new RouteDoc.Param("world", "Only this world")),
                null, "{\"stat\":\"blocks_broken\",\"window\":\"week\",\"world\":null,\"builtAt\":1760620000000,\"warming\":false,\"total\":40,\"rows\":[{\"rank\":1,\"uuid\":\"...\",\"name\":\"Steve\",\"value\":812,\"movement\":2}]}",
                List.of("stat_not_found", "bad_window", "not_exported", "file_missing", "sibling_timeout"))));
        out.add(Route.of(GROUP, "GET", "/api/chronicle/server", SCOPE, Access.READ,
                "Server totals, players online and the newest records.",
                call -> server(chronicle, export, call)).doc(new RouteDoc(
                List.of(new RouteDoc.Param("records", "How many of the newest records, 0 to 100, default 10")), null, null,
                List.of("file_missing", "file_broken"))));
        out.add(Route.of(GROUP, "GET", "/api/chronicle/series", SCOPE, Access.READ,
                "The server series Chronicle samples, split into activity (players.* and server.*) and performance.",
                call -> seriesList(chronicle, performance.getAsBoolean())).doc(new RouteDoc(List.of(), null,
                "{\"activity\":[\"players.online\"],\"performance\":[\"tick.mspt\"]}", List.of("not_supported"))));
        out.add(Route.of(GROUP, "GET", "/api/chronicle/series/{id}", SCOPE, Access.READ,
                "One server series at minute, hour or day resolution between two times. Empty buckets read as null.",
                call -> series(chronicle, call, performance.getAsBoolean(), clock.getAsLong())).doc(new RouteDoc(
                List.of(new RouteDoc.Param("resolution", "minute, hour or day, hour when missing"),
                        new RouteDoc.Param("from", "Epoch milliseconds or an ISO instant, a day back when missing"),
                        new RouteDoc.Param("to", "Epoch milliseconds or an ISO instant, now when missing")), null,
                "{\"id\":\"players.online\",\"resolution\":\"hour\",\"kind\":\"gauge\",\"unit\":\"players\",\"openIndex\":23,\"points\":[{\"at\":1760620000000,\"samples\":60,\"avg\":3.5,\"min\":2.0,\"max\":5.0,\"sum\":210.0}]}",
                List.of("series_not_found", "bad_resolution", "bad_time", "not_supported"))));
        return out;
    }

    private static Response summary(ChronicleSource c, JsonFile export) {
        JsonObject o = new JsonObject();
        o.addProperty("version", c.version());
        o.addProperty("apiVersion", c.apiVersion());
        o.addProperty("stats", c.stats().size());
        List<String> boards = c.boardKeys();
        if (boards != null) {
            o.addProperty("boards", boards.size());
        }
        java.util.Set<String> series = c.seriesIds();
        if (series != null) {
            o.addProperty("series", series.size());
        }
        int online = c.online();
        if (online >= 0) {
            o.addProperty("online", online);
        }
        JsonObject doc = null;
        try {
            doc = export.readOrNull();
        } catch (ApiException e) {
            // Reported as no export below.
        }
        o.addProperty("exported", doc != null);
        if (doc != null) {
            copy(doc, o, "updatedAt");
            if (online < 0) {
                copy(doc, o, "online");
            }
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

    private static Response boards(ChronicleSource c, JsonFile export) {
        List<String> keys = c.boardKeys();
        JsonObject o = new JsonObject();
        if (keys == null) {
            JsonObject doc = export.read();
            JsonObject boards = doc.has("boards") && doc.get("boards").isJsonObject() ? doc.getAsJsonObject("boards") : new JsonObject();
            o.add("boards", Json.strings(new TreeSet<>(boards.keySet())));
            o.addProperty("windows", false);
        } else {
            o.add("boards", Json.strings(keys));
            o.addProperty("windows", true);
        }
        return Response.ok(o);
    }

    private static Response top(ChronicleSource c, JsonFile export, Call call, Limits limits) {
        String stat = call.param("stat");
        Window w = window(call.query("window", null));
        List<String> keys = c.boardKeys();
        if (keys == null) {
            return topFromExport(export, call, limits, stat, w);
        }
        if (!keys.contains(stat)) {
            throw ApiException.notFound("stat_not_found", SuiteText.get(SuiteText.STAT_NOT_FOUND, "stat", stat));
        }
        String world = call.query("world", null);
        String worldKey = worldKey(c, world);
        int limit = Math.min(BOARD_MAX, limits.limit(call));
        CompletableFuture<Board> future = c.board(stat, w, worldKey, limit);
        if (future == null) {
            return topFromExport(export, call, limits, stat, w);
        }
        Board b = Waits.await(future, MOD, limits.waitMs());
        JsonObject o = new JsonObject();
        o.addProperty("stat", stat);
        o.addProperty("window", lower(w));
        o.addProperty("world", world == null || world.isBlank() ? null : world);
        if (b == null) {
            o.addProperty("warming", true);
            o.add("rows", new JsonArray());
            return Response.ok(o);
        }
        o.addProperty("builtAt", b.builtAt());
        o.addProperty("warming", b.warming());
        o.addProperty("total", b.total());
        JsonArray rows = new JsonArray();
        for (Board.Row row : b.rows()) {
            if (rows.size() >= limit) {
                break;
            }
            JsonObject x = new JsonObject();
            x.addProperty("rank", row.rank());
            x.add("uuid", Json.str(row.uuid()));
            x.addProperty("name", row.name());
            x.addProperty("hidden", row.uuid() == null);
            x.addProperty("value", row.value());
            x.addProperty("movement", row.movement());
            rows.add(x);
        }
        o.add("rows", rows);
        return Response.ok(o);
    }

    private static Response topFromExport(JsonFile export, Call call, Limits limits, String stat, Window w) {
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

    private static Response server(ChronicleSource c, JsonFile export, Call call) {
        Map<String, Long> totals = c.serverTotals();
        if (totals == null) {
            JsonObject doc = export.read();
            JsonObject o = new JsonObject();
            copy(doc, o, "updatedAt");
            copy(doc, o, "online");
            copy(doc, o, "chronicleVersion");
            copy(doc, o, "server");
            copy(doc, o, "recentRecords");
            return Response.ok(o);
        }
        int count = Json.integer(call.query("records", "10"), "records");
        count = Math.max(0, Math.min(100, count));
        JsonObject o = new JsonObject();
        o.addProperty("chronicleVersion", c.version());
        o.addProperty("online", c.online());
        o.add("server", Json.numbers(new TreeMap<>(totals)));
        JsonArray records = new JsonArray();
        List<RecordBroken> recent = count == 0 ? List.of() : c.recentRecords(count);
        if (recent != null) {
            for (RecordBroken rb : recent) {
                records.add(record(rb));
            }
        }
        o.add("recentRecords", records);
        return Response.ok(o);
    }

    static JsonObject record(RecordBroken r) {
        JsonObject o = new JsonObject();
        o.addProperty("kind", r.kind());
        o.addProperty("key", r.key());
        o.addProperty("label", r.label());
        o.addProperty("value", r.value());
        o.addProperty("valueText", r.valueText());
        o.add("holders", r.holders() == null ? null : Json.strings(r.holders()));
        o.add("names", r.names() == null ? null : Json.strings(r.names()));
        o.addProperty("world", r.worldKey());
        o.addProperty("at", r.at());
        o.addProperty("imported", r.imported());
        return o;
    }

    /** Activity series are players.* and server.*, the dashboard's public split. Everything else is performance. */
    static boolean activity(String id) {
        return id.startsWith("players.") || id.startsWith("server.");
    }

    private static Response seriesList(ChronicleSource c, boolean performance) {
        java.util.Set<String> ids = c.seriesIds();
        if (ids == null) {
            throw new ApiException(501, "not_supported", SuiteText.get(SuiteText.NO_SERIES));
        }
        JsonArray act = new JsonArray();
        JsonArray perf = new JsonArray();
        for (String id : new TreeSet<>(ids)) {
            if (activity(id)) {
                act.add(id);
            } else if (performance) {
                perf.add(id);
            }
        }
        JsonObject o = new JsonObject();
        o.add("activity", act);
        o.add("performance", perf);
        o.addProperty("performanceShown", performance);
        return Response.ok(o);
    }

    private static Response series(ChronicleSource c, Call call, boolean performance, long now) {
        String id = call.param("id");
        java.util.Set<String> ids = c.seriesIds();
        if (ids == null) {
            throw new ApiException(501, "not_supported", SuiteText.get(SuiteText.NO_SERIES));
        }
        if (!ids.contains(id) || !performance && !activity(id)) {
            throw ApiException.notFound("series_not_found", SuiteText.get(SuiteText.SERIES_NOT_FOUND, "id", id));
        }
        Resolution res;
        String raw = call.query("resolution", "hour");
        try {
            res = Resolution.valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw ApiException.badRequest("bad_resolution", SuiteText.get(SuiteText.BAD_RESOLUTION));
        }
        long to = Json.time(call.query("to", null), "to", now);
        long from = Json.time(call.query("from", null), "from", to - 86_400_000L);
        Series s = c.series(id, res, from, to);
        if (s == null) {
            throw ApiException.notFound("series_not_found", SuiteText.get(SuiteText.SERIES_NOT_FOUND, "id", id));
        }
        JsonObject o = new JsonObject();
        o.addProperty("id", s.id());
        o.addProperty("resolution", lower(s.resolution()));
        o.addProperty("kind", s.kind());
        o.addProperty("unit", s.unit());
        o.addProperty("openIndex", s.openIndex());
        o.addProperty("from", from);
        o.addProperty("to", to);
        JsonArray points = new JsonArray();
        for (Series.Point p : s.points()) {
            JsonObject x = new JsonObject();
            x.addProperty("at", p.at());
            x.addProperty("samples", p.samples());
            x.add("avg", num(p.avg()));
            x.add("min", num(p.min()));
            x.add("max", num(p.max()));
            x.add("sum", num(p.sum()));
            points.add(x);
        }
        o.add("points", points);
        return Response.ok(o);
    }

    /** NaN and the infinities as null, JSON has no word for them. */
    static JsonElement num(double v) {
        return Double.isFinite(v) ? new com.google.gson.JsonPrimitive(v) : com.google.gson.JsonNull.INSTANCE;
    }

    static String lower(Enum<?> e) {
        return e == null ? null : e.name().toLowerCase(Locale.ROOT);
    }
}
