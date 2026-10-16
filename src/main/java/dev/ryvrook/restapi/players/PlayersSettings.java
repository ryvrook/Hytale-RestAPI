package dev.ryvrook.restapi.players;

import dev.ryvrook.restapi.config.Setting;
import dev.ryvrook.restapi.config.Settings;

import java.util.List;

/** The keys under {@code players} in RestAPI.json. */
public final class PlayersSettings {
    private PlayersSettings() {
    }

    public static final Setting<Boolean> ENABLED = Settings.bool("players.enabled", true,
            "Mount the player, ban, whitelist, group and broadcast routes. Off removes every one of them on the next /restapi reload.");
    public static final Setting<Boolean> READ_ONLY = Settings.bool("players.readOnly", false,
            "Mount only the read routes of the players module. Kicks, bans, teleports, gives, group changes and broadcasts answer 404.");
    public static final Setting<Integer> WORLD_TIMEOUT_MS = Settings.integer("players.worldTimeoutMs", 2_000, 100, 30_000,
            "Milliseconds a request waits for a world thread to read or change a player. Past it the answer is 504 world_timeout and a change may still land a moment later.");
    public static final Setting<Integer> MAX_GIVE_AMOUNT = Settings.integer("players.maxGiveAmount", 1_000, 1, 1_000_000,
            "Largest amount POST /api/players/{player}/give accepts in one call.");
    public static final Setting<Boolean> TELEPORT_ACROSS_WORLDS = Settings.bool("players.teleportAcrossWorlds", true,
            "Let a teleport move a player into another world. Off answers 409 other_world when the target is in a different world.");
    public static final Setting<Boolean> KICK_NAMES_KEY = Settings.bool("players.kickNamesKey", false,
            "Add the name of the API key to the reason a kicked or banned player sees.");
    public static final Setting<Boolean> ALLOW_KILL = Settings.bool("players.allowKill", true,
            "Mount POST /api/players/{player}/kill.");
    public static final Setting<Boolean> ALLOW_CLEAR = Settings.bool("players.allowClear", true,
            "Mount POST /api/players/{player}/clear, which empties inventory sections.");
    public static final Setting<Boolean> ALLOW_REFER = Settings.bool("players.allowRefer", true,
            "Mount POST /api/players/{player}/refer, which sends a player to another server.");
    public static final Setting<List<String>> PROTECTED_GROUPS = Settings.list("players.protectedGroups", List.of("hytale:Admin"),
            "Permission groups the API never adds a player to or takes a player out of, so a key with the players scope cannot make an operator. hytale:Admin is the group /op adds. Empty allows every group.");
    public static final Setting<Integer> MAX_BAN_MINUTES = Settings.integer("players.maxBanMinutes", 0, 0, 52_560_000,
            "Longest ban in minutes the API may give. 0 allows permanent bans, any other value refuses bans without minutes or longer than it.");
    public static final Setting<Integer> MAX_TEXT_LENGTH = Settings.integer("players.maxTextLength", 500, 10, 4_000,
            "Longest text in characters for messages, broadcasts, titles and reasons.");
    public static final Setting<Integer> PAGE_SIZE = Settings.integer("players.pageSize", 100, 1, 10_000,
            "Entries per page of GET /api/players/offline and GET /api/bans when the request sets no limit.");
    public static final Setting<Integer> MAX_PAGE_SIZE = Settings.integer("players.maxPageSize", 1_000, 1, 100_000,
            "Largest limit a request may ask for on the paged player lists.");
    public static final Setting<Boolean> REMEMBER_NAMES = Settings.bool("players.rememberNames", true,
            "Keep names.json, the last name seen for each UUID, so offline players and bans resolve by name. Off forgets nothing already written but stops adding to it.");
}
