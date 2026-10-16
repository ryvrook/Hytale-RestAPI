package dev.ryvrook.restapi.auth;

import java.util.Collection;
import java.util.EnumSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * What a key may do. A route names the one scope it needs. Reads of the server state need READ, changes need the
 * scope of their area. The suite scopes only matter while that sibling is installed and bound.
 */
public enum Scope {
    READ("read", "Read server, player, world and plugin state."),
    PLAYERS("players", "Change players, such as kick, ban, teleport, give and message."),
    COMMANDS("commands", "Run console commands and read their output."),
    CONSOLE("console", "Read the server log and follow it as a stream."),
    PLUGINS("plugins", "Load, unload and reload plugins."),
    SERVER("server", "Save worlds, change time and weather, stop the server."),
    METRICS("metrics", "Read /metrics in Prometheus text format."),
    EVENTS("events", "Follow the event stream and manage webhooks."),
    TRACKER("tracker", "Read TrackerAPI counters, leaderboards and the journal."),
    COINAGE("coinage", "Read Coinage balances, accounts and the ledger."),
    COINAGE_WRITE("coinage.write", "Move money through Coinage."),
    DOMINION("dominion", "Read Dominion factions, claims, sieges and relations."),
    DOMINION_WRITE("dominion.write", "Change Dominion factions as staff would."),
    FEATS("feats", "Read Feats progress and unlocks."),
    CHRONICLE("chronicle", "Read Chronicle Stats player and server stats."),
    ATLAS("atlas", "Read the Atlas map link and status."),
    LULLABY("lullaby", "Read Lullaby sleep state per world.");

    /** The word that stands for every scope in keys.json and in commands. */
    public static final String ALL = "all";

    private final String id;
    private final String description;

    Scope(String id, String description) {
        this.id = id;
        this.description = description;
    }

    public String id() {
        return id;
    }

    public String description() {
        return description;
    }

    /** The scope with this id, or null. Ids are matched without regard to case. */
    public static Scope byId(String id) {
        if (id == null) {
            return null;
        }
        String s = id.trim().toLowerCase(Locale.ROOT);
        for (Scope x : values()) {
            if (x.id.equals(s)) {
                return x;
            }
        }
        return null;
    }

    /**
     * Parses scope ids given as a list or as one entry separated by commas, spaces or plus signs, such as
     * {@code read,players} or {@code read players}. {@code all}
     * gives every scope. Throws with the unknown ids named, so a typo never makes a key with fewer rights than
     * the admin meant without telling them.
     */
    public static Set<Scope> parse(Collection<String> ids) {
        EnumSet<Scope> out = EnumSet.noneOf(Scope.class);
        Set<String> unknown = new LinkedHashSet<>();
        for (String raw : ids) {
            if (raw == null) {
                continue;
            }
            for (String part : raw.split("[,+\\s]+")) {
                String p = part.trim();
                if (p.isEmpty()) {
                    continue;
                }
                if (p.equalsIgnoreCase(ALL)) {
                    out.addAll(EnumSet.allOf(Scope.class));
                    continue;
                }
                Scope s = byId(p);
                if (s == null) {
                    unknown.add(p);
                } else {
                    out.add(s);
                }
            }
        }
        if (!unknown.isEmpty()) {
            throw new IllegalArgumentException("Unknown scope " + String.join(", ", unknown) + ". Known scopes are "
                    + String.join(", ", ids()) + " and all");
        }
        return out;
    }

    /** Every scope id in declaration order. */
    public static List<String> ids() {
        return java.util.Arrays.stream(values()).map(Scope::id).toList();
    }

    /** The ids of a set, with {@code all} when it holds every scope. */
    public static List<String> describe(Set<Scope> scopes) {
        if (scopes.containsAll(EnumSet.allOf(Scope.class))) {
            return List.of(ALL);
        }
        return scopes.stream().sorted().map(Scope::id).toList();
    }
}
