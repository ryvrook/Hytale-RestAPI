package dev.ryvrook.restapi.suite;

import dev.ryvrook.restapi.config.Setting;
import dev.ryvrook.restapi.config.Settings;

/**
 * The keys under {@code suite} in RestAPI.json, read by the route groups of the suite mods. A unit reads them when
 * a request comes in, so a /restapi reload applies them without binding again. Units take a {@link Limits} built
 * from them, which keeps the tests away from the global settings.
 */
public final class SuiteSettings {
    private SuiteSettings() {
    }

    public static final Setting<Integer> DEFAULT_LIMIT = Settings.integer("suite.defaultLimit", 50, 1, 1000,
            "Rows a suite list route answers when the request names no limit.");
    public static final Setting<Integer> MAX_LIMIT = Settings.integer("suite.maxLimit", 500, 1, 10000,
            "Most rows a suite list route answers, a bigger limit is cut to this.");
    public static final Setting<Integer> WAIT_MS = Settings.integer("suite.waitMs", 5000, 100, 60000,
            "Milliseconds a suite route waits for a sibling that reads files, such as the TrackerAPI journal or the Coinage ledger search, before it answers 504.");

    public static final Setting<Integer> TRACKER_RECENT = Settings.integer("suite.tracker.recentPerKind", 100, 0, 1000,
            "TrackerAPI events kept in memory per kind for GET /api/tracker/recent. 0 opens no subscription.");
    public static final Setting<Boolean> TRACKER_RECENT_HIGH_VOLUME = Settings.bool("suite.tracker.recentHighVolume", false,
            "Also keep the high volume kinds such as player_moved and block_damaged for /api/tracker/recent. Applies on the next bind.");

    public static final Setting<Boolean> COINAGE_WRITES = Settings.bool("suite.coinage.writes", true,
            "Mount POST /api/coinage/transfer, give and take for keys with the coinage.write scope. Applies on the next bind.");
    public static final Setting<Integer> COINAGE_MAX_AMOUNT = Settings.integer("suite.coinage.maxAmount", 1000000, 0, Integer.MAX_VALUE,
            "Largest amount one Coinage write moves, in minor units of the currency (1000000 is 10000.00 Coins). 0 means no cap.");
    public static final Setting<Boolean> COINAGE_REQUIRE_IDEMPOTENCY = Settings.bool("suite.coinage.requireIdempotencyKey", false,
            "Refuse a Coinage write that has no Idempotency-Key header with 428.");
    public static final Setting<Integer> COINAGE_IDEMPOTENCY_SECONDS = Settings.integer("suite.coinage.idempotencySeconds", 600, 10, 86400,
            "Seconds the answer to a Coinage write is kept under its key name and Idempotency-Key, so a retry gets the same answer and moves nothing.");

    public static final Setting<Boolean> DOMINION_HOMES = Settings.bool("suite.dominion.homesAndWarps", true,
            "Include faction homes and warps in GET /api/dominion/points and in the faction detail.");

    public static final Setting<Boolean> DOMINION_WRITES = Settings.bool("suite.dominion.writes", true,
            "Mount the Dominion staff routes (disband, leader, power, unclaim, unclaim all) for keys with the dominion.write scope. Applies on the next bind.");

    public static final Setting<Boolean> CHRONICLE_PERFORMANCE = Settings.bool("suite.chronicle.performanceSeries", true,
            "Serve the Chronicle performance series (tick time, memory and the rest outside players.* and server.*) next to the activity series.");

    public static final Setting<Boolean> ATLAS_PROBE = Settings.bool("suite.atlas.probe", true,
            "GET /api/atlas asks the Atlas web server for its own /api/health on the loopback address.");

    /** Reads the limits in force. */
    public static Limits limits() {
        return new Limits(DEFAULT_LIMIT.get(), MAX_LIMIT.get(), WAIT_MS.get());
    }

    /** Touches the class so its keys are known before RestAPI.json is read. */
    public static void touch() {
        DEFAULT_LIMIT.key();
    }
}
