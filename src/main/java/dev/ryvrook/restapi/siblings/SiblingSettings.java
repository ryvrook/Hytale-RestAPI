package dev.ryvrook.restapi.siblings;

import dev.ryvrook.restapi.config.Setting;
import dev.ryvrook.restapi.config.Settings;

import java.util.EnumMap;
import java.util.Map;

/** {@code siblings.<id>} for each suite mod, and how often the plugin manager is asked. */
public final class SiblingSettings {
    private SiblingSettings() {
    }

    public static final Setting<Integer> CHECK_SECONDS = Settings.integer("siblings.checkSeconds", 5, 1, 300,
            "Seconds between checks of the plugin manager for suite mods that started, stopped or were reloaded.");

    private static final Map<Sibling, Setting<String>> MODES = new EnumMap<>(Sibling.class);

    static {
        for (Sibling s : Sibling.values()) {
            MODES.put(s, Settings.text("siblings." + s.id(), "auto",
                    "auto, always or never. " + s.adds() + " while " + s.pluginName() + " " + s.minimum()
                            + " or newer runs. " + (s == Sibling.LULLABY
                            ? "always changes nothing for Lullaby 1.0.1, which has no API to bind"
                            : "always binds an older version too") + ", never keeps the routes away."));
        }
    }

    public static Setting<String> of(Sibling s) {
        return MODES.get(s);
    }

    public static Mode mode(Sibling s) {
        return Mode.parse(MODES.get(s).get());
    }
}
