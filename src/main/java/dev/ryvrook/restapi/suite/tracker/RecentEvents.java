package dev.ryvrook.restapi.suite.tracker;

import dev.ryvrook.trackerapi.api.EventKind;
import dev.ryvrook.trackerapi.api.TrackerEvent;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.IntSupplier;

/**
 * The newest TrackerAPI events per kind, for GET /api/tracker/recent. Fed on TrackerAPI's dispatch thread, where
 * {@link #add} only appends to a small deque under one lock. The size per kind is read on every add, so a
 * /restapi reload that lowers it trims on the next event.
 */
public final class RecentEvents {
    private final Map<EventKind, ArrayDeque<TrackerEvent>> byKind = new EnumMap<>(EventKind.class);
    private final IntSupplier perKind;

    public RecentEvents(IntSupplier perKind) {
        this.perKind = perKind;
    }

    public void add(TrackerEvent e) {
        int cap = perKind.getAsInt();
        if (cap <= 0 || e == null || e.kind() == null) {
            return;
        }
        synchronized (byKind) {
            ArrayDeque<TrackerEvent> q = byKind.computeIfAbsent(e.kind(), k -> new ArrayDeque<>());
            q.addLast(e);
            while (q.size() > cap) {
                q.removeFirst();
            }
        }
    }

    /** The newest events of the kinds, or of every kind when kinds is empty, newest first. */
    public List<TrackerEvent> newest(Set<EventKind> kinds, int limit) {
        List<TrackerEvent> all = new ArrayList<>();
        synchronized (byKind) {
            for (Map.Entry<EventKind, ArrayDeque<TrackerEvent>> e : byKind.entrySet()) {
                if (kinds.isEmpty() || kinds.contains(e.getKey())) {
                    all.addAll(e.getValue());
                }
            }
        }
        all.sort(Comparator.comparingLong(TrackerEvent::at).thenComparingLong(TrackerEvent::seq).reversed());
        return all.size() > limit ? all.subList(0, limit) : all;
    }

    /** False while the size per kind is 0. */
    public boolean enabled() {
        return perKind.getAsInt() > 0;
    }

    public int size() {
        synchronized (byKind) {
            return byKind.values().stream().mapToInt(ArrayDeque::size).sum();
        }
    }

    public void clear() {
        synchronized (byKind) {
            byKind.clear();
        }
    }
}
