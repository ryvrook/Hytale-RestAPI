package dev.ryvrook.restapi.suite.tracker;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
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
import dev.ryvrook.restapi.suite.Json;
import dev.ryvrook.restapi.suite.Limits;
import dev.ryvrook.restapi.suite.Names;
import dev.ryvrook.restapi.suite.SuiteSettings;
import dev.ryvrook.restapi.suite.SuiteText;
import dev.ryvrook.restapi.suite.Waits;
import dev.ryvrook.trackerapi.api.Counters;
import dev.ryvrook.trackerapi.api.Delivery;
import dev.ryvrook.trackerapi.api.EventKind;
import dev.ryvrook.trackerapi.api.Journal;
import dev.ryvrook.trackerapi.api.PlayerSnapshot;
import dev.ryvrook.trackerapi.api.Stats;
import dev.ryvrook.trackerapi.api.Subscription;
import dev.ryvrook.trackerapi.api.TrackerApi;
import dev.ryvrook.trackerapi.api.TrackerEvent;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * The TrackerAPI route group under /api/tracker. Binds through {@code TrackerApi.onEnable} and {@code onDisable},
 * so the API object is the one TrackerAPI handed out last, and keeps a small ring of recent events through one
 * ASYNC subscription on TrackerAPI's dispatch thread.
 */
public final class TrackerRoutes implements SiblingUnit {
    static final String GROUP = Sibling.TRACKER.group();
    static final Scope SCOPE = Sibling.TRACKER.scope();
    static final String MOD = "TrackerAPI";
    static final String OWNER = "RestAPI";

    private volatile TrackerApi current;
    private volatile Subscription subscription;
    private Consumer<TrackerApi> enableHook;
    private Runnable disableHook;
    private RecentEvents recent;

    @Override
    public List<Route> bind(RestApi api) {
        recent = new RecentEvents(SuiteSettings.TRACKER_RECENT::get);
        boolean highVolume = SuiteSettings.TRACKER_RECENT_HIGH_VOLUME.get();
        enableHook = t -> {
            current = t;
            subscribe(t, highVolume);
        };
        disableHook = () -> {
            current = null;
            closeSubscription();
        };
        TrackerApi.onEnable(enableHook);
        TrackerApi.onDisable(disableHook);
        if (current == null) {
            TrackerApi.removeHooks(enableHook, disableHook);
            throw new IllegalStateException("TrackerAPI has not handed out its API yet");
        }
        return routes(() -> current, recent, Limits.live());
    }

    @Override
    public void unbind() {
        if (enableHook != null) {
            TrackerApi.removeHooks(enableHook, disableHook);
        }
        current = null;
        closeSubscription();
        if (recent != null) {
            recent.clear();
        }
    }

    private void subscribe(TrackerApi t, boolean highVolume) {
        closeSubscription();
        if (SuiteSettings.TRACKER_RECENT.get() <= 0) {
            return;
        }
        Set<EventKind> kinds = EnumSet.noneOf(EventKind.class);
        for (EventKind k : EventKind.values()) {
            if (highVolume || !k.highVolume()) {
                kinds.add(k);
            }
        }
        RecentEvents ring = recent;
        subscription = t.subscribeKinds(OWNER, kinds, Delivery.ASYNC, RecentEvents.guarded(ring::add, this::closeSubscription));
    }

    private void closeSubscription() {
        Subscription s = subscription;
        subscription = null;
        if (s != null) {
            try {
                s.close();
            } catch (RuntimeException e) {
                // TrackerAPI is stopping and closes it itself.
            }
        }
    }

    /** The routes over whatever API the supplier gives, for the unit and for the tests. */
    static List<Route> routes(Supplier<TrackerApi> source, RecentEvents recent, Supplier<Limits> limits) {
        Supplier<TrackerApi> api = () -> {
            TrackerApi t = source.get();
            if (t == null) {
                throw new ApiException(503, "sibling_gone", SuiteText.get(SuiteText.SIBLING_GONE, "mod", MOD));
            }
            return t;
        };
        List<Route> out = new ArrayList<>();
        out.add(Route.of(GROUP, "GET", "/api/tracker", SCOPE, Access.READ,
                "TrackerAPI version, queue and journal figures, and how many players and leaderboards it holds.",
                call -> summary(api.get(), recent)).doc(new RouteDoc(List.of(), null,
                "{\"version\":\"1.1.0\",\"kindsTracked\":41,\"queued\":0,\"queueCapacity\":65536,\"journalLines\":1200,\"knownPlayers\":12,\"online\":2,\"leaderboards\":58,\"recentKept\":140}",
                List.of("sibling_gone"))));
        out.add(Route.of(GROUP, "GET", "/api/tracker/kinds", SCOPE, Access.READ,
                "Every TrackerAPI event kind with whether it is tracked and journaled and how many were published and dropped.",
                call -> kinds(api.get())));
        out.add(Route.of(GROUP, "GET", "/api/tracker/counters/{player}", SCOPE, Access.READ,
                "Every counter of one player, read from disk when they are offline. prefix keeps the keys that start with it.",
                call -> counters(api.get(), call, limits.get())).doc(new RouteDoc(
                List.of(new RouteDoc.Param("prefix", "Only keys that start with this, such as block_broken: or dominion:")),
                null, "{\"uuid\":\"...\",\"name\":\"Steve\",\"online\":true,\"counters\":{\"block_broken\":812,\"deaths\":3}}",
                List.of("player_not_found", "sibling_timeout"))));
        out.add(Route.of(GROUP, "GET", "/api/tracker/server", SCOPE, Access.READ,
                "The server wide totals of every counter. prefix keeps the keys that start with it.",
                call -> server(api.get(), call)));
        out.add(Route.of(GROUP, "GET", "/api/tracker/leaderboards", SCOPE, Access.READ,
                "The counter keys TrackerAPI keeps a leaderboard for.",
                call -> boards(api.get())));
        out.add(Route.of(GROUP, "GET", "/api/tracker/leaderboards/{key}", SCOPE, Access.READ,
                "The top players for one counter key, with names where TrackerAPI knows them.",
                call -> board(api.get(), call, limits.get())).doc(new RouteDoc(
                List.of(new RouteDoc.Param("limit", "Rows, at most 1000 and at most suite.maxLimit")), null,
                "{\"key\":\"deaths\",\"rows\":[{\"rank\":1,\"uuid\":\"...\",\"name\":\"Steve\",\"value\":42}]}",
                List.of("no_board"))));
        out.add(Route.of(GROUP, "GET", "/api/tracker/players", SCOPE, Access.READ,
                "The latest TrackerAPI snapshot of every online player, position, game mode, zone, biome and movement.",
                call -> snapshots(api.get())));
        out.add(Route.of(GROUP, "GET", "/api/tracker/players/{player}", SCOPE, Access.READ,
                "The latest TrackerAPI snapshot of one online player.",
                call -> snapshot(api.get(), call)));
        out.add(Route.of(GROUP, "GET", "/api/tracker/recent", SCOPE, Access.READ,
                "The newest events RestAPI kept from TrackerAPI, newest first. Each kind keeps suite.tracker.recentPerKind.",
                call -> recent(recent, call, limits.get())).doc(new RouteDoc(
                List.of(new RouteDoc.Param("kinds", "Event kinds separated by commas, such as player_joined,entity_died"),
                        new RouteDoc.Param("limit", "Rows")), null,
                "{\"events\":[{\"kind\":\"player_joined\",\"at\":1760616000000,\"seq\":88,\"world\":\"default\",\"player\":\"...\",\"event\":{}}]}",
                List.of("unknown_kind", "recent_off"))));
        out.add(Route.of(GROUP, "GET", "/api/tracker/journal", SCOPE, Access.READ,
                "Searches the TrackerAPI journal on disk, newest first. Page back by passing the oldest at as to.",
                call -> journal(api.get(), call, limits.get())).doc(new RouteDoc(
                List.of(new RouteDoc.Param("kinds", "Event kinds separated by commas"),
                        new RouteDoc.Param("player", "A name or UUID"),
                        new RouteDoc.Param("world", "A world name"),
                        new RouteDoc.Param("from", "Epoch milliseconds or an ISO instant, the oldest time"),
                        new RouteDoc.Param("to", "Epoch milliseconds or an ISO instant, the newest time"),
                        new RouteDoc.Param("minX", "With minY, minZ, maxX, maxY and maxZ, a box of block positions"),
                        new RouteDoc.Param("limit", "Rows")), null,
                "{\"entries\":[{\"kind\":\"block_broken\",\"at\":1760616000000,\"seq\":10,\"world\":\"default\",\"player\":\"...\",\"event\":{}}],\"next\":1760615999999}",
                List.of("unknown_kind", "bad_time", "bad_box", "sibling_timeout"))));
        return out;
    }

    static Names names(TrackerApi t, int waitMs) {
        return new Names(MOD, Names::online,
                name -> Waits.await(t.counters().uuidOf(name), MOD, waitMs));
    }

    private static Response summary(TrackerApi t, RecentEvents recent) {
        JsonObject o = new JsonObject();
        o.addProperty("version", t.version());
        o.addProperty("apiVersion", TrackerApi.API_VERSION);
        int tracked = 0;
        for (EventKind k : EventKind.values()) {
            if (t.isTracked(k)) {
                tracked++;
            }
        }
        o.addProperty("kinds", EventKind.values().length);
        o.addProperty("kindsTracked", tracked);
        Stats s = t.stats();
        if (s != null) {
            o.addProperty("queued", s.queued());
            o.addProperty("queueCapacity", s.queueCapacity());
            o.addProperty("journalLines", s.journalLines());
            o.addProperty("journalPending", s.journalPending());
            o.addProperty("journalBytes", s.journalBytes());
            o.addProperty("statsSince", s.sinceMillis());
        }
        Counters c = t.counters();
        o.addProperty("knownPlayers", c.known().size());
        o.addProperty("online", t.snapshots().size());
        o.addProperty("leaderboards", c.boardKeys().size());
        o.addProperty("subscriptions", t.subscriptions().size());
        o.addProperty("recentKept", recent.size());
        return Response.ok(o);
    }

    private static Response kinds(TrackerApi t) {
        Stats s = t.stats();
        Set<EventKind> journaled = t.journal() == null ? Set.of() : t.journal().kinds();
        JsonArray a = new JsonArray();
        for (EventKind k : EventKind.values()) {
            JsonObject o = new JsonObject();
            o.addProperty("id", k.id());
            o.addProperty("highVolume", k.highVolume());
            o.addProperty("tracked", t.isTracked(k));
            o.addProperty("journaled", journaled.contains(k));
            o.addProperty("published", s == null ? 0 : s.published().getOrDefault(k, 0L));
            o.addProperty("dropped", s == null ? 0 : s.dropped().getOrDefault(k, 0L));
            a.add(o);
        }
        JsonObject o = new JsonObject();
        o.add("kinds", a);
        return Response.ok(o);
    }

    private static Response counters(TrackerApi t, Call call, Limits limits) {
        UUID uuid = names(t, limits.waitMs()).resolve(call.param("player"));
        Counters c = t.counters();
        boolean online = c.isLoaded(uuid);
        Map<String, Long> all = online ? c.all(uuid) : Waits.await(c.load(uuid), MOD, limits.waitMs());
        if (all == null || all.isEmpty() && !online && !c.known().contains(uuid)) {
            throw ApiException.notFound("player_not_found",
                    SuiteText.get(SuiteText.PLAYER_NOT_FOUND, "player", call.param("player"), "mod", MOD));
        }
        String prefix = call.query("prefix", "");
        Map<String, Long> sorted = new TreeMap<>();
        all.forEach((k, v) -> {
            if (k.startsWith(prefix)) {
                sorted.put(k, v);
            }
        });
        JsonObject o = new JsonObject();
        o.addProperty("uuid", uuid.toString());
        o.add("name", Json.str(nameOf(c, uuid, limits.waitMs())));
        o.addProperty("online", online);
        o.add("counters", Json.numbers(sorted));
        return Response.ok(o);
    }

    private static Response server(TrackerApi t, Call call) {
        String prefix = call.query("prefix", "");
        Map<String, Long> sorted = new TreeMap<>();
        t.counters().serverAll().forEach((k, v) -> {
            if (k.startsWith(prefix)) {
                sorted.put(k, v);
            }
        });
        JsonObject o = new JsonObject();
        o.add("counters", Json.numbers(sorted));
        return Response.ok(o);
    }

    private static Response boards(TrackerApi t) {
        JsonObject o = new JsonObject();
        o.add("keys", Json.strings(new java.util.TreeSet<>(t.counters().boardKeys())));
        return Response.ok(o);
    }

    private static Response board(TrackerApi t, Call call, Limits limits) {
        String key = call.param("key");
        Counters c = t.counters();
        if (!c.boardKeys().contains(key)) {
            throw ApiException.notFound("no_board", SuiteText.get(SuiteText.NO_BOARD, "key", key));
        }
        int limit = Math.min(limits.limit(call), 1000);
        List<Map.Entry<UUID, Long>> top = c.top(key, limit);
        List<CompletableFuture<Optional<String>>> names = new ArrayList<>();
        for (Map.Entry<UUID, Long> e : top) {
            names.add(c.nameOf(e.getKey()));
        }
        CompletableFuture<Void> all = CompletableFuture.allOf(names.toArray(CompletableFuture[]::new));
        try {
            Waits.await(all, MOD, limits.waitMs());
        } catch (ApiException e) {
            // Rows keep a null name where the lookup did not finish in time.
        }
        JsonArray rows = new JsonArray();
        int rank = 1;
        for (int i = 0; i < top.size(); i++) {
            Map.Entry<UUID, Long> e = top.get(i);
            CompletableFuture<Optional<String>> n = names.get(i);
            JsonObject r = new JsonObject();
            r.addProperty("rank", rank++);
            r.addProperty("uuid", e.getKey().toString());
            r.add("name", Json.str(n.isDone() && !n.isCompletedExceptionally() ? n.join().orElse(null) : null));
            r.addProperty("value", e.getValue());
            rows.add(r);
        }
        JsonObject o = new JsonObject();
        o.addProperty("key", key);
        o.add("rows", rows);
        return Response.ok(o);
    }

    private static Response snapshots(TrackerApi t) {
        JsonArray a = new JsonArray();
        for (PlayerSnapshot s : t.snapshots()) {
            a.add(Json.of(s));
        }
        JsonObject o = new JsonObject();
        o.add("players", a);
        return Response.ok(o);
    }

    private static Response snapshot(TrackerApi t, Call call) {
        String player = call.param("player");
        UUID uuid = new Names(MOD, Names::online, name -> t.snapshots().stream()
                .filter(s -> s.name() != null && s.name().equalsIgnoreCase(name)).map(PlayerSnapshot::uuid).findFirst())
                .resolve(player);
        PlayerSnapshot s = t.snapshot(uuid);
        if (s == null) {
            throw ApiException.notFound("player_not_online", SuiteText.get(SuiteText.NO_SNAPSHOT, "player", player));
        }
        return Response.ok(Json.of(s));
    }

    private static Response recent(RecentEvents recent, Call call, Limits limits) {
        if (!recent.enabled()) {
            throw new ApiException(409, "recent_off", SuiteText.get(SuiteText.RECENT_OFF));
        }
        Set<EventKind> kinds = kinds(call.query("kinds", ""));
        JsonArray a = new JsonArray();
        for (TrackerEvent e : recent.newest(kinds, limits.limit(call))) {
            a.add(event(e));
        }
        JsonObject o = new JsonObject();
        o.add("events", a);
        return Response.ok(o);
    }

    private static Response journal(TrackerApi t, Call call, Limits limits) {
        Journal.Query q = Journal.query()
                .withKinds(kinds(call.query("kinds", "")))
                .between(Json.time(call.query("from", null), "from", 0L),
                        Json.time(call.query("to", null), "to", Long.MAX_VALUE))
                .limit(limits.limit(call));
        String player = call.query("player", null);
        if (player != null && !player.isBlank()) {
            q = q.withPlayer(names(t, limits.waitMs()).resolve(player));
        }
        String world = call.query("world", null);
        if (world != null && !world.isBlank()) {
            q = q.withWorld(world);
        }
        String[] box = {"minX", "minY", "minZ", "maxX", "maxY", "maxZ"};
        int given = 0;
        Integer[] v = new Integer[6];
        for (int i = 0; i < 6; i++) {
            String raw = call.query(box[i], null);
            if (raw != null && !raw.isBlank()) {
                v[i] = Json.integer(raw, box[i]);
                given++;
            }
        }
        if (given != 0 && given != 6) {
            throw ApiException.badRequest("bad_box", SuiteText.get(SuiteText.BAD_BOX));
        }
        if (given == 6) {
            q = new Journal.Query(q.kinds(), q.player(), q.world(), v[0], v[1], v[2], v[3], v[4], v[5],
                    q.fromMillis(), q.toMillis(), q.limit());
        }
        List<Journal.Entry> entries = Waits.await(t.journal().find(q), MOD, limits.waitMs());
        JsonArray a = new JsonArray();
        long oldest = Long.MAX_VALUE;
        for (Journal.Entry e : entries) {
            JsonObject o = new JsonObject();
            o.addProperty("kind", e.kind() == null ? null : e.kind().id());
            o.addProperty("at", e.at());
            o.addProperty("seq", e.seq());
            o.addProperty("world", e.world());
            o.add("player", Json.str(e.player()));
            o.add("event", parse(e.json()));
            a.add(o);
            oldest = Math.min(oldest, e.at());
        }
        JsonObject o = new JsonObject();
        o.add("entries", a);
        if (entries.size() >= q.limit() && oldest != Long.MAX_VALUE) {
            o.addProperty("next", oldest - 1);
        } else {
            o.add("next", null);
        }
        return Response.ok(o);
    }

    private static JsonElement parse(String json) {
        if (json == null) {
            return null;
        }
        try {
            return JsonParser.parseString(json);
        } catch (RuntimeException e) {
            return Json.str(json);
        }
    }

    /** Kinds by id separated by commas, 400 unknown_kind for a word that is not one. Empty means every kind. */
    static Set<EventKind> kinds(String raw) {
        Set<EventKind> out = EnumSet.noneOf(EventKind.class);
        for (String part : raw.split("[,\\s]+")) {
            String p = part.trim();
            if (p.isEmpty()) {
                continue;
            }
            EventKind k = EventKind.byId(p.toLowerCase(Locale.ROOT));
            if (k == null) {
                try {
                    k = EventKind.valueOf(p.toUpperCase(Locale.ROOT));
                } catch (IllegalArgumentException e) {
                    throw ApiException.badRequest("unknown_kind", SuiteText.get(SuiteText.UNKNOWN_KIND, "kind", p));
                }
            }
            out.add(k);
        }
        return out;
    }

    static JsonObject event(TrackerEvent e) {
        JsonObject o = new JsonObject();
        o.addProperty("kind", e.kind().id());
        o.addProperty("at", e.at());
        o.addProperty("seq", e.seq());
        o.addProperty("world", e.world());
        o.add("player", Json.str(e.player()));
        JsonElement body = Json.of(e);
        if (body.isJsonObject()) {
            body.getAsJsonObject().remove("header");
        }
        o.add("event", body);
        return o;
    }

    private static String nameOf(Counters c, UUID uuid, int waitMs) {
        try {
            return Waits.await(c.nameOf(uuid), MOD, Math.min(waitMs, 1000)).orElse(null);
        } catch (ApiException e) {
            return null;
        }
    }
}
