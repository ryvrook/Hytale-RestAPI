package dev.ryvrook.restapi.console;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.LogRecord;

import static org.junit.jupiter.api.Assertions.*;

class LogRingTest {
    private static void add(LogRing r, long time, Level level, String msg) {
        r.add(time, level, "test", msg, null);
    }

    @Test
    void theOldestLinesGoFirstAndIdsKeepGrowing() {
        LogRing r = new LogRing(3);
        for (int i = 1; i <= 5; i++) {
            add(r, i, Level.INFO, "line " + i);
        }
        List<LogRing.Line> lines = r.tail(10, null, 0, 0);
        assertEquals(List.of("line 3", "line 4", "line 5"), lines.stream().map(LogRing.Line::message).toList());
        assertEquals(List.of(3L, 4L, 5L), lines.stream().map(LogRing.Line::id).toList());
        assertEquals(5, r.lastId());
        assertEquals(3, r.size());
    }

    @Test
    void colourCodesAreStrippedAndKeysCutToTheirId() {
        LogRing r = new LogRing(10);
        add(r, 1, Level.ALL, "\u001b[32mMade the key\u001b[0m rapi_1a2b3c4d_" + "A".repeat(43) + " now");
        LogRing.Line l = r.tail(1, null, 0, 0).get(0);
        assertEquals("Made the key rapi_1a2b3c4d_... now", l.message());
        assertEquals(LogRing.RAW, l.level());
    }

    @Test
    void dashboardLoginTokensAreCutToo() {
        LogRing r = new LogRing(10);
        add(r, 1, Level.ALL, "[RestAPI] http://127.0.0.1:8125/dashboard/login?t=rdl_" + "x-Y_".repeat(10) + "abc");
        assertEquals("[RestAPI] http://127.0.0.1:8125/dashboard/login?t=rdl_...", r.tail(1, null, 0, 0).get(0).message());
    }

    @Test
    void minLevelDropsAtAddAndFiltersAtRead() {
        LogRing r = new LogRing(10);
        r.add(1, Level.FINE, "x", "fine", Level.INFO);
        r.add(2, Level.INFO, "x", "info", Level.INFO);
        r.add(3, Level.WARNING, "x", "warn", Level.INFO);
        r.add(4, Level.ALL, "x", "raw", Level.INFO);
        assertEquals(List.of("info", "warn", "raw"), r.tail(10, null, 0, 0).stream().map(LogRing.Line::message).toList());
        assertEquals(List.of("warn"), r.tail(10, Level.WARNING, 0, 0).stream().map(LogRing.Line::message).toList());
        r.add(5, Level.ALL, "x", "dropped", Level.OFF);
        assertEquals(3, r.size(), "OFF keeps nothing");
    }

    @Test
    void tailTakesSinceIdOrSinceTime() {
        LogRing r = new LogRing(10);
        for (int i = 1; i <= 6; i++) {
            add(r, i * 1000L, Level.INFO, "l" + i);
        }
        assertEquals(List.of("l5", "l6"), r.tail(10, null, 4, 0).stream().map(LogRing.Line::message).toList());
        assertEquals(List.of("l3", "l4", "l5", "l6"), r.tail(10, null, 0, 3000).stream().map(LogRing.Line::message).toList());
        assertEquals(List.of("l5", "l6"), r.tail(2, null, 0, 0).stream().map(LogRing.Line::message).toList());
        assertEquals(List.of("l2", "l3"), r.after(1, null, 2).stream().map(LogRing.Line::message).toList());
    }

    @Test
    void awaitWakesOnANewLineAndOnClose() throws Exception {
        LogRing r = new LogRing(10);
        assertFalse(r.await(0, 20));
        CompletableFuture<Boolean> waiting = CompletableFuture.supplyAsync(() -> {
            try {
                return r.await(0, 5_000);
            } catch (InterruptedException e) {
                throw new RuntimeException(e);
            }
        });
        Thread.sleep(50);
        add(r, 1, Level.INFO, "hello");
        assertTrue(waiting.get(2, TimeUnit.SECONDS));

        CompletableFuture<Boolean> closing = CompletableFuture.supplyAsync(() -> {
            try {
                return r.await(1, 5_000);
            } catch (InterruptedException e) {
                throw new RuntimeException(e);
            }
        });
        Thread.sleep(50);
        r.close();
        assertFalse(closing.get(2, TimeUnit.SECONDS));
        assertTrue(r.closed());
        r.open();
        assertFalse(r.closed());
    }

    @Test
    void resizeKeepsTheNewest() {
        LogRing r = new LogRing(5);
        for (int i = 1; i <= 5; i++) {
            add(r, i, Level.INFO, "l" + i);
        }
        r.resize(2);
        assertEquals(List.of("l4", "l5"), r.tail(10, null, 0, 0).stream().map(LogRing.Line::message).toList());
        r.resize(4);
        add(r, 6, Level.INFO, "l6");
        assertEquals(List.of("l4", "l5", "l6"), r.tail(10, null, 0, 0).stream().map(LogRing.Line::message).toList());
        assertEquals(6, r.lastId());
    }

    @Test
    void levelsParseByName() {
        assertEquals(Level.WARNING, LogRing.parseLevel("warn"));
        assertEquals(Level.SEVERE, LogRing.parseLevel("ERROR"));
        assertEquals(Level.FINE, LogRing.parseLevel(" fine "));
        assertNull(LogRing.parseLevel("loud"));
    }

    @Test
    void theTapStoresNothingAndCopiesIntoTheRing() {
        LogRing r = new LogRing(10);
        StringBuilder echoed = new StringBuilder();
        LogTap tap = new LogTap(r, () -> Level.INFO, (thread, text) -> echoed.append(thread).append('|').append(text));
        LogRecord rec = new LogRecord(Level.INFO, "Player {0} joined");
        rec.setParameters(new Object[]{"Steve"});
        rec.setLoggerName("Universe");
        assertTrue(tap.add(rec));
        assertEquals(0, tap.size());
        LogRecord raw = new LogRecord(Level.ALL, "[RestAPI:admin] hi");
        tap.accept(raw, "pool-1 -- Running: say hi");
        LogRecord failed = new LogRecord(Level.SEVERE, "It broke");
        failed.setThrown(new IllegalStateException("bad state"));
        tap.add(failed);
        List<LogRing.Line> lines = r.tail(10, null, 0, 0);
        assertEquals("Player Steve joined", lines.get(0).message());
        assertEquals("Universe", lines.get(0).logger());
        assertEquals("[RestAPI:admin] hi", lines.get(1).message());
        assertEquals("It broke\njava.lang.IllegalStateException: bad state", lines.get(2).message());
        assertEquals("pool-1 -- Running: say hi|[RestAPI:admin] hi", echoed.toString());
        assertTrue(tap.add(null));
    }

    @Test
    void twoEmptyTapsAreNotEqualSoUnsubscribeRemovesTheRightOne() {
        LogRing r = new LogRing(1);
        LogTap a = new LogTap(r, () -> Level.INFO, null);
        LogTap b = new LogTap(r, () -> Level.INFO, null);
        assertNotEquals(a, b);
        assertEquals(a, a);
    }
}
