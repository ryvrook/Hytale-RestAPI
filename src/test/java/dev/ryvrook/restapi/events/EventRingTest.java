package dev.ryvrook.restapi.events;

import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class EventRingTest {
    private static final long BASE = EventRing.idBase(1_760_600_000_000L);
    private static final Set<String> ALL = Set.copyOf(EventRing.TYPES);

    private static JsonObject data(String k, String v) {
        JsonObject o = new JsonObject();
        o.addProperty(k, v);
        return o;
    }

    private static EventRing ring(int cap) {
        return new EventRing(cap, BASE, e -> fail(e));
    }

    @Test
    void idsRiseFromTheBaseAndStayExactInJavaScript() {
        EventRing r = ring(10);
        assertEquals(BASE, r.publish("join", null, 1, null).id());
        assertEquals(BASE + 1, r.publish("leave", null, 2, null).id());
        assertTrue(BASE < (1L << 53), "ids must fit a JavaScript number");
    }

    @Test
    void theRingKeepsTheNewestAndResizes() {
        EventRing r = ring(3);
        for (int i = 0; i < 5; i++) {
            r.publish("chat", null, i, null);
        }
        assertEquals(3, r.size());
        assertEquals(BASE + 2, r.recent(ALL, 10).get(0).id());
        r.resize(1);
        assertEquals(1, r.size());
        assertEquals(BASE + 4, r.recent(ALL, 10).get(0).id());
    }

    @Test
    void recentFiltersByTypeAndLimitsOldestFirst() {
        EventRing r = ring(100);
        r.publish("join", null, 0, null);
        r.publish("chat", null, 0, null);
        r.publish("join", null, 0, null);
        r.publish("join", null, 0, null);
        List<EventRing.Event> two = r.recent(Set.of("join"), 2);
        assertEquals(List.of(BASE + 2, BASE + 3), two.stream().map(EventRing.Event::id).toList());
    }

    @Test
    void sinceResumesAndReportsGaps() {
        EventRing r = ring(3);
        for (int i = 0; i < 5; i++) {
            r.publish("chat", null, i, null);
        }
        // ring holds BASE+2..BASE+4
        EventRing.Resume ok = r.since(BASE + 3, ALL);
        assertFalse(ok.gap());
        assertEquals(List.of(BASE + 4), ok.events().stream().map(EventRing.Event::id).toList());

        EventRing.Resume lost = r.since(BASE, ALL);
        assertTrue(lost.gap(), "BASE+1 was dropped");
        assertEquals(3, lost.events().size());

        EventRing.Resume edge = r.since(BASE + 1, ALL);
        assertFalse(edge.gap(), "everything after BASE+1 is still there");

        EventRing.Resume older = r.since(BASE - 500, ALL);
        assertTrue(older.gap(), "an id from an earlier run");
        assertEquals(3, older.events().size());

        EventRing.Resume future = r.since(BASE + 99, ALL);
        assertTrue(future.gap());
        assertEquals(3, future.events().size());

        EventRing.Resume upToDate = r.since(BASE + 4, ALL);
        assertFalse(upToDate.gap());
        assertTrue(upToDate.events().isEmpty());
    }

    @Test
    void aSubscriptionReplaysThenFollowsWithoutDuplicates() throws Exception {
        EventRing r = ring(10);
        r.publish("join", null, 0, null);
        r.publish("chat", null, 0, null);
        try (EventRing.Subscription s = r.subscribe(BASE, Set.of("chat", "leave"), 10)) {
            assertEquals(List.of(BASE + 1), s.first().events().stream().map(EventRing.Event::id).toList());
            r.publish("join", null, 0, null);
            r.publish("leave", null, 0, null);
            EventRing.Event next = s.next(1, TimeUnit.SECONDS);
            assertEquals("leave", next.type());
            assertNull(s.next(10, TimeUnit.MILLISECONDS));
            assertEquals(1, r.streams());
        }
        assertEquals(0, r.streams());
    }

    @Test
    void aSlowReaderIsClosedWithOverflow() throws Exception {
        EventRing r = ring(100);
        EventRing.Subscription s = r.subscribe(null, ALL, 3);
        for (int i = 0; i < 10; i++) {
            r.publish("chat", null, 0, null);
        }
        List<Long> got = new ArrayList<>();
        EventRing.Closed closed = assertThrows(EventRing.Closed.class, () -> {
            while (true) {
                EventRing.Event e = s.next(100, TimeUnit.MILLISECONDS);
                if (e != null) {
                    got.add(e.id());
                }
            }
        });
        assertTrue(closed.overflow());
        assertFalse(got.isEmpty());
        assertTrue(got.size() <= 3);
    }

    @Test
    void closeAllEndsEveryStream() {
        EventRing r = ring(10);
        EventRing.Subscription s = r.subscribe(null, ALL, 10);
        r.closeAll();
        EventRing.Closed c = assertThrows(EventRing.Closed.class, () -> s.next(1, TimeUnit.SECONDS));
        assertFalse(c.overflow());
        assertEquals(0, r.streams());
    }

    @Test
    void listenersHearEveryEvent() {
        EventRing r = ring(10);
        List<String> heard = new ArrayList<>();
        r.listen(e -> heard.add(e.type()));
        r.publish("world", "added", 0, null);
        assertEquals(List.of("world"), heard);
    }

    @Test
    void theJsonKeepsItsOwnFieldsFirst() {
        EventRing r = ring(10);
        JsonObject d = data("player", "Steve");
        d.addProperty("id", "spoofed");
        JsonObject j = r.publish("join", null, 0, d).toJson();
        assertEquals(BASE, j.get("id").getAsLong());
        assertEquals("join", j.get("type").getAsString());
        assertEquals("1970-01-01T00:00:00Z", j.get("time").getAsString());
        assertEquals("Steve", j.get("player").getAsString());
        assertFalse(j.has("action"));
    }

    @Test
    void typesParse() {
        assertEquals(ALL, EventRing.parseTypes((String) null));
        assertEquals(ALL, EventRing.parseTypes(" "));
        assertEquals(Set.of("join", "leave"), EventRing.parseTypes("JOIN, leave"));
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> EventRing.parseTypes("join,jion,dead"));
        assertEquals("jion, dead", e.getMessage());
        assertEquals(Set.of("death"), EventRing.parseTypes(List.of("death")));
    }
}
