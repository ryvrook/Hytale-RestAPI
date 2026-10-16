package dev.ryvrook.restapi.suite.chronicle;

import dev.ryvrook.chronicle.api.Board;
import dev.ryvrook.chronicle.api.ChronicleApi;
import dev.ryvrook.chronicle.api.RecordBroken;
import dev.ryvrook.chronicle.api.Resolution;
import dev.ryvrook.chronicle.api.Series;
import dev.ryvrook.chronicle.api.StatDef;
import dev.ryvrook.chronicle.api.Window;
import dev.ryvrook.restapi.http.ApiException;
import dev.ryvrook.restapi.suite.SuiteText;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * {@link ChronicleSource} over the ChronicleApi that {@code ChronicleApi.onEnable} handed out last (Chronicle Stats
 * 1.5.0 and newer). An older Chronicle bound under always has no hooks and is asked through
 * {@code ChronicleApi.get()} on every call, and its missing calls answer null so the routes read the export.
 */
final class ChronicleBridge implements ChronicleSource {
    private volatile ChronicleApi current;
    private Consumer<ChronicleApi> enableHook;
    private Runnable disableHook;
    private Supplier<ChronicleApi> source = () -> current;

    /** Registers the hooks. Throws when Chronicle has not handed out its API, with the hooks taken back. */
    void bind() {
        Consumer<ChronicleApi> enable = a -> current = a;
        Runnable disable = () -> current = null;
        try {
            ChronicleApi.onEnable(enable);
            ChronicleApi.onDisable(disable);
        } catch (LinkageError e) {
            // Chronicle Stats before 1.5.0.
            if (ChronicleApi.get() == null) {
                throw new IllegalStateException("Chronicle Stats has not published its API yet");
            }
            source = ChronicleApi::get;
            return;
        }
        enableHook = enable;
        disableHook = disable;
        if (current == null) {
            unbind();
            throw new IllegalStateException("Chronicle Stats has not handed out its API yet");
        }
    }

    void unbind() {
        if (enableHook != null) {
            ChronicleApi.removeHooks(enableHook, disableHook);
            enableHook = null;
            disableHook = null;
        }
        current = null;
    }

    private ChronicleApi api() {
        ChronicleApi a = source.get();
        if (a == null) {
            throw new ApiException(503, "sibling_gone", SuiteText.get(SuiteText.SIBLING_GONE, "mod", ChronicleRoutes.MOD));
        }
        return a;
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

    @Override
    public String version() {
        ChronicleApi a = api();
        try {
            return a.version();
        } catch (LinkageError e) {
            return null;
        }
    }

    @Override
    public List<String> boardKeys() {
        ChronicleApi a = api();
        try {
            return a.boardKeys();
        } catch (LinkageError e) {
            return null;
        }
    }

    @Override
    public CompletableFuture<Board> board(String key, Window window, String worldKey, int limit) {
        ChronicleApi a = api();
        try {
            return worldKey == null ? CompletableFuture.completedFuture(a.board(key, window, null, limit))
                    : a.boardAsync(key, window, worldKey, limit);
        } catch (LinkageError e) {
            return null;
        }
    }

    @Override
    public Map<String, Long> serverTotals() {
        ChronicleApi a = api();
        try {
            return a.serverTotals();
        } catch (LinkageError e) {
            return null;
        }
    }

    @Override
    public int online() {
        ChronicleApi a = api();
        try {
            return a.online();
        } catch (LinkageError e) {
            return -1;
        }
    }

    @Override
    public List<RecordBroken> recentRecords(int limit) {
        ChronicleApi a = api();
        try {
            return a.recentRecords(limit);
        } catch (LinkageError e) {
            return null;
        }
    }

    @Override
    public Set<String> seriesIds() {
        ChronicleApi a = api();
        try {
            return a.seriesIds();
        } catch (LinkageError e) {
            return null;
        }
    }

    @Override
    public Series series(String id, Resolution resolution, long from, long to) {
        ChronicleApi a = api();
        try {
            return a.series(id, resolution, from, to);
        } catch (LinkageError e) {
            return null;
        }
    }
}
