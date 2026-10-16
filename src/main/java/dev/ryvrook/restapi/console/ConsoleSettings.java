package dev.ryvrook.restapi.console;

import dev.ryvrook.restapi.config.Setting;
import dev.ryvrook.restapi.config.Settings;

import java.util.List;

/** The keys under commands and console in RestAPI.json. */
public final class ConsoleSettings {
    private ConsoleSettings() {
    }

    public static final Setting<Boolean> COMMANDS_ENABLED = Settings.bool("commands.enabled", true,
            "Serve the command routes under /api/commands and offer the command service to RCON. Off leaves both without commands.");
    public static final Setting<List<String>> COMMANDS_DENY = Settings.list("commands.deny",
            List.of("op", "deop", "perm", "whitelist clear", "plugin unload"),
            "Commands refused before the engine sees them, matched on the first words after aliases are resolved. stop, shutdown, sudo around a refused command, any plugin command naming Ryvrook:RestAPI and restapi reload or keys are refused on top of this list and cannot be taken off.");
    public static final Setting<List<String>> COMMANDS_ALLOW_FROM = Settings.list("commands.allowFrom",
            List.of("127.0.0.0/8", "::1"),
            "Client addresses or CIDR ranges that may run commands over HTTP or RCON, on top of the key's own allowFrom. Behind a reverse proxy this is the real client address. Empty allows every address.");
    public static final Setting<List<String>> COMMANDS_PERMISSIONS = Settings.list("commands.permissions", List.of("*"),
            "Permission nodes the command sender holds. * is every node, a.b.* every node under a.b, and -a.b takes a node away. The default answers like the console.");
    public static final Setting<Integer> COMMANDS_TIMEOUT_MS = Settings.integer("commands.timeoutMs", 5_000, 100, 120_000,
            "Milliseconds a command gets to finish before the answer goes out with the lines that arrived. The command may keep running.");
    public static final Setting<Integer> COMMANDS_MAX_TIMEOUT_MS = Settings.integer("commands.maxTimeoutMs", 60_000, 100, 600_000,
            "Largest timeoutMs a request may ask for.");
    public static final Setting<Integer> COMMANDS_GRACE_MS = Settings.integer("commands.graceMs", 300, 0, 10_000,
            "Milliseconds to keep listening after the command finished, for commands that reply late. Every new line starts the wait again.");
    public static final Setting<Integer> COMMANDS_GRACE_MAX_MS = Settings.integer("commands.graceMaxMs", 2_000, 0, 60_000,
            "Most milliseconds spent listening for late replies after the command finished, however often new lines arrive.");
    public static final Setting<Integer> COMMANDS_MAX_OUTPUT_LINES = Settings.integer("commands.maxOutputLines", 500, 1, 100_000,
            "Lines of output kept per command. The rest are counted and left out.");
    public static final Setting<Integer> COMMANDS_MAX_LINE_CHARS = Settings.integer("commands.maxLineChars", 2_000, 16, 65_536,
            "Longest command line accepted, in characters.");
    public static final Setting<Boolean> COMMANDS_ECHO = Settings.bool("commands.captureConsoleEcho", true,
            "Also return what a command sends to the server console instead of its sender, such as the line of say. Only console lines written by that command's own thread are taken.");
    public static final Setting<Integer> COMMANDS_IDEMPOTENCY_SECONDS = Settings.integer("commands.idempotencySeconds", 600, 1, 86_400,
            "Seconds a result is kept under its Idempotency-Key, so a retry gets the stored answer and runs nothing. Kept in memory only, a restart forgets them.");
    public static final Setting<Boolean> COMMANDS_REQUIRE_IDEMPOTENCY = Settings.bool("commands.requireIdempotencyKey", false,
            "Refuse command requests that carry no Idempotency-Key header with 400.");
    public static final Setting<Integer> COMMANDS_BATCH_MAX = Settings.integer("commands.batchMax", 20, 1, 500,
            "Most commands in one POST /api/commands/batch.");
    public static final Setting<Integer> COMMANDS_RUNS_KEPT = Settings.integer("commands.runsKept", 1_000, 10, 100_000,
            "Finished runs kept in memory for GET /api/commands/runs/{runId}, newest first, each for commands.idempotencySeconds.");

    public static final Setting<Boolean> CONSOLE_ENABLED = Settings.bool("console.enabled", true,
            "Keep a copy of the server log in memory and serve it under /api/console and as a stream.");
    public static final Setting<Integer> CONSOLE_RING_LINES = Settings.integer("console.ringLines", 2_000, 50, 100_000,
            "Log lines kept in memory. The oldest go first.");
    public static final Setting<String> CONSOLE_MIN_LEVEL = Settings.text("console.minLevel", "INFO",
            "Lowest log level kept, one of SEVERE, WARNING, INFO, CONFIG, FINE, FINER, FINEST. Console replies always count as INFO.");
    public static final Setting<Integer> CONSOLE_DEFAULT_LINES = Settings.integer("console.defaultLines", 200, 1, 100_000,
            "Lines GET /api/console answers when the request names no count, and lines a new stream starts with.");
    public static final Setting<Integer> CONSOLE_KEEP_ALIVE_SECONDS = Settings.integer("console.keepAliveSeconds", 15, 1, 300,
            "Seconds between keep alive comments on a quiet log stream, so proxies do not close it.");
}
