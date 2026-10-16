package dev.ryvrook.restapi.events;

import dev.ryvrook.restapi.config.Setting;
import dev.ryvrook.restapi.config.Settings;

/** The keys under events, webhooks and metrics in RestAPI.json. */
public final class EventsSettings {
    private EventsSettings() {
    }

    public static final Setting<Boolean> ENABLED = Settings.bool("events.enabled", true,
            "Serve the event stream, the recent events list and the webhook routes. Off unmounts them and stops every webhook delivery.");
    public static final Setting<Integer> RING = Settings.integer("events.ring", 1000, 50, 100_000,
            "Events kept in memory for /api/events/recent and for a stream that resumes with Last-Event-ID. Lost at a server stop.");
    public static final Setting<Boolean> CHAT = Settings.bool("events.chat", true,
            "Record chat messages as chat events. Messages a chat plugin cancelled are never recorded.");
    public static final Setting<Boolean> CHAT_TEXT = Settings.bool("events.chatText", true,
            "Put the chat message itself into chat events. Off keeps only the sender, the world and the length.");
    public static final Setting<Boolean> DEATHS = Settings.bool("events.deaths", true,
            "Record player deaths with the cause, the place and the killer when there is one.");
    public static final Setting<Boolean> NPC_DEATHS = Settings.bool("events.npcDeaths", false,
            "Also record the death of every creature. A busy server makes hundreds a minute.");
    public static final Setting<Boolean> COMMANDS = Settings.bool("events.commands", true,
            "Record every command the engine runs, from players, the console, RCON and the API, read from the engine's own log line.");
    public static final Setting<Integer> PLUGIN_POLL_SECONDS = Settings.integer("events.pluginPollSeconds", 5, 1, 300,
            "Seconds between two looks at the plugin list. The engine has no plugin enable event, so plugin events come from this poll.");
    public static final Setting<Integer> KEEP_ALIVE_SECONDS = Settings.integer("events.keepAliveSeconds", 15, 5, 300,
            "Seconds of quiet before an open event stream gets a comment line, so proxies do not close it.");

    public static final Setting<Boolean> WEBHOOKS_ENABLED = Settings.bool("webhooks.enabled", true,
            "Deliver events to the URLs in webhooks.json. Off keeps the file and the routes but sends nothing.");
    public static final Setting<Integer> WEBHOOKS_TIMEOUT = Settings.integer("webhooks.timeoutSeconds", 5, 1, 60,
            "Seconds a webhook target gets to connect and answer before the delivery counts as failed.");
    public static final Setting<Integer> WEBHOOKS_RETRIES = Settings.integer("webhooks.retries", 3, 0, 10,
            "Tries after the first one for a delivery that failed with a network error, a 429 or a 5xx answer.");
    public static final Setting<Integer> WEBHOOKS_BACKOFF = Settings.integer("webhooks.backoffSeconds", 2, 1, 300,
            "Seconds before the first retry. Each further retry waits twice as long as the one before.");
    public static final Setting<Integer> WEBHOOKS_QUEUE = Settings.integer("webhooks.queueSize", 1000, 10, 100_000,
            "Deliveries waiting per webhook. When a target is down long enough to fill it, the oldest waiting delivery is dropped.");
    public static final Setting<Integer> WEBHOOKS_MAX = Settings.integer("webhooks.max", 20, 1, 500,
            "Webhooks POST /api/webhooks may create in total.");
    public static final Setting<Boolean> WEBHOOKS_PRIVATE = Settings.bool("webhooks.allowPrivateTargets", false,
            "Let webhooks made over the API point at loopback, LAN and link local addresses. Hooks written into webhooks.json by hand may always do so.");

    public static final Setting<Boolean> METRICS_ENABLED = Settings.bool("metrics.enabled", true,
            "Serve GET /metrics in Prometheus text format.");
    public static final Setting<Boolean> METRICS_PUBLIC = Settings.bool("metrics.public", false,
            "Answer GET /metrics without a key, for a Prometheus scraper on the same machine. Leave it off when http.bind is not a loopback address.");
}
