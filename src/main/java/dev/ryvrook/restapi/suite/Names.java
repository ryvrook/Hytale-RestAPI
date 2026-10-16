package dev.ryvrook.restapi.suite;

import com.hypixel.hytale.server.core.NameMatching;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.Universe;
import dev.ryvrook.restapi.http.ApiException;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;

/**
 * Turns a {@code {player}} path segment into a UUID. A UUID is taken as it is. A name is looked up among the
 * online players first, then in each lookup the unit gives, such as Coinage's name index or the Dominion rosters,
 * so an offline player is found when the sibling has seen them.
 */
public final class Names {
    private final List<Function<String, Optional<UUID>>> lookups;
    private final String mod;

    @SafeVarargs
    public Names(String mod, Function<String, Optional<UUID>>... lookups) {
        this.mod = mod;
        this.lookups = List.of(lookups);
    }

    /** The UUID, or 404 player_not_found. */
    public UUID resolve(String player) {
        UUID direct = Json.uuid(player);
        if (direct != null) {
            return direct;
        }
        for (Function<String, Optional<UUID>> l : lookups) {
            Optional<UUID> found;
            try {
                found = l.apply(player);
            } catch (ApiException e) {
                throw e;
            } catch (RuntimeException e) {
                found = Optional.empty();
            }
            if (found != null && found.isPresent()) {
                return found.get();
            }
        }
        throw ApiException.notFound("player_not_found", SuiteText.get(SuiteText.PLAYER_NOT_FOUND, "player", player, "mod", mod));
    }

    /** The online player with that name, any case. Empty off a running server, as in the tests. */
    public static Optional<UUID> online(String name) {
        try {
            Universe u = Universe.get();
            if (u == null) {
                return Optional.empty();
            }
            PlayerRef p = u.getPlayerByUsername(name, NameMatching.EXACT_IGNORE_CASE);
            return p == null ? Optional.empty() : Optional.ofNullable(p.getUuid());
        } catch (RuntimeException | LinkageError e) {
            return Optional.empty();
        }
    }
}
