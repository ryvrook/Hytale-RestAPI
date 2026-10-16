package dev.ryvrook.restapi.server;

import com.hypixel.hytale.metrics.metric.HistoricMetric;
import com.hypixel.hytale.server.core.universe.Universe;
import com.hypixel.hytale.server.core.universe.world.World;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Samples every world on its own thread every server.sampleSeconds, so the HTTP threads read a cached answer and
 * never wait on a world. A world whose sample has not come back for three intervals counts as stalled, and
 * /api/health says so. That is the check HyRESTAPI's health endpoint was missing, it answered healthy while the
 * server hung.
 */
public final class WorldSampler {
    private final Map<String, WorldSample> samples = new ConcurrentHashMap<>();

    /** Scheduler thread. Posts one sample to each live world that has none pending, drops worlds that are gone. */
    public void tick() {
        Universe u = Universe.get();
        if (u == null) {
            return;
        }
        long now = System.currentTimeMillis();
        Map<String, World> worlds = u.getWorlds();
        samples.keySet().removeIf(name -> !worlds.containsKey(name));
        for (World w : worlds.values()) {
            if (w == null || !w.isAlive()) {
                continue;
            }
            String name = w.getName();
            WorldSample last = samples.getOrDefault(name, WorldSample.empty(name));
            if (last.waitingSince() > 0) {
                continue;
            }
            samples.put(name, last.waiting(now));
            try {
                w.execute(() -> samples.put(name, read(w)));
            } catch (RuntimeException e) {
                samples.put(name, last);
            }
        }
    }

    /** World thread. */
    private static WorldSample read(World w) {
        int target = w.getTps();
        double avg = 0;
        double max = 0;
        HistoricMetric ticks = w.getBufferedTickLengthMetricSet();
        if (ticks != null) {
            double a = ticks.getAverage(0);
            avg = Double.isNaN(a) ? 0 : a;
            max = ticks.calculateMax(0);
        }
        int entities = w.getEntityStore().getStore().getEntityCount();
        int chunks = w.getChunkStore().getLoadedChunksCount();
        return new WorldSample(w.getName(), w.getPlayerCount(), target, WorldSample.tps(avg, w.getTickStepNanos()), avg / 1e6,
                max / 1e6, chunks, entities, System.currentTimeMillis(), 0);
    }

    /** Every world sampled so far, by name. */
    public List<WorldSample> all() {
        List<WorldSample> out = new ArrayList<>(samples.values());
        out.sort(Comparator.comparing(WorldSample::name));
        return out;
    }

    public void clear() {
        samples.clear();
    }
}
