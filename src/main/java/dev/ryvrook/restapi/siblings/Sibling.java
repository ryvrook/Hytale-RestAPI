package dev.ryvrook.restapi.siblings;

import dev.ryvrook.restapi.auth.Scope;

import java.util.Locale;

/**
 * The suite mods RestAPI knows. Each one gets a route group under {@code /api/<id>} with its own scope, a setting
 * {@code siblings.<id>} and a row in /restapi status. The minimum is the version the routes were written against.
 */
public enum Sibling {
    TRACKER("tracker", "TrackerAPI", "1.1.0", Scope.TRACKER, "dev.ryvrook.restapi.suite.tracker.TrackerRoutes",
            "Counters, leaderboards, recent events and the journal under /api/tracker"),
    COINAGE("coinage", "Coinage", "1.2.0", Scope.COINAGE, "dev.ryvrook.restapi.suite.coinage.CoinageRoutes",
            "Balances, accounts, ledger moves and the rich list under /api/coinage"),
    DOMINION("dominion", "Dominion", "0.6.2", Scope.DOMINION, "dev.ryvrook.restapi.suite.dominion.DominionRoutes",
            "Factions, claims, sieges and relations under /api/dominion"),
    FEATS("feats", "Feats", "1.6.0", Scope.FEATS, "dev.ryvrook.restapi.suite.feats.FeatsRoutes",
            "Feat progress, unlocks and world records under /api/feats"),
    CHRONICLE("chronicle", "ChronicleStats", "1.4.0", Scope.CHRONICLE, "dev.ryvrook.restapi.suite.chronicle.ChronicleRoutes",
            "Player and server stats under /api/chronicle"),
    ATLAS("atlas", "Atlas", "1.7.0", Scope.ATLAS, "dev.ryvrook.restapi.suite.atlas.AtlasRoutes",
            "The map link, map status and markers under /api/atlas"),
    LULLABY("lullaby", "Lullaby", "1.0.1", Scope.LULLABY, "dev.ryvrook.restapi.suite.lullaby.LullabyRoutes",
            "Sleep state per world under /api/lullaby");

    public static final String GROUP = "Ryvrook";

    private final String id;
    private final String pluginName;
    private final String minimum;
    private final Scope scope;
    private final String unitClass;
    private final String adds;

    Sibling(String id, String pluginName, String minimum, Scope scope, String unitClass, String adds) {
        this.id = id;
        this.pluginName = pluginName;
        this.minimum = minimum;
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

    public Scope scope() {
        return scope;
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
