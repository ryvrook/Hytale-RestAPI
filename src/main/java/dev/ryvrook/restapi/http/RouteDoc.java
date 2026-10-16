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
 * @param produces the media type of the 200 answer, such as text/event-stream, or null to infer it from the example
 * @param path     sentences for path parameters, by name. A parameter left out gets the stock sentence
 */
public record RouteDoc(List<Param> query, String request, String response, List<String> errors, String produces,
                       List<Param> path) {
    public static final RouteDoc NONE = new RouteDoc(List.of(), null, null, List.of());

    public record Param(String name, String description) {
    }

    public RouteDoc {
        query = query == null ? List.of() : List.copyOf(query);
        errors = errors == null ? List.of() : List.copyOf(errors);
        produces = produces == null || produces.isBlank() ? null : produces;
        path = path == null ? List.of() : List.copyOf(path);
    }

    public RouteDoc(List<Param> query, String request, String response, List<String> errors) {
        this(query, request, response, errors, null, List.of());
    }

    /** This doc with the media type of the 200 answer set. */
    public RouteDoc produces(String type) {
        return new RouteDoc(query, request, response, errors, type, path);
    }

    /** This doc with sentences for its path parameters. */
    public RouteDoc path(Param... params) {
        return new RouteDoc(query, request, response, errors, produces, List.of(params));
    }

    /** The sentence for a path parameter, or null when none was given. */
    public String pathText(String name) {
        for (Param p : path) {
            if (p.name().equals(name)) {
                return p.description();
            }
        }
        return null;
    }
}
