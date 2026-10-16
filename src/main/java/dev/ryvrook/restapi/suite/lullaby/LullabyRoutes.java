package dev.ryvrook.restapi.suite.lullaby;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import dev.ryvrook.lullaby.api.LullabyApi;
import dev.ryvrook.lullaby.api.SleepStatus;
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
import dev.ryvrook.restapi.suite.JsonFile;
import dev.ryvrook.restapi.suite.SuiteText;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * The Lullaby route group under /api/lullaby, bound through {@code LullabyApi.onEnable} and {@code onDisable}
 * (Lullaby 1.1.0 and newer). Who is in bed, who is ready, how many are needed and the nights skipped come from
 * {@code LullabyApi.statuses()}, the mode from {@code mode()}. Only the count of players who opted out is read from
 * Lullaby's ignored.json, the API has no call for it. Lullaby 1.0.1 and older have no API and cannot be bound.
 */
public final class LullabyRoutes implements SiblingUnit {
    static final String GROUP = Sibling.LULLABY.group();
    static final Scope SCOPE = Sibling.LULLABY.scope();
    static final String MOD = "Lullaby";

    private volatile LullabyApi current;
    private Consumer<LullabyApi> enableHook;
    private Runnable disableHook;

    @Override
    public List<Route> bind(RestApi api) {
        Consumer<LullabyApi> enable = l -> current = l;
        Runnable disable = () -> current = null;
        try {
            LullabyApi.onEnable(enable);
            LullabyApi.onDisable(disable);
        } catch (NoClassDefFoundError e) {
            throw new IllegalStateException("Lullaby 1.0.1 and older have no API, RestAPI reads Lullaby 1.1.0 and newer");
        }
        enableHook = enable;
        disableHook = disable;
        if (current == null) {
            unbind();
            throw new IllegalStateException("Lullaby has not handed out its API yet");
        }
        return routes(() -> current, new JsonFile(Folders.of(api, Sibling.LULLABY).resolve("ignored.json"), MOD));
    }

    @Override
    public void unbind() {
        if (enableHook != null) {
            LullabyApi.removeHooks(enableHook, disableHook);
            enableHook = null;
            disableHook = null;
        }
        current = null;
    }

    static List<Route> routes(Supplier<LullabyApi> source, JsonFile ignored) {
        Supplier<LullabyApi> api = () -> {
            LullabyApi l = source.get();
            if (l == null) {
                throw new ApiException(503, "sibling_gone", SuiteText.get(SuiteText.SIBLING_GONE, "mod", MOD));
            }
            return l;
        };
        List<Route> out = new ArrayList<>();
        out.add(Route.of(GROUP, "GET", "/api/lullaby", SCOPE, Access.READ,
                "Lullaby's mode, the nights skipped, how many players opted out, and per world who is in bed, who is ready and how many are needed.",
                call -> summary(api.get(), ignored)).doc(new RouteDoc(List.of(), null,
                "{\"version\":\"1.1.0\",\"mode\":\"native\",\"nightsSkipped\":14,\"ignoredPlayers\":1,\"sleepersKnown\":true,\"worlds\":[{\"world\":\"default\",\"enabled\":true,\"requirement\":\"percent\",\"online\":4,\"eligible\":4,\"inBed\":1,\"ready\":1,\"needed\":2,\"missing\":1,\"progress\":0.5,\"cooldownSeconds\":0,\"nightsSkipped\":14}]}",
                List.of("sibling_gone"))));
        out.add(Route.of(GROUP, "GET", "/api/lullaby/worlds/{world}", SCOPE, Access.READ,
                "One world's sleep rule, players in bed and ready, sleepers needed, progress and nights skipped.",
                call -> world(api.get(), call)).doc(new RouteDoc(List.of(), null, null, List.of("unknown_world"))));
        return out;
    }

    private static Response summary(LullabyApi l, JsonFile ignored) {
        JsonObject o = new JsonObject();
        o.addProperty("version", l.version());
        o.addProperty("apiVersion", l.apiVersion());
        o.addProperty("mode", l.mode());
        o.addProperty("nightsSkipped", l.nightsSkipped());
        JsonObject ign = null;
        try {
            ign = ignored.readOrNull();
        } catch (ApiException e) {
            // A broken ignored.json counts as nobody opted out.
        }
        o.addProperty("ignoredPlayers", ign != null && ign.has("Ignored") && ign.get("Ignored").isJsonArray()
                ? ign.getAsJsonArray("Ignored").size() : 0);
        o.addProperty("sleepersKnown", true);
        JsonArray worlds = new JsonArray();
        for (SleepStatus s : l.statuses()) {
            worlds.add(status(s));
        }
        o.add("worlds", worlds);
        return Response.ok(o);
    }

    private static Response world(LullabyApi l, Call call) {
        String w = call.param("world");
        SleepStatus s = l.worlds().contains(w) ? l.status(w) : null;
        if (s == null) {
            throw ApiException.notFound("unknown_world", SuiteText.get(SuiteText.UNKNOWN_WORLD, "world", w));
        }
        return Response.ok(status(s));
    }

    static JsonObject status(SleepStatus s) {
        JsonObject o = new JsonObject();
        o.addProperty("world", s.world());
        o.addProperty("enabled", s.enabled());
        o.addProperty("requirement", s.requirement());
        o.addProperty("online", s.online());
        o.addProperty("eligible", s.eligible());
        o.addProperty("inBed", s.inBed());
        o.addProperty("ready", s.ready());
        o.addProperty("needed", s.needed());
        o.addProperty("missing", s.missing());
        o.addProperty("progress", Double.isFinite(s.progress()) ? s.progress() : 0.0);
        o.addProperty("cooldownSeconds", s.cooldownSeconds());
        o.addProperty("nightsSkipped", s.nightsSkipped());
        return o;
    }
}
