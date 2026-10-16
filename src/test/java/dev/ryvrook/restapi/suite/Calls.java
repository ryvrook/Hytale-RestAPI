package dev.ryvrook.restapi.suite;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.ryvrook.restapi.auth.ApiKey;
import dev.ryvrook.restapi.auth.Scope;
import dev.ryvrook.restapi.http.ApiException;
import dev.ryvrook.restapi.http.Call;
import dev.ryvrook.restapi.http.Request;
import dev.ryvrook.restapi.http.Response;
import dev.ryvrook.restapi.http.Route;
import dev.ryvrook.restapi.http.Router;

import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.fail;

/** Runs suite routes the way Gate does, without a socket, and makes stand ins for sibling interfaces. */
public final class Calls {
    public static final ApiKey KEY = new ApiKey("grafana", "0000abcd", "00", EnumSet.allOf(Scope.class),
            "2026-10-16T00:00:00Z", "test", -1, List.of());

    private final Router router = new Router();

    public Calls(List<Route> routes) {
        Map<String, List<Route>> groups = new LinkedHashMap<>();
        for (Route r : routes) {
            groups.computeIfAbsent(r.group(), g -> new java.util.ArrayList<>()).add(r);
        }
        groups.forEach(router::mount);
    }

    public Response get(String pathAndQuery) {
        return send(Request.of("GET", pathAndQuery, "127.0.0.1"));
    }

    public Response post(String path, String body, String... headers) {
        Map<String, List<String>> h = new LinkedHashMap<>();
        for (int i = 0; i + 1 < headers.length; i += 2) {
            h.put(headers[i], List.of(headers[i + 1]));
        }
        return send(new Request("POST", path, null, h, body.getBytes(StandardCharsets.UTF_8), "127.0.0.1"));
    }

    public Response send(Request req) {
        Router.Match m = router.match(req.method(), req.path());
        if (!(m instanceof Router.Found found)) {
            return Response.error(404, "not_found", "no route");
        }
        try {
            return found.route().handler().handle(new Call(req, KEY, "127.0.0.1", found.params()));
        } catch (ApiException e) {
            return Response.error(e.status(), e.code(), e.getMessage());
        } catch (Exception e) {
            fail("handler threw " + e, e);
            return null;
        }
    }

    public boolean has(String method, String path) {
        return router.match(method, path) instanceof Router.Found;
    }

    public static JsonObject json(Response r) {
        return JsonParser.parseString(r.bodyText()).getAsJsonObject();
    }

    /**
     * A stand in for an interface. Each method answers from the map by name, a missing one throws, so a test sees
     * at once when a route reads something it did not expect.
     */
    @SuppressWarnings("unchecked")
    public static <T> T stand(Class<T> type, Map<String, Function<Object[], Object>> answers) {
        return (T) Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type}, (proxy, method, args) -> {
            if (method.getDeclaringClass() == Object.class) {
                return switch (method.getName()) {
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> proxy == args[0];
                    default -> type.getSimpleName() + " stand in";
                };
            }
            Function<Object[], Object> f = answers.get(method.getName());
            if (f == null) {
                if (method.isDefault()) {
                    return java.lang.reflect.InvocationHandler.invokeDefault(proxy, method, args);
                }
                throw new UnsupportedOperationException(type.getSimpleName() + "." + method.getName() + " is not stood in");
            }
            return f.apply(args == null ? new Object[0] : args);
        });
    }
}
