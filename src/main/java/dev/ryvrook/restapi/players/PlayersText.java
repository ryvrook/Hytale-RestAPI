package dev.ryvrook.restapi.players;

import dev.ryvrook.restapi.text.Messages;

/**
 * The sentences of the players module, in messages.json under {@code players.*}. The in game ones are what a
 * kicked, banned or messaged player reads. The rest are the message part of API errors, the error codes beside
 * them stay fixed for scripts.
 */
public final class PlayersText {
    private PlayersText() {
    }

    public static final String KICK_REASON = Messages.define("players.kick.reason", "You were kicked by the server staff.");
    public static final String KICK_BY_KEY = Messages.define("players.kick.byKey", "{reason} (API key {key})");
    public static final String BAN_REASON = Messages.define("players.ban.reason", "Banned by the server staff.");
    public static final String BROADCAST = Messages.define("players.broadcast", "[Server] {text}");

    public static final String NOT_FOUND = Messages.define("players.error.notFound",
            "No player matches {player}. Online players match by name, anyone else by UUID or by a name seen before.");
    public static final String OFFLINE = Messages.define("players.error.offline", "{player} is not online.");
    public static final String NOT_IN_WORLD = Messages.define("players.error.notInWorld",
            "{player} is between worlds right now, try again in a moment.");
    public static final String WORLD_TIMEOUT = Messages.define("players.error.worldTimeout",
            "The world {world} did not answer within {ms} ms. A change may still happen when it catches up.");
    public static final String WORLD_GONE = Messages.define("players.error.worldGone", "The world {world} is not running.");
    public static final String WORLD_NOT_FOUND = Messages.define("players.error.worldNotFound", "No world is named {world}.");
    public static final String OTHER_WORLD = Messages.define("players.error.otherWorld",
            "The target is in another world and players.teleportAcrossWorlds is off.");
    public static final String FIELD_MISSING = Messages.define("players.error.fieldMissing", "The body needs {field}.");
    public static final String FIELD_TYPE = Messages.define("players.error.fieldType", "{field} must be {type}.");
    public static final String FIELD_RANGE = Messages.define("players.error.fieldRange", "{field} must be between {min} and {max}.");
    public static final String FIELD_LONG = Messages.define("players.error.fieldLong", "{field} is longer than {max} characters.");
    public static final String COLOR = Messages.define("players.error.color", "color must be a hex color such as #ffaa00.");
    public static final String ITEM = Messages.define("players.error.item", "No item is named {item}.");
    public static final String MODE = Messages.define("players.error.mode", "mode must be Adventure or Creative.");
    public static final String SECTION = Messages.define("players.error.section",
            "Unknown inventory section {section}. Known sections are {known}.");
    public static final String TELEPORT = Messages.define("players.error.teleport",
            "Send x, y and z, or to with a player name.");
    public static final String GROUP_PROTECTED = Messages.define("players.error.groupProtected",
            "The group {group} is in players.protectedGroups, the API does not change it.");
    public static final String GROUP_UNKNOWN = Messages.define("players.error.groupUnknown", "No permission group is named {group}.");
    public static final String BAN_PERMANENT = Messages.define("players.error.banPermanent",
            "players.maxBanMinutes is {max}, send minutes between 1 and {max}.");
    public static final String NOT_BANNED = Messages.define("players.error.notBanned", "{player} is not banned.");
    public static final String ENGINE = Messages.define("players.error.engine", "The server could not do that, the log has the cause.");
}
