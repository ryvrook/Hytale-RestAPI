package dev.ryvrook.restapi.events;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * The last events.ring events in memory, with rising ids so a stream that dropped can resume with
 * Last-Event-ID. Ids start at the start time in milliseconds times 1000, so an id from before a server restart
 * is always lower than every id of this run and is seen as a gap instead of being mistaken for a recent event.
 * The ids stay under 2^53, so JavaScript reads them without loss.
 *
 * <p>Publishing never blocks. A stream that reads too slowly to keep up with its queue is closed, and the client
 * resumes from the ring with Last-Event-ID. Listeners such as the webhook dispatcher run under the ring's lock and
 * must only queue work.
 */
public final class EventRing {
    /** Every event type, in the order the docs list them. */
    public static final List<String> TYPES = List.of("join", "ready", "leave", "chat", "death", "command", "world",
            "plugin", "server");

    /**
     * One event.
     *
     * @param id     rising id, the SSE id
     * @param type   one of {@link #TYPES}
     * @param action what happened within the type, such as added or removed for world, or null
     * @param time   epoch millis
     * @param data   the fields of the event
     */
    public record Event(long id, String type, String action, long time, JsonObject data) {
        /** {@code {"id", "type", "action", "time", ...data}}. The data fields never replace the four above. */
        public JsonObject toJson() {
            JsonObject o = new JsonObject();
            o.addProperty("id", id);
            o.addProperty("type", type);
            if (action != null) {
                o.addProperty("action", action);
            }
            o.addProperty("time", Instant.ofEpochMilli(time).toString());
            if (data != null) {
                for (Map.Entry<String, JsonElement> e : data.entrySet()) {
                    if (!o.has(e.getKey())) {
                        o.add(e.getKey(), e.getValue());
                    }
                }
            }
            return o;
        }
    }

    /** What a resume finds. gap is true when events between the given id and the first one here were lost. */
    public record Resume(List<Event> events, boolean gap) {
    }

    private final ArrayDeque<Event> ring = new ArrayDeque<>();
    private final long firstId;
    private final List<Subscriber> subscribers = new CopyOnWriteArrayList<>();
    private final List<Consumer<Event>> listeners = new CopyOnWriteArrayList<>();
    private final Consumer<Throwable> onListenerError;
    private int capacity;
    private long next;

    public EventRing(int capacity, long firstId, Consumer<Throwable> onListenerError) {
        this.capacity = Math.max(1, capacity);
        this.firstId = firstId;
        this.next = firstId;
        this.onListenerError = onListenerError;
    }

    /** An id base for this run, the start time in milliseconds times 1000. */
    public static long idBase(long epochMillis) {
        return epochMillis * 1000L;
    }

    /** Adds an event, hands it to every matching stream and listener and returns it. */
    public synchronized Event publish(String type, String action, long time, JsonObject data) {
        Event e = new Event(next++, type, action, time, data == null ? new JsonObject() : data);
        ring.addLast(e);
        while (ring.size() > capacity) {
            ring.removeFirst();
        }
        for (Subscriber s : subscribers) {
            s.offer(e);
        }
        for (Consumer<Event> l : listeners) {
            try {
                l.accept(e);
            } catch (RuntimeException ex) {
                onListenerError.accept(ex);
            }
        }
        return e;
    }

    public synchronized void resize(int newCapacity) {
        capacity = Math.max(1, newCapacity);
        while (ring.size() > capacity) {
            ring.removeFirst();
        }
    }

    public synchronized int size() {
        return ring.size();
    }

    /** The id the next event gets. */
    public synchronized long nextId() {
        return next;
    }

    /** Called with every event under the ring's lock. Must not block. */
    public void listen(Consumer<Event> listener) {
        listeners.add(listener);
    }

    public void unlisten(Consumer<Event> listener) {
        listeners.remove(listener);
    }

    /** The newest events of the types, oldest first, at most limit. */
    public synchronized List<Event> recent(Set<String> types, int limit) {
        List<Event> out = new ArrayList<>();
        Iterator<Event> it = ring.descendingIterator();
        while (it.hasNext() && out.size() < limit) {
            Event e = it.next();
            if (types.contains(e.type())) {
                out.add(e);
            }
        }
        java.util.Collections.reverse(out);
        return out;
    }

    /** The events after lastId, see {@link Resume}. */
    public synchronized Resume since(long lastId, Set<String> types) {
        List<Event> out = new ArrayList<>();
        boolean known = lastId >= firstId - 1 && lastId < next;
        long oldest = ring.isEmpty() ? next : ring.peekFirst().id();
        boolean gap = !known || oldest > lastId + 1;
        for (Event e : ring) {
            if ((!known || e.id() > lastId) && types.contains(e.type())) {
                out.add(e);
            }
        }
        return new Resume(out, gap);
    }

    /**
     * Opens a stream. With a lastId the events after it come first, taken under the same lock as the
     * subscription so nothing is lost or sent twice between the two.
     *
     * @param lastId the Last-Event-ID, or null to start with new events only
     */
    public synchronized Subscription subscribe(Long lastId, Set<String> types, int queueSize) {
        Resume r = lastId == null ? new Resume(List.of(), false) : since(lastId, types);
        Subscriber s = new Subscriber(types, queueSize);
        subscribers.add(s);
        return new Subscription(r, s);
    }

    /** Ends every open stream, at module stop. */
    public void closeAll() {
        for (Subscriber s : subscribers) {
            s.close();
        }
        subscribers.clear();
    }

    public int streams() {
        return subscribers.size();
    }

    /** An open stream with the events to send first. */
    public final class Subscription implements AutoCloseable {
        private final Resume first;
        private final Subscriber sub;

        Subscription(Resume first, Subscriber sub) {
            this.first = first;
            this.sub = sub;
        }

        public Resume first() {
            return first;
        }

        /**
         * The next event, or null after the timeout. Throws {@link Closed} once the stream was closed, because the
         * module stopped or the reader fell behind.
         */
        public Event next(long timeout, TimeUnit unit) throws InterruptedException, Closed {
            return sub.take(timeout, unit);
        }

        @Override
        public void close() {
            sub.close();
            subscribers.remove(sub);
        }
    }

    /** The stream was closed. overflow is true when the reader fell behind. */
    public static final class Closed extends Exception {
        private final boolean overflow;

        Closed(boolean overflow) {
            super(overflow ? "overflow" : "closed", null, false, false);
            this.overflow = overflow;
        }

        public boolean overflow() {
            return overflow;
        }
    }

    private static final Event END = new Event(-1, "", null, 0, null);

    private static final class Subscriber {
        private final Set<String> types;
        private final BlockingQueue<Event> queue;
        private volatile boolean closed;
        private volatile boolean overflow;

        Subscriber(Set<String> types, int size) {
            this.types = types;
            this.queue = new ArrayBlockingQueue<>(Math.max(2, size) + 1);
        }

        void offer(Event e) {
            if (closed || !types.contains(e.type())) {
                return;
            }
            if (queue.remainingCapacity() <= 1) {
                overflow = true;
                close();
                return;
            }
            queue.offer(e);
        }

        void close() {
            if (!closed) {
                closed = true;
                queue.offer(END);
            }
        }

        Event take(long timeout, TimeUnit unit) throws InterruptedException, Closed {
            Event e = queue.poll(timeout, unit);
            if (e == END || (e == null && closed)) {
                queue.offer(END);
                throw new Closed(overflow);
            }
            return e;
        }
    }

    /**
     * Event types from a comma separated query value. Empty or null means every type. Throws with the unknown
     * names, so a typo is reported instead of silently matching nothing.
     */
    public static Set<String> parseTypes(String csv) {
        if (csv == null || csv.isBlank()) {
            return Set.copyOf(TYPES);
        }
        Set<String> out = new LinkedHashSet<>();
        List<String> unknown = new ArrayList<>();
        for (String part : csv.split("[,\\s]+")) {
            String t = part.trim().toLowerCase(Locale.ROOT);
            if (t.isEmpty()) {
                continue;
            }
            if (TYPES.contains(t)) {
                out.add(t);
            } else {
                unknown.add(t);
            }
        }
        if (!unknown.isEmpty()) {
            throw new IllegalArgumentException(String.join(", ", unknown));
        }
        return out.isEmpty() ? Set.copyOf(TYPES) : Set.copyOf(out);
    }

    /** Types from a JSON list or a comma separated text, for webhooks. */
    public static Set<String> parseTypes(Collection<String> list) {
        return parseTypes(list == null ? null : String.join(",", list));
    }
}
