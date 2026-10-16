package dev.ryvrook.restapi.http;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

/** One answer. JSON answers are compact UTF-8. */
public record Response(int status, String contentType, byte[] body, Map<String, String> headers) {
    public static final String JSON = "application/json; charset=utf-8";
    public static final String TEXT = "text/plain; charset=utf-8";
    private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().serializeNulls().create();

    public Response {
        body = body == null ? new byte[0] : body;
        headers = headers == null ? Map.of() : Map.copyOf(headers);
    }

    public static Response json(int status, JsonElement value) {
        return new Response(status, JSON, (GSON.toJson(value) + "\n").getBytes(StandardCharsets.UTF_8), Map.of());
    }

    public static Response ok(JsonElement value) {
        return json(200, value);
    }

    public static Response text(int status, String text) {
        return new Response(status, TEXT, text.getBytes(StandardCharsets.UTF_8), Map.of());
    }

    /** {@code {"error": code, "message": message}} with the status. */
    public static Response error(int status, String code, String message) {
        JsonObject o = new JsonObject();
        o.addProperty("error", code);
        o.addProperty("message", message);
        return json(status, o);
    }

    public static Response noContent() {
        return new Response(204, null, new byte[0], Map.of());
    }

    /** A copy with one more header. */
    public Response with(String name, String value) {
        Map<String, String> h = new LinkedHashMap<>(headers);
        h.put(name, value);
        return new Response(status, contentType, body, h);
    }

    public String bodyText() {
        return new String(body, StandardCharsets.UTF_8);
    }
}
