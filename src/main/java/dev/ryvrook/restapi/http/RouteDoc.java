package dev.ryvrook.restapi.http;

import java.util.List;

/**
 * What the OpenAPI document says about a route beyond its method, path, scope and summary. Modules attach it with
 * {@link Route#doc(RouteDoc)}. Examples are JSON text, the docs module parses them into schemas.
 *
 * @param query    query parameters as name and sentence pairs
 * @param request  an example request body, or null
 * @param response an example 200 body, or null
 * @param errors   error codes the route can answer besides the common ones, such as player_not_found
 */
public record RouteDoc(List<Param> query, String request, String response, List<String> errors) {
    public static final RouteDoc NONE = new RouteDoc(List.of(), null, null, List.of());

    public record Param(String name, String description) {
    }

    public RouteDoc {
        query = query == null ? List.of() : List.copyOf(query);
        errors = errors == null ? List.of() : List.copyOf(errors);
    }
}
