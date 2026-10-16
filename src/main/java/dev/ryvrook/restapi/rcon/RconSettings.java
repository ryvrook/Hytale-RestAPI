package dev.ryvrook.restapi.rcon;

import dev.ryvrook.restapi.config.Setting;
import dev.ryvrook.restapi.config.Settings;

import java.util.List;

/**
 * The keys under {@code rcon} in RestAPI.json, and the two keys of the console module that RCON follows as well,
 * read by name so this package builds without the console module.
 */
public final class RconSettings {
    private RconSettings() {
    }

    public static final Setting<Boolean> ENABLED = Settings.bool("rcon.enabled", false,
            "Listen for Source RCON clients such as mcrcon. The password is any RestAPI key that holds the commands scope.");
    public static final Setting<String> BIND = Settings.text("rcon.bind", "127.0.0.1",
            "Address RCON listens on, an IP literal. RCON has no encryption, so keep 127.0.0.1 and reach it through SSH or a VPN.");
    public static final Setting<Integer> PORT = Settings.integer("rcon.port", 25575, 1, 65535,
            "TCP port of RCON. 25575 is the port Minecraft servers use, so most RCON tools try it first.");
    public static final Setting<Integer> MAX_CONNECTIONS = Settings.integer("rcon.maxConnections", 8, 1, 256,
            "RCON connections open at the same time. One more is closed right after it connects.");
    public static final Setting<Integer> IDLE_SECONDS = Settings.integer("rcon.idleSeconds", 300, 10, 86_400,
            "Seconds a connection may stay silent before it is closed.");
    public static final Setting<Integer> AUTH_FAILURES = Settings.integer("rcon.authFailuresPerMinute", 5, 1, 1_000,
            "Failed logins one address may make per minute. After that its connections are closed until the minute has passed.");

    /** Where command runs are accepted from when the console module is missing from the build. */
    static final List<String> DEFAULT_ALLOW_FROM = List.of("127.0.0.0/8", "::1");
    static final int DEFAULT_TIMEOUT_MS = 5_000;

    /** commands.allowFrom of the console module, which limits RCON as well as POST /api/commands. */
    @SuppressWarnings("unchecked")
    static List<String> commandsAllowFrom() {
        Setting<?> s = find("commands.allowFrom");
        return s != null && s.kind() == Setting.Kind.LIST ? (List<String>) s.get() : DEFAULT_ALLOW_FROM;
    }

    /** commands.timeoutMs of the console module, how long a run collects output. */
    static int commandTimeoutMs() {
        Setting<?> s = find("commands.timeoutMs");
        return s != null && s.kind() == Setting.Kind.INT ? (Integer) s.get() : DEFAULT_TIMEOUT_MS;
    }

    private static Setting<?> find(String key) {
        for (Setting<?> s : Settings.all()) {
            if (s.key().equals(key)) {
                return s;
            }
        }
        return null;
    }
}
