package dev.ryvrook.restapi.rcon;

import dev.ryvrook.restapi.text.Messages;

/** The sentences RCON clients and /restapi status read. */
public final class RconText {
    private RconText() {
    }

    public static final String NO_CONSOLE = Messages.define("rcon.noConsole",
            "No command ran. The console module is not running, check commands.enabled in RestAPI.json.");
    public static final String DENIED = Messages.define("rcon.denied",
            "{command} is on the deny list and was not run.");
    public static final String FAILED = Messages.define("rcon.failed",
            "{command} failed. The server log has the cause.");
    public static final String TIMED_OUT = Messages.define("rcon.timedOut",
            "{command} was still running after {ms} ms. The lines above are what it sent until then.");
    public static final String KEY_GONE = Messages.define("rcon.keyGone",
            "The key this connection logged in with was revoked or lost the commands scope. Log in again.");
    public static final String UNKNOWN_TYPE = Messages.define("rcon.unknownType", "Unknown request {type}");

    public static final String STATUS_ON = Messages.define("rcon.status.on",
            "RCON on {address} with {connections} open of {max}.");
    public static final String STATUS_OFF = Messages.define("rcon.status.off", "RCON is off because rcon.enabled is false.");
    public static final String STATUS_DOWN = Messages.define("rcon.status.down", "RCON is down, {reason}.");
    public static final String STATUS_EXPOSED = Messages.define("rcon.status.exposed",
            "RCON listens beyond this machine and sends keys and commands in clear. Reach it through SSH or a VPN.");
}
