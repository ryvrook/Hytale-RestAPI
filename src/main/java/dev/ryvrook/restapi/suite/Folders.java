package dev.ryvrook.restapi.suite;

import com.hypixel.hytale.common.plugin.PluginIdentifier;
import com.hypixel.hytale.server.core.plugin.PluginBase;
import com.hypixel.hytale.server.core.plugin.PluginManager;
import com.hypixel.hytale.server.core.universe.Universe;
import dev.ryvrook.restapi.RestApi;
import dev.ryvrook.restapi.siblings.Sibling;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** Where a sibling keeps its files, and the world names, read from the engine. */
public final class Folders {
    private Folders() {
    }

    /**
     * The sibling's data folder as the plugin manager reports it, else {@code mods/Ryvrook_<Name>} next to
     * RestAPI's own folder.
     */
    public static Path of(RestApi api, Sibling sibling) {
        try {
            PluginManager m = PluginManager.get();
            PluginBase p = m == null ? null : m.getPlugin(new PluginIdentifier(Sibling.GROUP, sibling.pluginName()));
            if (p != null && p.getDataDirectory() != null) {
                return p.getDataDirectory();
            }
        } catch (RuntimeException | LinkageError e) {
            // Falls back below.
        }
        return api.dataDirectory().resolveSibling(Sibling.GROUP + "_" + sibling.pluginName());
    }

    /** The names of the loaded worlds. Empty off a running server. */
    public static List<String> worlds() {
        try {
            Universe u = Universe.get();
            return u == null ? List.of() : new ArrayList<>(u.getWorlds().keySet());
        } catch (RuntimeException | LinkageError e) {
            return List.of();
        }
    }
}
