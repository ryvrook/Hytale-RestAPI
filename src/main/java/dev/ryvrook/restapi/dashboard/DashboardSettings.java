package dev.ryvrook.restapi.dashboard;

import dev.ryvrook.restapi.config.Setting;
import dev.ryvrook.restapi.config.Settings;

/** The keys under dashboard in RestAPI.json. */
public final class DashboardSettings {
    private DashboardSettings() {
    }

    public static final Setting<Boolean> ENABLED = Settings.bool("dashboard.enabled", false,
            "Serve the admin dashboard at /dashboard on the HTTP API's own address. Off mounts none of its routes. /restapi dashboard on and off change it and write it here.");
    public static final Setting<Boolean> WRITES = Settings.bool("dashboard.writes", false,
            "Let a dashboard session change the server. Off it may only read, and the page draws no controls. /restapi dashboard writes on and off change it and write it here.");
    public static final Setting<Integer> SESSION_HOURS = Settings.integer("dashboard.sessionHours", 12, 1, 168,
            "Hours a dashboard session lasts without use. A session ends after 7 days whatever this says.");
    public static final Setting<Integer> LINK_MINUTES = Settings.integer("dashboard.linkMinutes", 10, 1, 60,
            "Minutes a login link from /restapi dashboard login works. Each link works once.");
    public static final Setting<Integer> REQUESTS_PER_MINUTE = Settings.integer("dashboard.requestsPerMinute", 600, 0, 100_000,
            "Requests a dashboard session may make per minute, the same token bucket as a key. The page asks for many small things at once, so it gets more than limits.requestsPerMinute. 0 turns the limit off.");
    public static final Setting<String> ASSETS = Settings.text("dashboard.assets", "",
            "Path of the game's Assets.zip for the item, creature and map marker icons of the page. Empty looks for the archive the server loaded, then Assets.zip in the server folder and the folder above it. Without one the page shows names only.");
    public static final Setting<Boolean> CHRONICLE = Settings.bool("dashboard.chronicle", true,
            "Pass the character pictures of Chronicle Stats through to the page while Chronicle Stats is bound. Off keeps the letter busts.");
}
