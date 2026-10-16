package dev.ryvrook.restapi.suite.dominion;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.hypixel.hytale.math.util.ChunkUtil;
import dev.ryvrook.dominion.api.DominionApi;
import dev.ryvrook.dominion.api.PointOfInterest;
import dev.ryvrook.dominion.api.Result;
import dev.ryvrook.dominion.api.SiegeInfo;
import dev.ryvrook.dominion.api.Territory;
import dev.ryvrook.dominion.api.TerritoryProvider;
import dev.ryvrook.dominion.model.Faction;
import dev.ryvrook.dominion.model.FactionType;
import dev.ryvrook.dominion.model.Location;
import dev.ryvrook.dominion.model.Member;
import dev.ryvrook.dominion.model.Rank;
import dev.ryvrook.dominion.model.Relation;
import dev.ryvrook.restapi.audit.AuditEntry;
import dev.ryvrook.restapi.auth.Scope;
import dev.ryvrook.restapi.http.Access;
import dev.ryvrook.restapi.http.Response;
import dev.ryvrook.restapi.http.Route;
import dev.ryvrook.restapi.suite.Calls;
import dev.ryvrook.restapi.suite.Limits;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;

class DominionRoutesTest {
    static final UUID STEVE = UUID.fromString("00000000-0000-0000-0000-00000000000a");
    static final UUID ALEX = UUID.fromString("00000000-0000-0000-0000-00000000000b");

    Faction north;
    Faction south;
    Faction safe;
    AtomicBoolean homes;
    Calls calls;
    Map<String, java.util.function.Function<Object[], Object>> answers;
    List<String> staff;
    List<AuditEntry> audit;
    SiegeInfo siege;

    @BeforeEach
    void setUp() {
        north = new Faction(UUID.fromString("00000000-0000-0000-0000-0000000000f1"), "NRT", "North Watch", STEVE, 10L);
        north.putMember(new Member(STEVE, "Steve", Rank.LEADER, 11L));
        north.setBank(1200.0);
        north.setHome(new Location("default", 1, 64, 2, 0, 0));
        south = new Faction(UUID.fromString("00000000-0000-0000-0000-0000000000f2"), "SOU", "South Reach", ALEX, 20L);
        south.putMember(new Member(ALEX, "Alex", Rank.LEADER, 21L));
        north.setRelation(south.id(), Relation.ENEMY);
        south.setRelation(north.id(), Relation.ENEMY);
        long[] chunks = {ChunkUtil.indexChunk(3, -4), ChunkUtil.indexChunk(3, -5)};
        TerritoryProvider tp = Calls.stand(TerritoryProvider.class, Map.of(
                "territories", a -> "default".equals(a[0]) ? List.of(
                        new Territory(north.id(), "NRT", "North Watch", "#3366ff", "default", chunks, false, false, "normal"),
                        new Territory(south.id(), "SOU", "South Reach", "#ff3333", "default", new long[]{ChunkUtil.indexChunk(9, 9)}, true, true, "normal"))
                        : List.of(),
                "points", a -> "default".equals(a[0]) ? List.of(
                        new PointOfInterest("home:" + north.id(), "home", "NRT home", "default", 1, 64, 2, north.id(), "#3366ff", 0L),
                        new PointOfInterest("siege:1", "siege", "SOU attacks NRT", "default", 40, 70, 40, south.id(), "#ff3333", 99L))
                        : List.of()));
        safe = new Faction(UUID.fromString("00000000-0000-0000-0000-0000000000f3"), "SAFE", "Spawn", null, 1L);
        safe.setType(FactionType.SAFEZONE);
        siege = new SiegeInfo(UUID.fromString("00000000-0000-0000-0000-0000000000a1"), south.id(), "SOU", north.id(), "NRT",
                "default", ChunkUtil.indexChunk(3, -4), 3, -4, 100.5, 70, -120.5, "CAPTURING", 99L, 50L, ALEX, true, 4, 1, 0, 2);
        staff = new ArrayList<>();
        audit = new ArrayList<>();
        answers = new HashMap<>(Map.ofEntries(
                Map.entry("factions", a -> List.of(north, safe, south)),
                Map.entry("sieges", a -> List.of(siege)),
                Map.entry("siegeHistory", a -> {
                    staff.add("history " + a[0] + " " + a[1]);
                    return List.of(siege);
                }),
                Map.entry("disband", a -> {
                    staff.add("disband " + a[0] + " " + a[1]);
                    return Result.pass("North Watch was disbanded.");
                }),
                Map.entry("setLeader", a -> {
                    staff.add("leader " + a[0] + " " + a[1] + " " + a[2]);
                    return Result.fail("Alex is not a member of North Watch.");
                }),
                Map.entry("setPower", a -> {
                    staff.add("power " + a[0] + " " + a[1] + " " + a[2]);
                    return Result.pass("Power set.");
                }),
                Map.entry("unclaim", a -> {
                    staff.add("unclaim " + a[0] + " " + a[1] + " " + a[2] + " " + a[3]);
                    return Result.pass("Unclaimed.");
                }),
                Map.entry("unclaimAll", a -> {
                    staff.add("unclaimAll " + a[0] + " " + a[1]);
                    return Result.pass("Released 2 claims.");
                })));
        answers.putAll(Map.ofEntries(
                Map.entry("top", a -> List.of(north, south)),
                Map.entry("factionByTag", a -> List.of(north, south).stream().filter(f -> f.tag().equalsIgnoreCase((String) a[0])).findFirst()),
                Map.entry("factionOf", a -> STEVE.equals(a[0]) ? Optional.of(north) : ALEX.equals(a[0]) ? Optional.of(south) : Optional.empty()),
                Map.entry("factionAt", a -> (int) a[1] >= 0 ? Optional.of(north) : Optional.empty()),
                Map.entry("factionPower", a -> north.id().equals(a[0]) ? 48.456 : 10.0),
                Map.entry("powerOf", a -> 8.0),
                Map.entry("claimCount", a -> north.id().equals(a[0]) ? 2 : 1),
                Map.entry("raidable", a -> south.id().equals(a[0])),
                Map.entry("territoryProvider", a -> tp)));
        homes = new AtomicBoolean(true);
        calls = make();
    }

    Calls make() {
        DominionApi api = Calls.stand(DominionApi.class, answers);
        return new Calls(DominionRoutes.routes(() -> api, () -> List.of("default", "nether"), homes::get,
                () -> Limits.DEFAULTS, true, audit::add));
    }

    /** A Dominion before 0.7.0, whose API has none of the calls 0.7.0 added. */
    Calls older() {
        for (String m : List.of("factions", "sieges", "siegeHistory", "disband", "setLeader", "setPower", "unclaim", "unclaimAll")) {
            answers.put(m, a -> {
                throw new NoSuchMethodError("DominionApi." + m);
            });
        }
        return make();
    }

    @Test
    void everyRouteIsAReadUnderTheDominionScope() {
        for (Route r : DominionRoutes.routes(() -> null, List::of, () -> true, () -> Limits.DEFAULTS)) {
            assertTrue(r.pattern().equals("/api/dominion") || r.pattern().startsWith("/api/dominion/"));
            assertEquals(Scope.DOMINION, r.scope());
            assertEquals(Access.READ, r.access());
        }
    }

    @Test
    void staffRoutesNeedTheWriteScope() {
        List<Route> all = DominionRoutes.routes(() -> null, List::of, () -> true, () -> Limits.DEFAULTS, true, e -> { });
        List<Route> writes = all.stream().filter(r -> r.method().equals("POST")).toList();
        assertEquals(5, writes.size());
        for (Route r : writes) {
            assertEquals(Scope.DOMINION_WRITE, r.scope());
            assertEquals(Access.DANGEROUS, r.access());
            assertTrue(r.pattern().startsWith("/api/dominion/"), r.pattern());
        }
    }

    @Test
    void summaryCountsFactionsMembersAndClaims() {
        JsonObject o = Calls.json(calls.get("/api/dominion"));
        assertEquals(2, o.get("factions").getAsInt());
        assertEquals(1, o.get("zones").getAsInt());
        assertEquals(2, o.get("members").getAsInt());
        assertEquals(3, o.get("claims").getAsInt());
        assertEquals("default", o.getAsJsonArray("worldsWithClaims").get(0).getAsString());
    }

    @Test
    void theListHoldsTheZonesAfterTheRanking() {
        JsonArray rows = Calls.json(calls.get("/api/dominion/factions")).getAsJsonArray("factions");
        assertEquals(3, rows.size());
        JsonObject zone = rows.get(2).getAsJsonObject();
        assertEquals("SAFE", zone.get("tag").getAsString());
        assertEquals("safezone", zone.get("type").getAsString());
        assertTrue(zone.get("rank").isJsonNull());
        assertEquals(2, Calls.json(calls.get("/api/dominion/factions?zones=false")).get("total").getAsInt());
        assertEquals(2, Calls.json(older().get("/api/dominion/factions")).get("total").getAsInt(), "an older Dominion has no zones");
    }

    @Test
    void factionsPageInRankingOrder() {
        JsonObject o = Calls.json(calls.get("/api/dominion/factions?limit=1&offset=1&zones=false"));
        assertEquals(2, o.get("total").getAsInt());
        JsonObject row = o.getAsJsonArray("factions").get(0).getAsJsonObject();
        assertEquals("SOU", row.get("tag").getAsString());
        assertEquals(2, row.get("rank").getAsInt());
        assertTrue(row.get("raidable").getAsBoolean());
    }

    @Test
    void factionDetailHasMembersRelationsAndHome() {
        JsonObject o = Calls.json(calls.get("/api/dominion/factions/nrt"));
        assertEquals(48.46, o.get("power").getAsDouble());
        assertEquals(1200.0, o.get("bank").getAsDouble());
        JsonObject steve = o.getAsJsonArray("memberList").get(0).getAsJsonObject();
        assertEquals("Steve", steve.get("name").getAsString());
        assertEquals("leader", steve.get("rank").getAsString());
        JsonObject rel = o.getAsJsonArray("relations").get(0).getAsJsonObject();
        assertEquals("SOU", rel.get("tag").getAsString());
        assertEquals("enemy", rel.get("relation").getAsString());
        assertEquals(64.0, o.getAsJsonObject("home").get("y").getAsDouble());
        homes.set(false);
        assertFalse(Calls.json(calls.get("/api/dominion/factions/NRT")).has("home"));
        Response missing = calls.get("/api/dominion/factions/XYZ");
        assertEquals(404, missing.status());
        assertEquals("faction_not_found", Calls.json(missing).get("error").getAsString());
    }

    @Test
    void claimsGiveChunkCoordinates() {
        JsonArray t = Calls.json(calls.get("/api/dominion/claims?faction=NRT")).getAsJsonArray("territories");
        assertEquals(1, t.size());
        JsonObject n = t.get(0).getAsJsonObject();
        assertEquals(2, n.get("count").getAsInt());
        assertEquals(3, n.getAsJsonArray("chunks").get(0).getAsJsonArray().get(0).getAsInt());
        assertEquals(-4, n.getAsJsonArray("chunks").get(0).getAsJsonArray().get(1).getAsInt());
        assertEquals(2, Calls.json(calls.get("/api/dominion/claims")).getAsJsonArray("territories").size());
        assertEquals(0, Calls.json(calls.get("/api/dominion/claims?world=nether")).getAsJsonArray("territories").size());
        assertEquals(404, calls.get("/api/dominion/claims?world=moon").status());
    }

    @Test
    void siegesHaveBothSidesAndThePhase() {
        JsonObject o = Calls.json(calls.get("/api/dominion/sieges"));
        assertTrue(o.get("sidesKnown").getAsBoolean());
        JsonObject s = o.getAsJsonArray("sieges").get(0).getAsJsonObject();
        assertEquals("SOU", s.getAsJsonObject("attacker").get("tag").getAsString());
        assertEquals("NRT", s.getAsJsonObject("defender").get("tag").getAsString());
        assertEquals("capturing", s.get("phase").getAsString());
        assertEquals(99L, s.get("phaseEndsAt").getAsLong());
        assertEquals(-4, s.getAsJsonArray("chunk").get(1).getAsInt());
        assertEquals(0, Calls.json(calls.get("/api/dominion/sieges?world=nether")).getAsJsonArray("sieges").size());
        JsonObject h = Calls.json(calls.get("/api/dominion/sieges/history?faction=NRT&limit=5"));
        assertEquals(1, h.getAsJsonArray("sieges").size());
        assertEquals("history " + north.id() + " 5", staff.getFirst());
        assertEquals(404, calls.get("/api/dominion/sieges/history?faction=XYZ").status());
    }

    @Test
    void anOlderDominionGivesTheSiegeBanners() {
        Calls calls = older();
        JsonObject o = Calls.json(calls.get("/api/dominion/sieges"));
        assertFalse(o.get("sidesKnown").getAsBoolean());
        JsonArray s = o.getAsJsonArray("sieges");
        assertEquals(1, s.size());
        assertEquals("SOU", s.get(0).getAsJsonObject().get("faction").getAsString());
        assertEquals(99L, s.get(0).getAsJsonObject().get("endsAt").getAsLong());
        assertEquals(501, calls.get("/api/dominion/sieges/history").status());
        assertEquals(501, calls.post("/api/dominion/factions/NRT/disband", "").status());
    }

    @Test
    void staffActionsNameTheKeyAndLeaveAnAuditLine() {
        var r = calls.post("/api/dominion/factions/nrt/disband", "");
        assertEquals(200, r.status(), r.bodyText());
        assertEquals("disband " + north.id() + " RestAPI:grafana", staff.getFirst());
        assertTrue(Calls.json(r).get("ok").getAsBoolean());
        assertEquals("dominion disband", audit.getFirst().action());
        assertEquals("grafana", audit.getFirst().key());

        var refused = calls.post("/api/dominion/factions/NRT/leader", "{\"player\":\"Alex\"}");
        assertEquals(409, refused.status());
        assertEquals("refused", Calls.json(refused).get("error").getAsString());
        assertEquals("Alex is not a member of North Watch.", Calls.json(refused).get("message").getAsString());
        assertEquals("leader " + north.id() + " " + ALEX + " RestAPI:grafana", staff.get(1));
        assertEquals(409, audit.get(1).status());
        assertEquals(400, calls.post("/api/dominion/factions/NRT/leader", "{}").status());

        assertEquals(200, calls.post("/api/dominion/players/Steve/power", "{\"power\":12.5}").status());
        assertEquals("power " + STEVE + " 12.5 RestAPI:grafana", staff.get(2));
        assertEquals(400, calls.post("/api/dominion/players/Steve/power", "{\"power\":\"lots\"}").status());

        assertEquals(200, calls.post("/api/dominion/unclaim", "{\"world\":\"default\",\"chunkX\":3,\"chunkZ\":-4}").status());
        assertEquals("unclaim default 3 -4 RestAPI:grafana", staff.get(3));
        assertEquals(400, calls.post("/api/dominion/unclaim", "{\"world\":\"default\",\"chunkX\":3.5,\"chunkZ\":1}").status());

        assertEquals(200, calls.post("/api/dominion/factions/SOU/unclaim-all", "").status());
        assertEquals("unclaimAll " + south.id() + " RestAPI:grafana", staff.get(4));
        assertEquals(404, calls.post("/api/dominion/factions/XYZ/disband", "").status());
    }

    @Test
    void homesLeaveThePointsWhenTurnedOff() {
        assertEquals(2, Calls.json(calls.get("/api/dominion/points")).getAsJsonArray("points").size());
        homes.set(false);
        assertEquals(1, Calls.json(calls.get("/api/dominion/points")).getAsJsonArray("points").size());
        assertEquals(0, Calls.json(calls.get("/api/dominion/points?kind=home")).getAsJsonArray("points").size());
    }

    @Test
    void relationsAreListedOncePerPair() {
        JsonArray r = Calls.json(calls.get("/api/dominion/relations")).getAsJsonArray("relations");
        assertEquals(1, r.size());
        assertTrue(r.get(0).getAsJsonObject().get("mutual").getAsBoolean());
    }

    @Test
    void aPlayerIsFoundByRosterName() {
        JsonObject o = Calls.json(calls.get("/api/dominion/players/alex"));
        assertEquals(ALEX.toString(), o.get("uuid").getAsString());
        assertEquals("SOU", o.getAsJsonObject("faction").get("tag").getAsString());
        assertEquals("leader", o.get("rank").getAsString());
        JsonObject loner = Calls.json(calls.get("/api/dominion/players/" + UUID.randomUUID()));
        assertTrue(loner.get("faction").isJsonNull());
        assertEquals(404, calls.get("/api/dominion/players/Nobody").status());
    }

    @Test
    void theOwnerOfABlock() {
        assertEquals("NRT", Calls.json(calls.get("/api/dominion/at?world=default&x=5.5&z=1"))
                .getAsJsonObject("faction").get("tag").getAsString());
        assertTrue(Calls.json(calls.get("/api/dominion/at?world=default&x=-5&z=1")).get("faction").isJsonNull());
        assertEquals(400, calls.get("/api/dominion/at?world=default&x=1").status());
        assertEquals(400, calls.get("/api/dominion/at?world=default&x=one&z=1").status());
    }
}
