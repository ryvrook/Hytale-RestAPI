package dev.ryvrook.restapi.control;

import dev.ryvrook.restapi.text.Messages;

/**
 * The sentences of the control routes, error messages for scripts and the chat lines players see while a stop
 * counts down. The error codes next to them in the answers stay fixed, only the sentences can be reworded.
 */
public final class ControlText {
    private ControlText() {
    }

    public static final String OFF = Messages.define("control.error.off", "{setting} is false in RestAPI.json.");
    public static final String BAD_VALUE = Messages.define("control.error.badValue", "{field} {problem}.");
    public static final String MUST_INT = Messages.define("control.problem.int", "must be a whole number from {min} to {max}");
    public static final String MUST_NUMBER = Messages.define("control.problem.number", "must be a number from {min} to {max}");
    public static final String MUST_BOOL = Messages.define("control.problem.bool", "must be true or false");
    public static final String MUST_TEXT = Messages.define("control.problem.text", "must be text of at most {max} characters");
    public static final String MUST_LIST = Messages.define("control.problem.list", "must be a list of names");
    public static final String MUST_ONE_OF = Messages.define("control.problem.oneOf", "needs exactly one of {fields}");
    public static final String NOTHING = Messages.define("control.error.nothing", "The body changes nothing. Send at least one of {fields}.");
    public static final String INTERRUPTED = Messages.define("control.error.interrupted",
            "The request was interrupted before it finished, the server may be stopping.");
    public static final String BOOTING = Messages.define("control.error.booting", "The server is still booting, try again when it is up.");

    public static final String WORLD_NOT_FOUND = Messages.define("control.error.worldNotFound", "No world is named {world}.");
    public static final String WORLD_LOADED = Messages.define("control.error.worldLoaded", "The world {world} is already loaded.");
    public static final String WORLD_NOT_LOADABLE = Messages.define("control.error.worldNotLoadable",
            "There is no saved world named {world} in the universe folder.");
    public static final String WORLD_TIMEOUT = Messages.define("control.error.worldTimeout",
            "The world thread of {world} did not answer within {ms} ms.");
    public static final String WORLD_LOAD_FAILED = Messages.define("control.error.worldLoadFailed",
            "The world {world} did not load, {reason}.");
    public static final String WORLD_DEFAULT = Messages.define("control.error.worldDefault",
            "{world} is the default world in config.json. Set another default before you unload it.");
    public static final String WORLD_LAST = Messages.define("control.error.worldLast", "{world} is the only world loaded and the server needs one.");
    public static final String WORLD_REMOVE_FAILED = Messages.define("control.error.worldRemoveFailed",
            "The engine did not remove {world}, the server log may say why.");
    public static final String WEATHER_UNKNOWN = Messages.define("control.error.weatherUnknown",
            "No weather is named {weather}. GET /api/worlds/{world} lists the names under weatherNames.");

    public static final String PLUGIN_ID = Messages.define("control.error.pluginId", "A plugin id is Group:Name, such as Ryvrook:Lullaby.");
    public static final String PLUGIN_NOT_FOUND = Messages.define("control.error.pluginNotFound", "No plugin is named {id}.");
    public static final String PLUGIN_LOADED = Messages.define("control.error.pluginLoaded", "{id} is already loaded, its state is {state}.");
    public static final String PLUGIN_NOT_LOADED = Messages.define("control.error.pluginNotLoaded", "{id} is not loaded.");
    public static final String PLUGIN_REFUSED = Messages.define("control.error.pluginRefused",
            "The engine would not {action} {id}. The server log has the reason.");
    public static final String SELF = Messages.define("control.error.self",
            "RestAPI never loads, unloads or reloads itself through its own API. Use /restapi reload for its files, or /plugin reload Ryvrook:RestAPI on the server console.");

    public static final String IDEMPOTENCY_KEY = Messages.define("control.error.idempotencyKey",
            "Idempotency-Key is longer than {max} characters.");
    public static final String CONFIRM = Messages.define("control.error.confirm", "Send confirm with the value stop to stop the server.");
    public static final String STOP_PENDING = Messages.define("control.error.stopPending",
            "A stop is already counting down and runs at {at}. Cancel it with POST /api/server/stop/cancel first.");
    public static final String NO_STOP = Messages.define("control.error.noStop", "No stop is counting down.");
    public static final String STOPPING = Messages.define("control.error.stopping", "The server is already stopping.");
    public static final String BACKUP_DIR = Messages.define("control.error.backupDir",
            "No backup directory is set. Start the server with --backup-dir or set Backup.Directory in config.json.");
    public static final String BACKUP_RUNNING = Messages.define("control.error.backupRunning", "Backup {job} is still running.");
    public static final String JOB_NOT_FOUND = Messages.define("control.error.jobNotFound", "No backup job {job} is kept.");
    public static final String SAVE_FAILED = Messages.define("control.error.saveFailed", "config.json could not be saved, the server log has the cause.");

    public static final String STOP_IN = Messages.define("control.stop.in", "The server stops in {time}.");
    public static final String RESTART_IN = Messages.define("control.restart.in", "The server restarts in {time}.");
    public static final String STOP_NOW = Messages.define("control.stop.now", "The server is stopping now.");
    public static final String RESTART_NOW = Messages.define("control.restart.now", "The server is restarting now.");
    public static final String STOP_CANCELLED = Messages.define("control.stop.cancelled", "The stop was called off.");
    public static final String STOP_KICK = Messages.define("control.stop.kick", "The server stopped.");
    public static final String RESTART_KICK = Messages.define("control.restart.kick", "The server is restarting. Join again in a minute.");

    public static final String SECOND = Messages.define("control.unit.second", "1 second");
    public static final String SECONDS = Messages.define("control.unit.seconds", "{n} seconds");
    public static final String MINUTE = Messages.define("control.unit.minute", "1 minute");
    public static final String MINUTES = Messages.define("control.unit.minutes", "{n} minutes");
    public static final String HOUR = Messages.define("control.unit.hour", "1 hour");
    public static final String HOURS = Messages.define("control.unit.hours", "{n} hours");
    public static final String AND = Messages.define("control.unit.join", "{a} and {b}");

    /** 90 is 1 minute and 30 seconds, 3600 is 1 hour. Only the two largest units are named. */
    public static String duration(long seconds) {
        long s = Math.max(0, seconds);
        long h = s / 3600;
        long m = (s % 3600) / 60;
        long r = s % 60;
        if (h > 0) {
            return m == 0 ? unit(h, HOUR, HOURS) : Messages.get(AND, "a", unit(h, HOUR, HOURS), "b", unit(m, MINUTE, MINUTES));
        }
        if (m > 0) {
            return r == 0 ? unit(m, MINUTE, MINUTES) : Messages.get(AND, "a", unit(m, MINUTE, MINUTES), "b", unit(r, SECOND, SECONDS));
        }
        return unit(r, SECOND, SECONDS);
    }

    private static String unit(long n, String one, String many) {
        return n == 1 ? Messages.get(one) : Messages.get(many, "n", n);
    }
}
