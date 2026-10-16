package dev.ryvrook.restapi.suite;

import dev.ryvrook.restapi.text.Messages;

/**
 * The sentences the suite routes answer with, under ids that start with {@code suite.}. The error codes next to
 * them stay fixed for scripts, the sentences can be reworded in messages.json.
 */
public final class SuiteText {
    private SuiteText() {
    }

    public static final String BAD_LIMIT = Messages.define("suite.badLimit", "limit must be a whole number from 1 up.");
    public static final String BAD_OFFSET = Messages.define("suite.badOffset", "offset must be a whole number from 0 up.");
    public static final String BAD_NUMBER = Messages.define("suite.badNumber", "{name} must be a whole number, got {value}.");
    public static final String BAD_TIME = Messages.define("suite.badTime",
            "{name} must be epoch milliseconds or an ISO instant such as 2026-10-16T12:00:00Z.");
    public static final String PLAYER_NOT_FOUND = Messages.define("suite.playerNotFound",
            "No player named {player} is known to {mod}. Use the UUID for a player who is offline.");
    public static final String TIMEOUT = Messages.define("suite.timeout",
            "{mod} did not answer within {ms} ms. Try again or raise suite.waitMs.");
    public static final String SIBLING_FAILED = Messages.define("suite.siblingFailed", "{mod} failed to answer, the server log has the cause.");
    public static final String SIBLING_GONE = Messages.define("suite.siblingGone",
            "{mod} is stopping or was reloaded. The routes come back when RestAPI binds it again.");
    public static final String FILE_MISSING = Messages.define("suite.fileMissing",
            "{mod} has not written {file} yet. It writes the file on its own timer.");
    public static final String FILE_BROKEN = Messages.define("suite.fileBroken", "{file} from {mod} could not be read.");

    public static final String UNKNOWN_KIND = Messages.define("suite.tracker.unknownKind",
            "{kind} is not a TrackerAPI event kind. GET /api/tracker/kinds lists them.");
    public static final String NO_SNAPSHOT = Messages.define("suite.tracker.noSnapshot", "TrackerAPI has no snapshot of {player}, they are not online.");
    public static final String RECENT_OFF = Messages.define("suite.tracker.recentOff",
            "suite.tracker.recentPerKind is 0, so RestAPI keeps no recent events.");
    public static final String BAD_BOX = Messages.define("suite.tracker.badBox",
            "A box needs all of minX, minY, minZ, maxX, maxY and maxZ.");
    public static final String NO_BOARD = Messages.define("suite.tracker.noBoard",
            "TrackerAPI keeps no leaderboard for {key}. GET /api/tracker/leaderboards lists them.");

    public static final String UNKNOWN_CURRENCY = Messages.define("suite.coinage.unknownCurrency", "Coinage has no currency {currency}.");
    public static final String ACCOUNT_NOT_FOUND = Messages.define("suite.coinage.accountNotFound", "Coinage has no account {account}.");
    public static final String TX_NOT_FOUND = Messages.define("suite.coinage.txNotFound", "Coinage has no transaction {id}.");
    public static final String BAD_AMOUNT = Messages.define("suite.coinage.badAmount",
            "amount must be above 0 and fit the currency, such as \"12.50\" for Coins, or give minor as whole minor units.");
    public static final String OVER_CAP = Messages.define("suite.coinage.overCap",
            "{amount} is more than suite.coinage.maxAmount allows for one move ({cap}).");
    public static final String NEED_IDEMPOTENCY = Messages.define("suite.coinage.needIdempotency",
            "This server requires an Idempotency-Key header on Coinage writes.");
    public static final String BAD_IDEMPOTENCY = Messages.define("suite.coinage.badIdempotency",
            "Idempotency-Key must be 1 to 128 visible ASCII characters.");
    public static final String NEED_TARGET = Messages.define("suite.coinage.needTarget", "Name the {field} account, a player name, a UUID or a shared account handle.");
    public static final String NOT_TEXT = Messages.define("suite.notText", "{field} must be text.");
    public static final String BAD_DATE = Messages.define("suite.coinage.badDate", "Dates are written as 2026-10-16.");
    public static final String BAD_RANGE = Messages.define("suite.coinage.badRange", "from must be on or before to, at most 366 days apart.");
    public static final String BAD_TYPE = Messages.define("suite.coinage.badType", "type must be one of {types}.");

    public static final String FACTION_NOT_FOUND = Messages.define("suite.dominion.factionNotFound", "Dominion has no faction with the tag {tag}.");
    public static final String NEED_COORDS = Messages.define("suite.dominion.needCoords", "Give world, x and z as block coordinates.");
    public static final String NO_STAFF = Messages.define("suite.dominion.noStaff",
            "This Dominion has no staff actions in its API, they came with Dominion 0.7.0.");
    public static final String NO_SIEGE_HISTORY = Messages.define("suite.dominion.noSiegeHistory",
            "This Dominion keeps no siege history in its API, it came with Dominion 0.7.0.");
    public static final String NEED_PLAYER = Messages.define("suite.dominion.needPlayer", "Name the player, by name or UUID.");
    public static final String NEED_POWER = Messages.define("suite.dominion.needPower", "power must be a number, such as 10 or 12.5.");
    public static final String NEED_CHUNK = Messages.define("suite.dominion.needChunk",
            "Give world, chunkX and chunkZ, whole chunk coordinates (a block x of 100 is chunk 3).");
    public static final String UNKNOWN_WORLD = Messages.define("suite.unknownWorld", "There is no world named {world}.");

    public static final String FEAT_NOT_FOUND = Messages.define("suite.feats.featNotFound", "Feats has no feat {feat}.");

    public static final String STAT_NOT_FOUND = Messages.define("suite.chronicle.statNotFound",
            "Chronicle Stats has no stat {stat}. GET /api/chronicle/stats lists them.");
    public static final String BAD_WINDOW = Messages.define("suite.chronicle.badWindow", "window must be one of today, week, month or all.");
    public static final String NOT_EXPORTED = Messages.define("suite.chronicle.notExported",
            "This Chronicle Stats exports only the all time board of {stat}. Boards per window came with Chronicle Stats 1.5.0.");

    public static final String NO_SERIES = Messages.define("suite.chronicle.noSeries",
            "This Chronicle Stats has no server series in its API, they came with Chronicle Stats 1.5.0.");
    public static final String SERIES_NOT_FOUND = Messages.define("suite.chronicle.seriesNotFound",
            "Chronicle Stats has no series {id} that RestAPI serves. GET /api/chronicle/series lists them.");
    public static final String BAD_RESOLUTION = Messages.define("suite.chronicle.badResolution", "resolution must be minute, hour or day.");

    public static final String UNKNOWN_LAYER = Messages.define("suite.atlas.unknownLayer", "Atlas has no layer {layer}.");
    public static final String ATLAS_OLD = Messages.define("suite.atlas.old",
            "This Atlas has no render status or world list in its API, they came with Atlas 1.8.0.");

    /** The sentence with its placeholders filled. */
    public static String get(String id, Object... pairs) {
        return Messages.get(id, pairs);
    }
}
