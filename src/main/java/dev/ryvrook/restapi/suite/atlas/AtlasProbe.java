package dev.ryvrook.restapi.suite.atlas;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/**
 * Asks the Atlas web server for {@code /api/health} on the loopback address, the public part only, with no token.
 * One request with a 1.5 second limit, answered as {@code {reachable, status, ok, version}}.
 */
final class AtlasProbe {
    private AtlasProbe() {
    }

    /** The loopback address for a bind, the bind itself when it names one address. */
    static String host(String bind) {
        if (bind == null || bind.isBlank() || bind.equals("0.0.0.0") || bind.equals("::") || bind.equals("*")) {
            return "127.0.0.1";
        }
        return bind.contains(":") && !bind.startsWith("[") ? "[" + bind + "]" : bind;
    }

    static String base(String bind, int port, String basePath) {
        String path = basePath == null ? "" : basePath.trim();
        if (path.endsWith("/")) {
            path = path.substring(0, path.length() - 1);
        }
        if (!path.isEmpty() && !path.startsWith("/")) {
            path = "/" + path;
        }
        return "http://" + host(bind) + ":" + port + path;
    }

    static JsonObject health(String bind, int port, String basePath) {
        JsonObject o = new JsonObject();
        // A client per probe, closed before the answer goes back, so no selector thread outlives the request.
        try (HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofMillis(1500))
                .followRedirects(HttpClient.Redirect.NEVER).build()) {
            HttpRequest req = HttpRequest.newBuilder(URI.create(base(bind, port, basePath) + "/api/health"))
                    .timeout(Duration.ofMillis(1500)).GET().build();
            HttpResponse<String> r = client.send(req, HttpResponse.BodyHandlers.ofString());
            o.addProperty("reachable", true);
            o.addProperty("httpStatus", r.statusCode());
            try {
                JsonElement e = JsonParser.parseString(r.body());
                if (e.isJsonObject()) {
                    JsonObject b = e.getAsJsonObject();
                    for (String k : new String[]{"ok", "version", "status"}) {
                        if (b.has(k)) {
                            o.add(k, b.get(k));
                        }
                    }
                }
            } catch (RuntimeException ignored) {
                // Not JSON, the status code says enough.
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            o.addProperty("reachable", false);
        } catch (Exception e) {
            o.addProperty("reachable", false);
            o.addProperty("error", e.getClass().getSimpleName());
        }
        return o;
    }
}
