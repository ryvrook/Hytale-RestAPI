package dev.ryvrook.restapi.control;

import dev.ryvrook.restapi.http.ApiException;
import dev.ryvrook.restapi.text.Messages;

/** A plugin id as the routes take it, Group:Name such as Ryvrook:Lullaby. */
public record PluginId(String group, String name) {
    /** Parses Group:Name, or answers 400 bad_plugin_id. Both parts must be there and neither may hold a slash. */
    public static PluginId parse(String text) {
        String t = text == null ? "" : text.trim();
        int colon = t.indexOf(':');
        if (colon <= 0 || colon == t.length() - 1 || t.indexOf(':', colon + 1) >= 0 || t.contains("/")) {
            throw ApiException.badRequest("bad_plugin_id", Messages.get(ControlText.PLUGIN_ID));
        }
        return new PluginId(t.substring(0, colon).trim(), t.substring(colon + 1).trim());
    }

    @Override
    public String toString() {
        return group + ":" + name;
    }
}
