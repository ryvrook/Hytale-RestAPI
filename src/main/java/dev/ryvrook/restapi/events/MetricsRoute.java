package dev.ryvrook.restapi.events;

import com.hypixel.hytale.server.core.HytaleServer;
import com.hypixel.hytale.server.core.universe.Universe;
import dev.ryvrook.restapi.RestApi;
import dev.ryvrook.restapi.auth.Scope;
import dev.ryvrook.restapi.http.Access;
import dev.ryvrook.restapi.http.Response;
import dev.ryvrook.restapi.http.Route;
import dev.ryvrook.restapi.http.RouteDoc;
import dev.ryvrook.restapi.server.CoreModule;
import dev.ryvrook.restapi.server.WorldSample;

import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryUsage;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.Consumer;

/**
 * GET /metrics. Names follow the Apex Prometheus exporter where the two overlap, so its dashboards carry over.
 * Every engine read is safe off the world threads, world figures come from the sampler's cache. Each section is
 * filled in its own try, so one engine change costs one section and never the whole answer.
 */
final class MetricsRoute {
    private final RestApi api;
    private final EventRing ring;
    private final Webhooks hooks;
    private final RequestCounters requests;

    MetricsRoute(RestApi api, EventRing ring, Webhooks hooks, RequestCounters requests) {
        this.api = api;
        this.ring = ring;
        this.hooks = hooks;
        this.requests = requests;
    }

    Route route() {
        return new Route(EventsModule.GROUP, "GET", "/metrics", Scope.METRICS, Access.READ, EventsSettings.METRICS_PUBLIC::get,
                "Prometheus text format with players, per world tick rate, tick time, chunks and entities, plugins by state, JVM figures and RestAPI's own counters.",
                call -> answer())
                .doc(new RouteDoc(List.of(), null, null, List.of()));
    }

    private Response answer() {
        Metrics m = new Metrics();
        section(m, this::players);
        section(m, mm -> worlds(mm, api.worlds().all(), System.currentTimeMillis(), stallLimitMs()));
        section(m, this::server);
        section(m, MetricsRoute::plugins);
        section(m, MetricsRoute::jvm);
        section(m, this::restapi);
        return new Response(200, Metrics.CONTENT_TYPE, m.render().getBytes(StandardCharsets.UTF_8), Map.of());
    }

    private static void section(Metrics m, Consumer<Metrics> fill) {
        try {
            fill.accept(m);
        } catch (RuntimeException | LinkageError ignored) {
            // Left out of this scrape.
        }
    }

    /** Three sample intervals and at least 15 seconds, the rule /api/health uses. */
    static long stallLimitMs() {
        return CoreModule.stallLimitMs();
    }

    private void players(Metrics m) {
        Universe u = Universe.get();
        if (u != null) {
            m.gauge("hytale_players_online", "Players connected.").add(u.getPlayerCount());
        }
        HytaleServer s = HytaleServer.get();
        if (s != null && s.getConfig() != null) {
            m.gauge("hytale_players_max", "The MaxPlayers value of config.json.").add(s.getConfig().getMaxPlayers());
        }
    }

    /** One sample per world and family. A world with no finished sample yet reports only players and stalled. */
    static void worlds(Metrics m, List<WorldSample> samples, long now, long stallMs) {
        Metrics.Family tps = m.gauge("hytale_world_tps", "Ticks per second over the last 10 seconds, the /world perf formula.");
        Metrics.Family tick = m.gauge("hytale_world_tick_ms", "Tick length over the last 10 seconds in milliseconds.");
        Metrics.Family chunks = m.gauge("hytale_world_chunks_loaded", "Chunks loaded in the world.");
        Metrics.Family entities = m.gauge("hytale_world_entities", "Entities in the world's entity store.");
        Metrics.Family players = m.gauge("hytale_world_players", "Players in the world.");
        Metrics.Family stalled = m.gauge("hytale_world_stalled", "1 when the world thread has not answered a sample for three intervals.");
        for (WorldSample w : samples) {
            players.add(w.players(), "world", w.name());
            stalled.add(w.stalled(now, stallMs) ? 1 : 0, "world", w.name());
            if (w.sampledAt() == 0) {
                continue;
            }
            if (w.tps() >= 0) {
                tps.add(round(w.tps()), "world", w.name());
            }
            tick.add(round(w.tickAvgMs()), "world", w.name(), "stat", "avg");
            tick.add(round(w.tickMaxMs()), "world", w.name(), "stat", "max");
            chunks.add(w.chunks(), "world", w.name());
            entities.add(w.entities(), "world", w.name());
        }
    }

    private void server(Metrics m) {
        HytaleServer s = HytaleServer.get();
        Instant boot = s == null ? null : s.getBoot();
        if (boot != null) {
            m.gauge("hytale_server_uptime_seconds", "Seconds since the server booted.")
                    .add(Math.max(0, Instant.now().getEpochSecond() - boot.getEpochSecond()));
        }
    }

    private static void plugins(Metrics m) {
        Map<String, Integer> byState = new TreeMap<>();
        for (String[] v : EngineEvents.pluginStates().values()) {
            byState.merge(v[0], 1, Integer::sum);
        }
        Metrics.Family f = m.gauge("hytale_plugins", "Plugins by state.");
        byState.forEach((state, n) -> f.add(n, "state", state));
    }

    private static void jvm(Metrics m) {
        MemoryUsage heap = ManagementFactory.getMemoryMXBean().getHeapMemoryUsage();
        m.gauge("jvm_memory_heap_used_bytes", "Heap in use.").add(heap.getUsed());
        m.gauge("jvm_memory_heap_committed_bytes", "Heap the JVM holds from the system.").add(heap.getCommitted());
        if (heap.getMax() >= 0) {
            m.gauge("jvm_memory_heap_max_bytes", "Largest heap the JVM may grow to.").add(heap.getMax());
        }
        m.gauge("jvm_threads", "Live threads, virtual threads not included.").add(ManagementFactory.getThreadMXBean().getThreadCount());
        Metrics.Family gcCount = m.counter("jvm_gc_collections_total", "Garbage collections by collector.");
        Metrics.Family gcTime = m.counter("jvm_gc_collection_seconds_total", "Time spent in garbage collection by collector.");
        for (GarbageCollectorMXBean gc : ManagementFactory.getGarbageCollectorMXBeans()) {
            if (gc.getCollectionCount() >= 0) {
                gcCount.add(gc.getCollectionCount(), "gc", gc.getName());
            }
            if (gc.getCollectionTime() >= 0) {
                gcTime.add(gc.getCollectionTime() / 1000.0, "gc", gc.getName());
            }
        }
        if (ManagementFactory.getOperatingSystemMXBean() instanceof com.sun.management.OperatingSystemMXBean os) {
            double cpu = os.getProcessCpuLoad();
            if (cpu >= 0) {
                m.gauge("process_cpu_load", "CPU use of the server process from 0 to 1 over all cores.").add(round(cpu));
            }
        }
        double load = ManagementFactory.getOperatingSystemMXBean().getSystemLoadAverage();
        if (load >= 0) {
            m.gauge("system_load_average_1m", "System load average over one minute.").add(round(load));
        }
    }

    private void restapi(Metrics m) {
        if (requests.fed()) {
            Metrics.Family r = m.counter("restapi_requests_total", "HTTP requests answered, by method, route pattern and status.");
            requests.each((method, route, status, n) -> r.add(n, "method", method, "route", route, "status", status));
            m.counter("restapi_auth_failures_total", "Requests with a wrong key, or none on a route that needs one.")
                    .add(requests.authFailures());
        }
        Metrics.Family d = m.counter("restapi_webhook_deliveries_total",
                "Webhook deliveries by result. retried counts tries after the first, dropped counts deliveries pushed out of a full queue.");
        hooks.totals().forEach((result, n) -> d.add(n, "result", result));
        m.gauge("restapi_webhooks", "Webhooks in webhooks.json.").add(hooks.size());
        m.gauge("restapi_event_streams", "Event streams open now.").add(ring.streams());
        m.gauge("restapi_routes", "Routes mounted.").add(api.router().routes().size());
    }

    static double round(double v) {
        return Math.round(v * 1000) / 1000.0;
    }
}
