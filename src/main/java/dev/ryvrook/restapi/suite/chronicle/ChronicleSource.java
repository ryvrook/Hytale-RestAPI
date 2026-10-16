package dev.ryvrook.restapi.suite.chronicle;

import dev.ryvrook.chronicle.api.StatDef;
import dev.ryvrook.chronicle.api.Window;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/** The part of {@code ChronicleApi} the routes read. ChronicleApi is final, so the tests give a stand in. */
public interface ChronicleSource {
    int apiVersion();

    List<StatDef> stats();

    CompletableFuture<Map<String, Long>> totals(UUID player);

    long window(UUID player, String key, Window window, String worldKey);

    Map<String, Long> perId(UUID player, String prefix);

    String worldKey(String world);

    boolean isTracked(UUID player);
}
