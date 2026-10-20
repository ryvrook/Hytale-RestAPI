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
 * base path, CORS preflight, client address, key or session, failed login limit, per key limit, route, scope,
 * handler, audit.
 *
 * <p>A second source of credentials, the dashboard session cookie, plugs in through {@link Credentials}. It is
 * read only when the request carries no key, is refused with 403 on any request that does not come from a page of
 * this server, and may only GET while its writes are off.
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
    private volatile Credentials credentials;

    /** Told about every answer and every request turned away for its key, for the request counts in /metrics. */
    public interface Observer {
        /** pattern is the route pattern that matched, or null when none did. */
        void answered(String method, String pattern, int status);

        void authFailed();
    }

    /**
     * Credentials besides keys, such as the dashboard session cookie. The gate asks only when the request has no
     * key, so a key in the same request always wins.
     */
    public interface Credentials {
        /** What the request carries. {@link Found#NONE} when it carries nothing of this kind. */
        Found find(Request req);

        /**
         * @param present true when the request carries the credential at all, valid or not
         * @param key     the key it stands for, null when it is unknown or expired
         * @param writes  false when it may only GET and HEAD
         */
        record Found(boolean present, ApiKey key, boolean writes) {
            public static final Found NONE = new Found(false, null, false);
        }
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

    /** Sets the second source of credentials, or clears it with null. */
    public void credentials(Credentials c) {
        credentials = c;
    }

    /**
     * Counts a failed login that did not come through a key, such as a dashboard login link that is used or
     * expired, against the same address limit as a wrong key. Returns the seconds to wait, 0 while tries are left.
     */
    public long authFailure(String client) {
        Observer o = observer;
        if (o != null) {
            o.authFailed();
        }
        return failLimiter.acquire(client, CoreSettings.LIMITS_AUTH_FAILURES.get());
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
        Credentials creds = credentials;
        Credentials.Found session = token == null && creds != null ? creds.find(req) : Credentials.Found.NONE;
        if (session.present() && !sameSite(req)) {
            if (open) {
                // The page, its files and the login link are served to a stranger as they are. A browser that
                // does not call a link opened from the game client same site gets the same answer, and the
                // cookie counts for nothing on it.
                session = Credentials.Found.NONE;
            } else {
                if (CoreSettings.AUDIT_AUTH_FAILURES.get()) {
                    audit.record(new AuditEntry(clock.instant().toString(), "http",
                            session.key() == null ? "unknown" : session.key().name(), client,
                            req.method() + " " + path, 403, "session cookie on a request from another site"));
                }
                return Response.error(403, "cross_site", "The dashboard session is only taken from pages of this server");
            }
        }
        if (session.present()) {
            key = session.key();
            if (key == null && !open) {
                Observer o = observer;
                if (o != null) {
                    o.authFailed();
                }
                long wait = failLimiter.acquire(client, CoreSettings.LIMITS_AUTH_FAILURES.get());
                if (wait > 0) {
                    return Response.error(429, "too_many_failures", "Too many requests without a valid key")
                            .with("Retry-After", Long.toString(wait));
                }
                return Response.error(401, "login_required", "The dashboard session has ended, log in again");
            }
        }

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
        if (key != null && session.key() == key && !session.writes() && !open
                && !req.method().equals("GET") && !req.method().equals("HEAD")) {
            return Response.error(403, "read_only",
                    "The dashboard is read only. Turn writes on with /restapi dashboard writes on");
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

    /**
     * True when a browser says the request comes from a page of this server. A Sec-Fetch-Site of same-origin or
     * none decides when the browser sends one. A GET or HEAD that is a top level page navigation, Sec-Fetch-Mode
     * navigate with Sec-Fetch-Dest document, passes whatever the site says: a login link clicked in the game
     * client does not arrive as same-origin in Firefox, nor does the redirect after it, while a page shown in a
     * tab cannot be read by another site and changes nothing. Without the Sec-Fetch headers, the host of Origin,
     * else of Referer, must be the Host header. A request with none of the three is not taken as same site.
     */
    static boolean sameSite(Request req) {
        String fetchSite = req.header("sec-fetch-site");
        if (fetchSite != null) {
            String s = fetchSite.trim().toLowerCase(java.util.Locale.ROOT);
            return s.equals("same-origin") || s.equals("none") || pageNavigation(req);
        }
        String host = req.header("host");
        if (host == null || host.isBlank()) {
            return false;
        }
        String origin = req.header("origin");
        String from = origin != null ? origin : req.header("referer");
        if (from == null) {
            return false;
        }
        try {
            java.net.URI u = new java.net.URI(from.trim());
            String authority = u.getRawAuthority();
            return authority != null && (u.getScheme() != null)
                    && authority.equalsIgnoreCase(host.trim());
        } catch (java.net.URISyntaxException e) {
            return false;
        }
    }

    /** A GET or HEAD the browser makes to show a page in a tab or window, not a frame, a fetch or an image. */
    static boolean pageNavigation(Request req) {
        String method = req.method();
        if (!method.equals("GET") && !method.equals("HEAD")) {
            return false;
        }
        String mode = req.header("sec-fetch-mode");
        String dest = req.header("sec-fetch-dest");
        return mode != null && dest != null
                && mode.trim().equalsIgnoreCase("navigate")
                && dest.trim().equalsIgnoreCase("document");
    }

    /** True when a trusted proxy says in X-Forwarded-Proto that the browser reached it over https. */
    public boolean forwardedHttps(Request req) {
        List<String> trusted = CoreSettings.HTTP_TRUSTED_PROXIES.get();
        if (trusted != proxiesSource) {
            proxies = AddressRanges.parse(trusted);
            proxiesSource = trusted;
        }
        if (proxies.isEmpty() || req.peer() == null || !proxies.contains(req.peer())) {
            return false;
        }
        String proto = req.header("x-forwarded-proto");
        return proto != null && proto.split(",")[0].trim().equalsIgnoreCase("https");
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

    /** Headers every answer carries. A handler that set its own Cache-Control, such as a game icon, keeps it. */
    private static Response secure(Response r, Request req) {
        Response out = r.headers().containsKey("Cache-Control") ? r : r.with("Cache-Control", "no-store");
        return out.with("X-Content-Type-Options", "nosniff");
    }
}
