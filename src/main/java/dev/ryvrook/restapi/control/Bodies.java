package dev.ryvrook.restapi.control;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import dev.ryvrook.restapi.http.ApiException;
import dev.ryvrook.restapi.text.Messages;

import java.util.ArrayList;
import java.util.List;

/**
 * Reads typed fields from a request body. A field that is missing or JSON null reads as null, a field of the
 * wrong type or out of range answers 400 bad_value with the field named, so a typo is never guessed at.
 */
final class Bodies {
    private Bodies() {
    }

    static ApiException bad(String field, String problem) {
        return ApiException.badRequest("bad_value", Messages.get(ControlText.BAD_VALUE, "field", field, "problem", problem));
    }

    private static JsonElement field(JsonObject o, String name) {
        JsonElement e = o == null ? null : o.get(name);
        return e == null || e.isJsonNull() ? null : e;
    }

    static boolean has(JsonObject o, String name) {
        return field(o, name) != null;
    }

    static Integer optInt(JsonObject o, String name, int min, int max) {
        JsonElement e = field(o, name);
        if (e == null) {
            return null;
        }
        String problem = Messages.get(ControlText.MUST_INT, "min", min, "max", max);
        if (!(e instanceof JsonPrimitive p) || !p.isNumber()) {
            throw bad(name, problem);
        }
        double d = p.getAsDouble();
        if (d != Math.rint(d) || d < min || d > max) {
            throw bad(name, problem);
        }
        return (int) d;
    }

    static Double optNumber(JsonObject o, String name, double min, double max) {
        JsonElement e = field(o, name);
        if (e == null) {
            return null;
        }
        if (!(e instanceof JsonPrimitive p) || !p.isNumber() || Double.isNaN(p.getAsDouble())
                || p.getAsDouble() < min || p.getAsDouble() > max) {
            throw bad(name, Messages.get(ControlText.MUST_NUMBER, "min", plain(min), "max", plain(max)));
        }
        return p.getAsDouble();
    }

    static Boolean optBool(JsonObject o, String name) {
        JsonElement e = field(o, name);
        if (e == null) {
            return null;
        }
        if (!(e instanceof JsonPrimitive p) || !p.isBoolean()) {
            throw bad(name, Messages.get(ControlText.MUST_BOOL));
        }
        return p.getAsBoolean();
    }

    static String optText(JsonObject o, String name, int max) {
        JsonElement e = field(o, name);
        if (e == null) {
            return null;
        }
        if (!(e instanceof JsonPrimitive p) || !p.isString() || p.getAsString().length() > max) {
            throw bad(name, Messages.get(ControlText.MUST_TEXT, "max", max));
        }
        return p.getAsString();
    }

    static List<String> optNames(JsonObject o, String name) {
        JsonElement e = field(o, name);
        if (e == null) {
            return null;
        }
        if (!(e instanceof JsonArray a)) {
            throw bad(name, Messages.get(ControlText.MUST_LIST));
        }
        List<String> out = new ArrayList<>();
        for (JsonElement x : a) {
            if (!(x instanceof JsonPrimitive p) || !p.isString() || p.getAsString().isBlank()) {
                throw bad(name, Messages.get(ControlText.MUST_LIST));
            }
            out.add(p.getAsString().trim());
        }
        return out;
    }

    /** 12.0 prints as 12, 0.5 as 0.5. */
    static String plain(double d) {
        return d == Math.rint(d) ? Long.toString((long) d) : Double.toString(d);
    }
}
