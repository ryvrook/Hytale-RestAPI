package dev.ryvrook.restapi.dashboard;

import dev.ryvrook.restapi.http.Request;
import dev.ryvrook.restapi.http.Response;
import dev.ryvrook.restapi.text.Messages;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.function.LongSupplier;
import java.util.function.Supplier;
import java.util.regex.Pattern;

/**
 * GET /dashboard/chronicle/api/avatar/{player} and /dashboard/chronicle/api/asset/{path...}, passed to Chronicle
 * Stats over loopback on its own web port, the same shape as proxyChronicle in the dashboard's server.js. Only
 * these two paths and only GET. Asset files are kept for good once read, since Chronicle serves them unchanged for
 * a server version. Avatar answers are asked for again after a minute with If-None-Match, so a changed character
 * shows without a reload. At most 16 MB is kept, oldest first out.
 *
 * <p>Chronicle answers as it does to any visitor without a browser link, so a private profile or a profiles layer
 * that is not public keeps the letter bust.
 */
public final class ChroniclePass implements AutoCloseable {
    static final long KEEP_BYTES = 16L << 20;
    static final long AVATAR_MS = 60_000;
    static final int MAX_BODY = 8 << 20;
    static final String ASSET_CACHE = "public, max-age=2592000, immutable";
    static final String AVATAR_CACHE = "private, max-age=60";
    private static final Pattern WHO = Pattern.compile("[A-Za-z0-9_-]{1,64}");
    private static final Pattern SEGMENT = Pattern.compile("[A-Za-z0-9._~%()+,-]{1,200}");

    /** One GET to Chronicle. The plugin's sends it over the JDK client, the tests answer from memory. */
    public interface Upstream extends AutoCloseable {
        Answer get(String url, String ifNoneMatch, boolean asset) throws IOException, InterruptedException;

        @Override
        default void close() {
        }
    }

    public record Answer(int status, String type, String etag, byte[] body) {
    }

    private record Kept(byte[] body, String type, String etag, boolean asset, long at) {
    }

    private final Supplier<String> base;
    private final Upstream upstream;
    private final LongSupplier clock;
    private final Map<String, Kept> kept = new LinkedHashMap<>(64, 0.75f, true);
    private long keptBytes;

    /**
     * @param base     Chronicle's address with a trailing slash, such as http://127.0.0.1:8124/, or null while the
     *                 pass through is off, read on every request
     * @param upstream how the requests are sent
     * @param clock    epoch millis
     */
    public ChroniclePass(Supplier<String> base, Upstream upstream, LongSupplier clock) {
        this.base = base;
        this.upstream = upstream;
        this.clock = clock;
    }

    /** True while requests would reach Chronicle. */
    public boolean on() {
        return base.get() != null;
    }

    /** True for a player segment Chronicle takes, a UUID or a name. */
    static boolean validPlayer(String raw) {
        return raw != null && WHO.matcher(raw).matches();
    }

    /** True for an asset path of two segments or more with no dot segment, slash, backslash or null escape. */
    static boolean validAsset(String raw) {
        if (raw == null || raw.isEmpty()) {
            return false;
        }
        String[] parts = raw.split("/", -1);
        if (parts.length < 2) {
            return false;
        }
        for (String p : parts) {
            String lower = p.toLowerCase(Locale.ROOT);
            if (p.equals(".") || p.equals("..") || !SEGMENT.matcher(p).matches()
                    || lower.contains("%2f") || lower.contains("%5c") || lower.contains("%00") || lower.contains("%2e")) {
                return false;
            }
        }
        return true;
    }

    public Response avatar(String rawPlayer, Request req) {
        if (!validPlayer(rawPlayer)) {
            return notFound();
        }
        return pass("api/avatar/" + rawPlayer, false, req);
    }

    public Response asset(String rawPath, Request req) {
        if (!validAsset(rawPath)) {
            return notFound();
        }
        return pass("api/asset/" + rawPath, true, req);
    }

    private Response pass(String rest, boolean asset, Request req) {
        String at = base.get();
        if (at == null) {
            return Response.error(404, "no_chronicle", Messages.get(DashboardText.NO_CHRONICLE));
        }
        Kept k;
        synchronized (kept) {
            k = kept.get(rest);
        }
        long now = clock.getAsLong();
        if (k != null && (asset || now - k.at() < AVATAR_MS)) {
            return send(k, req);
        }
        Answer a;
        try {
            a = upstream.get(at + rest, k == null ? null : k.etag(), asset);
        } catch (IOException e) {
            return Response.error(502, "chronicle_unreachable", Messages.get(DashboardText.CHRONICLE_DOWN));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Response.error(502, "chronicle_unreachable", Messages.get(DashboardText.CHRONICLE_DOWN));
        }
        if (a.status() == 304 && k != null) {
            Kept fresh = new Kept(k.body(), k.type(), k.etag(), k.asset(), now);
            keep(rest, fresh);
            return send(fresh, req);
        }
        if (a.status() != 200) {
            return new Response(a.status(), a.type() == null ? Response.JSON : a.type(), a.body(), Map.of());
        }
        Kept fresh = new Kept(a.body(), a.type() == null ? "application/octet-stream" : a.type(), a.etag(), asset, now);
        keep(rest, fresh);
        return send(fresh, req);
    }

    private static Response send(Kept k, Request req) {
        Map<String, String> h = new LinkedHashMap<>();
        h.put("Cache-Control", k.asset() ? ASSET_CACHE : AVATAR_CACHE);
        if (k.etag() != null) {
            h.put("ETag", k.etag());
            if (k.etag().equals(req.header("if-none-match"))) {
                return new Response(304, null, new byte[0], h);
            }
        }
        return new Response(200, k.type(), k.body(), h);
    }

    private void keep(String key, Kept k) {
        synchronized (kept) {
            Kept old = kept.put(key, k);
            if (old != null) {
                keptBytes -= old.body().length;
            }
            keptBytes += k.body().length;
            var it = kept.entrySet().iterator();
            while (keptBytes > KEEP_BYTES && it.hasNext()) {
                Map.Entry<String, Kept> e = it.next();
                if (e.getKey().equals(key)) {
                    continue;
                }
                keptBytes -= e.getValue().body().length;
                it.remove();
            }
        }
    }

    /** Files and answers kept right now. */
    int keptCount() {
        synchronized (kept) {
            return kept.size();
        }
    }

    private static Response notFound() {
        return Response.error(404, "not_found", Messages.get(DashboardText.CHRONICLE_SHAPE));
    }

    @Override
    public void close() {
        upstream.close();
        synchronized (kept) {
            kept.clear();
            keptBytes = 0;
        }
    }

    /** Requests over the JDK client, HTTP/1.1, no redirects, 2 seconds to connect and 15 for the answer. */
    static Upstream http() {
        HttpClient client = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofSeconds(2)).followRedirects(HttpClient.Redirect.NEVER).build();
        return new Upstream() {
            @Override
            public Answer get(String url, String ifNoneMatch, boolean asset) throws IOException, InterruptedException {
                HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(15)).GET()
                        .header("Accept", asset ? "*/*" : "application/json");
                if (ifNoneMatch != null) {
                    b.header("If-None-Match", ifNoneMatch);
                }
                HttpResponse<InputStream> r = client.send(b.build(), HttpResponse.BodyHandlers.ofInputStream());
                byte[] body;
                try (InputStream in = r.body()) {
                    body = in.readNBytes(MAX_BODY + 1);
                }
                if (body.length > MAX_BODY) {
                    throw new IOException("Chronicle answered more than " + MAX_BODY + " bytes");
                }
                return new Answer(r.statusCode(), r.headers().firstValue("content-type").orElse(null),
                        r.headers().firstValue("etag").orElse(null), body);
            }

            @Override
            public void close() {
                // shutdownNow, not close, so a stop never waits for a slow answer.
                client.shutdownNow();
            }
        };
    }
}
