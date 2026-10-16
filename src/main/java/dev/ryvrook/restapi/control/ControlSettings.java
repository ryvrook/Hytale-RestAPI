package dev.ryvrook.restapi.control;

import dev.ryvrook.restapi.config.Setting;
import dev.ryvrook.restapi.config.Settings;

/** The keys under control in RestAPI.json, for the plugin, world and server routes. */
public final class ControlSettings {
    private ControlSettings() {
    }

    public static final Setting<Boolean> ENABLED = Settings.bool("control.enabled", true,
            "Serve the plugin, world and server control routes. Off removes all of them, GET /api/server and /api/health stay.");
    public static final Setting<Boolean> ALLOW_STOP = Settings.bool("control.allowStop", true,
            "Let POST /api/server/stop stop the server. Off answers 403 there.");
    public static final Setting<Boolean> ALLOW_PLUGIN_LOAD = Settings.bool("control.allowPluginLoad", true,
            "Let the plugin routes load, unload and reload plugins. RestAPI itself is always refused. Off answers 403 there.");
    public static final Setting<Boolean> ALLOW_WORLD_UNLOAD = Settings.bool("control.allowWorldUnload", false,
            "Let POST /api/worlds/<world>/unload remove a loaded world. The default world and the last world are always refused.");
    public static final Setting<Boolean> ALLOW_CONFIG_EDIT = Settings.bool("control.allowConfigEdit", true,
            "Let PATCH /api/server/config change config.json. Off answers 403 there, the GET still works.");
    public static final Setting<Integer> WORLD_TIMEOUT_MS = Settings.integer("control.worldTimeoutMs", 5_000, 100, 120_000,
            "Milliseconds a request waits for a world thread before it answers 504 world_timeout.");
    public static final Setting<Integer> SAVE_TIMEOUT_SECONDS = Settings.integer("control.saveTimeoutSeconds", 60, 1, 3_600,
            "Seconds a world save through the API may take before the answer reports it as still running.");
    public static final Setting<Integer> RELOAD_WAIT_SECONDS = Settings.integer("control.reloadWaitSeconds", 15, 1, 120,
            "Seconds a plugin reload waits for the new copy to reach ENABLED before it answers 202 with the state so far.");
    public static final Setting<Integer> LOCK_WAIT_SECONDS = Settings.integer("control.lockWaitSeconds", 30, 0, 300,
            "Seconds a plugin load, unload or reload waits for the suite lock that TrackerAPI and Coinage hold while they reload their dependents. After that it goes ahead without it.");
    public static final Setting<Integer> STOP_DELAY_SECONDS = Settings.integer("control.stopDelaySeconds", 0, 0, 86_400,
            "Countdown in seconds when a stop request gives no delaySeconds.");
    public static final Setting<Integer> STOP_MAX_DELAY_SECONDS = Settings.integer("control.stopMaxDelaySeconds", 3_600, 0, 86_400,
            "Longest countdown a stop request may ask for.");
    public static final Setting<Boolean> STOP_ANNOUNCE = Settings.bool("control.stopAnnounce", true,
            "Tell the players in chat while a stop counts down, when the request gives no announce field.");
    public static final Setting<Integer> RESTART_EXIT_CODE = Settings.integer("control.restartExitCode", 8, 1, 255,
            "Exit code a stop with restart true uses. 8 is the engine's update code. Your supervisor must start the server again on it, see the README.");
    public static final Setting<Integer> BACKUP_JOBS_KEPT = Settings.integer("control.backupJobsKept", 20, 1, 1_000,
            "Finished backup jobs kept in memory for GET /api/server/backup/<job>.");
    public static final Setting<Integer> IDEMPOTENCY_SECONDS = Settings.integer("control.idempotencySeconds", 600, 0, 86_400,
            "Seconds the answer of a dangerous control route is kept under its key name and Idempotency-Key header, so a retry gets it again without running twice. 0 keeps nothing.");
}
