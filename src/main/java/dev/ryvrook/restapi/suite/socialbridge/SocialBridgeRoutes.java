package dev.ryvrook.restapi.suite.socialbridge;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
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
import dev.ryvrook.restapi.suite.Names;
import dev.ryvrook.restapi.suite.SuiteText;
import dev.ryvrook.socialbridge.api.Announcement;
import dev.ryvrook.socialbridge.api.SocialBridgeApi;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * The SocialBridge route group under /api/socialbridge, bound through {@code SocialBridgeApi.onEnable} and
 * {@code onDisable} (SocialBridge 0.2.0 and newer). A bot or a panel can look up which Discord user is linked to a
 * player and the other way round, and post a line or an embed to one of SocialBridge's channels under
 * {@code socialbridge.write}. SocialBridge 0.2.0 does not reload the plugins that list it, so after a reload of
 * SocialBridge RestAPI reloads itself, see {@link dev.ryvrook.restapi.siblings.Siblings}.
 */
public final class SocialBridgeRoutes implements SiblingUnit {
    static final String GROUP = Sibling.SOCIALBRIDGE.group();
    static final Scope SCOPE = Sibling.SOCIALBRIDGE.scope();
    static final Scope WRITE = Scope.SOCIALBRIDGE_WRITE;
    static final String MOD = "SocialBridge";
    /** Longest text, title and footer RestAPI hands on. Discord cuts embeds at 4096 and titles at 256. */
    static final int MAX_TEXT = 2000;
    static final int MAX_TITLE = 256;

    private volatile SocialBridgeApi current;
    private Consumer<SocialBridgeApi> enableHook;
    private Runnable disableHook;

    @Override
    public List<Route> bind(RestApi api) {
        Consumer<SocialBridgeApi> enable = s -> current = s;
        Runnable disable = () -> current = null;
        SocialBridgeApi.onEnable(enable);
        SocialBridgeApi.onDisable(disable);
        enableHook = enable;
        disableHook = disable;
        if (current == null) {
            unbind();
            throw new IllegalStateException("SocialBridge has not handed out its API yet");
        }
        return routes(() -> current);
    }

    @Override
    public void unbind() {
        if (enableHook != null) {
            SocialBridgeApi.removeHooks(enableHook, disableHook);
            enableHook = null;
            disableHook = null;
        }
        current = null;
    }

    static List<Route> routes(Supplier<SocialBridgeApi> source) {
        Supplier<SocialBridgeApi> api = () -> {
            SocialBridgeApi s = source.get();
            if (s == null) {
                throw new ApiException(503, "sibling_gone", SuiteText.get(SuiteText.SIBLING_GONE, "mod", MOD));
            }
            return s;
        };
        Names names = new Names(MOD, Names::online);
        List<Route> out = new ArrayList<>();
        out.add(Route.of(GROUP, "GET", "/api/socialbridge", SCOPE, Access.READ,
                "SocialBridge's version and API version.",
                call -> summary(api.get())).doc(new RouteDoc(List.of(), null,
                "{\"version\":\"0.2.0\",\"apiVersion\":1}", List.of("sibling_gone"))));
        out.add(Route.of(GROUP, "GET", "/api/socialbridge/links/{player}", SCOPE, Access.READ,
                "The Discord user linked to a player, by UUID or by the name of a player who is online.",
                call -> link(api.get(), names, call)).doc(new RouteDoc(List.of(), null,
                "{\"player\":\"9b2f4c1e-0000-4000-8000-000000000001\",\"service\":\"discord\",\"user\":\"123456789012345678\"}",
                List.of("player_not_found", "not_linked"))));
        out.add(Route.of(GROUP, "GET", "/api/socialbridge/users/{user}", SCOPE, Access.READ,
                "The player linked to a Discord user id.",
                call -> user(api.get(), call)).doc(new RouteDoc(List.of(), null,
                "{\"service\":\"discord\",\"user\":\"123456789012345678\",\"player\":\"9b2f4c1e-0000-4000-8000-000000000001\"}",
                List.of("not_linked"))));
        out.add(Route.of(GROUP, "POST", "/api/socialbridge/post", WRITE, Access.WRITE,
                "Posts a line, or an embed when a title or footer is given, to a SocialBridge channel such as events or status.",
                call -> post(api.get(), call)).doc(new RouteDoc(List.of(),
                "{\"channel\":\"events\",\"kind\":\"restock\",\"text\":\"The market restocked\",\"title\":\"Market\",\"colour\":\"#3BA55C\"}",
                "{\"posted\":true,\"channel\":\"events\"}", List.of("invalid_json", "bad_post", "not_posted"))));
        return out;
    }

    private static Response summary(SocialBridgeApi s) {
        JsonObject o = new JsonObject();
        o.addProperty("version", s.version());
        o.addProperty("apiVersion", apiVersion());
        return Response.ok(o);
    }

    private static Response link(SocialBridgeApi s, Names names, Call call) {
        UUID player = names.resolve(call.param("player"));
        String user = s.linkedUser(SocialBridgeApi.DISCORD, player);
        if (user == null) {
            throw ApiException.notFound("not_linked", SuiteText.get(SuiteText.NOT_LINKED, "who", player.toString()));
        }
        JsonObject o = new JsonObject();
        o.addProperty("player", player.toString());
        o.addProperty("service", SocialBridgeApi.DISCORD);
        o.addProperty("user", user);
        return Response.ok(o);
    }

    private static Response user(SocialBridgeApi s, Call call) {
        String user = call.param("user");
        UUID player = s.linkedPlayer(SocialBridgeApi.DISCORD, user);
        if (player == null) {
            throw ApiException.notFound("not_linked", SuiteText.get(SuiteText.NOT_LINKED, "who", user));
        }
        JsonObject o = new JsonObject();
        o.addProperty("service", SocialBridgeApi.DISCORD);
        o.addProperty("user", user);
        o.addProperty("player", player.toString());
        return Response.ok(o);
    }

    static Response post(SocialBridgeApi s, Call call) {
        JsonObject body = call.jsonBody();
        String channel = text(body, "channel");
        channel = channel == null || channel.isBlank() ? "events" : channel.trim().toLowerCase(Locale.ROOT);
        String kind = text(body, "kind");
        kind = kind == null || kind.isBlank() ? "restapi" : kind.trim();
        String text = text(body, "text");
        if (text == null || text.isBlank()) {
            throw ApiException.badRequest("bad_post", SuiteText.get(SuiteText.BAD_POST));
        }
        String title = cut(text(body, "title"), MAX_TITLE);
        String footer = cut(text(body, "footer"), MAX_TITLE);
        int colour = colour(text(body, "colour"));
        Announcement a = new Announcement(kind, title, cut(text, MAX_TEXT), colour, footer, null, null);
        if (!s.post(channel, a)) {
            throw new ApiException(409, "not_posted", SuiteText.get(SuiteText.NOT_POSTED, "channel", channel));
        }
        JsonObject o = new JsonObject();
        o.addProperty("posted", true);
        o.addProperty("channel", channel);
        return Response.ok(o);
    }

    /** #RRGGBB or RRGGBB, else -1 for none. */
    static int colour(String text) {
        if (text == null || text.isBlank()) {
            return -1;
        }
        String hex = text.trim().startsWith("#") ? text.trim().substring(1) : text.trim();
        if (!hex.matches("[0-9A-Fa-f]{6}")) {
            throw ApiException.badRequest("bad_post", SuiteText.get(SuiteText.BAD_COLOUR, "colour", text));
        }
        return Integer.parseInt(hex, 16);
    }

    private static String cut(String s, int max) {
        if (s == null || s.isBlank()) {
            return null;
        }
        return s.length() > max ? s.substring(0, max) : s;
    }

    private static String text(JsonObject body, String field) {
        JsonElement e = body.get(field);
        if (e == null || e.isJsonNull()) {
            return null;
        }
        if (!e.isJsonPrimitive() || !e.getAsJsonPrimitive().isString()) {
            throw ApiException.badRequest("bad_post", SuiteText.get(SuiteText.NOT_TEXT, "field", field));
        }
        return e.getAsString();
    }

    /** The API version of the SocialBridge that runs, read from the live class. */
    static int apiVersion() {
        try {
            return SocialBridgeApi.class.getField("API_VERSION").getInt(null);
        } catch (ReflectiveOperationException | RuntimeException e) {
            return 1;
        }
    }
}
