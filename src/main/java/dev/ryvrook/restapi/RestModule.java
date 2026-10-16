package dev.ryvrook.restapi;

import dev.ryvrook.restapi.http.Route;

import java.util.List;

/**
 * One area of the API that does not depend on a sibling, such as players or the console. Listed by class name in
 * {@link Modules}, so each area lives in its own package and the build works with any of them missing. A module
 * class needs a public no argument constructor, and that constructor must touch the module's settings and text
 * classes so their keys are known before RestAPI.json and messages.json are read.
 */
public interface RestModule {
    /** The route group, such as players. Every route the module returns names it. */
    String group();

    /**
     * Called once in the plugin's setup, before the files are read. The place for ECS systems and event
     * listeners, which the engine accepts only during setup.
     */
    default void setup(RestApiPlugin plugin) throws Exception {
    }

    /** The routes to mount. Called at start and after each /restapi reload. */
    List<Route> routes(RestApi api) throws Exception;

    /** Starts threads, listeners or sockets. Called after the routes are mounted. */
    default void start(RestApi api) throws Exception {
    }

    /** Stops what start began. Called at shutdown and before a reload starts the module again. */
    default void stop() {
    }

    /** Lines /restapi status prints for this module after the audit line. Most modules have none. */
    default List<String> status() {
        return List.of();
    }

    /**
     * Lines for {@code /restapi <group> <topic>}, such as rcon status or events webhooks. Empty when the module has
     * nothing under that topic.
     */
    default List<String> lines(String topic) {
        return List.of();
    }
}
