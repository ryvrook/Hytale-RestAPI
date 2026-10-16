package dev.ryvrook.restapi.server;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import dev.ryvrook.restapi.RestApi;
import dev.ryvrook.restapi.RestModule;
import dev.ryvrook.restapi.auth.Scope;
import dev.ryvrook.restapi.config.CoreSettings;
import dev.ryvrook.restapi.http.Access;
import dev.ryvrook.restapi.http.Call;
import dev.ryvrook.restapi.http.Response;
import dev.ryvrook.restapi.http.Route;
import dev.ryvrook.restapi.siblings.Presence;

import java.util.ArrayList;
import java.util.List;

/** GET /api/health and GET /api/server. */
public final class CoreModule implements RestModule {
    public static final String GROUP = "core";

    @Override
    public String group() {
        return GROUP;
    }

    @Override
    public List<Route> routes(RestApi api) {
        return List.of(
                new Route(GROUP, "GET", "/api/health", Scope.READ, Access.READ, CoreSettings.HTTP_HEALTH_PUBLIC::get,
                        "Whether the server is up and every world thread answers. 503 while booting, stopping or stalled.",
                        call -> health(api, call)),
                Route.of(GROUP, "GET", "/api/server", Scope.READ, Access.READ,
                        "Server name, version, uptime, players, worlds with tick times, JVM figures and the suite mods RestAPI found.",
                        call -> server(api)));
    }

    private static Response health(RestApi api, Call call) {
        long now = System.currentTimeMillis();
        List<String> stalled = new ArrayList<>();
        for (WorldSample w : api.worlds().all()) {
            if (w.stalled(now, stallLimitMs())) {
                stalled.add(w.name());
            }
        }
        String status = Health.status(ServerFacts.phase(), stalled);
        JsonObject o = new JsonObject();
        o.addProperty("status", status);
        o.addProperty("restapi", api.version());
        if (call.key() != null && call.key().has(Scope.READ)) {
            JsonArray s = new JsonArray();
            stalled.forEach(s::add);
            o.add("stalledWorlds", s);
            o.addProperty("worlds", api.worlds().all().size());
            o.addProperty("uptimeSeconds", (now - api.startedAt()) / 1000);
            o.addProperty("siblingsBound", api.siblings() == null ? 0
                    : (int) api.siblings().table().stream().filter(Presence::bound).count());
        }
        return Response.json(Health.httpStatus(status), o);
    }

    /** Three sample intervals and at least 15 seconds. Health, worlds and /metrics all use this rule. */
    public static long stallLimitMs() {
        return Math.max(15_000L, 3_000L * CoreSettings.SAMPLE_SECONDS.get());
    }

    private static Response server(RestApi api) {
        JsonObject o = new JsonObject();
        o.addProperty("restapi", api.version());
        o.add("server", ServerFacts.server());
        o.add("players", ServerFacts.players());
        JsonArray worlds = new JsonArray();
        long now = System.currentTimeMillis();
        for (WorldSample w : api.worlds().all()) {
            worlds.add(Health.world(w, now, stallLimitMs()));
        }
        o.add("worlds", worlds);
        o.add("jvm", ServerFacts.jvm());
        o.add("siblings", Health.siblings(api.siblings() == null ? List.of() : api.siblings().table()));
        JsonObject routes = new JsonObject();
        routes.addProperty("count", api.router().routes().size());
        JsonArray groups = new JsonArray();
        api.router().groups().forEach(groups::add);
        routes.add("groups", groups);
        o.add("api", routes);
        return Response.ok(o);
    }
}
