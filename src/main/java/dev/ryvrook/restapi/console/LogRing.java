package dev.ryvrook.restapi.console;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;
import java.util.logging.Level;
import java.util.regex.Pattern;

/**
 * The newest log lines in memory, oldest dropped first. Each line gets an id that only grows, which the stream
 * uses as its event id so a client can resume with Last-Event-ID. ANSI colour codes are stripped and RestAPI key
 * tokens are cut down to their id before a line is stored, so no reader of the log ever sees a whole key.
 *
 * <p>{@link #add} runs on whatever thread logged and only copies into the ring under a short lock.
 */
public final class LogRing {
    /** One stored line. Console replies have the level RAW and count as INFO for filters. */
    public record Line(long id, long time, String level, String logger, String message) {
    }

    public static final String RAW = "RAW";
    private static final Pattern ANSI = Pattern.compile("\u001b\\[[;\\d]*[A-Za-z]");
    private static final Pattern TOKEN = Pattern.compile("rapi_([0-9a-f]{8})_[A-Za-z0-9_-]{43}");
    private static final List<Level> NAMED = List.of(Level.SEVERE, Level.WARNING, Level.INFO, Level.CONFIG,
            Level.FINE, Level.FINER, Level.FINEST);

    private final ReentrantLock lock = new ReentrantLock();
    private final Condition grew = lock.newCondition();
    private Line[] ring;
    private int start;
    private int size;
    private long nextId = 1;
    private boolean closed;

    public LogRing(int capacity) {
        ring = new Line[Math.max(1, capacity)];
    }

    /** Stores a line. Levels below minLevel are dropped, a RAW line counts as INFO. */
    public void add(long time, Level level, String logger, String message, Level minLevel) {
        int value = level == null || level == Level.ALL ? Level.INFO.intValue() : level.intValue();
        if (minLevel != null && value < minLevel.intValue()) {
            return;
        }
        String name = level == null || level == Level.ALL ? RAW : levelName(level);
        String text = clean(message);
        lock.lock();
        try {
            Line line = new Line(nextId++, time, name, logger == null ? "" : logger, text);
            if (size < ring.length) {
                ring[(start + size) % ring.length] = line;
                size++;
            } else {
                ring[start] = line;
                start = (start + 1) % ring.length;
            }
            grew.signalAll();
        } finally {
            lock.unlock();
        }
    }

    /** ANSI codes out, key tokens cut to {@code rapi_<id>_...}. */
    static String clean(String message) {
        String t = message == null ? "" : message;
        if (t.indexOf('\u001b') >= 0) {
            t = ANSI.matcher(t).replaceAll("");
        }
        if (t.contains("rapi_")) {
            t = TOKEN.matcher(t).replaceAll("rapi_$1_...");
        }
        return t;
    }

    /**
     * Lines at or above minLevel after the given id and at or after the given time, newest n of them, oldest
     * first. A sinceId of 0 and a sinceTime of 0 take everything kept.
     */
    public List<Line> tail(int n, Level minLevel, long sinceId, long sinceTime) {
        List<Line> out = new ArrayList<>();
        lock.lock();
        try {
            for (int i = size - 1; i >= 0 && out.size() < n; i--) {
                Line l = ring[(start + i) % ring.length];
                if (l.id() <= sinceId) {
                    break;
                }
                if (l.time() >= sinceTime && passes(l, minLevel)) {
                    out.add(l);
                }
            }
        } finally {
            lock.unlock();
        }
        java.util.Collections.reverse(out);
        return out;
    }

    /** Every kept line after the id that passes the level, oldest first, at most max. */
    public List<Line> after(long id, Level minLevel, int max) {
        List<Line> out = new ArrayList<>();
        lock.lock();
        try {
            for (int i = 0; i < size && out.size() < max; i++) {
                Line l = ring[(start + i) % ring.length];
                if (l.id() > id && passes(l, minLevel)) {
                    out.add(l);
                }
            }
        } finally {
            lock.unlock();
        }
        return out;
    }

    /**
     * Waits until a line with an id above the given one exists, the ring is closed, or the time runs out. True
     * when there is something new.
     */
    public boolean await(long id, long millis) throws InterruptedException {
        long nanos = TimeUnit.MILLISECONDS.toNanos(millis);
        lock.lock();
        try {
            while (!closed && nextId - 1 <= id) {
                if (nanos <= 0) {
                    return false;
                }
                nanos = grew.awaitNanos(nanos);
            }
            return nextId - 1 > id;
        } finally {
            lock.unlock();
        }
    }

    /** The id of the newest line, 0 when none was stored yet. */
    public long lastId() {
        lock.lock();
        try {
            return nextId - 1;
        } finally {
            lock.unlock();
        }
    }

    public int size() {
        lock.lock();
        try {
            return size;
        } finally {
            lock.unlock();
        }
    }

    public int capacity() {
        lock.lock();
        try {
            return ring.length;
        } finally {
            lock.unlock();
        }
    }

    /** Changes the capacity and keeps the newest lines that fit. Ids carry on. */
    public void resize(int capacity) {
        lock.lock();
        try {
            int cap = Math.max(1, capacity);
            if (cap == ring.length) {
                return;
            }
            Line[] next = new Line[cap];
            int keep = Math.min(size, cap);
            for (int i = 0; i < keep; i++) {
                next[i] = ring[(start + size - keep + i) % ring.length];
            }
            ring = next;
            start = 0;
            size = keep;
        } finally {
            lock.unlock();
        }
    }

    /** Wakes every stream so it ends. Lines are kept and {@link #open()} starts taking waiters again. */
    public void close() {
        lock.lock();
        try {
            closed = true;
            grew.signalAll();
        } finally {
            lock.unlock();
        }
    }

    public void open() {
        lock.lock();
        try {
            closed = false;
        } finally {
            lock.unlock();
        }
    }

    public boolean closed() {
        lock.lock();
        try {
            return closed;
        } finally {
            lock.unlock();
        }
    }

    private static boolean passes(Line l, Level min) {
        if (min == null) {
            return true;
        }
        Level lv = RAW.equals(l.level()) ? Level.INFO : parseLevel(l.level());
        return lv == null || lv.intValue() >= min.intValue();
    }

    /** SEVERE, WARNING and the rest by name, WARN also reads as WARNING. Null for anything else. */
    public static Level parseLevel(String name) {
        if (name == null) {
            return null;
        }
        String n = name.trim().toUpperCase(Locale.ROOT);
        if (n.equals("WARN")) {
            return Level.WARNING;
        }
        if (n.equals("ERROR")) {
            return Level.SEVERE;
        }
        for (Level l : NAMED) {
            if (l.getName().equals(n)) {
                return l;
            }
        }
        return null;
    }

    static String levelName(Level level) {
        for (Level l : NAMED) {
            if (l.intValue() == level.intValue()) {
                return l.getName();
            }
        }
        return level.getName();
    }
}
