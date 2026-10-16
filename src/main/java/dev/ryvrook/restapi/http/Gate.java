package dev.ryvrook.restapi.http;

import dev.ryvrook.restapi.audit.AuditEntry;
import dev.ryvrook.restapi.audit.AuditSink;
import dev.ryvrook.restapi.auth.ApiKey;
import dev.ryvrook.restapi.auth.KeyStore;
import dev.ryvrook.restapi.config.CoreSettings;
import dev.ryvrook.restapi.util.AddressRanges;

import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BiConsumer;

/**
 * The request pipeline in front of every handler, free of the JDK server so tests drive it whole. In order:
 * base path, CORS preflight, client address, key, failed login limit, per key limit, route, scope, handler, audit.
 *
 * <p>A request without a valid key learns nothing about which paths exist. It gets 401 for every path except
 * the open ones, and an address that keeps sending wrong keys gets 429 after limits.authFailuresPerMinute.
 */
public final class Gate {
    private final Router router;
    private final KeyStore keys;
    private final AuditSink audit;
    private final Clock clock;
    private final RateLimiter keyLimiter;
    private final RateLimiter failLimiter;
    /** Told about handler failures, with the request line. The plugin logs them. */
    private final BiConsumer<String, Throwable> onFailure;
    private volatile AddressRanges proxies = AddressRanges.NONE;
    private volatile List<String> proxiesSource = List.of();
    private volatile Observer observer;

    /** Told about every answer and every request turned away for its key, for the request counts in /metrics. */
    public interface Observer {
        /** pattern is the route pattern that matched, or null when none did. */
        void answered(String method, String pattern, int status);

        void authFailed();
    }

    public Gate(Router router, KeyStore keys, AuditSink audit, Clock clock, RateLimiter keyLimiter,
                RateLimiter failLimiter, BiConsumer<String, Throwable> onFailure) {
        this.router = router;
        this.keys = keys;
        this.audit = audit;
        this.clock = clock;
        this.keyLimiter = keyLimiter;
        this.failLimiter = failLimiter;
        this.onFailure = onFailure;
    }

    public Router router() {
        return router;
    }

    /** Sets the observer, or clears it with null. */
    public void observe(Observer o) {
        observer = o;
    }

    public Response handle(Request req) {
        Response r = secure(answer(req), req);
        Observer o = observer;
        if (o != null) {
            try {
                String path = stripBase(req.path(), CoreSettings.HTTP_BASE_PATH.get());
                String pattern = path != null && router.match(req.method(), path) instanceof Router.Found f
                        ? f.route().pattern() : null;
                o.answered(req.method(), pattern, r.status());
            } catch (RuntimeException e) {
                onFailure.accept("the request counter", e);
            }
        }
        return r;
    }

    private Response answer(Request req) {
        String path = stripBase(req.path(), CoreSettings.HTTP_BASE_PATH.get());
        if (path == null) {
            return Response.error(404, "not_found", "Nothing is served here");
        }
        String client = clientOf(req);
        String origin = req.header("origin");
        boolean corsAllowed = origin != null && CoreSettings.HTTP_CORS_ORIGINS.get().contains(origin);
        if (req.method().equals("OPTIONS") && req.header("access-control-request-method") != null) {
            if (!corsAllowed) {
                return Response.error(403, "cors_denied", "This origin is not in http.corsOrigins");
            }
            return cors(Response.noContent()
                    .with("Access-Control-Allow-Methods", "GET, POST, PUT, PATCH, DELETE, OPTIONS")
                    .with("Access-Control-Allow-Headers", "Authorization, X-API-Key, Content-Type, Idempotency-Key")
                    .with("Access-Control-Max-Age", "600"), origin);
        }
        Response r = route(req, path, client);
        return corsAllowed ? cors(r, origin) : r;
    }

    private Response route(Request req, String path, String client) {
        Router.Match match = router.match(req.method(), path);
        Route found = match instanceof Router.Found f ? f.route() : null;
        String token = tokenOf(req);
        ApiKey key = token == null ? null : keys.authenticate(token);
        boolean open = found != null && found.open().getAsBoolean();

        if (key == null && (token != null || !open)) {
            Observer o = observer;
            if (o != null) {
                o.authFailed();
            }
            long wait = failLimiter.acquire(client, CoreSettings.LIMITS_AUTH_FAILURES.get());
            if (token != null && CoreSettings.AUDIT_AUTH_FAILURES.get()) {
                audit.record(new AuditEntry(clock.instant().toString(), "http", "unknown", client,
                        req.method() + " " + path, wait > 0 ? 429 : 401, "wrong key"));
            }
            if (wait > 0) {
                return Response.error(429, "too_many_failures", "Too many requests without a valid key")
                        .with("Retry-After", Long.toString(wait));
            }
            return Response.error(401, "unauthorized",
                    token == null ? "Send a key in Authorization: Bearer <key>" : "That key is not valid")
                    .with("WWW-Authenticate", "Bearer realm=\"RestAPI\"");
        }
        if (key != null && !key.allowFrom().isEmpty() && !AddressRanges.parse(key.allowFrom()).contains(client)) {
            audit.record(new AuditEntry(clock.instant().toString(), "http", key.name(), client,
                    req.method() + " " + path, 403, "address not in allowFrom"));
            return Response.error(403, "address_not_allowed", "This key is not accepted from " + client);
        }
        if (key != null) {
            int limit = key.perMinute() >= 0 ? key.perMinute() : CoreSettings.LIMITS_PER_MINUTE.get();
            long wait = keyLimiter.acquire(key.id(), limit);
            if (wait > 0) {
                return Response.error(429, "rate_limited", "This key is over " + limit + " requests a minute")
                        .with("Retry-After", Long.toString(wait));
            }
        }
        if (match instanceof Router.WrongMethod w) {
            return Response.error(405, "method_not_allowed", "Use " + String.join(" or ", w.allowed()))
                    .with("Allow", String.join(", ", w.allowed()));
        }
        if (!(match instanceof Router.Found f)) {
            return Response.error(404, "not_found", "No route at " + path);
        }
        Route route = f.route();
        if (key != null && !key.has(route.scope()) && !open) {
            audit.record(new AuditEntry(clock.instant().toString(), "http", key.name(), client,
                    req.method() + " " + path, 403, "missing scope " + route.scope().id()));
            return Response.error(403, "missing_scope", "This key needs the " + route.scope().id() + " scope");
        }
        Call call = new Call(req, key, client, f.params());
        Response r;
        String detail = null;
        try {
            r = route.handler().handle(call);
        } catch (ApiException e) {
            r = Response.error(e.status(), e.code(), e.getMessage());
        } catch (Exception | LinkageError | AssertionError e) {
            // AssertionError too: the engine's store thread checks throw it, and a request must still get an answer.
            onFailure.accept(req.method() + " " + path, e);
            r = Response.error(500, "internal_error", "The request failed on the server, the log has the cause");
            detail = e.getClass().getSimpleName();
        }
        if (route.access() != Access.READ || CoreSettings.AUDIT_READS.get()) {
            audit.record(new AuditEntry(clock.instant().toString(), "http", call.keyName(), client,
                    req.method() + " " + path, r.status(), detail == null ? route.access().name().toLowerCase(java.util.Locale.ROOT) : detail));
        }
        return r;
    }

    /** The token from Authorization Bearer, or from X-API-Key when keys.acceptXApiKey is on. */
    static String tokenOf(Request req) {
        String auth = req.header("authorization");
        if (auth != null && auth.regionMatches(true, 0, "Bearer ", 0, 7)) {
            String t = auth.substring(7).trim();
            return t.isEmpty() ? null : t;
        }
        if (CoreSettings.KEYS_ACCEPT_X_API_KEY.get()) {
            String x = req.header("x-api-key");
            return x == null || x.isBlank() ? null : x.trim();
        }
        return null;
    }

    /** The path without the base path, or null when the request is outside it. */
    static String stripBase(String path, String base) {
        String b = base == null ? "" : base.trim();
        while (b.endsWith("/")) {
            b = b.substring(0, b.length() - 1);
        }
        if (b.isEmpty()) {
            return path;
        }
        if (!b.startsWith("/")) {
            b = "/" + b;
        }
        if (path.equals(b)) {
            return "/";
        }
        return path.startsWith(b + "/") ? path.substring(b.length()) : null;
    }

    /**
     * The client address. The peer, unless the peer is a trusted proxy and sent X-Forwarded-For, then the header
     * is walked from the right and the first hop that is not a trusted proxy wins.
     */
    String clientOf(Request req) {
        List<String> trusted = CoreSettings.HTTP_TRUSTED_PROXIES.get();
        if (trusted != proxiesSource) {
            proxies = AddressRanges.parse(trusted);
            proxiesSource = trusted;
        }
        return client(proxies, req.peer(), req.headers("x-forwarded-for"));
    }

    static String client(AddressRanges trusted, String peer, List<String> forwardedFor) {
        if (trusted.isEmpty() || peer == null || forwardedFor.isEmpty() || !trusted.contains(peer)) {
            return peer;
        }
        List<String> hops = new ArrayList<>();
        for (String line : forwardedFor) {
            for (String part : line.split(",")) {
                String hop = part.trim();
                if (!hop.isEmpty()) {
                    hops.add(hop);
                }
            }
        }
        String last = null;
        for (int i = hops.size() - 1; i >= 0; i--) {
            String hop = hops.get(i);
            if (AddressRanges.literal(hop) == null) {
                break;
            }
            last = hop;
            if (!trusted.contains(hop)) {
                return hop;
            }
        }
        return last != null ? last : peer;
    }

    private static Response cors(Response r, String origin) {
        return r.with("Access-Control-Allow-Origin", origin).with("Vary", "Origin");
    }

    /** Headers every answer carries. */
    private static Response secure(Response r, Request req) {
        return r.with("Cache-Control", "no-store").with("X-Content-Type-Options", "nosniff");
    }
}
