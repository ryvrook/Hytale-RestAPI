package dev.ryvrook.restapi.events;

import com.google.gson.JsonObject;
import com.hypixel.hytale.logger.HytaleLogger;
import dev.ryvrook.restapi.RestApi;
import dev.ryvrook.restapi.RestApiPlugin;
import dev.ryvrook.restapi.RestModule;
import dev.ryvrook.restapi.http.Gate;
import dev.ryvrook.restapi.http.Route;
import dev.ryvrook.restapi.text.Messages;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;

/**
 * Events, webhooks and metrics, group events. The engine listeners and the death system go in at setup and stay
 * for the life of the plugin. The ring of events lives as long as the module, so /restapi reload keeps it and
 * open streams stay open unless events.enabled went off. The webhook workers, the log subscription and the
 * plugin poll stop and start with the module. At a server stop the HTTP server goes first and ends the streams.
 */
public final class EventsModule implements RestModule {
    public static final String GROUP = "events";
    /** How long a stop waits for webhook queues to empty, so the shutdown event still goes out. */
    static final long DRAIN_MILLIS = 2_000;

    private final EventRing ring;
    private final RequestCounters requests = new RequestCounters();
    private volatile RestApi api;
    private final Set<String> reported = ConcurrentHashMap.newKeySet();
    private volatile HytaleLogger logger;
    private volatile EngineEvents engine;
    private volatile Webhooks hooks;
    private ScheduledFuture<?> poll;

    public EventsModule() {
        EventsSettings.ENABLED.key();
        EventsText.DISCORD_JOIN.length();
        ring = new EventRing(EventsSettings.RING.fallback(), EventRing.idBase(System.currentTimeMillis()),
                e -> warn("a webhook listener", e));
        ring.listen(e -> {
            Webhooks w = hooks;
            if (w != null) {
                w.offer(e);
            }
        });
    }

    @Override
    public String group() {
        return GROUP;
    }

    @Override
    public void setup(RestApiPlugin plugin) {
        logger = plugin.getLogger();
        EngineEvents e = new EngineEvents(this::publish, this::warn);
        List<String> failed = e.register(plugin);
        engine = e;
        if (!failed.isEmpty()) {
            logger.at(Level.WARNING).log("RestAPI records no %s events, this server version refused the listener",
                    String.join(", ", failed));
        }
    }

    /** Any thread. Drops the event while events.enabled is off. */
    void publish(String type, String action, JsonObject data) {
        if (!EventsSettings.ENABLED.get()) {
            return;
        }
        ring.publish(type, action, System.currentTimeMillis(), data);
    }

    /** Logs the first failure of each kind once, so a broken listener cannot flood the log. */
    private void warn(String what, Throwable t) {
        HytaleLogger l = logger;
        if (l != null && reported.add(what + t.getClass().getName())) {
            l.at(Level.WARNING).withCause(t).log("RestAPI events, %s failed. Later failures of the same kind are not logged", what);
        }
    }

    @Override
    public List<Route> routes(RestApi api) {
        if (logger == null) {
            logger = api.logger();
        }
        if (hooks == null) {
            hooks = Webhooks.standard(api.dataDirectory().resolve(Webhooks.FILE), this::warn);
        }
        List<Route> out = new ArrayList<>();
        if (EventsSettings.ENABLED.get()) {
            out.addAll(new EventRoutes(ring).routes());
            out.addAll(new WebhookRoutes(hooks, this::warn).routes());
        }
        if (EventsSettings.METRICS_ENABLED.get()) {
            out.add(new MetricsRoute(api, ring, hooks, requests).route());
        }
        return out;
    }

    @Override
    public void start(RestApi api) {
        this.api = api;
        api.provide(Gate.Observer.class, requests);
        ring.resize(EventsSettings.RING.get());
        if (!EventsSettings.ENABLED.get()) {
            ring.closeAll();
            return;
        }
        for (String p : hooks.load()) {
            api.logger().at(Level.WARNING).log("RestAPI %s", p);
        }
        hooks.start();
        EngineEvents e = engine;
        if (e != null) {
            e.startCommandTap();
            int every = EventsSettings.PLUGIN_POLL_SECONDS.get();
            poll = api.scheduler().scheduleWithFixedDelay(() -> {
                try {
                    e.pollPlugins();
                } catch (RuntimeException | LinkageError ex) {
                    warn("the plugin poll", ex);
                }
            }, 0, every, TimeUnit.SECONDS);
        }
    }

    @Override
    public void stop() {
        RestApi a = api;
        if (a != null) {
            a.withdraw(Gate.Observer.class, requests);
            api = null;
        }
        if (poll != null) {
            poll.cancel(false);
            poll = null;
        }
        EngineEvents e = engine;
        if (e != null) {
            e.stopCommandTap();
            e.resetPlugins();
        }
        Webhooks w = hooks;
        if (w != null) {
            w.stop(DRAIN_MILLIS);
        }
    }

    /** /restapi events webhooks lists the hooks with their delivery counts. The url shows its host only. */
    @Override
    public List<String> lines(String topic) {
        if (!"webhooks".equals(topic)) {
            return List.of();
        }
        Webhooks w = hooks;
        if (w == null || !EventsSettings.ENABLED.get() || !EventsSettings.WEBHOOKS_ENABLED.get()) {
            return List.of(Messages.get(EventsText.WEBHOOKS_OFF));
        }
        List<Webhooks.Hook> list = w.list();
        if (list.isEmpty()) {
            return List.of(Messages.get(EventsText.WEBHOOKS_NONE));
        }
        List<String> out = new ArrayList<>();
        for (Webhooks.Hook h : list) {
            Webhooks.State st = w.state(h.id());
            String host;
            try {
                host = java.net.URI.create(h.url()).getHost();
            } catch (IllegalArgumentException e) {
                host = "?";
            }
            out.add(Messages.get(EventsText.WEBHOOKS_LINE, "id", h.id(), "host", host,
                    "format", h.discord() ? "discord" : "json", "types", String.join(",", new java.util.TreeSet<>(h.types())),
                    "delivered", st.delivered.get(), "failed", st.failed.get(), "queued", w.queued(h.id()),
                    "last", st.lastStatus == 0 ? "none" : st.lastStatus));
        }
        return out;
    }

    /** The request counters /metrics reports, for the gate to feed. */
    public RequestCounters requests() {
        return requests;
    }
}
