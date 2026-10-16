package dev.ryvrook.restapi.siblings;

/**
 * What RestAPI knows about one suite mod right now.
 *
 * @param installed true when the plugin manager holds it in any state
 * @param version   its manifest version, or null
 * @param state     the engine state such as ENABLED, or null
 * @param bound     true while its routes are mounted
 * @param routes    the number of routes mounted for it
 * @param reason    why it is not bound, or a note while bound, such as an old version under always
 */
public record Presence(Sibling sibling, Mode mode, boolean installed, String version, String state, boolean bound,
                       int routes, String reason) {
}
