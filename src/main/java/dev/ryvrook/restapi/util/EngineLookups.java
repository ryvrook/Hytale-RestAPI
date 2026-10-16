package dev.ryvrook.restapi.util;

import com.hypixel.hytale.common.plugin.PluginIdentifier;
import com.hypixel.hytale.server.core.HytaleServer;
import com.hypixel.hytale.server.core.plugin.PluginBase;
import com.hypixel.hytale.server.core.plugin.PluginManager;

import java.lang.reflect.Field;
import java.util.List;
import java.util.concurrent.locks.ReadWriteLock;

/**
 * Every plugin manager read made off a world thread or the plugin's own setup, start and shutdown, behind one
 * {@link GatedLookup}. The sibling check, the plugin poll of the events module, the metrics route, the plugin
 * list routes, the data folder lookup and the relink thread all read through here. Safe from any thread.
 */
public final class EngineLookups {
    private static final GatedLookup GATE = new GatedLookup(EngineLookups::managerLock, EngineLookups::stopping);
    private static volatile Field lockField;
    private static volatile boolean lockFieldMissing;

    private EngineLookups() {
    }

    /** The loaded plugin, or null. */
    public static PluginBase plugin(PluginIdentifier id) {
        return GATE.read("plugin " + id, () -> {
            PluginManager m = PluginManager.get();
            return m == null ? null : m.getPlugin(id);
        }, null);
    }

    /** A copy of the loaded plugins, empty before the manager is up. */
    public static List<PluginBase> plugins() {
        return GATE.read("plugins", () -> {
            PluginManager m = PluginManager.get();
            return m == null ? null : List.copyOf(m.getPlugins());
        }, List.of());
    }

    private static boolean stopping() {
        HytaleServer s = HytaleServer.get();
        return s != null && s.isShuttingDown();
    }

    /**
     * The plugin manager's private {@code lock} field, a ReentrantReadWriteLock in 0.7.1, read reflectively. Null
     * when the manager is not up or the field is not there, and then reads are not gated.
     */
    static ReadWriteLock managerLock() {
        if (lockFieldMissing) {
            return null;
        }
        try {
            PluginManager manager = PluginManager.get();
            if (manager == null) {
                return null;
            }
            Field f = lockField;
            if (f == null) {
                f = PluginManager.class.getDeclaredField("lock");
                f.setAccessible(true);
                lockField = f;
            }
            return f.get(manager) instanceof ReadWriteLock rw ? rw : null;
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            lockFieldMissing = true;
            return null;
        }
    }
}
