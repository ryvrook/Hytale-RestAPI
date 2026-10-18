package dev.ryvrook.restapi.dashboard;

import com.google.gson.JsonObject;
import dev.ryvrook.restapi.audit.AuditEntry;
import dev.ryvrook.restapi.audit.AuditSink;
import dev.ryvrook.restapi.auth.ApiKey;
import dev.ryvrook.restapi.auth.Scope;
import dev.ryvrook.restapi.config.CoreSettings;
import dev.ryvrook.restapi.docs.DocsSettings;
import dev.ryvrook.restapi.http.Access;
import dev.ryvrook.restapi.http.ApiException;
import dev.ryvrook.restapi.http.Call;
import dev.ryvrook.restapi.http.Gate;
import dev.ryvrook.restapi.http.Response;
import dev.ryvrook.restapi.http.Route;
import dev.ryvrook.restapi.http.RouteDoc;
import dev.ryvrook.restapi.text.Messages;
import dev.ryvrook.restapi.util.AddressRanges;

import java.io.IOException;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

/**
 * The routes of the dashboard group, mounted only while dashboard.enabled is on. The page and its files are open
 * like the docs page and carry a Content-Security-Policy that lets them reach this server only, plus the Atlas
 * address for the mini map tiles. Capabilities, login and logout are open too and look at the session cookie
 * themselves. The game icons and the Chronicle Stats pass through need a session or a key, like the API.
 *
 * <p>The page asks for every route relative to its own address, so the same files work under http.basePath and
 * behind a proxy that serves RestAPI under a sub path.
 */
public final class DashboardRoutes {
    public static final String GROUP = "dashboard";
    static final String HTML = "text/html; charset=utf-8";
    static final String INDEX_CACHE = "public, max-age=86400";
    static final String ICON_CACHE = "public, max-age=604800";

    private final PageFiles page;
    private final SessionStore sessions;
    private final Supplier<AssetZip> assets;
    private final ChroniclePass chronicle;
    private final Gate gate;
    private final AuditSink audit;
    private final Clock clock;
    private final Supplier<String> imageOrigins;
    private final String version;

    /**
     * @param page         the page files from the jar
     * @param sessions     the session store, kept across reloads
     * @param assets       the open Assets.zip, or null while there is none, read on each request
     * @param chronicle    the Chronicle Stats pass through
     * @param gate         for the failed login limit and the https check through a trusted proxy
     * @param audit        where logins go
     * @param clock        for the audit times
     * @param imageOrigins origins besides this server that the page may load images from, separated by spaces,
     *                     such as the Atlas address, or empty
     * @param version      the RestAPI version for the capabilities
     */
    public DashboardRoutes(PageFiles page, SessionStore sessions, Supplier<AssetZip> assets, ChroniclePass chronicle,
                           Gate gate, AuditSink audit, Clock clock, Supplier<String> imageOrigins, String version) {
        this.page = page;
        this.sessions = sessions;
        this.assets = assets;
        this.chronicle = chronicle;
        this.gate = gate;
        this.audit = audit;
        this.clock = clock;
        this.imageOrigins = imageOrigins;
        this.version = version;
    }

    public List<Route> routes() {
        BooleanSupplier open = () -> true;
        return List.of(
                new Route(GROUP, "GET", "/dashboard", Scope.READ, Access.READ, open,
                        Messages.get(DashboardText.ROUTE_PAGE), this::page)
                        .doc(new RouteDoc(List.of(), null, "<!doctype html>", List.of()).produces("text/html")),
                new Route(GROUP, "GET", "/dashboard/{file...}", Scope.READ, Access.READ, open,
                        Messages.get(DashboardText.ROUTE_FILE), this::file)
                        .doc(new RouteDoc(List.of(), null, null, List.of("file_not_found"))
                                .path(new RouteDoc.Param("file", "A path under the page, such as js/app.js."))),
                new Route(GROUP, "GET", "/dashboard/capabilities", Scope.READ, Access.READ, open,
                        Messages.get(DashboardText.ROUTE_CAPABILITIES), this::capabilities)
                        .doc(new RouteDoc(List.of(), null, "{\"auth\":\"session\",\"session\":true,\"mode\":\"live\",\"writes\":false,"
                                + "\"assets\":true,\"chronicle\":false,\"who\":\"Steve\"}", List.of())),
                new Route(GROUP, "GET", "/dashboard/login", Scope.READ, Access.READ, open,
                        Messages.get(DashboardText.ROUTE_LOGIN), this::login)
                        .doc(new RouteDoc(List.of(new RouteDoc.Param("t", "The token of a login link.")), null, null, List.of())),
                new Route(GROUP, "POST", "/dashboard/logout", Scope.READ, Access.WRITE, open,
                        Messages.get(DashboardText.ROUTE_LOGOUT), this::logout)
                        .doc(new RouteDoc(List.of(), null, "{\"ok\":true,\"auth\":\"session\"}", List.of())),
                Route.of(GROUP, "GET", "/dashboard/assets/index.json", Scope.READ, Access.READ,
                        Messages.get(DashboardText.ROUTE_ASSET_INDEX), this::assetIndex)
                        .doc(new RouteDoc(List.of(), null, "{\"items\":[\"Ore_Iron\"],\"markers\":[\"Home\"],\"models\":[\"Boar\"]}",
                                List.of("no_assets"))),
                Route.of(GROUP, "GET", "/dashboard/assets/{kind}/{name}", Scope.READ, Access.READ,
                        Messages.get(DashboardText.ROUTE_ASSET), this::asset)
                        .doc(new RouteDoc(List.of(), null, null, List.of("no_assets", "not_found")).produces("image/png")
                                .path(new RouteDoc.Param("kind", "items, markers or models."),
                                        new RouteDoc.Param("name", "The icon name and .png, such as Ore_Iron.png."))),
                Route.of(GROUP, "GET", "/dashboard/chronicle/api/avatar/{player}", Scope.READ, Access.READ,
                        Messages.get(DashboardText.ROUTE_AVATAR), c -> chronicle.avatar(c.params().get("player"), c.request()))
                        .doc(new RouteDoc(List.of(), null, null, List.of("no_chronicle", "chronicle_unreachable", "not_found"))),
                Route.of(GROUP, "GET", "/dashboard/chronicle/api/asset/{path...}", Scope.READ, Access.READ,
                        Messages.get(DashboardText.ROUTE_CHRONICLE_ASSET), c -> chronicle.asset(c.params().get("path"), c.request()))
                        .doc(new RouteDoc(List.of(), null, null, List.of("no_chronicle", "chronicle_unreachable", "not_found"))
                                .path(new RouteDoc.Param("path", "A character file path, such as Characters/Player.blockymodel."))));
    }

    // ---- the page ---------------------------------------------------------------------------------------

    Response page(Call call) {
        if (!call.request().path().endsWith("/")) {
            // Relative links in the page resolve against the folder, so the address must end with a slash.
            return new Response(308, null, new byte[0], Map.of("Location", "dashboard/"));
        }
        return html(200, page.get("index.html"));
    }

    Response file(Call call) {
        String name = call.params().get("file");
        byte[] bytes = name != null && PageFiles.safe(name) ? page.get(name) : null;
        if (bytes == null) {
            throw ApiException.notFound("file_not_found", Messages.get(DashboardText.NO_FILE, "file", name));
        }
        String type = PageFiles.typeOf(name);
        return type.startsWith("text/html") ? html(200, bytes) : new Response(200, type, bytes, Map.of());
    }

    private Response html(int status, byte[] body) {
        return new Response(status, HTML, body, Map.of())
                .with("Content-Security-Policy", csp(imageOrigins.get()))
                .with("Referrer-Policy", "same-origin")
                .with("X-Frame-Options", "DENY");
    }

    /**
     * The policy of the page. Scripts, styles, fonts and requests from this server only, images from this server
     * and the given origins. The page sets chart geometry through element.style, which a style-src without
     * unsafe-inline allows, and has no inline script, style element or style attribute.
     */
    static String csp(String imageOrigins) {
        String extra = imageOrigins == null || imageOrigins.isBlank() ? "" : " " + imageOrigins.trim();
        return "default-src 'none'; script-src 'self'; style-src 'self'; img-src 'self'" + extra
                + "; font-src 'self'; connect-src 'self'; manifest-src 'self'; base-uri 'none'; form-action 'none'; frame-ancestors 'none'";
    }

    // ---- capabilities, login and logout ------------------------------------------------------------------

    /** The session of a call, when its credentials were a dashboard session and not a key. */
    private Session sessionOf(Call call) {
        ApiKey k = call.key();
        if (k == null || !k.id().startsWith("s") || !k.name().startsWith("dashboard:")) {
            return null;
        }
        return sessions.resolve(SessionStore.cookieOf(call.request()));
    }

    Response capabilities(Call call) {
        Session s = sessionOf(call);
        JsonObject o = new JsonObject();
        o.addProperty("auth", "session");
        if (s == null) {
            return Response.ok(o);
        }
        AssetZip z = assets.get();
        String bind = CoreSettings.HTTP_BIND.get();
        int port = CoreSettings.HTTP_PORT.get();
        o.addProperty("session", true);
        o.addProperty("mode", "live");
        o.addProperty("writes", DashboardSettings.WRITES.get());
        o.addProperty("assets", z != null);
        o.addProperty("chronicle", chronicle.on());
        o.addProperty("upstream", apiRoot());
        o.addProperty("listen", hostPart(bind) + ":" + port);
        o.addProperty("port", port);
        o.addProperty("remote", !AddressRanges.isLoopback(bind));
        String pub = DocsSettings.SERVER_URL.get();
        if (pub.isBlank()) {
            o.add("publicUrl", null);
        } else {
            o.addProperty("publicUrl", trimSlash(pub) + "/dashboard/");
        }
        o.addProperty("who", s.who());
        o.addProperty("sessionEnds", Instant.ofEpochMilli(sessions.endsAt(s)).toString());
        o.addProperty("restapi", version);
        o.addProperty("dashboard", page.version());
        return Response.ok(o);
    }

    Response login(Call call) {
        String token = call.request().queryParams().get("t");
        String cookie = SessionStore.cookieOf(call.request());
        if (token == null || token.isEmpty()) {
            if (cookie != null && sessions.resolve(cookie) != null) {
                return new Response(303, null, new byte[0], Map.of("Location", "./"));
            }
            return html(200, page.get("login.html"));
        }
        SessionStore.Opened opened = sessions.redeem(token);
        String now = clock.instant().toString();
        if (opened == null) {
            long wait = gate.authFailure(call.client());
            if (CoreSettings.AUDIT_AUTH_FAILURES.get()) {
                audit.record(new AuditEntry(now, "http", "unknown", call.client(), "dashboard login",
                        wait > 0 ? 429 : 401, "login link used, expired or unknown"));
            }
            Response r = new Response(303, null, new byte[0], Map.of("Location", "login?link=" + (wait > 0 ? "wait" : "used")));
            return wait > 0 ? r.with("Retry-After", Long.toString(wait)) : r;
        }
        audit.record(new AuditEntry(now, "http", opened.session().principal(), call.client(), "dashboard login", 303,
                "session opened"));
        return new Response(303, null, new byte[0], Map.of(
                "Location", "./",
                "Set-Cookie", SessionStore.cookie(opened.cookie(), cookiePath(), gate.forwardedHttps(call.request())),
                "Referrer-Policy", "no-referrer"));
    }

    Response logout(Call call) {
        sessions.end(SessionStore.cookieOf(call.request()));
        JsonObject o = new JsonObject();
        o.addProperty("ok", true);
        o.addProperty("auth", "session");
        return Response.ok(o).with("Set-Cookie", SessionStore.clearCookie(cookiePath(), gate.forwardedHttps(call.request())));
    }

    // ---- game icons ---------------------------------------------------------------------------------------

    Response assetIndex(Call call) {
        AssetZip z = assets.get();
        if (z == null) {
            throw ApiException.notFound("no_assets", Messages.get(DashboardText.NO_ASSETS));
        }
        String tag = z.indexTag();
        if (tag.equals(call.request().header("if-none-match"))) {
            return new Response(304, null, new byte[0], Map.of("ETag", tag, "Cache-Control", INDEX_CACHE));
        }
        String accept = call.request().header("accept-encoding");
        boolean gz = accept != null && accept.toLowerCase(java.util.Locale.ROOT).contains("gzip");
        Response r = new Response(200, Response.JSON, gz ? z.indexGzip() : z.indexJson(),
                Map.of("ETag", tag, "Cache-Control", INDEX_CACHE, "Vary", "Accept-Encoding"));
        return gz ? r.with("Content-Encoding", "gzip") : r;
    }

    Response asset(Call call) {
        String kind = call.params().get("kind");
        String file = call.params().get("name");
        String name = file != null && file.endsWith(".png") ? file.substring(0, file.length() - 4) : null;
        if (!AssetZip.valid(kind, name)) {
            throw ApiException.notFound("not_found", Messages.get(DashboardText.ASSET_SHAPE));
        }
        AssetZip z = assets.get();
        if (z == null) {
            throw ApiException.notFound("no_assets", Messages.get(DashboardText.NO_ASSETS));
        }
        String tag = z.tag(kind, name);
        if (tag == null) {
            throw ApiException.notFound("not_found", Messages.get(DashboardText.NO_ICON, "kind", kind, "name", name));
        }
        if (tag.equals(call.request().header("if-none-match"))) {
            return new Response(304, null, new byte[0], Map.of("ETag", tag, "Cache-Control", ICON_CACHE));
        }
        byte[] png;
        try {
            png = z.read(kind, name);
        } catch (IOException e) {
            throw new ApiException(500, "asset_unreadable", Messages.get(DashboardText.ASSET_BROKEN));
        }
        if (png == null) {
            throw ApiException.notFound("not_found", Messages.get(DashboardText.NO_ICON, "kind", kind, "name", name));
        }
        return new Response(200, "image/png", png, Map.of("ETag", tag, "Cache-Control", ICON_CACHE));
    }

    // ---- addresses ----------------------------------------------------------------------------------------

    /** The path of the session cookie, the base path or / without one. */
    static String cookiePath() {
        String b = trimSlash(CoreSettings.HTTP_BASE_PATH.get().trim());
        if (b.isEmpty()) {
            return "/";
        }
        return b.startsWith("/") ? b : "/" + b;
    }

    /**
     * The API root as a browser reaches it. docs.serverUrl when it is set, since that is the address a proxy
     * serves RestAPI at, else http.bind and http.port with http.basePath, a wildcard bind written as 127.0.0.1.
     */
    public static String apiRoot() {
        String pub = DocsSettings.SERVER_URL.get();
        if (!pub.isBlank()) {
            return trimSlash(pub.trim());
        }
        String base = cookiePath();
        return "http://" + hostPart(CoreSettings.HTTP_BIND.get()) + ":" + CoreSettings.HTTP_PORT.get()
                + (base.equals("/") ? "" : base);
    }

    /** The page address, the API root and /dashboard/. */
    public static String pageUrl() {
        return apiRoot() + "/dashboard/";
    }

    static String hostPart(String bind) {
        if (bind == null || bind.isBlank() || bind.equals("0.0.0.0") || bind.equals("::") || bind.equals("*")) {
            return "127.0.0.1";
        }
        return bind.contains(":") && !bind.startsWith("[") ? "[" + bind + "]" : bind;
    }

    static String trimSlash(String s) {
        String t = s;
        while (t.endsWith("/")) {
            t = t.substring(0, t.length() - 1);
        }
        return t;
    }
}
