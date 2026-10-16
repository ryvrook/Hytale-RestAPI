package dev.ryvrook.restapi.suite;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import dev.ryvrook.restapi.http.ApiException;

import java.lang.reflect.Array;
import java.lang.reflect.RecordComponent;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.Collection;
import java.util.Map;
import java.util.UUID;

/** Small JSON helpers the suite routes share. */
public final class Json {
    private Json() {
    }

    /** A UUID, or null when the text is not one. */
    public static UUID uuid(String text) {
        if (text == null || text.length() != 36) {
            return null;
        }
        try {
            return UUID.fromString(text);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    public static int integer(String raw, String name) {
        try {
            return Integer.parseInt(raw.trim());
        } catch (NumberFormatException e) {
            throw ApiException.badRequest("bad_number", SuiteText.get(SuiteText.BAD_NUMBER, "name", name, "value", raw));
        }
    }

    public static long whole(String raw, String name) {
        try {
            return Long.parseLong(raw.trim());
        } catch (NumberFormatException e) {
            throw ApiException.badRequest("bad_number", SuiteText.get(SuiteText.BAD_NUMBER, "name", name, "value", raw));
        }
    }

    /** Epoch millis or an ISO instant, or the fallback when missing. */
    public static long time(String raw, String name, long fallback) {
        if (raw == null || raw.isBlank()) {
            return fallback;
        }
        String s = raw.trim();
        if (s.chars().allMatch(Character::isDigit)) {
            try {
                return Long.parseLong(s);
            } catch (NumberFormatException e) {
                // Too long, answered below.
            }
        } else {
            try {
                return Instant.parse(s).toEpochMilli();
            } catch (DateTimeParseException e) {
                // Answered below.
            }
        }
        throw ApiException.badRequest("bad_time", SuiteText.get(SuiteText.BAD_TIME, "name", name));
    }

    public static JsonElement str(Object o) {
        return o == null ? JsonNull.INSTANCE : new JsonPrimitive(String.valueOf(o));
    }

    /** A map of names to numbers, in the map's order. */
    public static JsonObject numbers(Map<String, ? extends Number> map) {
        JsonObject o = new JsonObject();
        if (map != null) {
            map.forEach(o::addProperty);
        }
        return o;
    }

    public static JsonArray strings(Collection<?> items) {
        JsonArray a = new JsonArray();
        if (items != null) {
            for (Object x : items) {
                a.add(x == null ? JsonNull.INSTANCE : new JsonPrimitive(String.valueOf(x)));
            }
        }
        return a;
    }

    /**
     * Any value as JSON, records by their components, enums by name, maps and collections by their items. Used
     * for the TrackerAPI event records, which add fields over time, so the answer follows the record without a
     * change here. Stops at depth 6.
     */
    public static JsonElement of(Object v) {
        return of(v, 0);
    }

    private static JsonElement of(Object v, int depth) {
        if (v == null || depth > 6) {
            return JsonNull.INSTANCE;
        }
        if (v instanceof JsonElement e) {
            return e;
        }
        if (v instanceof Boolean b) {
            return new JsonPrimitive(b);
        }
        if (v instanceof Number n) {
            if (n instanceof Double d && (d.isNaN() || d.isInfinite()) || n instanceof Float f && (f.isNaN() || f.isInfinite())) {
                return JsonNull.INSTANCE;
            }
            return new JsonPrimitive(n);
        }
        if (v instanceof CharSequence || v instanceof UUID || v instanceof Character) {
            return new JsonPrimitive(v.toString());
        }
        if (v instanceof Enum<?> e) {
            return new JsonPrimitive(e.name().toLowerCase(java.util.Locale.ROOT));
        }
        if (v instanceof Map<?, ?> m) {
            JsonObject o = new JsonObject();
            m.forEach((k, x) -> o.add(String.valueOf(k), of(x, depth + 1)));
            return o;
        }
        if (v instanceof Iterable<?> it) {
            JsonArray a = new JsonArray();
            for (Object x : it) {
                a.add(of(x, depth + 1));
            }
            return a;
        }
        if (v.getClass().isArray()) {
            JsonArray a = new JsonArray();
            int n = Math.min(Array.getLength(v), 4096);
            for (int i = 0; i < n; i++) {
                a.add(of(Array.get(v, i), depth + 1));
            }
            return a;
        }
        if (v.getClass().isRecord()) {
            JsonObject o = new JsonObject();
            for (RecordComponent c : v.getClass().getRecordComponents()) {
                try {
                    o.add(c.getName(), of(c.getAccessor().invoke(v), depth + 1));
                } catch (ReflectiveOperationException | RuntimeException e) {
                    o.add(c.getName(), JsonNull.INSTANCE);
                }
            }
            return o;
        }
        return new JsonPrimitive(v.toString());
    }

    /** A page of a list with the total, as every suite list answers it. */
    public static JsonObject page(String field, JsonArray rows, int total, int limit, int offset) {
        JsonObject o = new JsonObject();
        o.addProperty("total", total);
        o.addProperty("limit", limit);
        o.addProperty("offset", offset);
        o.add(field, rows);
        return o;
    }
}
