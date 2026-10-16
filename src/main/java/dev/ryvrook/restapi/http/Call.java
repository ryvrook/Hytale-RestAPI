package dev.ryvrook.restapi.http;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.ryvrook.restapi.auth.ApiKey;

import java.util.Map;

/**
 * What a handler gets, the request, the key that made it (null on an open route called without one), the
 * client address after the trusted proxies, and the path parameters.
 */
public record Call(Request request, ApiKey key, String client, Map<String, String> params) {
    public Call {
        params = Map.copyOf(params);
    }

    /** A path parameter, decoded. */
    public String param(String name) {
        String v = params.get(name);
        return v == null ? null : Request.decode(v);
    }

    /** A query parameter, or the fallback. */
    public String query(String name, String fallback) {
        return request.queryParams().getOrDefault(name, fallback);
    }

    /** The body as a JSON object, or 400 invalid_json. An empty body reads as an empty object. */
    public JsonObject jsonBody() {
        String text = request.bodyText().trim();
        if (text.isEmpty()) {
            return new JsonObject();
        }
        try {
            JsonElement e = JsonParser.parseString(text);
            if (e.isJsonObject()) {
                return e.getAsJsonObject();
            }
        } catch (RuntimeException ignored) {
            // Answered below.
        }
        throw ApiException.badRequest("invalid_json", "The body must be one JSON object");
    }

    /** The key name for the audit log and replies, or "anonymous". */
    public String keyName() {
        return key == null ? "anonymous" : key.name();
    }
}
