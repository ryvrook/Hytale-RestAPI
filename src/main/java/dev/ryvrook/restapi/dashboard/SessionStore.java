package dev.ryvrook.restapi.dashboard;

import dev.ryvrook.restapi.auth.ApiKey;
import dev.ryvrook.restapi.auth.Scope;
import dev.ryvrook.restapi.http.Gate;
import dev.ryvrook.restapi.http.Request;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.EnumSet;
import java.util.HexFormat;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BooleanSupplier;
import java.util.function.IntSupplier;
import java.util.function.LongSupplier;
import java.util.regex.Pattern;

/**
 * Dashboard sessions and login links, in memory only, so a server restart ends every session. A login link carries
 * a token of 32 random bytes that works once within dashboard.linkMinutes. Using it opens a session whose cookie
 * value is another 32 random bytes. Both are kept by their SHA-256 only, so the store never holds a value a
 * browser could replay.
 *
 * <p>A session ends after dashboard.sessionHours without a request, after 7 days whatever happens, at logout and
 * when the dashboard is turned off. As credentials in the gate it stands for a key named
 * {@code dashboard:<who>} with every scope, which may only GET and HEAD while dashboard.writes is off.
 *
 * <p>Thread safe, every method takes the store's monitor for a few map operations.
 */
public final class SessionStore implements Gate.Credentials {
    /** The cookie name. Different from the standalone server.js, which may run on the same host. */
    public static final String COOKIE = "restapi_session";
    /** Login tokens start with this, so the console log ring can cut them like key tokens. */
    public static final String LINK_PREFIX = "rdl_";
    /** The longest a session lives. */
    public static final long HARD_CAP_MS = 7L * 24 * 3600 * 1000;
    static final int MAX_SESSIONS = 64;
    static final int MAX_LINKS = 32;
    private static final Pattern VALUE = Pattern.compile("[A-Za-z0-9_-]{43}");

    private static final class Live {
        final Session session;
        long seen;

        Live(Session session, long seen) {
            this.session = session;
            this.seen = seen;
        }
    }

    /** What a used login link gives back, the cookie value to set and the session it opened. */
    public record Opened(String cookie, Session session) {
    }

    private final LongSupplier clock;
    private final LongSupplier idleMs;
    private final LongSupplier linkMs;
    private final BooleanSupplier writes;
    private final IntSupplier perMinute;
    private final SecureRandom random = new SecureRandom();
    private final Map<String, Live> sessions = new LinkedHashMap<>();
    private final Map<String, LoginLink> links = new LinkedHashMap<>();

    /**
     * @param clock     epoch millis
     * @param idleMs    how long a session lasts without use, read on every check
     * @param linkMs    how long a new login link works
     * @param writes    whether a session may change the server, read on every request
     * @param perMinute the request limit of a session, 0 for none
     */
    public SessionStore(LongSupplier clock, LongSupplier idleMs, LongSupplier linkMs, BooleanSupplier writes,
                        IntSupplier perMinute) {
        this.clock = clock;
        this.idleMs = idleMs;
        this.linkMs = linkMs;
        this.writes = writes;
        this.perMinute = perMinute;
    }

    /** The store as the plugin uses it, with the dashboard settings and the system clock. */
    public static SessionStore live() {
        return new SessionStore(System::currentTimeMillis,
                () -> DashboardSettings.SESSION_HOURS.get() * 3600_000L,
                () -> DashboardSettings.LINK_MINUTES.get() * 60_000L,
                DashboardSettings.WRITES::get, DashboardSettings.REQUESTS_PER_MINUTE::get);
    }

    // ---- login links ----------------------------------------------------------------------------------

    /** A new login link token for who. It works once, until the link time runs out. */
    public synchronized String issue(String who) {
        long now = clock.getAsLong();
        sweep(now);
        String token = LINK_PREFIX + randomValue();
        links.put(digest(token), new LoginLink(who, now + linkMs.getAsLong()));
        while (links.size() > MAX_LINKS) {
            Iterator<String> it = links.keySet().iterator();
            it.next();
            it.remove();
        }
        return token;
    }

    /**
     * Uses a login link. Answers the new session, or null when the token is unknown, used or expired. A token is
     * gone after its first try whatever the answer.
     */
    public synchronized Opened redeem(String token) {
        if (token == null || !token.startsWith(LINK_PREFIX) || !VALUE.matcher(token.substring(LINK_PREFIX.length())).matches()) {
            return null;
        }
        long now = clock.getAsLong();
        LoginLink link = links.remove(digest(token));
        if (link == null || now > link.expires()) {
            return null;
        }
        String cookie = randomValue();
        Session s = new Session(digest(cookie), HexFormat.of().formatHex(bytes(4)), link.who(), now);
        sessions.put(s.digest(), new Live(s, now));
        while (sessions.size() > MAX_SESSIONS) {
            Iterator<String> it = sessions.keySet().iterator();
            it.next();
            it.remove();
        }
        return new Opened(cookie, s);
    }

    /** Login links handed out and not used or expired yet. */
    public synchronized int pendingLinks() {
        sweep(clock.getAsLong());
        return links.size();
    }

    // ---- sessions -------------------------------------------------------------------------------------

    /** The session of a cookie value, or null. A hit counts as use and keeps it from the idle limit. */
    public synchronized Session resolve(String cookie) {
        if (cookie == null || !VALUE.matcher(cookie).matches()) {
            return null;
        }
        String key = digest(cookie);
        Live l = sessions.get(key);
        if (l == null) {
            return null;
        }
        long now = clock.getAsLong();
        if (expired(l, now)) {
            sessions.remove(key);
            return null;
        }
        l.seen = now;
        return l.session;
    }

    /** When a session ends unless it is used again, or 0 when it is gone. */
    public synchronized long endsAt(Session s) {
        Live l = s == null ? null : sessions.get(s.digest());
        return l == null ? 0 : Math.min(l.seen + idleMs.getAsLong(), l.session.created() + HARD_CAP_MS);
    }

    /** Ends the session of a cookie value. False when there was none. */
    public synchronized boolean end(String cookie) {
        return cookie != null && VALUE.matcher(cookie).matches() && sessions.remove(digest(cookie)) != null;
    }

    /** Ends every session and forgets every login link. Answers how many sessions were open. */
    public synchronized int endAll() {
        sweep(clock.getAsLong());
        int n = sessions.size();
        sessions.clear();
        links.clear();
        return n;
    }

    /** Sessions open right now. */
    public synchronized int count() {
        sweep(clock.getAsLong());
        return sessions.size();
    }

    private boolean expired(Live l, long now) {
        return now - l.seen > idleMs.getAsLong() || now - l.session.created() > HARD_CAP_MS;
    }

    private void sweep(long now) {
        sessions.values().removeIf(l -> expired(l, now));
        links.values().removeIf(k -> now > k.expires());
    }

    // ---- credentials for the gate ---------------------------------------------------------------------

    @Override
    public Gate.Credentials.Found find(Request req) {
        String cookie = cookieOf(req);
        if (cookie == null) {
            return Gate.Credentials.Found.NONE;
        }
        Session s = resolve(cookie);
        return new Gate.Credentials.Found(true, s == null ? null : keyOf(s), writes.getAsBoolean());
    }

    /** The key a session stands for in the gate, with every scope and its own request limit. */
    ApiKey keyOf(Session s) {
        return new ApiKey(s.principal(), "s" + s.handle(), "", EnumSet.allOf(Scope.class),
                Instant.ofEpochMilli(s.created()).toString(), s.who(), perMinute.getAsInt(), List.of());
    }

    /** The value of the session cookie in a request, or null when it has none. */
    public static String cookieOf(Request req) {
        for (String line : req.headers("cookie")) {
            for (String part : line.split(";")) {
                int eq = part.indexOf('=');
                if (eq > 0 && part.substring(0, eq).trim().equals(COOKIE)) {
                    return part.substring(eq + 1).trim();
                }
            }
        }
        return null;
    }

    /** The Set-Cookie value for a new session. Path is the base path, Secure when the browser came over https. */
    public static String cookie(String value, String path, boolean secure) {
        return COOKIE + "=" + value + "; Path=" + path + "; HttpOnly; SameSite=Strict; Max-Age=" + HARD_CAP_MS / 1000
                + (secure ? "; Secure" : "");
    }

    /** The Set-Cookie value that removes the session cookie. */
    public static String clearCookie(String path, boolean secure) {
        return COOKIE + "=; Path=" + path + "; HttpOnly; SameSite=Strict; Max-Age=0" + (secure ? "; Secure" : "");
    }

    private String randomValue() {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes(32));
    }

    private byte[] bytes(int n) {
        byte[] b = new byte[n];
        random.nextBytes(b);
        return b;
    }

    static String digest(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
