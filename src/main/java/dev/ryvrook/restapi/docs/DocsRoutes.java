package dev.ryvrook.restapi.docs;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import dev.ryvrook.restapi.auth.Scope;
import dev.ryvrook.restapi.config.CoreSettings;
import dev.ryvrook.restapi.http.Access;
import dev.ryvrook.restapi.http.ApiException;
import dev.ryvrook.restapi.http.Call;
import dev.ryvrook.restapi.http.Response;
import dev.ryvrook.restapi.http.Route;
import dev.ryvrook.restapi.http.RouteDoc;
import dev.ryvrook.restapi.text.Messages;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

/**
 * GET /openapi.json, GET /docs and the page's two files. The document is built from the routes mounted at the
 * moment it is asked for and kept until the routes, the settings it reads, or a sibling's presence change.
 *
 * <p>The page is plain HTML with one script and one style sheet from this jar, all under 60 KB. It loads
 * openapi.json with a relative URL, so it works behind a proxy that serves RestAPI under a sub path. Every answer
 * of the page carries a Content-Security-Policy of default-src 'self', so the page can reach nothing but this
 * server.
 */
public final class DocsRoutes {
    public static final String GROUP = "docs";
    /** The folder in the jar that holds the page. */
    public static final String RESOURCES = "restapi-docs/";
    public static final String CSP = "default-src 'self'; base-uri 'none'; form-action 'none'; frame-ancestors 'none'";
    static final Map<String, String> FILES = Map.of(
            "docs.js", "text/javascript; charset=utf-8",
            "docs.css", "text/css; charset=utf-8");
    private static final String HTML = "text/html; charset=utf-8";
    private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();

    private record Built(List<Route> routes, OpenApi.Meta meta, byte[] json) {
    }

    private final Supplier<List<Route>> routes;
    private final String version;
    private final Map<String, byte[]> files;
    private volatile Built cache;

    /**
     * @param routes the routes mounted now, read on each request
     * @param version the RestAPI version for info.version
     * @param loader where the page files are read from
     */
    public DocsRoutes(Supplier<List<Route>> routes, String version, ClassLoader loader) throws IOException {
        this.routes = routes;
        this.version = version;
        Map<String, byte[]> f = new LinkedHashMap<>();
        f.put("index.html", read(loader, "index.html"));
        for (String name : FILES.keySet()) {
            f.put(name, read(loader, name));
        }
        this.files = Map.copyOf(f);
    }

    private static byte[] read(ClassLoader loader, String name) throws IOException {
        try (InputStream in = loader.getResourceAsStream(RESOURCES + name)) {
            if (in == null) {
                throw new IOException("The jar has no " + RESOURCES + name);
            }
            return in.readAllBytes();
        }
    }

    /** Drops the kept document, so the next request builds it again. Called when a sibling binds or unbinds. */
    public void invalidate() {
        cache = null;
    }

    /** The routes to mount. The page routes are left out while docs.page is off. */
    public List<Route> routes() {
        BooleanSupplier always = () -> true;
        Route spec = new Route(GROUP, "GET", "/openapi.json", Scope.READ, Access.READ, DocsSettings.PUBLIC::get,
                Messages.get(DocsText.ROUTE_SPEC), call -> spec())
                .doc(new RouteDoc(List.of(), null, "{\"openapi\":\"3.1.0\",\"info\":{\"title\":\"RestAPI\",\"version\":\""
                        + version + "\"},\"paths\":{}}", List.of()));
        if (!DocsSettings.PAGE.get()) {
            return List.of(spec);
        }
        Route page = new Route(GROUP, "GET", "/docs", Scope.READ, Access.READ, always,
                Messages.get(DocsText.ROUTE_PAGE), this::page)
                .doc(new RouteDoc(List.of(), null, "<!doctype html>", List.of()));
        Route asset = new Route(GROUP, "GET", "/docs/{file}", Scope.READ, Access.READ, always,
                Messages.get(DocsText.ROUTE_ASSET), this::asset)
                .doc(new RouteDoc(List.of(), null, null, List.of("file_not_found")));
        return List.of(spec, page, asset);
    }

    /** The document as JSON bytes, built again only when something it depends on changed. */
    byte[] specBytes() {
        List<Route> now = routes.get();
        String server = DocsSettings.SERVER_URL.get();
        if (server.isBlank()) {
            server = CoreSettings.HTTP_BASE_PATH.get().isBlank() ? "/" : CoreSettings.HTTP_BASE_PATH.get();
        }
        OpenApi.Meta meta = new OpenApi.Meta(DocsSettings.TITLE.get(), version, server,
                CoreSettings.KEYS_ACCEPT_X_API_KEY.get());
        Built b = cache;
        if (b != null && b.routes.equals(now) && b.meta.equals(meta)) {
            return b.json;
        }
        byte[] json = (GSON.toJson(OpenApi.build(now, meta)) + "\n").getBytes(StandardCharsets.UTF_8);
        cache = new Built(now, meta, json);
        return json;
    }

    private Response spec() {
        return new Response(200, Response.JSON, specBytes(), Map.of());
    }

    Response page(Call call) {
        if (call.request().path().endsWith("/")) {
            // A relative link from /docs/ would point one folder too deep. Send the browser to /docs.
            return new Response(308, null, new byte[0], Map.of("Location", "../docs"));
        }
        String html = new String(files.get("index.html"), StandardCharsets.UTF_8)
                .replace("data-try-it=\"true\"", "data-try-it=\"" + DocsSettings.TRY_IT.get() + "\"");
        return pageHeaders(new Response(200, HTML, html.getBytes(StandardCharsets.UTF_8), Map.of()));
    }

    Response asset(Call call) {
        String name = call.param("file");
        String type = name == null ? null : FILES.get(name);
        if (type == null) {
            throw ApiException.notFound("file_not_found", Messages.get(DocsText.NO_FILE, "file", name));
        }
        return pageHeaders(new Response(200, type, files.get(name), Map.of()));
    }

    private static Response pageHeaders(Response r) {
        return r.with("Content-Security-Policy", CSP)
                .with("Referrer-Policy", "no-referrer")
                .with("X-Frame-Options", "DENY");
    }

    /** Bytes of the page and its two files together, for the size check. */
    int pageBytes() {
        return files.values().stream().mapToInt(b -> b.length).sum();
    }
}
