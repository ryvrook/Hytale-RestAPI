package dev.ryvrook.restapi.siblings;

import dev.ryvrook.restapi.auth.Scope;

import java.util.Locale;

/**
 * The suite mods RestAPI knows. Each one gets a route group under {@code /api/<id>} with its own scope, a setting
 * {@code siblings.<id>} and a row in /restapi status. The minimum is the version the routes were written against,
 * hooksSince the first version that hands its API out through enable and disable hooks and reloads its dependents.
 * Every unit binds through those hooks and falls back to the older calls when {@code always} binds an older copy.
 */
public enum Sibling {
    TRACKER("tracker", "TrackerAPI", "1.1.0", "1.1.0", Scope.TRACKER, "dev.ryvrook.restapi.suite.tracker.TrackerRoutes",
            "Counters, leaderboards, recent events and the journal under /api/tracker"),
    COINAGE("coinage", "Coinage", "1.3.0", "1.2.0", Scope.COINAGE, "dev.ryvrook.restapi.suite.coinage.CoinageRoutes",
            "Balances, accounts, the ledger, moves made under the key's own name and the rich list under /api/coinage",
            Scope.COINAGE_WRITE),
    DOMINION("dominion", "Dominion", "0.7.0", "0.7.0", Scope.DOMINION, "dev.ryvrook.restapi.suite.dominion.DominionRoutes",
            "Every faction, claims, sieges with both sides, relations and staff actions under /api/dominion",
            Scope.DOMINION_WRITE),
    FEATS("feats", "Feats", "1.7.0", "1.7.0", Scope.FEATS, "dev.ryvrook.restapi.suite.feats.FeatsRoutes",
            "Feat progress, the points board, unlocks and world records under /api/feats"),
    CHRONICLE("chronicle", "ChronicleStats", "1.5.0", "1.5.0", Scope.CHRONICLE,
            "dev.ryvrook.restapi.suite.chronicle.ChronicleRoutes",
            "Player stats, boards per window, server totals and the server series under /api/chronicle"),
    ATLAS("atlas", "Atlas", "1.8.0", "1.8.0", Scope.ATLAS, "dev.ryvrook.restapi.suite.atlas.AtlasRoutes",
            "The map link, render status, worlds and markers under /api/atlas"),
    LULLABY("lullaby", "Lullaby", "1.1.0", "1.1.0", Scope.LULLABY, "dev.ryvrook.restapi.suite.lullaby.LullabyRoutes",
            "Sleepers, sleep progress and nights skipped per world under /api/lullaby"),
    SOCIALBRIDGE("socialbridge", "SocialBridge", "0.2.0", null, Scope.SOCIALBRIDGE,
            "dev.ryvrook.restapi.suite.socialbridge.SocialBridgeRoutes",
            "Discord account links both ways and posts to its channels under /api/socialbridge", Scope.SOCIALBRIDGE_WRITE);

    public static final String GROUP = "Ryvrook";

    private final String id;
    private final String pluginName;
    private final String minimum;
    private final String hooksSince;
    private final Scope scope;
    private final String unitClass;
    private final String adds;
    private final Scope writeScope;

    Sibling(String id, String pluginName, String minimum, String hooksSince, Scope scope, String unitClass, String adds) {
        this(id, pluginName, minimum, hooksSince, scope, unitClass, adds, null);
    }

    Sibling(String id, String pluginName, String minimum, String hooksSince, Scope scope, String unitClass, String adds,
            Scope writeScope) {
        this.writeScope = writeScope;
        this.id = id;
        this.pluginName = pluginName;
        this.minimum = minimum;
        this.hooksSince = hooksSince;
        this.scope = scope;
        this.unitClass = unitClass;
        this.adds = adds;
    }

    public String id() {
        return id;
    }

    /** The manifest Name, such as ChronicleStats. */
    public String pluginName() {
        return pluginName;
    }

    /** Group and name as the engine writes them, such as Ryvrook:Dominion. */
    public String pluginId() {
        return GROUP + ":" + pluginName;
    }

    public String minimum() {
        return minimum;
    }

    /**
     * The first version with static onEnable and onDisable hooks and a dependents pass, which reloads RestAPI two
     * seconds after the sibling itself was reloaded. From this version on the unit binds through the hooks. Null for
     * SocialBridge, which has the hooks but no dependents pass, so RestAPI reloads itself after it.
     */
    public String hooksSince() {
        return hooksSince;
    }

    /** True when this version of the sibling reloads RestAPI after its own reload. */
    public boolean reloadsDependents(String version) {
        return hooksSince != null && version != null && !dev.ryvrook.restapi.util.Versions.below(version, hooksSince);
    }

    public Scope scope() {
        return scope;
    }

    /** The scope of the group's write routes, such as coinage.write, or null when the group only reads. */
    public Scope writeScope() {
        return writeScope;
    }

    /** The class of the route group, loaded by name only after the presence check passed. */
    public String unitClass() {
        return unitClass;
    }

    /** What appears while it is bound, for the README matrix and /api/server. */
    public String adds() {
        return adds;
    }

    /** The route group name in the router. */
    public String group() {
        return "suite." + id;
    }

    public static Sibling byId(String id) {
        for (Sibling s : values()) {
            if (s.id.equals(id == null ? null : id.toLowerCase(Locale.ROOT))) {
                return s;
            }
        }
        return null;
    }
}
