package dev.ryvrook.restapi.server;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import dev.ryvrook.restapi.siblings.Presence;

import java.util.List;

/** The pure parts of /api/health and /api/server, kept apart from the engine so tests cover them. */
public final class Health {
    private Health() {
    }

    /** ok, booting, stopping or stalled. */
    public static String status(String phase, List<String> stalledWorlds) {
        if (!"running".equals(phase)) {
            return phase;
        }
        return stalledWorlds.isEmpty() ? "ok" : "stalled";
    }

    /** 200 for ok, 503 for everything else, so a load balancer or uptime check can act on the code alone. */
    public static int httpStatus(String status) {
        return "ok".equals(status) ? 200 : 503;
    }

    public static JsonObject world(WorldSample w, long now, long stallLimitMs) {
        JsonObject o = new JsonObject();
        o.addProperty("name", w.name());
        o.addProperty("players", w.players());
        o.addProperty("targetTps", w.targetTps());
        o.addProperty("tps", round(w.tps()));
        o.addProperty("tickAvgMs", round(w.tickAvgMs()));
        o.addProperty("tickMaxMs", round(w.tickMaxMs()));
        o.addProperty("chunks", w.chunks());
        o.addProperty("entities", w.entities());
        o.addProperty("sampledAgoMs", w.sampledAt() == 0 ? -1 : now - w.sampledAt());
        o.addProperty("stalled", w.stalled(now, stallLimitMs));
        return o;
    }

    public static JsonArray siblings(List<Presence> table) {
        JsonArray a = new JsonArray();
        for (Presence p : table) {
            JsonObject o = new JsonObject();
            o.addProperty("id", p.sibling().id());
            o.addProperty("plugin", p.sibling().pluginId());
            o.addProperty("mode", p.mode().key());
            o.addProperty("installed", p.installed());
            o.addProperty("version", p.version());
            o.addProperty("minimum", p.sibling().minimum());
            o.addProperty("bound", p.bound());
            o.addProperty("routes", p.routes());
            o.addProperty("path", "/api/" + p.sibling().id());
            o.addProperty("scope", p.sibling().scope().id());
            o.addProperty("reason", p.reason());
            a.add(o);
        }
        return a;
    }

    static double round(double v) {
        return Math.round(v * 100) / 100.0;
    }
}
