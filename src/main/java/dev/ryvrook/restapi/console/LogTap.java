package dev.ryvrook.restapi.console;

import com.hypixel.hytale.logger.backend.HytaleLoggerBackend;

import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BiConsumer;
import java.util.function.Supplier;
import java.util.logging.Formatter;
import java.util.logging.Level;
import java.util.logging.LogRecord;

/**
 * RestAPI's subscription to the engine log. {@code HytaleLoggerBackend.subscribe} takes a list and calls
 * {@code add} with every record that reaches the root logger, on the thread that logged. This list stores
 * nothing, {@code add} copies the record into the {@link LogRing} and answers true, so the list never grows.
 *
 * <p>Console replies, records at level ALL written through {@code rawLog}, also go to the echo consumer with the
 * name of the thread that wrote them, which lets a command run pick up what it sent to the console.
 */
public final class LogTap extends CopyOnWriteArrayList<LogRecord> {
    private static final Formatter FORMAT = new Formatter() {
        @Override
        public String format(LogRecord record) {
            return formatMessage(record);
        }
    };

    private final transient LogRing ring;
    private final transient Supplier<Level> minLevel;
    private final transient BiConsumer<String, String> echo;
    private transient volatile boolean subscribed;

    /**
     * @param ring     where lines go
     * @param minLevel the lowest level kept, read for every record so a reload applies at once
     * @param echo     told about console replies with the thread name and the text, or null
     */
    public LogTap(LogRing ring, Supplier<Level> minLevel, BiConsumer<String, String> echo) {
        this.ring = ring;
        this.minLevel = minLevel;
        this.echo = echo;
    }

    @Override
    public boolean add(LogRecord record) {
        try {
            accept(record, Thread.currentThread().getName());
        } catch (RuntimeException ignored) {
            // A log line that could not be copied must never break the code that logged it.
        }
        return true;
    }

    /** The work of add, with the thread name passed in so tests can drive it. */
    void accept(LogRecord record, String threadName) {
        if (record == null) {
            return;
        }
        String text = text(record);
        ring.add(record.getMillis(), record.getLevel(), record.getLoggerName(), text, minLevel.get());
        if (echo != null && record.getLevel() == Level.ALL) {
            echo.accept(threadName, LogRing.clean(text));
        }
    }

    static String text(LogRecord record) {
        String msg;
        try {
            msg = FORMAT.formatMessage(record);
        } catch (RuntimeException e) {
            msg = String.valueOf(record.getMessage());
        }
        if (msg == null) {
            msg = "";
        }
        Throwable t = record.getThrown();
        if (t != null) {
            msg = msg + "\n" + t;
        }
        return msg;
    }

    /** Joins the engine log. Calling it twice subscribes once. */
    public synchronized void subscribe() {
        if (!subscribed) {
            HytaleLoggerBackend.subscribe(this);
            subscribed = true;
        }
    }

    public synchronized void unsubscribe() {
        if (subscribed) {
            HytaleLoggerBackend.unsubscribe(this);
            subscribed = false;
        }
    }

    // A list that stores nothing is equal only to itself, so the backend's remove finds this exact object.
    @Override
    public boolean equals(Object o) {
        return this == o;
    }

    @Override
    public int hashCode() {
        return System.identityHashCode(this);
    }
}
