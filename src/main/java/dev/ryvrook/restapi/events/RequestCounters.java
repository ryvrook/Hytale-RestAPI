package dev.ryvrook.restapi.events;

import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.LongAdder;

/**
 * Request counts for /metrics, by route pattern and status, so the label set stays as small as the route table.
 * A request that matched no route counts under the route label {@code unmatched}. The gate reports every answer
 * here once the lead wires it, see docs/dev/events.md.
 */
public final class RequestCounters {
    public static final String UNMATCHED = "unmatched";

    private final Map<String, LongAdder> requests = new ConcurrentHashMap<>();
    private final LongAdder authFailures = new LongAdder();
    private volatile boolean fed;

    /** One answered request. pattern is the route pattern, such as /api/players/{player}, or null. */
    public void answered(String method, String pattern, int status) {
        fed = true;
        String key = (method == null ? "GET" : method) + "\u0000" + (pattern == null ? UNMATCHED : pattern) + "\u0000" + status;
        requests.computeIfAbsent(key, k -> new LongAdder()).increment();
    }

    /** A request with a wrong key or none on a closed route. */
    public void authFailed() {
        fed = true;
        authFailures.increment();
    }

    /** True once anything was counted, so /metrics leaves the families out until the gate reports. */
    public boolean fed() {
        return fed;
    }

    public long authFailures() {
        return authFailures.sum();
    }

    /** method, route, status, count, sorted so the exposition is stable. */
    public void each(Row row) {
        Map<String, Long> sorted = new TreeMap<>();
        requests.forEach((k, v) -> sorted.put(k, v.sum()));
        sorted.forEach((k, v) -> {
            String[] p = k.split("\u0000", 3);
            row.accept(p[0], p[1], p[2], v);
        });
    }

    @FunctionalInterface
    public interface Row {
        void accept(String method, String route, String status, long count);
    }
}
