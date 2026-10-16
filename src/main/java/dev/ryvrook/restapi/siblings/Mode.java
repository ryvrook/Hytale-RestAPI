package dev.ryvrook.restapi.siblings;

import java.util.Locale;

/** The value of {@code siblings.<id>}. */
public enum Mode {
    /** Bind when the sibling runs a version at or above the minimum. */
    AUTO,
    /** Bind even when the version is older than the minimum, and warn in the log when the sibling is missing. */
    ALWAYS,
    /** Never bind, the routes never appear. */
    NEVER;

    /** The mode for a setting value. Anything unknown reads as auto, and the caller warns. */
    public static Mode parse(String raw) {
        if (raw == null) {
            return AUTO;
        }
        return switch (raw.trim().toLowerCase(Locale.ROOT)) {
            case "always" -> ALWAYS;
            case "never", "off", "false" -> NEVER;
            default -> AUTO;
        };
    }

    public static boolean known(String raw) {
        if (raw == null) {
            return false;
        }
        String s = raw.trim().toLowerCase(Locale.ROOT);
        return s.equals("auto") || s.equals("always") || s.equals("never");
    }

    public String key() {
        return name().toLowerCase(Locale.ROOT);
    }
}
