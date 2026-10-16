package dev.ryvrook.restapi.http;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * One HTTP request as the router sees it, with no tie to the JDK server, so the whole pipeline runs in tests.
 * Header names are lower case.
 *
 * @param method       upper case method
 * @param path         the raw path without the query, still percent encoded
 * @param query        the raw query string, or null
 * @param headers      lower case header name to its values
 * @param body         the body bytes, empty when there is none
 * @param peer         the address of the direct peer, a proxy when there is one
 */
public record Request(String method, String path, String query, Map<String, List<String>> headers, byte[] body,
                      String peer) {
    public Request {
        method = method == null ? "GET" : method.toUpperCase(Locale.ROOT);
        path = path == null || path.isEmpty() ? "/" : path;
        Map<String, List<String>> h = new LinkedHashMap<>();
        if (headers != null) {
            headers.forEach((k, v) -> h.put(k.toLowerCase(Locale.ROOT), List.copyOf(v)));
        }
        headers = Map.copyOf(h);
        body = body == null ? new byte[0] : body;
    }

    /** A request without headers or body, for tests. */
    public static Request of(String method, String pathAndQuery, String peer) {
        int q = pathAndQuery.indexOf('?');
        return new Request(method, q < 0 ? pathAndQuery : pathAndQuery.substring(0, q),
                q < 0 ? null : pathAndQuery.substring(q + 1), Map.of(), null, peer);
    }

    /** A copy with one more header, for tests. */
    public Request with(String header, String value) {
        Map<String, List<String>> h = new LinkedHashMap<>(headers);
        h.put(header.toLowerCase(Locale.ROOT), List.of(value));
        return new Request(method, path, query, h, body, peer);
    }

    /** The first value of a header, or null. */
    public String header(String name) {
        List<String> v = headers.get(name.toLowerCase(Locale.ROOT));
        return v == null || v.isEmpty() ? null : v.get(0);
    }

    public List<String> headers(String name) {
        return headers.getOrDefault(name.toLowerCase(Locale.ROOT), List.of());
    }

    /** Query parameters, decoded. A name given twice keeps its first value. */
    public Map<String, String> queryParams() {
        Map<String, String> out = new LinkedHashMap<>();
        if (query == null || query.isEmpty()) {
            return out;
        }
        for (String pair : query.split("&")) {
            if (pair.isEmpty()) {
                continue;
            }
            int eq = pair.indexOf('=');
            String k = decode(eq < 0 ? pair : pair.substring(0, eq));
            String v = eq < 0 ? "" : decode(pair.substring(eq + 1));
            out.putIfAbsent(k, v);
        }
        return out;
    }

    public String bodyText() {
        return new String(body, StandardCharsets.UTF_8);
    }

    static String decode(String s) {
        try {
            return URLDecoder.decode(s, StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            return s;
        }
    }
}
