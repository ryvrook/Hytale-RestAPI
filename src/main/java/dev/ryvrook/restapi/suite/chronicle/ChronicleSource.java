package dev.ryvrook.restapi.suite.chronicle;

import dev.ryvrook.chronicle.api.Board;
import dev.ryvrook.chronicle.api.RecordBroken;
import dev.ryvrook.chronicle.api.Resolution;
import dev.ryvrook.chronicle.api.Series;
import dev.ryvrook.chronicle.api.StatDef;
import dev.ryvrook.chronicle.api.Window;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * The part of {@code ChronicleApi} the routes read. ChronicleApi is final, so the tests give a stand in. The calls
 * with a default came with Chronicle Stats 1.5.0 (API version 2). Their default, null, means this Chronicle has no
 * such call, and the routes read {@code export/stats.json} instead.
 */
public interface ChronicleSource {
    int apiVersion();

    List<StatDef> stats();

    CompletableFuture<Map<String, Long>> totals(UUID player);

    long window(UUID player, String key, Window window, String worldKey);

    Map<String, Long> perId(UUID player, String prefix);

    String worldKey(String world);

    boolean isTracked(UUID player);

    default String version() {
        return null;
    }

    default List<String> boardKeys() {
        return null;
    }

    /** A board built or warming, world null for all worlds. A world filtered board may come later. */
    default CompletableFuture<Board> board(String key, Window window, String worldKey, int limit) {
        return null;
    }

    default Map<String, Long> serverTotals() {
        return null;
    }

    /** Players online, or -1 when unknown. */
    default int online() {
        return -1;
    }

    default List<RecordBroken> recentRecords(int limit) {
        return null;
    }

    default Set<String> seriesIds() {
        return null;
    }

    default Series series(String id, Resolution resolution, long from, long to) {
        return null;
    }
}
