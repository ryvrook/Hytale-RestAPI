package dev.ryvrook.restapi.http;

import dev.ryvrook.restapi.auth.Scope;

import java.util.function.BooleanSupplier;

/**
 * One method and path pattern. Patterns are literal segments and {@code {name}} segments that match one segment,
 * such as {@code /api/players/{player}}. Paths here leave out http.basePath.
 *
 * @param group   the route group that owns it, such as core, players or dominion, so a group can leave as a unit
 * @param scope   the scope a key needs
 * @param access  read, write or dangerous
 * @param open    when this answers true the route also answers without a key, with the key when one is sent
 * @param summary one sentence for the OpenAPI document and the README
 * @param doc     query parameters, examples and error codes for the OpenAPI document
 */
public record Route(String group, String method, String pattern, Scope scope, Access access, BooleanSupplier open,
                    String summary, Handler handler, RouteDoc doc) {
    private static final BooleanSupplier CLOSED = () -> false;

    public Route {
        if (scope == null) {
            throw new IllegalArgumentException("Route " + method + " " + pattern + " needs a scope");
        }
        if (pattern == null || !pattern.startsWith("/")) {
            throw new IllegalArgumentException("Route pattern must start with /, got " + pattern);
        }
        open = open == null ? CLOSED : open;
        doc = doc == null ? RouteDoc.NONE : doc;
    }

    public Route(String group, String method, String pattern, Scope scope, Access access, BooleanSupplier open,
                 String summary, Handler handler) {
        this(group, method, pattern, scope, access, open, summary, handler, RouteDoc.NONE);
    }

    /** The same route with its OpenAPI details. */
    public Route doc(RouteDoc details) {
        return new Route(group, method, pattern, scope, access, open, summary, handler, details);
    }

    /** A route that always needs a key. */
    public static Route of(String group, String method, String pattern, Scope scope, Access access, String summary,
                           Handler handler) {
        return new Route(group, method, pattern, scope, access, CLOSED, summary, handler);
    }

    /** The code that answers a route. Runs on an HTTP virtual thread, never on a world thread. */
    @FunctionalInterface
    public interface Handler {
        Response handle(Call call) throws Exception;
    }
}
