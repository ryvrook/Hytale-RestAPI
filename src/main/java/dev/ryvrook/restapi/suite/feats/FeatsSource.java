package dev.ryvrook.restapi.suite.feats;

import dev.ryvrook.feats.api.FeatInfo;
import dev.ryvrook.feats.api.FeatProgress;
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
}
