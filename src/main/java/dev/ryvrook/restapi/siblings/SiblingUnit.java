package dev.ryvrook.restapi.siblings;

import dev.ryvrook.restapi.RestApi;
import dev.ryvrook.restapi.http.Route;

import java.util.List;

/**
 * The route group of one suite mod, the only code that touches that mod's API. Loaded by name from
 * {@link Sibling#unitClass()} after the presence check passed, so its imports of the sibling's classes resolve
 * only when the sibling is there. Needs a public no argument constructor.
 */
public interface SiblingUnit {
    /**
     * Connects to the sibling's API and returns the routes to mount under {@code /api/<id>}, every one with the
     * sibling's scope. Throws when the API is not ready, the registry then reports the reason and tries again on
     * the next check.
     */
    List<Route> bind(RestApi api) throws Exception;

    /** Lets go of the sibling's API, subscriptions and hooks. Called before the routes are unmounted. */
    default void unbind() {
    }
}
