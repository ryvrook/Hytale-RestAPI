package dev.ryvrook.restapi.suite.feats;

import dev.ryvrook.feats.api.FeatInfo;
import dev.ryvrook.feats.api.FeatProgress;
import dev.ryvrook.feats.api.LeaderboardEntry;
import dev.ryvrook.feats.api.WorldRecord;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * The part of {@code FeatsApi} the routes read. FeatsApi is a final class that only Feats can make, so the unit
 * reads it through this and the tests give a stand in.
 */
public interface FeatsSource {
    List<FeatInfo> feats();

    int points(UUID player);

    Set<String> unlocked(UUID player);

    FeatProgress progress(UUID player, String featId);

    String title(UUID player);

    int rank(UUID player);

    Map<String, WorldRecord> milestones();

    Map<String, WorldRecord> firsts();

    long stats(UUID player, String key);

    /**
     * The points board, best first, from {@code FeatsApi.top} (Feats 1.7.0 and newer). Null when this Feats has no
     * such call, the routes then read export/feats.json.
     */
    default List<LeaderboardEntry> top(int limit) {
        return null;
    }
}
