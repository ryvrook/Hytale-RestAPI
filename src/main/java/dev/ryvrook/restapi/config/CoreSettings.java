package dev.ryvrook.restapi.config;

import java.util.List;

/**
 * The keys of the HTTP server, the key store, the rate limits and the audit log. Modules declare their own keys
 * in their own classes, see {@link Settings}.
 */
public final class CoreSettings {
    private CoreSettings() {
    }

    public static final Setting<Boolean> HTTP_ENABLED = Settings.bool("http.enabled", true,
            "Serve the HTTP API. Off leaves only the in game command and RCON, when RCON is on.");
    public static final Setting<String> HTTP_BIND = Settings.text("http.bind", "127.0.0.1",
            "Address the HTTP API listens on. 127.0.0.1 keeps it on this machine. 0.0.0.0 opens it to every network the machine is on, read the README section on exposure first.");
    public static final Setting<Integer> HTTP_PORT = Settings.integer("http.port", 8125, 1, 65535,
            "TCP port of the HTTP API. Atlas uses 8123 and Chronicle Stats 8124 by default.");
    public static final Setting<String> HTTP_BASE_PATH = Settings.text("http.basePath", "",
            "Path prefix when a reverse proxy serves the API under a sub path, such as /restapi. Empty serves at the root.");
    public static final Setting<Integer> HTTP_MAX_BODY_BYTES = Settings.integer("http.maxBodyBytes", 65_536, 1_024, 16_777_216,
            "Largest request body accepted, in bytes. Bigger requests get 413 before the body is read.");
    public static final Setting<Integer> HTTP_MAX_IN_FLIGHT = Settings.integer("http.maxInFlight", 32, 1, 1_024,
            "Requests handled at the same time. The rest get 503 with Retry-After 1. Event streams do not count.");
    public static final Setting<Integer> HTTP_MAX_STREAMS = Settings.integer("http.maxStreams", 16, 0, 1_024,
            "Server Sent Event streams open at the same time, for the console and event streams. 0 refuses every stream.");
    public static final Setting<Integer> HTTP_REQUEST_SECONDS = Settings.restart(Settings.integer("http.requestSeconds", 30, 5, 600,
            "Seconds a client gets to send a whole request before the connection is closed. Shared with Atlas and Chronicle Stats in the same JVM, the first plugin to start sets it."));
    public static final Setting<Boolean> HTTP_HEALTH_PUBLIC = Settings.bool("http.healthPublic", true,
            "Answer GET /api/health without a key, with the status and version only. Off asks for a key there too.");
    public static final Setting<List<String>> HTTP_TRUSTED_PROXIES = Settings.list("http.trustedProxies", List.of(),
            "Addresses or CIDR ranges of reverse proxies whose X-Forwarded-For header is believed, such as 127.0.0.1 or 10.0.0.0/8. Empty believes nobody.");
    public static final Setting<List<String>> HTTP_CORS_ORIGINS = Settings.list("http.corsOrigins", List.of(),
            "Browser origins allowed to call the API, such as https://panel.example.com. Empty sends no CORS headers, which is what scripts and panels on a server need.");

    public static final Setting<Boolean> KEYS_ACCEPT_X_API_KEY = Settings.bool("keys.acceptXApiKey", true,
            "Accept the key in an X-API-Key header as well as in Authorization Bearer, for tools written against HyRESTAPI.");
    public static final Setting<Boolean> KEYS_CREATE_FIRST = Settings.bool("keys.createFirstKey", true,
            "Create a key on the first start when keys.json holds none, and print it to the server log once.");
    public static final Setting<String> KEYS_FIRST_NAME = Settings.text("keys.firstKeyName", "admin",
            "Name of the key made on the first start.");
    public static final Setting<List<String>> KEYS_FIRST_SCOPES = Settings.list("keys.firstKeyScopes", List.of("all"),
            "Scopes of the key made on the first start. all means every scope, the command deny list still applies.");

    public static final Setting<Integer> LIMITS_PER_MINUTE = Settings.integer("limits.requestsPerMinute", 120, 0, 100_000,
            "Requests a key may make per minute, as a token bucket that holds one minute. A key in keys.json can set its own. 0 turns the limit off.");
    public static final Setting<Integer> LIMITS_AUTH_FAILURES = Settings.integer("limits.authFailuresPerMinute", 10, 1, 10_000,
            "Requests with a missing or wrong key one address may make per minute before it gets 429.");

    public static final Setting<Boolean> AUDIT_ENABLED = Settings.bool("audit.enabled", true,
            "Write every change made through the API, RCON or /restapi keys to audit/audit-<date>.jsonl.");
    public static final Setting<Boolean> AUDIT_READS = Settings.bool("audit.logReads", false,
            "Also write read requests to the audit log. Busy dashboards make this file grow fast.");
    public static final Setting<Boolean> AUDIT_AUTH_FAILURES = Settings.bool("audit.logAuthFailures", true,
            "Write requests that carry a wrong key to the audit log, with the address. Requests with no key at all are not written.");
    public static final Setting<Integer> AUDIT_KEEP_DAYS = Settings.integer("audit.keepDays", 30, 1, 3_650,
            "Days of audit files kept. Older files are deleted at start and at midnight.");

    public static final Setting<Integer> SAMPLE_SECONDS = Settings.integer("server.sampleSeconds", 5, 1, 300,
            "Seconds between the samples of each world's tick time, chunk and entity counts that /api/server reports.");
}
