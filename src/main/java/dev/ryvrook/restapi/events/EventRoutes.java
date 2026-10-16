package dev.ryvrook.restapi.events;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import dev.ryvrook.restapi.auth.Scope;
import dev.ryvrook.restapi.http.Access;
import dev.ryvrook.restapi.http.ApiException;
import dev.ryvrook.restapi.http.Call;
import dev.ryvrook.restapi.http.Response;
import dev.ryvrook.restapi.http.Route;
import dev.ryvrook.restapi.http.RouteDoc;
import dev.ryvrook.restapi.text.Messages;

import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/** GET /api/events as Server Sent Events and GET /api/events/recent as JSON. */
final class EventRoutes {
    /** Events a stream may fall behind by before it is closed and has to resume. */
    static final int STREAM_QUEUE = 1024;

    private final EventRing ring;

    EventRoutes(EventRing ring) {
        this.ring = ring;
    }

    List<Route> routes() {
        RouteDoc.Param types = new RouteDoc.Param("types", "Comma separated event types, " + String.join(", ", EventRing.TYPES)
                + ". Every type when left out.");
        return List.of(
                Route.of(EventsModule.GROUP, "GET", "/api/events", Scope.EVENTS, Access.READ,
                        "A Server Sent Events stream with one event per engine happening. The SSE event name is the type and the id resumes the stream through Last-Event-ID.",
                        this::stream)
                        .doc(new RouteDoc(List.of(types,
                                new RouteDoc.Param("since", "An event id to resume after, for clients that cannot send Last-Event-ID.")),
                                null, null, List.of("unknown_type", "bad_since"))),
                Route.of(EventsModule.GROUP, "GET", "/api/events/recent", Scope.EVENTS, Access.READ,
                        "The newest events from the ring as JSON, oldest first.", this::recent)
                        .doc(new RouteDoc(List.of(types,
                                new RouteDoc.Param("limit", "How many, 1 to events.ring. 100 when left out."),
                                new RouteDoc.Param("since", "Only events after this id. The answer says gap true when some were already dropped from the ring.")),
                                null,
                                "{\"events\":[{\"id\":1760600000000001,\"type\":\"join\",\"time\":\"2026-10-16T08:00:00Z\",\"player\":\"Steve\",\"uuid\":\"0d6a...\",\"world\":\"default\"}],\"gap\":false,\"nextId\":1760600000000002}",
                                List.of("unknown_type", "bad_limit", "bad_since"))));
    }

    static Set<String> types(Call call) {
        try {
            return EventRing.parseTypes(call.query("types", null));
        } catch (IllegalArgumentException e) {
            throw ApiException.badRequest("unknown_type", Messages.get(EventsText.ERR_TYPES, "types", e.getMessage(),
                    "known", String.join(", ", EventRing.TYPES)));
        }
    }

    static Long since(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return Long.parseLong(raw.trim());
        } catch (NumberFormatException e) {
            throw ApiException.badRequest("bad_since", Messages.get(EventsText.ERR_SINCE));
        }
    }

    private Response stream(Call call) {
        Set<String> types = types(call);
        String header = call.request().header("last-event-id");
        Long last = since(header != null && !header.isBlank() ? header : call.query("since", null));
        return Response.stream(sink -> {
            try (EventRing.Subscription sub = ring.subscribe(last, types, STREAM_QUEUE)) {
                sink.comment("RestAPI events, types " + String.join(",", EventRing.TYPES.stream().filter(types::contains).toList()));
                if (last != null && sub.first().gap()) {
                    JsonObject g = new JsonObject();
                    g.addProperty("after", last);
                    sink.event("gap", null, g.toString());
                }
                for (EventRing.Event e : sub.first().events()) {
                    sink.event(e.type(), Long.toString(e.id()), e.toJson().toString());
                }
                long keepAlive = EventsSettings.KEEP_ALIVE_SECONDS.get();
                while (true) {
                    EventRing.Event e = sub.next(keepAlive, TimeUnit.SECONDS);
                    if (e == null) {
                        sink.comment("keep alive");
                    } else {
                        sink.event(e.type(), Long.toString(e.id()), e.toJson().toString());
                    }
                }
            } catch (EventRing.Closed c) {
                if (c.overflow()) {
                    JsonObject o = new JsonObject();
                    o.addProperty("message", Messages.get(EventsText.STREAM_OVERFLOW));
                    sink.event("overflow", null, o.toString());
                }
            }
        });
    }

    private Response recent(Call call) {
        Set<String> types = types(call);
        int max = EventsSettings.RING.get();
        int limit;
        try {
            limit = Integer.parseInt(call.query("limit", "100").trim());
        } catch (NumberFormatException e) {
            limit = -1;
        }
        if (limit < 1 || limit > max) {
            throw ApiException.badRequest("bad_limit", Messages.get(EventsText.ERR_LIMIT, "max", max));
        }
        Long since = since(call.query("since", null));
        List<EventRing.Event> events;
        boolean gap = false;
        if (since != null) {
            EventRing.Resume r = ring.since(since, types);
            events = r.events().size() > limit ? r.events().subList(0, limit) : r.events();
            gap = r.gap();
        } else {
            events = ring.recent(types, limit);
        }
        JsonObject o = new JsonObject();
        JsonArray list = new JsonArray();
        events.forEach(e -> list.add(e.toJson()));
        o.add("events", list);
        o.addProperty("gap", gap);
        o.addProperty("nextId", ring.nextId());
        return Response.ok(o);
    }
}
