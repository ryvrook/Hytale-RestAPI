package dev.ryvrook.restapi;

import com.hypixel.hytale.logger.HytaleLogger;
import dev.ryvrook.restapi.audit.AuditLog;
import dev.ryvrook.restapi.auth.KeyStore;
import dev.ryvrook.restapi.http.Gate;
import dev.ryvrook.restapi.http.Router;
import dev.ryvrook.restapi.server.WorldSampler;
import dev.ryvrook.restapi.siblings.Siblings;

import java.nio.file.Path;
import java.util.concurrent.ScheduledExecutorService;

/**
 * What modules and sibling units get from the plugin. One object per start, made in setup and dropped at
 * shutdown. Every field is safe to use from any thread.
 */
public final class RestApi {
    private final String version;
    private final Path dataDirectory;
    private final HytaleLogger logger;
    private final KeyStore keys;
    private final AuditLog audit;
    private final Router router;
    private final Gate gate;
    private final ScheduledExecutorService scheduler;
    private final WorldSampler worlds;
    private final long startedAt;
    private volatile Siblings siblings;
    private final java.util.Map<Class<?>, Object> services = new java.util.concurrent.ConcurrentHashMap<>();

    RestApi(String version, Path dataDirectory, HytaleLogger logger, KeyStore keys, AuditLog audit, Router router,
            Gate gate, ScheduledExecutorService scheduler, WorldSampler worlds, long startedAt) {
        this.version = version;
        this.dataDirectory = dataDirectory;
        this.logger = logger;
        this.keys = keys;
        this.audit = audit;
        this.router = router;
        this.gate = gate;
        this.scheduler = scheduler;
        this.worlds = worlds;
        this.startedAt = startedAt;
    }

    /** The RestAPI version from the manifest. */
    public String version() {
        return version;
    }

    /** mods/Ryvrook_RestAPI. */
    public Path dataDirectory() {
        return dataDirectory;
    }

    public HytaleLogger logger() {
        return logger;
    }

    public KeyStore keys() {
        return keys;
    }

    public AuditLog audit() {
        return audit;
    }

    public Router router() {
        return router;
    }

    /** The request pipeline, for modules that add credentials or count failed logins of their own. */
    public Gate gate() {
        return gate;
    }

    /** RestAPI's own scheduler with two threads. Never the engine's shared executor. Tasks must not block long. */
    public ScheduledExecutorService scheduler() {
        return scheduler;
    }

    /** The latest sample of every world, taken on each world's own thread. */
    public WorldSampler worlds() {
        return worlds;
    }

    public long startedAt() {
        return startedAt;
    }

    public Siblings siblings() {
        return siblings;
    }

    void siblings(Siblings s) {
        siblings = s;
    }

    /**
     * Offers a service to other modules, such as the console module's CommandService for RCON. A module provides
     * in start and withdraws in stop.
     */
    public <T> void provide(Class<T> type, T service) {
        services.put(type, type.cast(service));
    }

    public <T> void withdraw(Class<T> type, T service) {
        services.remove(type, service);
    }

    /** A service another module provides, or empty when that module is missing or stopped. */
    public <T> java.util.Optional<T> service(Class<T> type) {
        return java.util.Optional.ofNullable(services.get(type)).map(type::cast);
    }
}
