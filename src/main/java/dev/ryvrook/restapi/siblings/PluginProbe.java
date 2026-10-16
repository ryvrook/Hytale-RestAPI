package dev.ryvrook.restapi.siblings;

/** Asks the engine whether a plugin is loaded, without touching any class of that plugin. */
@FunctionalInterface
public interface PluginProbe {
    /**
     * A loaded plugin. identity is the plugin object, which changes when the plugin is reloaded. state is the
     * engine's PluginState name, such as ENABLED.
     */
    record Found(Object identity, String version, String state) {
        public boolean running() {
            return "ENABLED".equals(state);
        }
    }

    /** The plugin in any state, or null when the plugin manager does not hold it. */
    Found find(Sibling sibling);
}
