package dev.ryvrook.restapi.suite.feats;

import dev.ryvrook.feats.api.FeatInfo;
import dev.ryvrook.feats.api.FeatProgress;
import dev.ryvrook.feats.api.FeatsApi;
import dev.ryvrook.feats.api.LeaderboardEntry;
import dev.ryvrook.feats.api.WorldRecord;
import dev.ryvrook.restapi.http.ApiException;
import dev.ryvrook.restapi.suite.SuiteText;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * {@link FeatsSource} over the FeatsApi that {@code FeatsApi.onEnable} handed out last (Feats 1.7.0 and newer). An
 * older Feats bound under always has no hooks and is asked through {@code FeatsApi.get()} on every call.
 */
final class FeatsBridge implements FeatsSource {
    private volatile FeatsApi current;
    private Consumer<FeatsApi> enableHook;
    private Runnable disableHook;
    private Supplier<FeatsApi> source = () -> current;

    /** Registers the hooks. Throws when Feats has not handed out its API, with the hooks taken back. */
    void bind() {
        Consumer<FeatsApi> enable = a -> current = a;
        Runnable disable = () -> current = null;
        try {
            FeatsApi.onEnable(enable);
            FeatsApi.onDisable(disable);
        } catch (NoSuchMethodError e) {
            // Feats before 1.7.0.
            if (FeatsApi.get() == null) {
                throw new IllegalStateException("Feats has not published its API yet");
            }
            source = FeatsApi::get;
            return;
        }
        enableHook = enable;
        disableHook = disable;
        if (current == null) {
            unbind();
            throw new IllegalStateException("Feats has not handed out its API yet");
        }
    }

    void unbind() {
        if (enableHook != null) {
            FeatsApi.removeHooks(enableHook, disableHook);
            enableHook = null;
            disableHook = null;
        }
        current = null;
    }

    private FeatsApi api() {
        FeatsApi a = source.get();
        if (a == null) {
            throw new ApiException(503, "sibling_gone", SuiteText.get(SuiteText.SIBLING_GONE, "mod", FeatsRoutes.MOD));
        }
        return a;
    }

    @Override
    public List<LeaderboardEntry> top(int limit) {
        try {
            return api().top(limit);
        } catch (NoSuchMethodError e) {
            return null;
        }
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
