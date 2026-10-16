package dev.ryvrook.restapi.suite.dominion;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.hypixel.hytale.math.util.ChunkUtil;
import dev.ryvrook.dominion.api.DominionApi;
import dev.ryvrook.dominion.api.PointOfInterest;
import dev.ryvrook.dominion.api.Territory;
import dev.ryvrook.dominion.api.TerritoryProvider;
import dev.ryvrook.dominion.model.Faction;
import dev.ryvrook.dominion.model.Location;
import dev.ryvrook.dominion.model.Member;
import dev.ryvrook.dominion.model.Relation;
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
import dev.ryvrook.restapi.suite.Limits;
import dev.ryvrook.restapi.suite.Names;
import dev.ryvrook.restapi.suite.SuiteSettings;
import dev.ryvrook.restapi.suite.SuiteText;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

/**
 * The Dominion route group under /api/dominion, reads only. Dominion has no enable hook, so the unit binds from
 * {@code DominionApi.get()} and reads it again on every request, which answers 503 once Dominion stopped. Faction
 * objects and the repository behind the API are thread safe in Dominion. Staff actions wait for a Dominion
 * API that defines them.
 */
public final class DominionRoutes implements SiblingUnit {
    static final String GROUP = Sibling.DOMINION.group();
    static final Scope SCOPE = Sibling.DOMINION.scope();
    static final String MOD = "Dominion";

    @Override
    public List<Route> bind(RestApi api) {
        if (DominionApi.get() == null) {
            throw new IllegalStateException("Dominion has not published its API yet");
        }
        return routes(DominionApi::get, Folders::worlds, SuiteSettings.DOMINION_HOMES::get, Limits.live());
    }

    static List<Route> routes(Supplier<DominionApi> source, Supplier<List<String>> worlds, BooleanSupplier homes,
                              Supplier<Limits> limits) {
        Supplier<DominionApi> api = () -> {
            DominionApi d = source.get();
            if (d == null) {
                throw new ApiException(503, "sibling_gone", SuiteText.get(SuiteText.SIBLING_GONE, "mod", MOD));
            }
            return d;
        };
        List<Route> out = new ArrayList<>();
        out.add(Route.of(GROUP, "GET", "/api/dominion", SCOPE, Access.READ,
                "How many factions, members and claims Dominion holds, and in which worlds.",
                call -> summary(api.get(), worlds.get())));
        out.add(Route.of(GROUP, "GET", "/api/dominion/factions", SCOPE, Access.READ,
                "Factions in Dominion's ranking order with power, claims, members and bank.",
                call -> factions(api.get(), call, limits.get())).doc(new RouteDoc(
                List.of(new RouteDoc.Param("limit", "Rows"), new RouteDoc.Param("offset", "Rows to skip")), null,
                "{\"total\":3,\"limit\":50,\"offset\":0,\"factions\":[{\"rank\":1,\"tag\":\"NRT\",\"name\":\"North Watch\",\"power\":48.5,\"claims\":12,\"members\":5,\"bank\":1200.0}]}",
                List.of())));
        out.add(Route.of(GROUP, "GET", "/api/dominion/factions/{tag}", SCOPE, Access.READ,
                "One faction by tag with its members and ranks, relations, upgrades, statistics and home.",
                call -> faction(api.get(), call, homes.getAsBoolean())).doc(new RouteDoc(List.of(), null, null,
                List.of("faction_not_found"))));
        out.add(Route.of(GROUP, "GET", "/api/dominion/claims", SCOPE, Access.READ,
                "Claimed chunks per faction and world, with the raidable and breached flags.",
                call -> claims(api.get(), call, worlds.get())).doc(new RouteDoc(
                List.of(new RouteDoc.Param("world", "One world, every loaded world when missing"),
                        new RouteDoc.Param("faction", "One faction tag")), null,
                "{\"territories\":[{\"faction\":\"NRT\",\"world\":\"default\",\"type\":\"normal\",\"raidable\":false,\"breached\":false,\"count\":2,\"chunks\":[[3,-4],[3,-5]]}]}",
                List.of("unknown_world"))));
        out.add(Route.of(GROUP, "GET", "/api/dominion/sieges", SCOPE, Access.READ,
                "Sieges running now, with the attacker, the banner position and when the phase ends.",
                call -> points(api.get(), call, worlds.get(), "siege", true)));
        out.add(Route.of(GROUP, "GET", "/api/dominion/points", SCOPE, Access.READ,
                "Points Dominion puts on maps, homes, warps, siege banners, hills, outposts and envoys.",
                call -> points(api.get(), call, worlds.get(), call.query("kind", null), homes.getAsBoolean())).doc(new RouteDoc(
                List.of(new RouteDoc.Param("world", "One world"), new RouteDoc.Param("kind", "home, warp, siege, hill, outpost or envoy")),
                null, null, List.of("unknown_world"))));
        out.add(Route.of(GROUP, "GET", "/api/dominion/relations", SCOPE, Access.READ,
                "Every relation between two factions that is not neutral, once per pair.",
                call -> relations(api.get())));
        out.add(Route.of(GROUP, "GET", "/api/dominion/players/{player}", SCOPE, Access.READ,
                "One player's faction, rank, title, power, kills and deaths.",
                call -> player(api.get(), call)).doc(new RouteDoc(List.of(), null,
                "{\"uuid\":\"...\",\"name\":\"Steve\",\"power\":8.0,\"faction\":{\"tag\":\"NRT\",\"name\":\"North Watch\"},\"rank\":\"officer\"}",
                List.of("player_not_found"))));
        out.add(Route.of(GROUP, "GET", "/api/dominion/at", SCOPE, Access.READ,
                "The faction that owns the chunk at a block position, or null for wilderness.",
                call -> at(api.get(), call)).doc(new RouteDoc(
                List.of(new RouteDoc.Param("world", "World name"), new RouteDoc.Param("x", "Block x"),
                        new RouteDoc.Param("z", "Block z")), null, null, List.of("missing_coordinates"))));
        return out;
    }

    /** Every normal faction in ranking order. */
    static List<Faction> all(DominionApi d) {
        return d.top(Integer.MAX_VALUE);
    }

    private static Response summary(DominionApi d, List<String> worlds) {
        List<Faction> all = all(d);
        int members = 0;
        int claims = 0;
        for (Faction f : all) {
            members += f.memberCount();
            claims += d.claimCount(f.id());
        }
        JsonObject o = new JsonObject();
        o.addProperty("factions", all.size());
        o.addProperty("members", members);
        o.addProperty("claims", claims);
        TerritoryProvider tp = d.territoryProvider();
        JsonArray w = new JsonArray();
        for (String world : worlds) {
            if (tp != null && !tp.territories(world).isEmpty()) {
                w.add(world);
            }
        }
        o.add("worldsWithClaims", w);
        return Response.ok(o);
    }

    private static Response factions(DominionApi d, Call call, Limits limits) {
        List<Faction> all = all(d);
        int limit = limits.limit(call);
        int offset = limits.offset(call);
        JsonArray rows = new JsonArray();
        for (int i = offset; i < all.size() && i < offset + limit; i++) {
            JsonObject o = summary(d, all.get(i));
            o.addProperty("rank", i + 1);
            rows.add(o);
        }
        return Response.ok(Json.page("factions", rows, all.size(), limit, offset));
    }

    static JsonObject summary(DominionApi d, Faction f) {
        JsonObject o = new JsonObject();
        o.addProperty("id", f.id().toString());
        o.addProperty("tag", f.tag());
        o.addProperty("name", f.name());
        o.addProperty("label", f.label());
        o.addProperty("type", lower(f.type()));
        o.addProperty("color", f.color());
        o.addProperty("description", f.description());
        o.add("leader", Json.str(f.leader()));
        o.addProperty("members", f.memberCount());
        o.addProperty("power", round(d.factionPower(f.id())));
        o.addProperty("claims", d.claimCount(f.id()));
        o.addProperty("raidable", d.raidable(f.id()));
        o.addProperty("bank", f.bank());
        o.addProperty("points", f.points());
        o.add("nation", Json.str(f.nationId()));
        o.addProperty("createdAt", f.createdAt());
        return o;
    }

    private static Faction byTag(DominionApi d, String tag) {
        return d.factionByTag(tag).orElseThrow(() -> ApiException.notFound("faction_not_found",
                SuiteText.get(SuiteText.FACTION_NOT_FOUND, "tag", tag)));
    }

    private static Response faction(DominionApi d, Call call, boolean homes) {
        Faction f = byTag(d, call.param("tag"));
        JsonObject o = summary(d, f);
        o.addProperty("motd", f.motd());
        JsonArray members = new JsonArray();
        for (Member m : Faction.sortedByRank(f.members())) {
            members.add(member(d, f, m));
        }
        o.add("memberList", members);
        Map<UUID, Faction> byId = index(d);
        JsonArray rel = new JsonArray();
        for (Map.Entry<UUID, Relation> e : f.relations().entrySet()) {
            if (e.getValue() == Relation.NEUTRAL || e.getValue() == Relation.OWN) {
                continue;
            }
            Faction other = byId.get(e.getKey());
            JsonObject r = new JsonObject();
            r.addProperty("id", e.getKey().toString());
            r.addProperty("tag", other == null ? null : other.tag());
            r.addProperty("relation", lower(e.getValue()));
            rel.add(r);
        }
        o.add("relations", rel);
        o.add("upgrades", Json.numbers(new TreeMap<>(f.upgrades())));
        o.add("stats", Json.numbers(new TreeMap<>(f.stats())));
        o.add("rankNames", Json.of(new TreeMap<>(f.rankNames())));
        if (homes) {
            o.add("home", location(f.home()));
            o.add("warps", Json.strings(new TreeMap<>(f.warps()).keySet()));
        }
        return Response.ok(o);
    }

    private static JsonObject member(DominionApi d, Faction f, Member m) {
        JsonObject o = new JsonObject();
        o.addProperty("uuid", m.uuid().toString());
        o.addProperty("name", m.name());
        o.addProperty("rank", lower(m.rank()));
        o.addProperty("rankName", m.rank() == null ? null : f.rankName(m.rank()));
        o.addProperty("title", m.title());
        o.addProperty("joinedAt", m.joinedAt());
        o.addProperty("lastSeen", m.lastSeen());
        o.addProperty("kills", m.kills());
        o.addProperty("deaths", m.deaths());
        o.addProperty("power", round(d.powerOf(m.uuid())));
        return o;
    }

    private static Map<UUID, Faction> index(DominionApi d) {
        Map<UUID, Faction> out = new HashMap<>();
        for (Faction f : all(d)) {
            out.put(f.id(), f);
        }
        return out;
    }

    private static List<String> worldsOf(Call call, List<String> loaded) {
        String world = call.query("world", null);
        if (world == null || world.isBlank()) {
            return loaded;
        }
        if (!loaded.isEmpty() && !loaded.contains(world)) {
            throw ApiException.notFound("unknown_world", SuiteText.get(SuiteText.UNKNOWN_WORLD, "world", world));
        }
        return List.of(world);
    }

    private static Response claims(DominionApi d, Call call, List<String> loaded) {
        String tag = call.query("faction", null);
        UUID only = tag == null || tag.isBlank() ? null : byTag(d, tag).id();
        TerritoryProvider tp = d.territoryProvider();
        JsonArray a = new JsonArray();
        if (tp != null) {
            for (String world : worldsOf(call, loaded)) {
                for (Territory t : tp.territories(world)) {
                    if (only != null && !only.equals(t.ownerId())) {
                        continue;
                    }
                    JsonObject o = new JsonObject();
                    o.add("factionId", Json.str(t.ownerId()));
                    o.addProperty("faction", t.ownerTag());
                    o.addProperty("name", t.ownerName());
                    o.addProperty("color", t.color());
                    o.addProperty("world", t.world());
                    o.addProperty("type", t.type());
                    o.addProperty("raidable", t.raidable());
                    o.addProperty("breached", t.breached());
                    long[] chunks = t.chunkIndexes() == null ? new long[0] : t.chunkIndexes();
                    o.addProperty("count", chunks.length);
                    JsonArray c = new JsonArray();
                    for (long index : chunks) {
                        JsonArray xz = new JsonArray();
                        xz.add(ChunkUtil.xOfChunkIndex(index));
                        xz.add(ChunkUtil.zOfChunkIndex(index));
                        c.add(xz);
                    }
                    o.add("chunks", c);
                    a.add(o);
                }
            }
        }
        JsonObject o = new JsonObject();
        o.add("territories", a);
        return Response.ok(o);
    }

    private static Response points(DominionApi d, Call call, List<String> loaded, String kind, boolean homes) {
        TerritoryProvider tp = d.territoryProvider();
        Map<UUID, Faction> byId = index(d);
        JsonArray a = new JsonArray();
        if (tp != null) {
            for (String world : worldsOf(call, loaded)) {
                Collection<PointOfInterest> pts = tp.points(world);
                for (PointOfInterest p : pts) {
                    if (kind != null && !kind.isBlank() && !kind.equalsIgnoreCase(p.kind())) {
                        continue;
                    }
                    if (!homes && ("home".equals(p.kind()) || "warp".equals(p.kind()))) {
                        continue;
                    }
                    JsonObject o = new JsonObject();
                    o.addProperty("id", p.id());
                    o.addProperty("kind", p.kind());
                    o.addProperty("label", p.label());
                    o.addProperty("world", p.world());
                    o.addProperty("x", p.x());
                    o.addProperty("y", p.y());
                    o.addProperty("z", p.z());
                    o.add("factionId", Json.str(p.ownerId()));
                    Faction owner = p.ownerId() == null ? null : byId.get(p.ownerId());
                    o.addProperty("faction", owner == null ? null : owner.tag());
                    o.addProperty("color", p.color());
                    o.addProperty("endsAt", p.expiresAt());
                    a.add(o);
                }
            }
        }
        JsonObject o = new JsonObject();
        o.add("siege".equals(kind) ? "sieges" : "points", a);
        return Response.ok(o);
    }

    private static Response relations(DominionApi d) {
        List<Faction> all = all(d);
        Map<UUID, Faction> byId = new LinkedHashMap<>();
        all.forEach(f -> byId.put(f.id(), f));
        JsonArray a = new JsonArray();
        java.util.Set<String> seen = new java.util.HashSet<>();
        for (Faction f : all) {
            for (Map.Entry<UUID, Relation> e : f.relations().entrySet()) {
                Relation r = e.getValue();
                if (r == null || r == Relation.NEUTRAL || r == Relation.OWN) {
                    continue;
                }
                Faction other = byId.get(e.getKey());
                String a1 = f.id().toString();
                String b1 = e.getKey().toString();
                String pair = (a1.compareTo(b1) < 0 ? a1 + b1 : b1 + a1) + r;
                if (!seen.add(pair)) {
                    continue;
                }
                JsonObject o = new JsonObject();
                o.addProperty("a", f.tag());
                o.addProperty("b", other == null ? null : other.tag());
                o.addProperty("relation", lower(r));
                Relation back = other == null ? null : other.relations().get(f.id());
                o.addProperty("mutual", back == r);
                a.add(o);
            }
        }
        JsonObject o = new JsonObject();
        o.add("relations", a);
        return Response.ok(o);
    }

    static Names names(DominionApi d) {
        return new Names(MOD, Names::online, name -> {
            for (Faction f : all(d)) {
                for (Member m : f.members()) {
                    if (m.name() != null && m.name().equalsIgnoreCase(name)) {
                        return Optional.of(m.uuid());
                    }
                }
            }
            return Optional.empty();
        });
    }

    private static Response player(DominionApi d, Call call) {
        UUID uuid = names(d).resolve(call.param("player"));
        Optional<Faction> f = d.factionOf(uuid);
        JsonObject o = new JsonObject();
        o.addProperty("uuid", uuid.toString());
        o.addProperty("power", round(d.powerOf(uuid)));
        if (f.isPresent()) {
            Faction faction = f.get();
            Member m = faction.member(uuid);
            o.addProperty("name", m == null ? null : m.name());
            JsonObject fj = new JsonObject();
            fj.addProperty("id", faction.id().toString());
            fj.addProperty("tag", faction.tag());
            fj.addProperty("name", faction.name());
            o.add("faction", fj);
            if (m != null) {
                o.addProperty("rank", lower(m.rank()));
                o.addProperty("rankName", m.rank() == null ? null : faction.rankName(m.rank()));
                o.addProperty("title", m.title());
                o.addProperty("joinedAt", m.joinedAt());
                o.addProperty("kills", m.kills());
                o.addProperty("deaths", m.deaths());
            }
        } else {
            o.add("name", null);
            o.add("faction", null);
        }
        return Response.ok(o);
    }

    private static Response at(DominionApi d, Call call) {
        String world = call.query("world", null);
        String x = call.query("x", null);
        String z = call.query("z", null);
        if (world == null || world.isBlank() || x == null || z == null) {
            throw ApiException.badRequest("missing_coordinates", SuiteText.get(SuiteText.NEED_COORDS));
        }
        int bx = (int) Math.floor(Double.parseDouble(number(x, "x")));
        int bz = (int) Math.floor(Double.parseDouble(number(z, "z")));
        Optional<Faction> f = d.factionAt(world, bx, bz);
        JsonObject o = new JsonObject();
        o.addProperty("world", world);
        o.addProperty("x", bx);
        o.addProperty("z", bz);
        o.add("faction", f.<com.google.gson.JsonElement>map(faction -> summary(d, faction)).orElse(null));
        return Response.ok(o);
    }

    private static String number(String raw, String name) {
        try {
            Double.parseDouble(raw.trim());
            return raw.trim();
        } catch (NumberFormatException e) {
            throw ApiException.badRequest("bad_number", SuiteText.get(SuiteText.BAD_NUMBER, "name", name, "value", raw));
        }
    }

    private static JsonObject location(Location l) {
        if (l == null) {
            return null;
        }
        JsonObject o = new JsonObject();
        o.addProperty("world", l.world());
        o.addProperty("x", l.x());
        o.addProperty("y", l.y());
        o.addProperty("z", l.z());
        return o;
    }

    static double round(double v) {
        return Math.round(v * 100.0) / 100.0;
    }

    static String lower(Enum<?> e) {
        return e == null ? null : e.name().toLowerCase(Locale.ROOT);
    }
}
