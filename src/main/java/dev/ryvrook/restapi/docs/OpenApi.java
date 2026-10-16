package dev.ryvrook.restapi.docs;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import dev.ryvrook.restapi.http.Access;
import dev.ryvrook.restapi.http.Route;
import dev.ryvrook.restapi.http.RouteDoc;
import dev.ryvrook.restapi.siblings.Sibling;
import dev.ryvrook.restapi.text.Messages;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Writes the OpenAPI 3.1 document from the mounted routes. Pure, so the tests build it from a list of routes with
 * no server. Each route becomes one operation with its group as the tag, its scope in {@code x-scope} and its
 * access in {@code x-access}. Examples in a {@link RouteDoc} are parsed and turned into schemas, so the schema of
 * an answer is exactly as detailed as its example.
 *
 * <p>An example that is not JSON is served as text. One that starts with {@code event:}, {@code id:} or
 * {@code data:} is marked as {@code text/event-stream}, which is how the stream routes say what they send, and
 * one that starts with {@code <} as {@code text/html}.
 */
public final class OpenApi {
    private OpenApi() {
    }

    public static final String VERSION = "3.1.0";
    private static final Pattern PARAM = Pattern.compile("\\{([^/}]+)}");

    /**
     * What goes in the document besides the routes.
     *
     * @param title      info.title
     * @param version    info.version, the RestAPI version
     * @param serverUrl  the one entry under servers
     * @param xApiKey    true when keys.acceptXApiKey is on, which adds the X-API-Key scheme
     */
    public record Meta(String title, String version, String serverUrl, boolean xApiKey) {
    }

    public static JsonObject build(List<Route> routes, Meta meta) {
        JsonObject doc = new JsonObject();
        doc.addProperty("openapi", VERSION);

        JsonObject info = new JsonObject();
        info.addProperty("title", meta.title() == null || meta.title().isBlank() ? "RestAPI" : meta.title());
        info.addProperty("version", meta.version());
        info.addProperty("description", Messages.get(DocsText.INFO));
        doc.add("info", info);

        JsonArray servers = new JsonArray();
        JsonObject server = new JsonObject();
        server.addProperty("url", meta.serverUrl() == null || meta.serverUrl().isBlank() ? "/" : meta.serverUrl());
        servers.add(server);
        doc.add("servers", servers);

        JsonArray security = new JsonArray();
        security.add(requirement("bearer"));
        if (meta.xApiKey()) {
            security.add(requirement("apiKey"));
        }
        doc.add("security", security);

        Map<String, JsonObject> tags = new LinkedHashMap<>();
        JsonObject paths = new JsonObject();
        Set<String> operationIds = new HashSet<>();
        for (Route r : routes) {
            tags.computeIfAbsent(r.group(), OpenApi::tag);
            JsonObject item = paths.has(r.pattern()) ? paths.getAsJsonObject(r.pattern()) : new JsonObject();
            item.add(r.method().toLowerCase(Locale.ROOT), operation(r, meta, operationIds));
            paths.add(r.pattern(), item);
        }
        JsonArray tagList = new JsonArray();
        tags.values().forEach(tagList::add);
        doc.add("tags", tagList);
        doc.add("paths", paths);
        doc.add("components", components(meta));
        return doc;
    }

    private static JsonObject requirement(String scheme) {
        JsonObject o = new JsonObject();
        o.add(scheme, new JsonArray());
        return o;
    }

    /** The tag of a group, with a sentence for the groups RestAPI knows. */
    static JsonObject tag(String group) {
        JsonObject t = new JsonObject();
        t.addProperty("name", group);
        String text = switch (group) {
            case "core" -> Messages.get(DocsText.TAG_CORE);
            case "players" -> Messages.get(DocsText.TAG_PLAYERS);
            case "console" -> Messages.get(DocsText.TAG_CONSOLE);
            case "control" -> Messages.get(DocsText.TAG_CONTROL);
            case "events" -> Messages.get(DocsText.TAG_EVENTS);
            case "docs" -> Messages.get(DocsText.TAG_DOCS);
            default -> {
                Sibling s = group.startsWith("suite.") ? Sibling.byId(group.substring(6)) : null;
                yield s == null ? null : Messages.get(DocsText.TAG_SUITE, "adds", s.adds(), "plugin", s.pluginName());
            }
        };
        if (text != null) {
            t.addProperty("description", text);
        }
        return t;
    }

    private static JsonObject operation(Route r, Meta meta, Set<String> operationIds) {
        RouteDoc d = r.doc();
        JsonObject op = new JsonObject();
        JsonArray tags = new JsonArray();
        tags.add(r.group());
        op.add("tags", tags);
        op.addProperty("operationId", operationId(r, operationIds));
        if (r.summary() != null && !r.summary().isBlank()) {
            op.addProperty("summary", r.summary());
        }
        List<String> notes = new ArrayList<>();
        if (r.summary() != null && !r.summary().isBlank()) {
            notes.add(r.summary());
        }
        if (r.access() == Access.WRITE) {
            notes.add(Messages.get(DocsText.ACCESS_WRITE));
        } else if (r.access() == Access.DANGEROUS) {
            notes.add(Messages.get(DocsText.ACCESS_DANGEROUS));
        }
        boolean open = r.open().getAsBoolean();
        if (open) {
            notes.add(Messages.get(DocsText.OPEN));
        }
        if (!notes.isEmpty()) {
            op.addProperty("description", String.join(" ", notes));
        }
        op.addProperty("x-scope", r.scope().id());
        op.addProperty("x-access", r.access().name().toLowerCase(Locale.ROOT));
        if (open) {
            op.addProperty("x-open", true);
            JsonArray security = new JsonArray();
            security.add(new JsonObject());
            security.add(requirement("bearer"));
            if (meta.xApiKey()) {
                security.add(requirement("apiKey"));
            }
            op.add("security", security);
        }

        JsonArray params = new JsonArray();
        for (String name : pathParams(r.pattern())) {
            JsonObject p = new JsonObject();
            p.addProperty("name", name);
            p.addProperty("in", "path");
            p.addProperty("required", true);
            p.addProperty("description", pathParamText(name));
            p.add("schema", type("string"));
            params.add(p);
        }
        for (RouteDoc.Param q : d.query()) {
            JsonObject p = new JsonObject();
            p.addProperty("name", q.name());
            p.addProperty("in", "query");
            p.addProperty("required", false);
            if (q.description() != null && !q.description().isBlank()) {
                p.addProperty("description", q.description());
            }
            p.add("schema", type("string"));
            params.add(p);
        }
        if (!params.isEmpty()) {
            op.add("parameters", params);
        }

        if (d.request() != null && !d.request().isBlank()) {
            JsonObject body = new JsonObject();
            body.addProperty("required", true);
            body.add("content", content(d.request()));
            op.add("requestBody", body);
        }

        JsonObject responses = new JsonObject();
        JsonObject ok = new JsonObject();
        if (d.response() != null && !d.response().isBlank()) {
            ok.addProperty("description", Messages.get(DocsText.RESPONSE_OK));
            ok.add("content", content(d.response()));
            responses.add("200", ok);
        } else if (r.method().equals("DELETE")) {
            ok.addProperty("description", Messages.get(DocsText.RESPONSE_NO_CONTENT));
            responses.add("204", ok);
        } else {
            ok.addProperty("description", Messages.get(DocsText.RESPONSE_OK));
            responses.add("200", ok);
        }
        if (!d.errors().isEmpty()) {
            JsonObject errors = new JsonObject();
            errors.addProperty("description", Messages.get(DocsText.RESPONSE_ERRORS, "codes", String.join(", ", d.errors())));
            errors.add("content", errorContent());
            responses.add("4XX", errors);
            JsonArray codes = new JsonArray();
            d.errors().forEach(codes::add);
            op.add("x-errors", codes);
        }
        responses.add("401", ref("Unauthorized"));
        if (!open) {
            responses.add("403", ref("Forbidden"));
        }
        responses.add("429", ref("TooManyRequests"));
        responses.add("500", ref("ServerError"));
        op.add("responses", responses);
        return op;
    }

    private static String pathParamText(String name) {
        return switch (name) {
            case "player" -> Messages.get(DocsText.PARAM_PLAYER);
            case "world" -> Messages.get(DocsText.PARAM_WORLD);
            default -> Messages.get(DocsText.PATH_PARAM, "name", name);
        };
    }

    /** The {name} segments of a pattern, in order. */
    static List<String> pathParams(String pattern) {
        List<String> out = new ArrayList<>();
        Matcher m = PARAM.matcher(pattern);
        while (m.find()) {
            out.add(m.group(1));
        }
        return out;
    }

    /** get /api/players/{player}/kick becomes getPlayersByPlayerKick. Repeats get a number. */
    static String operationId(Route r, Set<String> taken) {
        StringBuilder b = new StringBuilder(r.method().toLowerCase(Locale.ROOT));
        String[] segs = r.pattern().split("/");
        for (int i = 0; i < segs.length; i++) {
            String s = segs[i];
            if (s.isEmpty() || (i == 1 && s.equals("api") && segs.length > 2)) {
                continue;
            }
            boolean param = s.startsWith("{") && s.endsWith("}");
            if (param) {
                b.append("By");
                s = s.substring(1, s.length() - 1);
            }
            for (String word : s.split("[^A-Za-z0-9]+")) {
                if (!word.isEmpty()) {
                    b.append(Character.toUpperCase(word.charAt(0))).append(word.substring(1));
                }
            }
        }
        String id = b.toString();
        String out = id;
        for (int n = 2; !taken.add(out); n++) {
            out = id + n;
        }
        return out;
    }

    /** A content map for an example: JSON with a schema, an event stream, or plain text. */
    static JsonObject content(String example) {
        JsonObject media = new JsonObject();
        JsonObject content = new JsonObject();
        JsonElement parsed = parse(example);
        if (parsed != null) {
            media.add("schema", schemaOf(parsed));
            media.add("example", parsed);
            content.add("application/json", media);
        } else {
            media.add("schema", type("string"));
            media.addProperty("example", example);
            String head = example.stripLeading();
            boolean events = head.startsWith("event:") || head.startsWith("id:") || head.startsWith("data:");
            String type = events ? "text/event-stream" : head.startsWith("<") ? "text/html" : "text/plain";
            content.add(type, media);
        }
        return content;
    }

    private static JsonElement parse(String text) {
        String t = text.strip();
        if (!(t.startsWith("{") || t.startsWith("["))) {
            return null;
        }
        try {
            return JsonParser.parseString(t);
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** The schema an example implies. Arrays take the items of their first element. */
    static JsonObject schemaOf(JsonElement e) {
        if (e == null || e.isJsonNull()) {
            return type("null");
        }
        if (e.isJsonObject()) {
            JsonObject s = type("object");
            JsonObject props = new JsonObject();
            for (Map.Entry<String, JsonElement> f : e.getAsJsonObject().entrySet()) {
                props.add(f.getKey(), schemaOf(f.getValue()));
            }
            s.add("properties", props);
            return s;
        }
        if (e.isJsonArray()) {
            JsonObject s = type("array");
            JsonArray a = e.getAsJsonArray();
            s.add("items", a.isEmpty() ? new JsonObject() : schemaOf(a.get(0)));
            return s;
        }
        JsonPrimitive p = e.getAsJsonPrimitive();
        if (p.isBoolean()) {
            return type("boolean");
        }
        if (p.isNumber()) {
            String n = p.getAsString();
            return type(n.contains(".") || n.contains("e") || n.contains("E") ? "number" : "integer");
        }
        return type("string");
    }

    private static JsonObject type(String t) {
        JsonObject o = new JsonObject();
        o.addProperty("type", t);
        return o;
    }

    private static JsonObject ref(String name) {
        JsonObject o = new JsonObject();
        o.addProperty("$ref", "#/components/responses/" + name);
        return o;
    }

    private static JsonObject errorContent() {
        JsonObject media = new JsonObject();
        JsonObject schemaRef = new JsonObject();
        schemaRef.addProperty("$ref", "#/components/schemas/Error");
        media.add("schema", schemaRef);
        JsonObject content = new JsonObject();
        content.add("application/json", media);
        return content;
    }

    private static JsonObject components(Meta meta) {
        JsonObject c = new JsonObject();
        JsonObject schemes = new JsonObject();
        JsonObject bearer = new JsonObject();
        bearer.addProperty("type", "http");
        bearer.addProperty("scheme", "bearer");
        bearer.addProperty("description", Messages.get(DocsText.BEARER));
        schemes.add("bearer", bearer);
        if (meta.xApiKey()) {
            JsonObject apiKey = new JsonObject();
            apiKey.addProperty("type", "apiKey");
            apiKey.addProperty("in", "header");
            apiKey.addProperty("name", "X-API-Key");
            apiKey.addProperty("description", Messages.get(DocsText.X_API_KEY));
            schemes.add("apiKey", apiKey);
        }
        c.add("securitySchemes", schemes);

        JsonObject schemas = new JsonObject();
        JsonObject error = type("object");
        JsonObject props = new JsonObject();
        props.add("error", type("string"));
        props.add("message", type("string"));
        error.add("properties", props);
        JsonArray required = new JsonArray();
        required.add("error");
        required.add("message");
        error.add("required", required);
        schemas.add("Error", error);
        c.add("schemas", schemas);

        JsonObject responses = new JsonObject();
        responses.add("Unauthorized", errorResponse(DocsText.RESPONSE_401));
        responses.add("Forbidden", errorResponse(DocsText.RESPONSE_403));
        responses.add("TooManyRequests", errorResponse(DocsText.RESPONSE_429));
        responses.add("ServerError", errorResponse(DocsText.RESPONSE_500));
        c.add("responses", responses);
        return c;
    }

    private static JsonObject errorResponse(String textId) {
        JsonObject r = new JsonObject();
        r.addProperty("description", Messages.get(textId));
        r.add("content", errorContent());
        return r;
    }
}
