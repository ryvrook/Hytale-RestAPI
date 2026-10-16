package dev.ryvrook.restapi.siblings;

import com.hypixel.hytale.common.plugin.PluginIdentifier;
import com.hypixel.hytale.server.core.plugin.PluginBase;
import com.hypixel.hytale.server.core.plugin.PluginManager;

/** The plugin manager behind {@link PluginProbe}. Safe from any thread. */
public final class EngineProbe implements PluginProbe {
    @Override
    public Found find(Sibling sibling) {
        try {
            PluginManager manager = PluginManager.get();
            if (manager == null) {
                return null;
            }
            PluginBase p = manager.getPlugin(new PluginIdentifier(Sibling.GROUP, sibling.pluginName()));
            if (p == null) {
                return null;
            }
            String version = p.getManifest() == null || p.getManifest().getVersion() == null
                    ? "0.0.0" : p.getManifest().getVersion().toString();
            return new Found(p, version, p.getState() == null ? "NONE" : p.getState().name());
        } catch (RuntimeException | LinkageError e) {
            return null;
        }
    }
}
