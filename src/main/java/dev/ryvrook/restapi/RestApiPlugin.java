package dev.ryvrook.restapi;

import com.hypixel.hytale.common.plugin.PluginIdentifier;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.plugin.PluginManager;
import com.hypixel.hytale.server.core.plugin.JavaPlugin;
import com.hypixel.hytale.server.core.plugin.JavaPluginInit;
import dev.ryvrook.restapi.audit.AuditEntry;
import dev.ryvrook.restapi.audit.AuditLog;
import dev.ryvrook.restapi.auth.ApiKey;
import dev.ryvrook.restapi.auth.KeyStore;
import dev.ryvrook.restapi.auth.Scope;
import dev.ryvrook.restapi.command.Replies;
import dev.ryvrook.restapi.command.RestApiCommand;
import dev.ryvrook.restapi.config.CoreSettings;
import dev.ryvrook.restapi.config.Settings;
import dev.ryvrook.restapi.control.SuiteLock;
import dev.ryvrook.restapi.http.Gate;
import dev.ryvrook.restapi.http.HttpHost;
import dev.ryvrook.restapi.http.RateLimiter;
import dev.ryvrook.restapi.http.Router;
import dev.ryvrook.restapi.server.WorldSampler;
import dev.ryvrook.restapi.siblings.EngineProbe;
import dev.ryvrook.restapi.siblings.Mode;
import dev.ryvrook.restapi.siblings.Presence;
import dev.ryvrook.restapi.siblings.SelfReload;
import dev.ryvrook.restapi.siblings.Sibling;
import dev.ryvrook.restapi.siblings.SiblingSettings;
import dev.ryvrook.restapi.siblings.Siblings;
import dev.ryvrook.restapi.text.CommandText;
import dev.ryvrook.restapi.text.Messages;
import dev.ryvrook.restapi.util.AddressRanges;
import dev.ryvrook.restapi.util.EngineLookups;
import dev.ryvrook.restapi.util.Pools;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Level;

import static dev.ryvrook.restapi.text.CommandText.*;

/**
 * RestAPI. An HTTP API for server admins, with named keys and scopes, rate limits and an audit log. Modules add
 * areas such as players or the console, suite route groups come and go with their sibling.
 *
 * <p>Threads. Requests run on virtual threads of the JDK server. RestAPI's own scheduler, two daemon threads named
 * RestAPI-scheduler, samples the worlds, checks the siblings and runs /restapi work that touches files. World state
 * is read on each world's thread through world.execute. The audit log has a writer thread of its own.
 *
 * <p>Reload. /restapi reload reads the files again in place. RestAPI never reloads itself through the plugin
 * manager, the suite reload rule in TrackerAPI's docs/DEVELOPMENT.md.
 */
public final class RestApiPlugin extends JavaPlugin {
    private final Object lock = new Object();
    private RestApi api;
    private KeyStore keys;
    private AuditLog audit;
    private Router router;
    private HttpHost host;
    private Gate gate;
    private Siblings siblings;
    private List<RestModule> modules = List.of();
    private volatile ScheduledThreadPoolExecutor scheduler;
    private final List<ScheduledFuture<?>> tasks = new ArrayList<>();
    private ScheduledFuture<?> siblingTask;
    private ScheduledFuture<?> sampleTask;
    /** Why the settings were refused at start, or null. The HTTP API stays down while set. */
    private volatile String configError;
    /** Why the HTTP API is down, or null while it serves or is off on purpose. */
    private volatile String httpError;
    private String boundBind;
    private int boundPort;

    public RestApiPlugin(JavaPluginInit init) {
        super(init);
    }

    @Override
    protected void setup() {
        Path dir = getDataDirectory();
        // Touch every class that declares settings or text before the files are read.
        modules = Modules.load(getClass().getClassLoader(),
                p -> getLogger().at(Level.WARNING).log("RestAPI skipped a module, %s", p));
        CoreSettings.HTTP_ENABLED.key();
        SiblingSettings.CHECK_SECONDS.key();
        for (RestModule m : modules) {
            try {
                m.setup(this);
            } catch (Exception | LinkageError e) {
                getLogger().at(Level.WARNING).withCause(e).log("RestAPI could not set up %s", m.group());
            }
        }
        CommandText.ROOT.length();

        Settings.useDefaults();
        Settings.LoadReport report = Settings.load(dir.resolve(Settings.FILE));
        if (!report.ok()) {
            configError = String.join(". ", report.problems());
            for (String p : report.problems()) {
                getLogger().at(Level.SEVERE).log("RestAPI.json, %s", p);
            }
            getLogger().at(Level.SEVERE).log("RestAPI keeps the HTTP API down until RestAPI.json is fixed and /restapi reload runs");
        }
        for (String p : Messages.load(dir.resolve(Messages.FILE))) {
            getLogger().at(Level.WARNING).log("RestAPI %s", p);
        }
        for (Sibling s : Sibling.values()) {
            if (!Mode.known(SiblingSettings.of(s).get())) {
                getLogger().at(Level.WARNING).log("RestAPI reads siblings.%s = %s as auto, use auto, always or never",
                        s.id(), SiblingSettings.of(s).get());
            }
        }

        keys = new KeyStore(dir.resolve(KeyStore.FILE));
        for (String p : keys.load()) {
            getLogger().at(Level.WARNING).log("RestAPI keys.json, %s", p);
        }
        audit = new AuditLog(dir.resolve("audit"), Clock.systemUTC(),
                msg -> getLogger().at(Level.WARNING).log("%s", msg));
        router = new Router();
        gate = new Gate(router, keys, audit, Clock.systemUTC(), new RateLimiter(), new RateLimiter(),
                (what, e) -> getLogger().at(Level.WARNING).withCause(e).log("RestAPI failed to answer %s", what));
        host = new HttpHost(gate);
        String version = getManifest() == null || getManifest().getVersion() == null
                ? "0.0.0" : getManifest().getVersion().toString();
        scheduler = newScheduler();
        api = new RestApi(version, dir, getLogger(), keys, audit, router, scheduler, new WorldSampler(),
                System.currentTimeMillis());
        siblings = new Siblings(new EngineProbe(), router, Siblings.byName(getClass().getClassLoader()),
                SiblingSettings::mode, () -> api, msg -> getLogger().at(Level.INFO).log("%s", msg),
                msg -> getLogger().at(Level.FINE).log("%s", msg));
        api.siblings(siblings);
        siblings.onStaleLink(this::reloadSelf);

        getCommandRegistry().registerCommand(new RestApiCommand(this));
    }

    @Override
    protected void start() {
        if (keys.isEmpty() && CoreSettings.KEYS_CREATE_FIRST.get()) {
            makeFirstKey();
        }
        synchronized (lock) {
            mountModules();
            siblings.check();
            scheduleChecks();
            startHttp();
        }
        long bound = siblings.table().stream().filter(Presence::bound).count();
        getLogger().at(Level.INFO).log("RestAPI %s ready, %s, %s, %d of %d suite mods bound",
                api.version(), httpSummary(), count(keys.list().size(), "key"), bound, Sibling.values().length);
    }

    @Override
    protected void shutdown() {
        // The plugin manager holds its write lock through this whole method. Cancel the checks first so none is
        // waiting for the monitor below, and stop the pool outside it, bounded to 1.5 s.
        ScheduledFuture<?> check = siblingTask;
        if (check != null) {
            check.cancel(false);
        }
        ScheduledFuture<?> sample = sampleTask;
        if (sample != null) {
            sample.cancel(false);
        }
        synchronized (lock) {
            if (host != null) {
                host.stop();
            }
            for (RestModule m : modules) {
                try {
                    m.stop();
                } catch (RuntimeException | LinkageError e) {
                    getLogger().at(Level.WARNING).withCause(e).log("RestAPI could not stop %s", m.group());
                }
            }
            if (siblings != null) {
                siblings.close();
            }
        }
        ScheduledThreadPoolExecutor s = scheduler;
        if (s != null && !Pools.stop(s)) {
            getLogger().at(Level.WARNING).log("RestAPI scheduler did not stop within %d ms and was left behind",
                    Pools.GRACE_MILLIS + Pools.INTERRUPT_MILLIS);
        }
        if (audit != null) {
            audit.close();
        }
    }

    /** Reloads RestAPI after a sibling without a dependents pass was reloaded, see {@link SelfReload}. */
    private void reloadSelf(Sibling sibling) {
        PluginManager manager = PluginManager.get();
        PluginIdentifier id = getIdentifier();
        if (manager == null || id == null) {
            return;
        }
        String line = "RestAPI could not reload itself after " + sibling.pluginName() + " was reloaded, run /plugin reload " + id;
        SelfReload.schedule(() -> EngineLookups.plugin(id), this, () -> manager.reload(id), SuiteLock.shared(),
                () -> getLogger().at(Level.WARNING).log("%s", line));
    }

    private ScheduledThreadPoolExecutor newScheduler() {
        AtomicInteger n = new AtomicInteger();
        ScheduledThreadPoolExecutor s = new ScheduledThreadPoolExecutor(2, r -> {
            Thread t = new Thread(r, "RestAPI-scheduler-" + n.incrementAndGet());
            t.setDaemon(true);
            return t;
        });
        s.setRemoveOnCancelPolicy(true);
        s.setContinueExistingPeriodicTasksAfterShutdownPolicy(false);
        s.setExecuteExistingDelayedTasksAfterShutdownPolicy(false);
        return s;
    }

    private void makeFirstKey() {
        try {
            Set<Scope> scopes = Scope.parse(CoreSettings.KEYS_FIRST_SCOPES.get());
            KeyStore.Created made = keys.create(CoreSettings.KEYS_FIRST_NAME.get(), scopes, "first start");
            getLogger().at(Level.INFO).log("RestAPI made the key %s with %s. It is printed this once and only its hash is kept, "
                            + "copy it now. Key %s", made.key().name(), String.join(",", Scope.describe(scopes)), made.token());
            audit.record(new AuditEntry(Instant.now().toString(), "command", "first start", "console",
                    "keys create " + made.key().name(), 0, String.join(",", Scope.describe(scopes))));
        } catch (IOException | IllegalArgumentException e) {
            getLogger().at(Level.WARNING).log("RestAPI could not make its first key, %s. Make one with /restapi keys create",
                    e.getMessage());
        }
    }

    /** Under lock. Stops, mounts and starts every module again. */
    private void mountModules() {
        for (RestModule m : modules) {
            try {
                m.stop();
            } catch (RuntimeException | LinkageError e) {
                getLogger().at(Level.WARNING).withCause(e).log("RestAPI could not stop %s", m.group());
            }
            try {
                router.mount(m.group(), m.routes(api));
                m.start(api);
            } catch (Exception | LinkageError e) {
                router.unmount(m.group());
                getLogger().at(Level.WARNING).withCause(e).log("RestAPI left out the %s routes", m.group());
            }
        }
        gate.observe(api.service(Gate.Observer.class).orElse(null));
    }

    /** Under lock. */
    private void scheduleChecks() {
        if (siblingTask != null) {
            siblingTask.cancel(false);
        }
        if (sampleTask != null) {
            sampleTask.cancel(false);
        }
        int every = SiblingSettings.CHECK_SECONDS.get();
        siblingTask = scheduler.scheduleWithFixedDelay(() -> guard("sibling check", () -> {
            synchronized (lock) {
                siblings.check();
            }
        }), every, every, TimeUnit.SECONDS);
        int sample = CoreSettings.SAMPLE_SECONDS.get();
        sampleTask = scheduler.scheduleWithFixedDelay(() -> guard("world sample", api.worlds()::tick),
                1, sample, TimeUnit.SECONDS);
    }

    private void guard(String what, Runnable r) {
        try {
            r.run();
        } catch (RuntimeException | LinkageError e) {
            getLogger().at(Level.WARNING).withCause(e).log("RestAPI %s failed", what);
        }
    }

    /** Under lock. Starts, moves or stops the HTTP server to match the settings. */
    private void startHttp() {
        String bind = CoreSettings.HTTP_BIND.get();
        int port = CoreSettings.HTTP_PORT.get();
        boolean want = CoreSettings.HTTP_ENABLED.get() && configError == null;
        if (!want) {
            host.stop();
            boundBind = null;
            httpError = configError == null ? null : "RestAPI.json has problems";
            return;
        }
        if (host.boundTo() != null && bind.equals(boundBind) && port == boundPort) {
            return;
        }
        host.stop();
        String reqTime = System.getProperty("sun.net.httpserver.maxReqTime");
        int wanted = CoreSettings.HTTP_REQUEST_SECONDS.get();
        if (reqTime != null && !reqTime.trim().equals(Integer.toString(wanted))) {
            getLogger().at(Level.INFO).log("RestAPI uses a request time limit of %s seconds, set before it started by "
                    + "another plugin in this JVM such as Atlas or Chronicle Stats, so http.requestSeconds %d has no effect",
                    reqTime.trim(), wanted);
        }
        try {
            host.start(bind, port);
            boundBind = bind;
            boundPort = port;
            httpError = null;
            if (!AddressRanges.isLoopback(bind)) {
                getLogger().at(Level.WARNING).log("RestAPI listens on %s:%d, which other machines can reach. Keys travel "
                        + "in clear text without TLS, put a reverse proxy with TLS in front, see the README", bind, port);
            }
        } catch (IOException | RuntimeException e) {
            boundBind = null;
            httpError = "it could not bind " + bind + ":" + port + ", " + e.getMessage();
            getLogger().at(Level.SEVERE).log("RestAPI could not start its HTTP API on %s:%d, %s", bind, port, e.getMessage());
        }
    }

    private String httpSummary() {
        String at = host.boundTo();
        if (at != null) {
            return "HTTP API on http://" + at + CoreSettings.HTTP_BASE_PATH.get() + " with " + count(router.routes().size(), "route");
        }
        return httpError == null ? "HTTP API off" : "HTTP API down, " + httpError;
    }

    // ---- /restapi ----------------------------------------------------------------------------------------

    private CompletableFuture<List<Message>> async(java.util.function.Supplier<List<Message>> work) {
        ScheduledThreadPoolExecutor s = scheduler;
        if (s == null || s.isShutdown()) {
            return CompletableFuture.completedFuture(List.of(Replies.line(Messages.get(FAILED), Replies.ERROR)));
        }
        return CompletableFuture.supplyAsync(() -> {
            try {
                return work.get();
            } catch (RuntimeException e) {
                getLogger().at(Level.WARNING).withCause(e).log("RestAPI command failed");
                throw e;
            }
        }, s);
    }

    /** 1 route, 2 routes. */
    static String count(int n, String word) {
        return n + " " + word + (n == 1 ? "" : "s");
    }

    private static Message info(String text) {
        return Replies.line(text, Replies.MUTED);
    }

    public CompletableFuture<List<Message>> statusLines() {
        return async(() -> {
            List<Message> out = new ArrayList<>();
            String at = host.boundTo();
            if (at != null) {
                out.add(info(Messages.get(HTTP_ON, "address", "http://" + at, "base", CoreSettings.HTTP_BASE_PATH.get(),
                        "routes", count(router.routes().size(), "route"), "groups", count(router.groups().size(), "group"))));
                if (!AddressRanges.isLoopback(boundBind)) {
                    out.add(Replies.line(Messages.get(HTTP_EXPOSED), Replies.ERROR));
                }
            } else if (httpError != null) {
                out.add(Replies.line(Messages.get(HTTP_DOWN, "reason", configError != null ? configError : httpError),
                        Replies.ERROR));
            } else {
                out.add(info(Messages.get(HTTP_OFF)));
            }
            out.add(info(Messages.get(KEYS_COUNT, "keys", count(keys.list().size(), "key"), "limit", CoreSettings.LIMITS_PER_MINUTE.get())));
            out.add(info(Messages.get(AUDIT_STATE, "state", CoreSettings.AUDIT_ENABLED.get() ? "on" : "off",
                    "days", CoreSettings.AUDIT_KEEP_DAYS.get())));
            for (RestModule m : modules) {
                try {
                    for (String line : m.status()) {
                        out.add(info(line));
                    }
                } catch (RuntimeException | LinkageError e) {
                    getLogger().at(Level.WARNING).withCause(e).log("RestAPI could not read the %s status", m.group());
                }
            }
            out.add(info(Messages.get(SIBLINGS_HEAD, "seconds", SiblingSettings.CHECK_SECONDS.get())));
            for (Presence p : siblings.table()) {
                Sibling s = p.sibling();
                out.add(p.bound()
                        ? Replies.line(Messages.get(SIBLING_BOUND, "plugin", s.pluginName(), "version", p.version(),
                        "routes", count(p.routes(), "route"), "id", s.id(), "scope", s.scope().id()), Replies.OK)
                        : info(Messages.get(SIBLING_UNBOUND, "plugin", s.pluginName(), "reason", p.reason())));
            }
            return out;
        });
    }

    /** Lines for /restapi rcon status, /restapi events webhooks and the like, from the module of that group. */
    public CompletableFuture<List<Message>> moduleLines(String group, String topic) {
        return async(() -> {
            List<Message> out = new ArrayList<>();
            for (RestModule m : modules) {
                if (m.group().equals(group)) {
                    for (String line : m.lines(topic)) {
                        out.add(info(line));
                    }
                }
            }
            if (out.isEmpty()) {
                out.add(Replies.line(Messages.get(MODULE_MISSING, "group", group), Replies.ERROR));
            }
            return out;
        });
    }

    /** /restapi reload. Reads the files again in place, never through the plugin manager. */
    public CompletableFuture<List<Message>> reload(String by) {
        return async(() -> {
            synchronized (lock) {
                List<Message> out = new ArrayList<>();
                int restartBefore = CoreSettings.HTTP_REQUEST_SECONDS.get();
                Settings.LoadReport report = Settings.load(getDataDirectory().resolve(Settings.FILE));
                if (!report.ok()) {
                    out.add(Replies.line(Messages.get(RELOAD_REFUSED), Replies.ERROR));
                    for (String p : report.problems()) {
                        out.add(Replies.line(Messages.get(RELOAD_PROBLEM, "problem", p), Replies.ERROR));
                    }
                    return out;
                }
                configError = null;
                for (String p : Messages.load(getDataDirectory().resolve(Messages.FILE))) {
                    out.add(Replies.line(Messages.get(RELOAD_PROBLEM, "problem", p), Replies.ERROR));
                }
                for (String p : keys.load()) {
                    out.add(Replies.line(Messages.get(RELOAD_PROBLEM, "problem", "keys.json, " + p), Replies.ERROR));
                }
                mountModules();
                siblings.check();
                scheduleChecks();
                startHttp();
                if (restartBefore != CoreSettings.HTTP_REQUEST_SECONDS.get()) {
                    out.add(info(Messages.get(RELOAD_RESTART)));
                }
                out.add(0, Replies.line(Messages.get(RELOADED, "routes", count(router.routes().size(), "route"),
                        "groups", count(router.groups().size(), "group"), "keys", count(keys.list().size(), "key")), Replies.OK));
                if (httpError != null) {
                    out.add(Replies.line(Messages.get(HTTP_DOWN, "reason", httpError), Replies.ERROR));
                }
                audit.record(new AuditEntry(Instant.now().toString(), "command", by, by, "reload", 0, "ok"));
                return out;
            }
        });
    }

    public CompletableFuture<List<Message>> keyLines() {
        return async(() -> {
            List<ApiKey> list = keys.list();
            if (list.isEmpty()) {
                return List.of(info(Messages.get(KEYS_NONE)));
            }
            List<Message> out = new ArrayList<>();
            for (ApiKey k : list) {
                out.add(info(Messages.get(KEYS_LINE, "name", k.name(), "id", k.id(),
                        "scopes", String.join(",", Scope.describe(k.scopes())), "created", k.created(), "by", k.createdBy())));
            }
            return out;
        });
    }

    public CompletableFuture<List<Message>> createKey(String name, String scopeText, String by) {
        return async(() -> {
            try {
                Set<Scope> scopes = Scope.parse(List.of(scopeText));
                KeyStore.Created made = keys.create(name, scopes, by);
                audit.record(new AuditEntry(Instant.now().toString(), "command", by, by, "keys create " + name, 0,
                        String.join(",", Scope.describe(scopes))));
                return List.of(Replies.line(Messages.get(KEY_MADE, "name", name,
                                "scopes", String.join(",", Scope.describe(scopes))), Replies.OK),
                        Replies.line(made.token(), Replies.OK));
            } catch (IllegalArgumentException | IOException e) {
                return List.of(Replies.line(Messages.get(KEY_FAILED, "reason", e.getMessage()), Replies.ERROR));
            }
        });
    }

    public CompletableFuture<List<Message>> revokeKey(String name, String by) {
        return async(() -> {
            try {
                if (!keys.revoke(name)) {
                    return List.of(Replies.line(Messages.get(KEY_UNKNOWN, "name", name), Replies.ERROR));
                }
                audit.record(new AuditEntry(Instant.now().toString(), "command", by, by, "keys revoke " + name, 0, "revoked"));
                return List.of(Replies.line(Messages.get(KEY_REVOKED, "name", name), Replies.OK));
            } catch (IOException e) {
                return List.of(Replies.line(Messages.get(KEY_FAILED, "reason", e.getMessage()), Replies.ERROR));
            }
        });
    }

    public CompletableFuture<List<Message>> auditLines(int n) {
        return async(() -> {
            try {
                List<AuditEntry> entries = audit.tail(n);
                if (entries.isEmpty()) {
                    return List.of(info(Messages.get(AUDIT_EMPTY)));
                }
                List<Message> out = new ArrayList<>();
                for (AuditEntry e : entries) {
                    out.add(info("command".equals(e.source())
                            ? Messages.get(AUDIT_LINE_COMMAND, "time", e.time(), "key", e.key(), "action", e.action(),
                            "detail", e.detail())
                            : Messages.get(AUDIT_LINE, "time", e.time(), "key", e.key(), "client", e.client(),
                            "action", e.action(), "status", e.status(), "detail", e.detail())));
                }
                return out;
            } catch (IOException e) {
                return List.of(Replies.line(Messages.get(KEY_FAILED, "reason", e.getMessage()), Replies.ERROR));
            }
        });
    }
}
