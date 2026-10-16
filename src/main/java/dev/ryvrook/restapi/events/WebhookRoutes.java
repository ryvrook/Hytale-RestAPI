package dev.ryvrook.restapi.events;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import dev.ryvrook.restapi.auth.Scope;
import dev.ryvrook.restapi.http.Access;
import dev.ryvrook.restapi.http.ApiException;
import dev.ryvrook.restapi.http.Call;
import dev.ryvrook.restapi.http.Response;
import dev.ryvrook.restapi.http.Route;
import dev.ryvrook.restapi.http.RouteDoc;
import dev.ryvrook.restapi.text.Messages;

import java.io.IOException;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.function.BiConsumer;

/** GET, POST and DELETE /api/webhooks and POST /api/webhooks/{id}/test. */
final class WebhookRoutes {
    private final Webhooks hooks;
    private final BiConsumer<String, Throwable> log;

    WebhookRoutes(Webhooks hooks, BiConsumer<String, Throwable> log) {
        this.hooks = hooks;
        this.log = log;
    }

    List<Route> routes() {
        String g = EventsModule.GROUP;
        return List.of(
                Route.of(g, "GET", "/api/webhooks", Scope.EVENTS, Access.READ,
                        "Every webhook with its event types and what its last delivery answered. Secrets are never shown and long URL parts are hidden.",
                        call -> list())
                        .doc(new RouteDoc(List.of(), null,
                                "{\"enabled\":true,\"webhooks\":[{\"id\":\"3f9a0c12\",\"url\":\"https://discord.com/api/webhooks/***/***\",\"types\":[\"join\",\"leave\"],\"format\":\"discord\",\"secretSet\":false,\"queued\":0,\"delivered\":12,\"failed\":0,\"dropped\":0,\"lastStatus\":204}]}",
                                List.of())),
                Route.of(g, "POST", "/api/webhooks", Scope.EVENTS, Access.WRITE,
                        "Makes a webhook. Without a secret field one is made and shown in this answer only. A secret of null sends unsigned deliveries.",
                        this::create)
                        .doc(new RouteDoc(List.of(),
                                "{\"url\":\"https://example.com/hook\",\"types\":[\"join\",\"leave\",\"death\"],\"format\":\"json\"}",
                                "{\"id\":\"3f9a0c12\",\"url\":\"https://example.com/hook\",\"types\":[\"join\",\"leave\",\"death\"],\"format\":\"json\",\"secretSet\":true,\"secret\":\"9c1e...\"}",
                                List.of("invalid_url", "private_target", "unresolved", "unknown_type", "invalid_format",
                                        "invalid_secret", "too_many_webhooks", "store_failed"))),
                Route.of(g, "DELETE", "/api/webhooks/{id}", Scope.EVENTS, Access.WRITE,
                        "Removes a webhook. Deliveries still waiting for it are dropped.", this::delete)
                        .doc(new RouteDoc(List.of(), null, null, List.of("webhook_not_found", "store_failed"))),
                Route.of(g, "POST", "/api/webhooks/{id}/test", Scope.EVENTS, Access.WRITE,
                        "Sends one test delivery now, without retries, and answers with what the target said.", this::test)
                        .doc(new RouteDoc(List.of(), null,
                                "{\"ok\":true,\"status\":204,\"error\":null,\"millis\":143}", List.of("webhook_not_found"))));
    }

    private Response list() {
        JsonObject o = new JsonObject();
        o.addProperty("enabled", EventsSettings.WEBHOOKS_ENABLED.get());
        JsonArray a = new JsonArray();
        for (Webhooks.Hook h : hooks.list()) {
            a.add(describe(h));
        }
        o.add("webhooks", a);
        return Response.ok(o);
    }

    JsonObject describe(Webhooks.Hook h) {
        JsonObject o = new JsonObject();
        o.addProperty("id", h.id());
        o.addProperty("url", Webhooks.masked(h.url()));
        JsonArray t = new JsonArray();
        EventRing.TYPES.stream().filter(h.types()::contains).forEach(t::add);
        o.add("types", t);
        o.addProperty("format", h.format());
        o.addProperty("secretSet", h.secret() != null && !h.secret().isEmpty());
        o.addProperty("created", h.created());
        o.addProperty("createdBy", h.createdBy());
        o.addProperty("viaApi", h.viaApi());
        Webhooks.State s = hooks.state(h.id());
        o.addProperty("queued", hooks.queued(h.id()));
        o.addProperty("delivered", s.delivered.get());
        o.addProperty("failed", s.failed.get());
        o.addProperty("dropped", s.dropped.get());
        o.addProperty("lastStatus", s.lastStatus);
        o.addProperty("lastAt", s.lastAt);
        o.addProperty("lastError", s.lastError);
        return o;
    }

    private Response create(Call call) {
        JsonObject body = call.jsonBody();
        String url = body.has("url") && body.get("url").isJsonPrimitive() ? body.get("url").getAsString().trim() : null;
        URI uri;
        try {
            uri = Webhooks.checkUrl(url);
        } catch (IllegalArgumentException e) {
            throw ApiException.badRequest("invalid_url", Messages.get(EventsText.ERR_URL));
        }
        Set<String> types;
        try {
            types = EventRing.parseTypes(stringList(body.get("types")));
        } catch (IllegalArgumentException e) {
            throw ApiException.badRequest("unknown_type", Messages.get(EventsText.ERR_TYPES, "types", e.getMessage(),
                    "known", String.join(", ", EventRing.TYPES)));
        }
        String format = body.has("format") && !body.get("format").isJsonNull() ? body.get("format").getAsString() : "json";
        if (!format.equals("json") && !format.equals("discord")) {
            throw ApiException.badRequest("invalid_format", Messages.get(EventsText.ERR_FORMAT));
        }
        boolean generated = false;
        String secret;
        if (!body.has("secret")) {
            secret = Webhooks.newSecret();
            generated = true;
        } else if (body.get("secret").isJsonNull()) {
            secret = null;
        } else {
            JsonElement s = body.get("secret");
            secret = s.isJsonPrimitive() && s.getAsJsonPrimitive().isString() ? s.getAsString() : "";
            if (secret.length() < 16 || secret.length() > 256) {
                throw ApiException.badRequest("invalid_secret", Messages.get(EventsText.ERR_SECRET));
            }
        }
        if (!EventsSettings.WEBHOOKS_PRIVATE.get()) {
            String problem = hooks.targetProblem(uri.getHost());
            if ("private_target".equals(problem)) {
                throw ApiException.badRequest("private_target", Messages.get(EventsText.ERR_PRIVATE, "host", uri.getHost()));
            }
            if (problem != null) {
                throw ApiException.badRequest("unresolved", Messages.get(EventsText.ERR_RESOLVE, "host", uri.getHost()));
            }
        }
        int max = EventsSettings.WEBHOOKS_MAX.get();
        if (hooks.size() >= max) {
            throw new ApiException(409, "too_many_webhooks", Messages.get(EventsText.ERR_TOO_MANY, "max", max));
        }
        Webhooks.Hook h;
        try {
            h = hooks.add(url, types, secret, format, call.keyName());
        } catch (IOException e) {
            log.accept("webhooks.json", e);
            throw new ApiException(500, "store_failed", Messages.get(EventsText.ERR_STORE));
        }
        JsonObject o = describe(h);
        o.addProperty("url", h.url());
        if (generated) {
            o.addProperty("secret", secret);
        }
        return Response.json(201, o);
    }

    static List<String> stringList(JsonElement e) {
        List<String> out = new ArrayList<>();
        if (e == null || e.isJsonNull()) {
            return out;
        }
        if (e.isJsonArray()) {
            for (JsonElement x : e.getAsJsonArray()) {
                out.add(x.isJsonPrimitive() ? x.getAsString() : x.toString());
            }
        } else {
            out.add(e.isJsonPrimitive() ? e.getAsString() : e.toString());
        }
        return out;
    }

    private Webhooks.Hook find(Call call) {
        String id = call.param("id");
        Webhooks.Hook h = id == null ? null : hooks.get(id);
        if (h == null) {
            throw ApiException.notFound("webhook_not_found", Messages.get(EventsText.ERR_HOOK_UNKNOWN, "id", id));
        }
        return h;
    }

    private Response delete(Call call) {
        Webhooks.Hook h = find(call);
        try {
            hooks.remove(h.id());
        } catch (IOException e) {
            log.accept("webhooks.json", e);
            throw new ApiException(500, "store_failed", Messages.get(EventsText.ERR_STORE));
        }
        return Response.noContent();
    }

    private Response test(Call call) throws InterruptedException {
        Webhooks.Hook h = find(call);
        Webhooks.Result r = hooks.test(h);
        JsonObject o = new JsonObject();
        o.addProperty("ok", r.ok());
        o.addProperty("status", r.status());
        o.addProperty("error", r.error());
        o.addProperty("millis", r.millis());
        return Response.ok(o);
    }
}
