package dev.ryvrook.restapi.suite.feats;

import dev.ryvrook.feats.api.FeatInfo;
import dev.ryvrook.feats.api.FeatProgress;
import dev.ryvrook.feats.api.FeatsApi;
import dev.ryvrook.feats.api.WorldRecord;
import dev.ryvrook.restapi.http.ApiException;
import dev.ryvrook.restapi.suite.SuiteText;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** {@link FeatsSource} over {@code FeatsApi.get()}, asked again on every call since Feats has no enable hook. */
final class FeatsBridge implements FeatsSource {
    private static FeatsApi api() {
        FeatsApi a = FeatsApi.get();
        if (a == null) {
            throw new ApiException(503, "sibling_gone", SuiteText.get(SuiteText.SIBLING_GONE, "mod", FeatsRoutes.MOD));
        }
        return a;
    }

    static boolean present() {
        return FeatsApi.get() != null;
    }

    @Override
    public List<FeatInfo> feats() {
        return api().feats();
    }

    @Override
    public int points(UUID player) {
        return api().points(player);
    }

    @Override
    public Set<String> unlocked(UUID player) {
        return api().unlocked(player);
    }

    @Override
    public FeatProgress progress(UUID player, String featId) {
        return api().progress(player, featId);
    }

    @Override
    public String title(UUID player) {
        return api().title(player);
    }

    @Override
    public int rank(UUID player) {
        return api().rank(player);
    }

    @Override
    public Map<String, WorldRecord> milestones() {
        return api().milestones();
    }

    @Override
    public Map<String, WorldRecord> firsts() {
        return api().firsts();
    }

    @Override
    public long stats(UUID player, String key) {
        return api().stats(player, key);
    }
}
