package dev.ryvrook.restapi.suite.chronicle;

import dev.ryvrook.chronicle.api.ChronicleApi;
import dev.ryvrook.chronicle.api.StatDef;
import dev.ryvrook.chronicle.api.Window;
import dev.ryvrook.restapi.http.ApiException;
import dev.ryvrook.restapi.suite.SuiteText;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/** {@link ChronicleSource} over {@code ChronicleApi.get()}, asked again on every call. */
final class ChronicleBridge implements ChronicleSource {
    private static ChronicleApi api() {
        ChronicleApi a = ChronicleApi.get();
        if (a == null) {
            throw new ApiException(503, "sibling_gone", SuiteText.get(SuiteText.SIBLING_GONE, "mod", ChronicleRoutes.MOD));
        }
        return a;
    }

    static boolean present() {
        return ChronicleApi.get() != null;
    }

    @Override
    public int apiVersion() {
        return api().apiVersion();
    }

    @Override
    public List<StatDef> stats() {
        return api().stats();
    }

    @Override
    public CompletableFuture<Map<String, Long>> totals(UUID player) {
        return api().totalsAsync(player);
    }

    @Override
    public long window(UUID player, String key, Window window, String worldKey) {
        return api().window(player, key, window, worldKey);
    }

    @Override
    public Map<String, Long> perId(UUID player, String prefix) {
        return api().perId(player, prefix);
    }

    @Override
    public String worldKey(String world) {
        return api().worldKey(world);
    }

    @Override
    public boolean isTracked(UUID player) {
        return api().isTracked(player);
    }
}
