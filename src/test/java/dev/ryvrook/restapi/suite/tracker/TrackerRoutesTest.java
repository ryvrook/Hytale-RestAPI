package dev.ryvrook.restapi.suite.tracker;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import dev.ryvrook.restapi.http.Response;
import dev.ryvrook.restapi.suite.Calls;
import dev.ryvrook.restapi.suite.Limits;
import dev.ryvrook.trackerapi.api.Counters;
import dev.ryvrook.trackerapi.api.Delivery;
import dev.ryvrook.trackerapi.api.EventKind;
import dev.ryvrook.trackerapi.api.Header;
import dev.ryvrook.trackerapi.api.Journal;
import dev.ryvrook.trackerapi.api.PlayerSnapshot;
import dev.ryvrook.trackerapi.api.Stats;
import dev.ryvrook.trackerapi.api.Subscription;
import dev.ryvrook.trackerapi.api.TrackerApi;
import dev.ryvrook.trackerapi.api.TrackerEvent;
import dev.ryvrook.trackerapi.api.event.PlayerJoined;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.RecordComponent;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;

class TrackerRoutesTest {
    static final UUID STEVE = UUID.fromString("00000000-0000-0000-0000-00000000000a");
    static final UUID ALEX = UUID.fromString("00000000-0000-0000-0000-00000000000b");

    /** TrackerAPI as a consumer sees it, with counters and a journal held in maps. */
    static final class FakeTracker extends TrackerApi {
        final Map<UUID, Map<String, Long>> online = new HashMap<>();
        final Map<UUID, Map<String, Long>> onDisk = new HashMap<>();
        final AtomicReference<Journal.Query> lastQuery = new AtomicReference<>();
        final List<Journal.Entry> journalEntries = new ArrayList<>();
        final List<PlayerSnapshot> snaps = new ArrayList<>();

        final Counters counters = Calls.stand(Counters.class, Map.of(
                "isLoaded", a -> online.containsKey((UUID) a[0]),
                "all", a -> online.get((UUID) a[0]),
                "load", a -> CompletableFuture.completedFuture(onDisk.getOrDefault((UUID) a[0], Map.of())),
                "known", a -> Set.of(STEVE, ALEX),
                "nameOf", a -> CompletableFuture.completedFuture(Optional.ofNullable(a[0].equals(STEVE) ? "Steve" : a[0].equals(ALEX) ? "Alex" : null)),
                "uuidOf", a -> CompletableFuture.completedFuture(Optional.ofNullable(
                        "steve".equalsIgnoreCase((String) a[0]) ? STEVE : "alex".equalsIgnoreCase((String) a[0]) ? ALEX : null)),
                "serverAll", a -> Map.of("deaths", 9L, "block_broken", 400L),
                "boardKeys", a -> Set.of("deaths", "block_broken"),
                "top", a -> List.of(Map.entry(ALEX, 6L), Map.entry(STEVE, 3L))));

        final Journal journal = Calls.stand(Journal.class, Map.of(
                "kinds", a -> Set.of(EventKind.BLOCK_BROKEN),
                "find", a -> {
                    lastQuery.set((Journal.Query) a[0]);
                    return CompletableFuture.completedFuture(journalEntries);
                }));

        @Override
        public <E extends TrackerEvent> Subscription subscribe(String owner, Class<E> type, Delivery d, Consumer<? super E> l) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Subscription subscribeAll(String owner, Delivery d, Consumer<TrackerEvent> l) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Subscription subscribeKinds(String owner, Set<EventKind> kinds, Delivery d, Consumer<TrackerEvent> l) {
            throw new UnsupportedOperationException();
        }

        @Override
        public List<Subscription> subscriptions() {
            return List.of();
        }

        @Override
        public boolean isTracked(EventKind kind) {
            return kind == EventKind.BLOCK_BROKEN || kind == EventKind.PLAYER_JOINED;
        }

        @Override
        public PlayerSnapshot snapshot(UUID uuid) {
            return snaps.stream().filter(s -> s.uuid().equals(uuid)).findFirst().orElse(null);
        }

        @Override
        public List<PlayerSnapshot> snapshots() {
            return snaps;
        }

        @Override
        public Counters counters() {
            return counters;
        }

        @Override
        public Journal journal() {
            return journal;
        }

        @Override
        public Stats stats() {
            return new Stats(Map.of(EventKind.BLOCK_BROKEN, 120L), Map.of(EventKind.BLOCK_BROKEN, 2L), 3, 65536, 900, 0,
                    4096, Map.of(), Map.of(), 1000L);
        }

        @Override
        public String version() {
            return "1.1.0";
        }
    }

    FakeTracker tracker;
    RecentEvents recent;
    Calls calls;

    @BeforeEach
    void setUp() {
        tracker = new FakeTracker();
        recent = new RecentEvents(() -> 2);
        calls = new Calls(TrackerRoutes.routes(() -> tracker, recent, () -> new Limits(50, 100, 500)));
    }

    @Test
    void everyRouteSitsUnderItsPathWithTheTrackerScope() {
        for (var r : TrackerRoutes.routes(() -> tracker, recent, () -> Limits.DEFAULTS)) {
            assertTrue(r.pattern().equals("/api/tracker") || r.pattern().startsWith("/api/tracker/"), r.pattern());
            assertEquals("suite.tracker", r.group());
            assertEquals(dev.ryvrook.restapi.auth.Scope.TRACKER, r.scope());
            assertEquals(dev.ryvrook.restapi.http.Access.READ, r.access());
        }
    }

    @Test
    void theSummaryCountsWhatTrackerApiHolds() {
        JsonObject o = Calls.json(calls.get("/api/tracker"));
        assertEquals("1.1.0", o.get("version").getAsString());
        assertEquals(2, o.get("kindsTracked").getAsInt());
        assertEquals(2, o.get("knownPlayers").getAsInt());
        assertEquals(2, o.get("leaderboards").getAsInt());
        assertEquals(900, o.get("journalLines").getAsLong());
    }

    @Test
    void kindsCarryTheirFigures() {
        JsonArray kinds = Calls.json(calls.get("/api/tracker/kinds")).getAsJsonArray("kinds");
        assertEquals(EventKind.values().length, kinds.size());
        JsonObject broken = kinds.asList().stream().map(e -> e.getAsJsonObject())
                .filter(o -> o.get("id").getAsString().equals(EventKind.BLOCK_BROKEN.id())).findFirst().orElseThrow();
        assertTrue(broken.get("tracked").getAsBoolean());
        assertTrue(broken.get("journaled").getAsBoolean());
        assertEquals(120, broken.get("published").getAsLong());
        assertEquals(2, broken.get("dropped").getAsLong());
    }

    @Test
    void countersOfAnOnlinePlayerByNameWithAPrefix() {
        tracker.online.put(STEVE, Map.of("block_broken", 10L, "block_broken:Rock_Stone", 7L, "deaths", 1L));
        JsonObject o = Calls.json(calls.get("/api/tracker/counters/steve?prefix=block_broken"));
        assertEquals(STEVE.toString(), o.get("uuid").getAsString());
        assertEquals("Steve", o.get("name").getAsString());
        assertTrue(o.get("online").getAsBoolean());
        JsonObject c = o.getAsJsonObject("counters");
        assertEquals(2, c.size());
        assertEquals(7, c.get("block_broken:Rock_Stone").getAsLong());
    }

    @Test
    void countersOfAnOfflinePlayerComeFromDisk() {
        tracker.onDisk.put(ALEX, Map.of("deaths", 4L));
        JsonObject o = Calls.json(calls.get("/api/tracker/counters/" + ALEX));
        assertFalse(o.get("online").getAsBoolean());
        assertEquals(4, o.getAsJsonObject("counters").get("deaths").getAsLong());
    }

    @Test
    void anUnknownPlayerIs404() {
        Response r = calls.get("/api/tracker/counters/Nobody");
        assertEquals(404, r.status());
        assertEquals("player_not_found", Calls.json(r).get("error").getAsString());
        Response u = calls.get("/api/tracker/counters/" + UUID.randomUUID());
        assertEquals(404, u.status());
    }

    @Test
    void leaderboardsNameTheirPlayers() {
        JsonArray keys = Calls.json(calls.get("/api/tracker/leaderboards")).getAsJsonArray("keys");
        assertEquals("block_broken", keys.get(0).getAsString());
        JsonObject o = Calls.json(calls.get("/api/tracker/leaderboards/deaths?limit=5"));
        JsonObject first = o.getAsJsonArray("rows").get(0).getAsJsonObject();
        assertEquals(1, first.get("rank").getAsInt());
        assertEquals("Alex", first.get("name").getAsString());
        assertEquals(6, first.get("value").getAsLong());
        assertEquals(404, calls.get("/api/tracker/leaderboards/nope").status());
    }

    @Test
    void serverTotalsFilterByPrefix() {
        JsonObject c = Calls.json(calls.get("/api/tracker/server?prefix=dea")).getAsJsonObject("counters");
        assertEquals(1, c.size());
        assertEquals(9, c.get("deaths").getAsLong());
    }

    @Test
    void snapshotsOfOnlinePlayers() {
        tracker.snaps.add(new PlayerSnapshot(STEVE, "Steve", "default", 1, 64, 2, 0, 0, "Adventure", "Zone1", "r", "forest",
                false, false, false, false, 1L, 2L));
        assertEquals(1, Calls.json(calls.get("/api/tracker/players")).getAsJsonArray("players").size());
        JsonObject s = Calls.json(calls.get("/api/tracker/players/STEVE"));
        assertEquals("forest", s.get("biome").getAsString());
        assertEquals(64.0, s.get("y").getAsDouble());
        assertEquals(404, calls.get("/api/tracker/players/" + ALEX).status());
    }

    @Test
    void theRecentRingKeepsTheNewestPerKindNewestFirst() {
        for (int i = 1; i <= 3; i++) {
            recent.add(joined(i));
        }
        JsonArray events = Calls.json(calls.get("/api/tracker/recent?kinds=player_joined")).getAsJsonArray("events");
        assertEquals(2, events.size());
        JsonObject newest = events.get(0).getAsJsonObject();
        assertEquals(3, newest.get("seq").getAsLong());
        assertEquals("player_joined", newest.get("kind").getAsString());
        assertFalse(newest.getAsJsonObject("event").has("header"));
        assertEquals(400, calls.get("/api/tracker/recent?kinds=sleepwalking").status());
        assertEquals(0, Calls.json(calls.get("/api/tracker/recent?kinds=entity_died")).getAsJsonArray("events").size());
    }

    @Test
    void aZeroSizedRingAnswers409() {
        Calls off = new Calls(TrackerRoutes.routes(() -> tracker, new RecentEvents(() -> 0), () -> Limits.DEFAULTS));
        Response r = off.get("/api/tracker/recent");
        assertEquals(409, r.status());
        assertEquals("recent_off", Calls.json(r).get("error").getAsString());
    }

    @Test
    void theJournalQueryCarriesEveryFilter() {
        tracker.journalEntries.add(new Journal.Entry(EventKind.BLOCK_BROKEN, 5000L, 9L, "default", STEVE, "{\"block\":\"Rock\"}"));
        JsonObject o = Calls.json(calls.get("/api/tracker/journal?kinds=block_broken&player=Steve&world=default"
                + "&from=1000&to=2026-10-16T00:00:00Z&minX=0&minY=0&minZ=0&maxX=10&maxY=10&maxZ=10&limit=1"));
        Journal.Query q = tracker.lastQuery.get();
        assertEquals(Set.of(EventKind.BLOCK_BROKEN), q.kinds());
        assertEquals(STEVE, q.player());
        assertEquals("default", q.world());
        assertEquals(1000L, q.fromMillis());
        assertEquals(10, q.maxX());
        assertEquals(1, q.limit());
        JsonObject entry = o.getAsJsonArray("entries").get(0).getAsJsonObject();
        assertEquals("Rock", entry.getAsJsonObject("event").get("block").getAsString());
        assertEquals(4999L, o.get("next").getAsLong());
    }

    @Test
    void aHalfBoxIsRefused() {
        Response r = calls.get("/api/tracker/journal?minX=0&maxX=4");
        assertEquals(400, r.status());
        assertEquals("bad_box", Calls.json(r).get("error").getAsString());
    }

    @Test
    void routesAnswer503OnceTrackerApiIsGone() {
        Calls gone = new Calls(TrackerRoutes.routes(() -> null, recent, () -> Limits.DEFAULTS));
        Response r = gone.get("/api/tracker");
        assertEquals(503, r.status());
        assertEquals("sibling_gone", Calls.json(r).get("error").getAsString());
    }

    /** A PlayerJoined record with only its header filled, whatever fields the record has. */
    static TrackerEvent joined(long seq) {
        try {
            RecordComponent[] parts = PlayerJoined.class.getRecordComponents();
            Class<?>[] types = new Class<?>[parts.length];
            Object[] args = new Object[parts.length];
            for (int i = 0; i < parts.length; i++) {
                types[i] = parts[i].getType();
                args[i] = parts[i].getType() == Header.class ? new Header(seq * 10, seq, "default", STEVE) : zero(types[i]);
            }
            return PlayerJoined.class.getDeclaredConstructor(types).newInstance(args);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(e);
        }
    }

    private static Object zero(Class<?> t) {
        if (t == boolean.class) {
            return false;
        }
        if (t == int.class) {
            return 0;
        }
        if (t == long.class) {
            return 0L;
        }
        if (t == double.class) {
            return 0.0;
        }
        if (t == float.class) {
            return 0f;
        }
        return null;
    }

    @Test
    void aLinkageErrorDropsTheSubscriptionOnceAndNeverThrows() {
        int[] adds = {0};
        int[] drops = {0};
        java.util.function.Consumer<dev.ryvrook.trackerapi.api.TrackerEvent> l = RecentEvents.guarded(e -> {
            adds[0]++;
            throw new NoClassDefFoundError("dev/ryvrook/trackerapi/api/event/PlayerJoined");
        }, () -> drops[0]++);
        assertDoesNotThrow(() -> l.accept(null));
        assertDoesNotThrow(() -> l.accept(null));
        assertEquals(1, adds[0], "events after the drop are ignored");
        assertEquals(1, drops[0]);
    }
}
