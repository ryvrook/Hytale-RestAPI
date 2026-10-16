package dev.ryvrook.restapi.suite.feats;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import dev.ryvrook.feats.api.FeatInfo;
import dev.ryvrook.feats.api.FeatProgress;
import dev.ryvrook.feats.api.WorldRecord;
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

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * The Feats route group under /api/feats. Definitions, player progress and world records come from FeatsApi.
 * The points leaderboard and the recent unlocks come from {@code export/feats.json}, which Feats writes for Atlas
 * and other tools, since the API has no call for either yet.
 */
public final class FeatsRoutes implements SiblingUnit {
    static final String GROUP = Sibling.FEATS.group();
    static final Scope SCOPE = Sibling.FEATS.scope();
    static final String MOD = "Feats";

    @Override
    public List<Route> bind(RestApi api) {
        if (!FeatsBridge.present()) {
            throw new IllegalStateException("Feats has not published its API yet");
        }
        JsonFile export = new JsonFile(Folders.of(api, Sibling.FEATS).resolve("export").resolve("feats.json"), MOD);
        return routes(new FeatsBridge(), export, Limits.live());
    }

    static List<Route> routes(FeatsSource feats, JsonFile export, Supplier<Limits> limits) {
        List<Route> out = new ArrayList<>();
        out.add(Route.of(GROUP, "GET", "/api/feats", SCOPE, Access.READ,
                "Every feat with its category, tier, points and scope, and the share of players who own it when Feats exported it.",
                call -> list(feats, export, call)).doc(new RouteDoc(
                List.of(new RouteDoc.Param("category", "Only this category"), new RouteDoc.Param("tier", "Only this tier"),
                        new RouteDoc.Param("hidden", "false leaves out hidden feats")), null,
                "{\"count\":327,\"feats\":[{\"id\":\"first_steps\",\"name\":\"First Steps\",\"category\":\"Exploration\",\"tier\":\"Bronze\",\"points\":5,\"hidden\":false,\"scope\":\"personal\",\"rarity\":0.82}]}",
                List.of())));
        out.add(Route.of(GROUP, "GET", "/api/feats/players/{player}", SCOPE, Access.READ,
                "One player's points, rank, title and unlocked feats. progress=true adds the progress toward every feat still locked.",
                call -> player(feats, export, call)).doc(new RouteDoc(
                List.of(new RouteDoc.Param("progress", "true adds current and target of each locked feat")), null,
                "{\"uuid\":\"...\",\"points\":140,\"rank\":4,\"title\":\"first_steps\",\"unlockedCount\":31,\"unlocked\":[{\"id\":\"first_steps\",\"name\":\"First Steps\"}]}",
                List.of("player_not_found"))));
        out.add(Route.of(GROUP, "GET", "/api/feats/players/{player}/stats/{key}", SCOPE, Access.READ,
                "One of the counters Feats keeps for a player, such as blocks_broken.",
                call -> stat(feats, export, call)));
        out.add(Route.of(GROUP, "GET", "/api/feats/records", SCOPE, Access.READ,
                "Server milestones reached and server firsts claimed, with who and when.",
                call -> records(feats)));
        out.add(Route.of(GROUP, "GET", "/api/feats/top", SCOPE, Access.READ,
                "The points leaderboard from export/feats.json, the top 100 at most.",
                call -> top(export, call, limits.get())).doc(new RouteDoc(
                List.of(new RouteDoc.Param("limit", "Rows")), null, null, List.of("file_missing", "file_broken"))));
        out.add(Route.of(GROUP, "GET", "/api/feats/recent", SCOPE, Access.READ,
                "The newest unlocks from export/feats.json, newest first, 50 at most.",
                call -> recent(export, call, limits.get())).doc(new RouteDoc(
                List.of(new RouteDoc.Param("limit", "Rows")), null, null, List.of("file_missing", "file_broken"))));
        return out;
    }

    static Names names(JsonFile export) {
        return new Names(MOD, Names::online, name -> {
            JsonObject doc = export.readOrNull();
            if (doc == null || !doc.has("players") || !doc.get("players").isJsonArray()) {
                return Optional.empty();
            }
            for (JsonElement e : doc.getAsJsonArray("players")) {
                JsonObject p = e.getAsJsonObject();
                if (p.has("name") && name.equalsIgnoreCase(p.get("name").getAsString())) {
                    return Optional.ofNullable(Json.uuid(p.get("uuid").getAsString()));
                }
            }
            return Optional.empty();
        });
    }

    private static Response list(FeatsSource feats, JsonFile export, Call call) {
        String category = call.query("category", null);
        String tier = call.query("tier", null);
        boolean hidden = !"false".equalsIgnoreCase(call.query("hidden", "true"));
        JsonObject doc = null;
        try {
            doc = export.readOrNull();
        } catch (ApiException e) {
            // Rarity is left out when the export does not read.
        }
        JsonObject rarity = doc != null && doc.has("rarity") && doc.get("rarity").isJsonObject() ? doc.getAsJsonObject("rarity") : null;
        JsonArray a = new JsonArray();
        for (FeatInfo f : feats.feats()) {
            if (category != null && !category.equalsIgnoreCase(f.category())
                    || tier != null && !tier.equalsIgnoreCase(f.tier()) || !hidden && f.hidden()) {
                continue;
            }
            JsonObject o = info(f);
            if (rarity != null && rarity.has(f.id())) {
                o.add("rarity", rarity.get(f.id()));
            }
            a.add(o);
        }
        JsonObject o = new JsonObject();
        o.addProperty("count", a.size());
        o.add("feats", a);
        return Response.ok(o);
    }

    static JsonObject info(FeatInfo f) {
        JsonObject o = new JsonObject();
        o.addProperty("id", f.id());
        o.addProperty("name", f.name());
        o.addProperty("description", f.description());
        o.addProperty("category", f.category());
        o.addProperty("tier", f.tier());
        o.addProperty("points", f.points());
        o.addProperty("hidden", f.hidden());
        o.addProperty("scope", f.scope());
        return o;
    }

    private static Response player(FeatsSource feats, JsonFile export, Call call) {
        UUID uuid = names(export).resolve(call.param("player"));
        Map<String, FeatInfo> byId = new HashMap<>();
        for (FeatInfo f : feats.feats()) {
            byId.put(f.id(), f);
        }
        Set<String> unlocked = feats.unlocked(uuid);
        JsonObject o = new JsonObject();
        o.addProperty("uuid", uuid.toString());
        o.addProperty("points", feats.points(uuid));
        o.addProperty("rank", feats.rank(uuid));
        o.addProperty("title", feats.title(uuid));
        o.addProperty("unlockedCount", unlocked.size());
        o.addProperty("featCount", byId.size());
        JsonArray u = new JsonArray();
        for (String id : unlocked) {
            FeatInfo f = byId.get(id);
            JsonObject x = new JsonObject();
            x.addProperty("id", id);
            x.addProperty("name", f == null ? null : f.name());
            x.addProperty("tier", f == null ? null : f.tier());
            x.addProperty("points", f == null ? 0 : f.points());
            u.add(x);
        }
        o.add("unlocked", u);
        if ("true".equalsIgnoreCase(call.query("progress", "false"))) {
            JsonArray p = new JsonArray();
            for (FeatInfo f : feats.feats()) {
                if (unlocked.contains(f.id())) {
                    continue;
                }
                FeatProgress pr = feats.progress(uuid, f.id());
                if (pr == null || pr.target() <= 0) {
                    continue;
                }
                JsonObject x = new JsonObject();
                x.addProperty("id", f.id());
                x.addProperty("current", pr.current());
                x.addProperty("target", pr.target());
                p.add(x);
            }
            o.add("progress", p);
        }
        return Response.ok(o);
    }

    private static Response stat(FeatsSource feats, JsonFile export, Call call) {
        UUID uuid = names(export).resolve(call.param("player"));
        String key = call.param("key");
        JsonObject o = new JsonObject();
        o.addProperty("uuid", uuid.toString());
        o.addProperty("key", key);
        o.addProperty("value", feats.stats(uuid, key));
        return Response.ok(o);
    }

    private static Response records(FeatsSource feats) {
        JsonObject o = new JsonObject();
        o.add("milestones", records(feats.milestones()));
        o.add("firsts", records(feats.firsts()));
        return Response.ok(o);
    }

    private static JsonArray records(Map<String, WorldRecord> map) {
        JsonArray a = new JsonArray();
        for (WorldRecord r : map.values()) {
            JsonObject o = new JsonObject();
            o.addProperty("feat", r.featId());
            o.addProperty("at", r.at());
            o.add("by", Json.str(r.by()));
            o.addProperty("byName", r.byName());
            o.addProperty("hidden", r.hidden());
            a.add(o);
        }
        return a;
    }

    private static Response top(JsonFile export, Call call, Limits limits) {
        return fromExport(export, "players", "players", limits.limit(call));
    }

    private static Response recent(JsonFile export, Call call, Limits limits) {
        return fromExport(export, "recent", "unlocks", limits.limit(call));
    }

    private static Response fromExport(JsonFile export, String field, String as, int limit) {
        JsonObject doc = export.read();
        JsonArray rows = new JsonArray();
        if (doc.has(field) && doc.get(field).isJsonArray()) {
            for (JsonElement e : doc.getAsJsonArray(field)) {
                if (rows.size() >= limit) {
                    break;
                }
                rows.add(e);
            }
        }
        JsonObject o = new JsonObject();
        o.addProperty("exportedAt", export.modifiedAt());
        if (doc.has("online")) {
            o.add("online", doc.get("online"));
        }
        o.add(as, rows);
        return Response.ok(o);
    }
}
