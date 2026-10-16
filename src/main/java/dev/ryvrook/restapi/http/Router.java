package dev.ryvrook.restapi.http;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * Routes by group. A group, such as players or dominion, is mounted and unmounted as a whole, which is how a
 * suite route group comes and goes with its sibling. Matching is lock free over a list swapped whole on each
 * change. A literal segment wins over a {@code {name}} segment at the same place, so {@code /api/players/online}
 * and {@code /api/players/{player}} can live side by side.
 */
public final class Router {
    /** The outcome of a match. */
    public sealed interface Match permits Found, NotFound, WrongMethod {
    }

    public record Found(Route route, Map<String, String> params) implements Match {
    }

    public record NotFound() implements Match {
    }

    /** The path exists for other methods, listed in allowed for the Allow header. */
    public record WrongMethod(Set<String> allowed) implements Match {
    }

    private record Compiled(Route route, String[] segments) {
    }

    private final Map<String, List<Route>> groups = new LinkedHashMap<>();
    private volatile List<Compiled> compiled = List.of();

    /**
     * Mounts a group, replacing what it held before. Throws when a route repeats a method and pattern that another
     * group already serves, so two modules can never answer the same path.
     */
    public synchronized void mount(String group, List<Route> routes) {
        Map<String, List<Route>> next = new LinkedHashMap<>(groups);
        next.put(group, List.copyOf(routes));
        Set<String> seen = new java.util.HashSet<>();
        for (List<Route> rs : next.values()) {
            for (Route r : rs) {
                String sig = r.method() + " " + r.pattern().replaceAll("\\{[^/]+}", "{}");
                if (!seen.add(sig)) {
                    throw new IllegalStateException("Two routes answer " + r.method() + " " + r.pattern());
                }
                if (!r.group().equals(groupOf(next, r))) {
                    throw new IllegalStateException("Route " + r.pattern() + " names group " + r.group());
                }
            }
        }
        groups.clear();
        groups.putAll(next);
        rebuild();
    }

    private static String groupOf(Map<String, List<Route>> all, Route r) {
        for (Map.Entry<String, List<Route>> e : all.entrySet()) {
            if (e.getValue().contains(r)) {
                return e.getKey();
            }
        }
        return null;
    }

    /** Removes a group. False when it was not mounted. */
    public synchronized boolean unmount(String group) {
        if (groups.remove(group) == null) {
            return false;
        }
        rebuild();
        return true;
    }

    public synchronized boolean mounted(String group) {
        return groups.containsKey(group);
    }

    /** Every mounted route, group by group in mount order. For the OpenAPI document and /restapi status. */
    public List<Route> routes() {
        return compiled.stream().map(Compiled::route).toList();
    }

    public synchronized Set<String> groups() {
        return new java.util.LinkedHashSet<>(groups.keySet());
    }

    private void rebuild() {
        List<Compiled> out = new ArrayList<>();
        for (List<Route> rs : groups.values()) {
            for (Route r : rs) {
                out.add(new Compiled(r, split(r.pattern())));
            }
        }
        compiled = List.copyOf(out);
    }

    /** Finds the route for a method and a path that already lost http.basePath. */
    public Match match(String method, String path) {
        String[] segs = split(path);
        Found best = null;
        int bestScore = -1;
        Set<String> allowed = new TreeSet<>();
        for (Compiled c : compiled) {
            Map<String, String> params = bind(c.segments, segs);
            if (params == null) {
                continue;
            }
            if (!c.route.method().equals(method) && !(method.equals("HEAD") && c.route.method().equals("GET"))) {
                allowed.add(c.route.method());
                continue;
            }
            int score = literalScore(c.segments);
            if (score > bestScore) {
                best = new Found(c.route, params);
                bestScore = score;
            }
        }
        if (best != null) {
            return best;
        }
        return allowed.isEmpty() ? new NotFound() : new WrongMethod(allowed);
    }

    /** A number where each literal segment counts more the earlier it sits. */
    private static int literalScore(String[] pattern) {
        int score = 0;
        for (String p : pattern) {
            score = score * 2 + (isParam(p) ? 0 : 1);
        }
        return score;
    }

    private static Map<String, String> bind(String[] pattern, String[] path) {
        if (pattern.length != path.length) {
            return null;
        }
        Map<String, String> params = null;
        for (int i = 0; i < pattern.length; i++) {
            String p = pattern[i];
            if (isParam(p)) {
                if (path[i].isEmpty()) {
                    return null;
                }
                if (params == null) {
                    params = new LinkedHashMap<>();
                }
                params.put(p.substring(1, p.length() - 1), path[i]);
            } else if (!p.equals(path[i])) {
                return null;
            }
        }
        return params == null ? Map.of() : params;
    }

    private static boolean isParam(String seg) {
        return seg.length() > 2 && seg.charAt(0) == '{' && seg.charAt(seg.length() - 1) == '}';
    }

    /** Splits a path into segments. A trailing slash is ignored, so /api/server/ matches /api/server. */
    static String[] split(String path) {
        String p = path;
        while (p.length() > 1 && p.endsWith("/")) {
            p = p.substring(0, p.length() - 1);
        }
        if (p.equals("/") || p.isEmpty()) {
            return new String[0];
        }
        return (p.startsWith("/") ? p.substring(1) : p).split("/", -1);
    }
}
