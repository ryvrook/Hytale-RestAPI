package dev.ryvrook.restapi.players;

import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.Universe;
import dev.ryvrook.restapi.http.ApiException;
import dev.ryvrook.restapi.text.Messages;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Turns the {player} of a path or a body into a player. A UUID always resolves, online or not. A name matches an
 * online player first, ignoring case, then the last name seen in {@link NameBook}. Offline mode servers have no
 * profile lookup, so a name never seen on this server does not resolve.
 */
public final class Lookup {
    private static final Pattern UUID_DASHED = Pattern.compile(
            "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");
    private static final Pattern UUID_PLAIN = Pattern.compile("[0-9a-fA-F]{32}");

    /** A resolved player. online is null when the player is not connected. */
    public record Target(UUID uuid, String name, PlayerRef online) {
        /** The name when known, else the UUID, for messages. */
        public String label() {
            return name != null ? name : uuid.toString();
        }
    }

    /** What the pure part found: the UUID, the best known name, and whether it came from an online player. */
    record Found(UUID uuid, String name, boolean online) {
    }

    private final NameBook book;

    public Lookup(NameBook book) {
        this.book = book;
    }

    /** A UUID with or without dashes, or null. */
    static UUID parseUuid(String s) {
        if (s == null) {
            return null;
        }
        String t = s.trim();
        if (UUID_DASHED.matcher(t).matches()) {
            return UUID.fromString(t);
        }
        if (UUID_PLAIN.matcher(t).matches()) {
            return UUID.fromString(t.substring(0, 8) + "-" + t.substring(8, 12) + "-" + t.substring(12, 16) + "-"
                    + t.substring(16, 20) + "-" + t.substring(20));
        }
        return null;
    }

    /** The pure lookup over a map of online UUIDs to names and the name book. Null when nothing matches. */
    static Found resolve(String text, Map<UUID, String> online, NameBook names) {
        if (text == null || text.isBlank()) {
            return null;
        }
        UUID id = parseUuid(text);
        if (id != null) {
            String n = online.get(id);
            if (n != null) {
                return new Found(id, n, true);
            }
            return new Found(id, names == null ? null : names.name(id), false);
        }
        String want = text.trim().toLowerCase(Locale.ROOT);
        for (Map.Entry<UUID, String> e : online.entrySet()) {
            if (e.getValue() != null && e.getValue().toLowerCase(Locale.ROOT).equals(want)) {
                return new Found(e.getKey(), e.getValue(), true);
            }
        }
        UUID seen = names == null ? null : names.find(text.trim());
        return seen == null ? null : new Found(seen, names.name(seen), false);
    }

    /** Online players as UUID to name, read off the world threads. */
    static Map<UUID, PlayerRef> onlineRefs() {
        Map<UUID, PlayerRef> out = new LinkedHashMap<>();
        Universe u = Universe.get();
        if (u != null) {
            for (PlayerRef p : u.getPlayers()) {
                if (p != null && p.getUuid() != null) {
                    out.put(p.getUuid(), p);
                }
            }
        }
        return out;
    }

    /** Any player, online or not, or 404 player_not_found. */
    public Target find(String text) {
        Map<UUID, PlayerRef> refs = onlineRefs();
        Map<UUID, String> names = new LinkedHashMap<>();
        refs.forEach((k, v) -> names.put(k, v.getUsername()));
        Found f = resolve(text, names, book);
        if (f == null) {
            throw ApiException.notFound("player_not_found", Messages.get(PlayersText.NOT_FOUND, "player", text));
        }
        return new Target(f.uuid(), f.name(), f.online() ? refs.get(f.uuid()) : null);
    }

    /** An online player, or 404 player_not_found, or 409 player_offline for a known player who is not connected. */
    public Target online(String text) {
        Target t = find(text);
        if (t.online() == null) {
            throw new ApiException(409, "player_offline", Messages.get(PlayersText.OFFLINE, "player", t.label()));
        }
        return t;
    }
}
