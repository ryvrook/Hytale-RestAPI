package dev.ryvrook.restapi.suite;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import dev.ryvrook.restapi.RestApi;
import dev.ryvrook.restapi.RestModule;
import dev.ryvrook.restapi.auth.Scope;
import dev.ryvrook.restapi.http.Access;
import dev.ryvrook.restapi.http.Response;
import dev.ryvrook.restapi.http.Route;
import dev.ryvrook.restapi.http.RouteDoc;
import dev.ryvrook.restapi.siblings.Presence;
import dev.ryvrook.restapi.siblings.Sibling;

import java.util.List;
import java.util.Map;

/**
 * GET /api/suite, the suite matrix as RestAPI sees it right now: each suite mod, whether its routes are mounted,
 * the routes themselves, the scope a key needs, and what still waits on a change in that mod. Also makes sure the
 * suite settings and sentences are known before RestAPI.json and messages.json are read.
 */
public final class SuiteModule implements RestModule {
    public static final String GROUP = "suite";

    /** What each route group cannot serve until the sibling adds an API for it. */
    static final Map<Sibling, List<String>> WAITING = Map.of(
            Sibling.TRACKER, List.of(),
            Sibling.COINAGE, List.of(),
            Sibling.DOMINION, List.of("Staff actions under dominion.write", "A list of every faction including safe and war zones",
                    "Sieges with both sides and the phase, today they come from the siege banners"),
            Sibling.FEATS, List.of("A points leaderboard call, today it comes from export/feats.json"),
            Sibling.CHRONICLE, List.of("Boards per window and the server series, today the all time boards come from export/stats.json"),
            Sibling.ATLAS, List.of("Render status and the worlds rendered"),
            Sibling.LULLABY, List.of("A read API with the sleepers per world, today the files are read"));

    public SuiteModule() {
        SuiteSettings.touch();
        SuiteText.BAD_LIMIT.length();
    }

    @Override
    public String group() {
        return GROUP;
    }

    @Override
    public List<Route> routes(RestApi api) {
        return List.of(Route.of(GROUP, "GET", "/api/suite", Scope.READ, Access.READ,
                "Each suite mod with its version, whether its routes are mounted, the scope they need, the routes and what waits on that mod.",
                call -> Response.ok(matrix(api)))
                .doc(new RouteDoc(List.of(), null,
                        "{\"mods\":[{\"id\":\"coinage\",\"plugin\":\"Ryvrook:Coinage\",\"installed\":true,\"version\":\"1.2.0\",\"bound\":true,\"scope\":\"coinage\",\"routes\":[\"GET /api/coinage\"],\"reason\":null,\"waiting\":[]}]}",
                        List.of())));
    }

    static JsonObject matrix(RestApi api) {
        JsonArray mods = new JsonArray();
        List<Presence> table = api.siblings() == null ? List.of() : api.siblings().table();
        List<Route> routes = api.router().routes();
        for (Presence p : table) {
            Sibling s = p.sibling();
            JsonObject o = new JsonObject();
            o.addProperty("id", s.id());
            o.addProperty("plugin", s.pluginId());
            o.addProperty("installed", p.installed());
            o.addProperty("version", p.version());
            o.addProperty("minimum", s.minimum());
            o.addProperty("hooksSince", s.hooksSince());
            o.addProperty("hooks", s.reloadsDependents(p.version()));
            o.addProperty("mode", p.mode().key());
            o.addProperty("bound", p.bound());
            o.addProperty("scope", s.scope().id());
            o.addProperty("adds", s.adds());
            JsonArray r = new JsonArray();
            for (Route x : routes) {
                if (x.group().equals(s.group())) {
                    r.add(x.method() + " " + x.pattern());
                }
            }
            o.add("routes", r);
            o.addProperty("reason", p.reason());
            o.add("waiting", Json.strings(WAITING.getOrDefault(s, List.of())));
            mods.add(o);
        }
        JsonObject o = new JsonObject();
        o.add("mods", mods);
        return o;
    }
}
