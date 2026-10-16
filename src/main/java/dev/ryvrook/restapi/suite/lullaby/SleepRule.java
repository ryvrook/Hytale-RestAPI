package dev.ryvrook.restapi.suite.lullaby;

import com.google.gson.JsonObject;

import java.util.Locale;

/**
 * One world's sleep rule as Lullaby.json sets it, the top level values with the world's entry under Worlds laid
 * over them. {@link #needed} follows Lullaby 1.0.1's own Rule.needed, which this copies because Lullaby has no API.
 *
 * @param enabled     false leaves the world to the engine, where everyone has to sleep
 * @param requirement percent, count or dynamic
 */
record SleepRule(boolean enabled, String requirement, int percent, int count, int minimumSleepers) {
    static SleepRule of(JsonObject config, String world) {
        JsonObject top = config == null ? new JsonObject() : config;
        boolean enabled = true;
        String requirement = text(top, "Requirement", "percent");
        int percent = number(top, "Percent", 50);
        int count = number(top, "Count", 1);
        int minimum = number(top, "MinimumSleepers", 1);
        if (top.has("Worlds") && top.get("Worlds").isJsonObject()) {
            JsonObject worlds = top.getAsJsonObject("Worlds");
            if (worlds.has(world) && worlds.get(world).isJsonObject()) {
                JsonObject w = worlds.getAsJsonObject(world);
                if (w.has("Enabled") && w.get("Enabled").isJsonPrimitive()) {
                    enabled = w.get("Enabled").getAsBoolean();
                }
                requirement = text(w, "Requirement", requirement);
                percent = number(w, "Percent", percent);
                count = number(w, "Count", count);
            }
        }
        return new SleepRule(enabled, requirement.toLowerCase(Locale.ROOT), Math.max(0, Math.min(100, percent)),
                Math.max(1, count), Math.max(1, minimum));
    }

    /** Sleepers needed out of eligible players, at least 1 and at most the eligible count. */
    int needed(int eligible) {
        if (eligible <= 0) {
            return 1;
        }
        int raw = switch (requirement) {
            case "count" -> count;
            case "dynamic" -> 1;
            default -> (int) Math.ceil(eligible * (percent / 100.0));
        };
        return Math.max(1, Math.min(eligible, Math.max(raw, minimumSleepers)));
    }

    private static String text(JsonObject o, String key, String fallback) {
        return o.has(key) && o.get(key).isJsonPrimitive() ? o.get(key).getAsString() : fallback;
    }

    private static int number(JsonObject o, String key, int fallback) {
        try {
            return o.has(key) && o.get(key).isJsonPrimitive() ? o.get(key).getAsInt() : fallback;
        } catch (RuntimeException e) {
            return fallback;
        }
    }
}
