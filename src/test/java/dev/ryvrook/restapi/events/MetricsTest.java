package dev.ryvrook.restapi.events;

import dev.ryvrook.restapi.server.WorldSample;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;

class MetricsTest {
    /** The line shapes of the text format 0.0.4, the checks promtool makes on a single exposition. */
    private static final Pattern HELP = Pattern.compile("# HELP [a-zA-Z_:][a-zA-Z0-9_:]* .*");
    private static final Pattern TYPE = Pattern.compile("# TYPE [a-zA-Z_:][a-zA-Z0-9_:]* (gauge|counter)");
    private static final Pattern SAMPLE = Pattern.compile(
            "[a-zA-Z_:][a-zA-Z0-9_:]*(\\{[a-zA-Z_][a-zA-Z0-9_]*=\"([^\"\\\\\\n]|\\\\[\\\\\"n])*\"(,[a-zA-Z_][a-zA-Z0-9_]*=\"([^\"\\\\\\n]|\\\\[\\\\\"n])*\")*})? (NaN|[+-]Inf|-?[0-9.eE+-]+)");

    static void assertValid(String text) {
        assertTrue(text.isEmpty() || text.endsWith("\n"));
        String family = null;
        java.util.Set<String> seenFamilies = new java.util.HashSet<>();
        for (String line : text.split("\n")) {
            if (line.startsWith("# HELP ")) {
                assertTrue(HELP.matcher(line).matches(), line);
                family = line.split(" ")[2];
                assertTrue(seenFamilies.add(family), "family twice " + family);
            } else if (line.startsWith("# TYPE ")) {
                assertTrue(TYPE.matcher(line).matches(), line);
                assertEquals(family, line.split(" ")[2]);
            } else {
                assertTrue(SAMPLE.matcher(line).matches(), line);
                assertTrue(line.startsWith(family), line + " outside its family " + family);
            }
        }
    }

    @Test
    void writesHelpTypeAndSamples() {
        Metrics m = new Metrics();
        m.gauge("hytale_players_online", "Players connected.").add(3);
        m.counter("restapi_requests_total", "Requests.").add(5, "route", "/api/x", "status", "200");
        m.gauge("empty_family", "Nothing.");
        String text = m.render();
        assertEquals("""
                # HELP hytale_players_online Players connected.
                # TYPE hytale_players_online gauge
                hytale_players_online 3
                # HELP restapi_requests_total Requests.
                # TYPE restapi_requests_total counter
                restapi_requests_total{route="/api/x",status="200"} 5
                """, text);
        assertValid(text);
    }

    @Test
    void escapesLabelsAndHelp() {
        Metrics m = new Metrics();
        m.gauge("x", "a \\ b\nc").add(1, "world", "we\"ird\\name\nx");
        String text = m.render();
        assertTrue(text.contains("# HELP x a \\\\ b\\nc\n"));
        assertTrue(text.contains("x{world=\"we\\\"ird\\\\name\\nx\"} 1\n"));
        assertValid(text);
    }

    @Test
    void numbersUseTheFormatSpelling() {
        assertEquals("NaN", Metrics.number(Double.NaN));
        assertEquals("+Inf", Metrics.number(Double.POSITIVE_INFINITY));
        assertEquals("-Inf", Metrics.number(Double.NEGATIVE_INFINITY));
        assertEquals("42", Metrics.number(42));
        assertEquals("-1", Metrics.number(-1));
        assertEquals("29.97", Metrics.number(29.97));
    }

    @Test
    void refusesWhatTheFormatForbids() {
        Metrics m = new Metrics();
        assertThrows(IllegalArgumentException.class, () -> m.counter("requests", "No _total."));
        assertThrows(IllegalArgumentException.class, () -> m.gauge("1bad", "Bad."));
        assertThrows(IllegalArgumentException.class, () -> m.gauge("ok", "Ok.").add(1, "bad-label", "x"));
        assertThrows(IllegalArgumentException.class, () -> m.gauge("ok2", "Ok.").add(1, "__reserved", "x"));
        assertThrows(IllegalArgumentException.class, () -> m.gauge("ok3", "Ok.").add(1, "odd"));
        Metrics.Family f = m.gauge("dup", "Dup.").add(1, "a", "1");
        assertThrows(IllegalArgumentException.class, () -> f.add(2, "a", "1"));
        m.counter("same_total", "A counter.");
        assertThrows(IllegalArgumentException.class, () -> m.gauge("same_total", "Now a gauge."));
    }

    @Test
    void worldsBecomeOneSamplePerFamily() {
        Metrics m = new Metrics();
        long now = 100_000;
        List<WorldSample> worlds = List.of(
                new WorldSample("default", 2, 30, 29.9876, 4.25, 12.5, 400, 1200, now - 1000, 0),
                new WorldSample("stuck", 0, 30, 30, 1, 2, 10, 20, now - 60_000, now - 30_000),
                new WorldSample("new", 0, 0, -1, 0, 0, 0, 0, 0, 0));
        MetricsRoute.worlds(m, worlds, now, 15_000);
        String text = m.render();
        assertValid(text);
        assertTrue(text.contains("hytale_world_tps{world=\"default\"} 29.988\n"));
        assertTrue(text.contains("hytale_world_tick_ms{world=\"default\",stat=\"avg\"} 4.25\n"));
        assertTrue(text.contains("hytale_world_tick_ms{world=\"default\",stat=\"max\"} 12.5\n"));
        assertTrue(text.contains("hytale_world_chunks_loaded{world=\"default\"} 400\n"));
        assertTrue(text.contains("hytale_world_entities{world=\"default\"} 1200\n"));
        assertTrue(text.contains("hytale_world_stalled{world=\"stuck\"} 1\n"));
        assertTrue(text.contains("hytale_world_stalled{world=\"default\"} 0\n"));
        assertTrue(text.contains("hytale_world_players{world=\"new\"} 0\n"));
        assertFalse(text.contains("hytale_world_tps{world=\"new\"}"));
    }

    @Test
    void requestCountersSortAndSplitLabels() {
        RequestCounters c = new RequestCounters();
        assertFalse(c.fed());
        c.answered("GET", "/api/server", 200);
        c.answered("GET", "/api/server", 200);
        c.answered("POST", null, 404);
        c.authFailed();
        assertTrue(c.fed());
        StringBuilder b = new StringBuilder();
        c.each((method, route, status, n) -> b.append(method).append(' ').append(route).append(' ').append(status)
                .append('=').append(n).append(';'));
        assertEquals("GET /api/server 200=2;POST unmatched 404=1;", b.toString());
        assertEquals(1, c.authFailures());
    }
}
