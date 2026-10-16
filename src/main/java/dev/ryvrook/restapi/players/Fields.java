package dev.ryvrook.restapi.players;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import dev.ryvrook.restapi.http.ApiException;
import dev.ryvrook.restapi.text.Messages;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/** Reads typed fields from a request body and answers 400 with the field named when one does not fit. */
final class Fields {
    private Fields() {
    }

    private static final Pattern HEX = Pattern.compile("#[0-9a-fA-F]{6}");

    static boolean has(JsonObject o, String name) {
        return o.has(name) && !o.get(name).isJsonNull();
    }

    /** A text field, trimmed. Null when absent and not required. */
    static String text(JsonObject o, String name, boolean required, int max) {
        if (!has(o, name)) {
            if (required) {
                throw missing(name);
            }
            return null;
        }
        JsonElement e = o.get(name);
        if (!e.isJsonPrimitive() || !e.getAsJsonPrimitive().isString()) {
            throw type(name, "text");
        }
        String s = e.getAsString().trim();
        if (required && s.isEmpty()) {
            throw missing(name);
        }
        if (s.length() > max) {
            throw ApiException.badRequest("invalid_field", Messages.get(PlayersText.FIELD_LONG, "field", name, "max", max));
        }
        return s;
    }

    /** A whole number in range, or the fallback when absent. A null fallback makes it required. */
    static Integer integer(JsonObject o, String name, Integer fallback, long min, long max) {
        if (!has(o, name)) {
            if (fallback == null) {
                throw missing(name);
            }
            return fallback;
        }
        JsonElement e = o.get(name);
        if (!e.isJsonPrimitive() || !e.getAsJsonPrimitive().isNumber()) {
            throw type(name, "a whole number");
        }
        double d = e.getAsDouble();
        if (d != Math.rint(d)) {
            throw type(name, "a whole number");
        }
        if (d < min || d > max) {
            throw range(name, min, max);
        }
        return (int) d;
    }

    /** A finite number, or the fallback when absent. A null fallback makes it required. */
    static Double number(JsonObject o, String name, Double fallback, double min, double max) {
        if (!has(o, name)) {
            if (fallback == null) {
                throw missing(name);
            }
            return fallback;
        }
        JsonElement e = o.get(name);
        if (!e.isJsonPrimitive() || !e.getAsJsonPrimitive().isNumber()) {
            throw type(name, "a number");
        }
        double d = e.getAsDouble();
        if (!Double.isFinite(d)) {
            throw type(name, "a number");
        }
        if (d < min || d > max) {
            throw range(name, fmt(min), fmt(max));
        }
        return d;
    }

    static Boolean bool(JsonObject o, String name, Boolean fallback) {
        if (!has(o, name)) {
            if (fallback == null) {
                throw missing(name);
            }
            return fallback;
        }
        JsonElement e = o.get(name);
        if (!e.isJsonPrimitive() || !e.getAsJsonPrimitive().isBoolean()) {
            throw type(name, "true or false");
        }
        return e.getAsBoolean();
    }

    /** A list of text entries, or null when absent. */
    static List<String> strings(JsonObject o, String name, boolean required) {
        if (!has(o, name)) {
            if (required) {
                throw missing(name);
            }
            return null;
        }
        JsonElement e = o.get(name);
        if (!e.isJsonArray()) {
            throw type(name, "a list of text entries");
        }
        JsonArray a = e.getAsJsonArray();
        List<String> out = new ArrayList<>(a.size());
        for (JsonElement x : a) {
            if (!x.isJsonPrimitive() || !((JsonPrimitive) x).isString()) {
                throw type(name, "a list of text entries");
            }
            String s = x.getAsString().trim();
            if (!s.isEmpty()) {
                out.add(s);
            }
        }
        return out;
    }

    /** A color such as #ffaa00, or null when absent. */
    static String color(JsonObject o) {
        String c = text(o, "color", false, 7);
        if (c == null || c.isEmpty()) {
            return null;
        }
        if (!HEX.matcher(c).matches()) {
            throw ApiException.badRequest("invalid_field", Messages.get(PlayersText.COLOR));
        }
        return c.toLowerCase(java.util.Locale.ROOT);
    }

    static ApiException missing(String name) {
        return ApiException.badRequest("missing_field", Messages.get(PlayersText.FIELD_MISSING, "field", name));
    }

    static ApiException type(String name, String type) {
        return ApiException.badRequest("invalid_field", Messages.get(PlayersText.FIELD_TYPE, "field", name, "type", type));
    }

    static ApiException range(String name, Object min, Object max) {
        return ApiException.badRequest("invalid_field", Messages.get(PlayersText.FIELD_RANGE, "field", name, "min", min, "max", max));
    }

    private static String fmt(double d) {
        return d == Math.rint(d) && Math.abs(d) < 1e15 ? String.valueOf((long) d) : String.valueOf(d);
    }
}
