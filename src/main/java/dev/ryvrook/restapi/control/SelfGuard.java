package dev.ryvrook.restapi.control;

import dev.ryvrook.restapi.http.ApiException;
import dev.ryvrook.restapi.text.Messages;

import java.util.Locale;

/**
 * Refuses every load, unload or reload of Ryvrook:RestAPI through the plugin routes, whatever the key or the
 * settings say. A plugin that reloads itself over its own channel loses the thread that asked half way through,
 * and an earlier mod of the suite looped that way. The suite reload rule in TrackerAPI's docs/DEVELOPMENT.md says
 * a plugin never reloads itself through its own channel, and this class is that rule for the HTTP API.
 */
public final class SelfGuard {
    private SelfGuard() {
    }

    public static final String GROUP = "Ryvrook";
    public static final String NAME = "RestAPI";

    /** True for Ryvrook:RestAPI in any letter case, since a case mistake must not slip past. */
    public static boolean isSelf(PluginId id) {
        return id != null && id.group().toLowerCase(Locale.ROOT).equals(GROUP.toLowerCase(Locale.ROOT))
                && id.name().toLowerCase(Locale.ROOT).equals(NAME.toLowerCase(Locale.ROOT));
    }

    /** Throws 403 self_refused for RestAPI. */
    public static void check(PluginId id) {
        if (isSelf(id)) {
            throw new ApiException(403, "self_refused", Messages.get(ControlText.SELF));
        }
    }
}
