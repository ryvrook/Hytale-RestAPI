package dev.ryvrook.restapi.events;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.ryvrook.restapi.text.Messages;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.IOException;
import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.BlockingDeque;
import java.util.concurrent.LinkedBlockingDeque;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BiConsumer;

/**
 * Outgoing webhooks. The hooks live in webhooks.json next to RestAPI.json, written with owner only permissions
 * where the file system has them, because the signing secrets are kept in clear (HMAC needs them). Each hook has
 * a queue capped at webhooks.queueSize and a virtual thread of its own that sends one delivery at a time, so a
 * target sees events in order and a slow target never holds up another.
 *
 * <p>A delivery is a POST of the event as JSON, or of a Discord message for format discord, with
 * {@code X-RestAPI-Event}, {@code X-RestAPI-Delivery} and, when the hook has a secret,
 * {@code X-RestAPI-Signature: sha256=<hex HMAC-SHA256 of the body>}, always over HTTP/1.1, since the JDK
 * client's HTTP/2 upgrade on plain http held the body back from a target that does not answer the upgrade.
 * Redirects are never followed. A network
 * error, a 429 or a 5xx is tried again webhooks.retries times, waiting webhooks.backoffSeconds and twice that
 * each time after. Any other answer outside 2xx fails at once. Hooks made over the API are checked for private
 * targets at creation and again before every delivery, so a name that later resolves to the LAN is caught.
 */
public final class Webhooks {
    public static final String FILE = "webhooks.json";
    static final int DISCORD_MAX = 2000;

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
    private static final Gson COMPACT = new GsonBuilder().disableHtmlEscaping().serializeNulls().create();
    private static final SecureRandom RANDOM = new SecureRandom();

    /**
     * One hook as webhooks.json keeps it.
     *
     * @param viaApi true when POST /api/webhooks made it, which subjects it to the private target check
     */
    public record Hook(String id, String url, Set<String> types, String secret, String format, String created,
                       String createdBy, boolean viaApi) {
        public Hook {
            types = Set.copyOf(types);
            format = format == null ? "json" : format;
        }

        public boolean discord() {
            return "discord".equals(format);
        }
    }

    /** What the last delivery of a hook did, kept in memory only. */
    public static final class State {
        final AtomicLong delivered = new AtomicLong();
        final AtomicLong failed = new AtomicLong();
        final AtomicLong dropped = new AtomicLong();
        volatile int lastStatus;
        volatile String lastAt;
        volatile String lastError;
    }

    /** The result of one send. status 0 means no HTTP answer, error then says why. */
    public record Result(int status, String error, long millis) {
        public boolean ok() {
            return status >= 200 && status < 300;
        }

        /** Network errors, 429 and 5xx are worth another try. A refused target is not. */
        public boolean retry() {
            return (status == 0 && !"private_target".equals(error) && !"unresolved".equals(error))
                    || status == 429 || status >= 500;
        }
    }

    /** Sends one POST. The default uses the JDK HTTP client, tests stand in their own. */
    @FunctionalInterface
    public interface Transport {
        Result post(URI url, Map<String, String> headers, byte[] body, Duration timeout) throws InterruptedException;
    }

    /** Name lookup, a stand in for tests. */
    @FunctionalInterface
    public interface Resolver {
        InetAddress[] resolve(String host) throws UnknownHostException;
    }

    /** How long to wait, a stand in for tests so retries do not sleep. */
    @FunctionalInterface
    public interface Sleeper {
        void sleep(long millis) throws InterruptedException;
    }

    private final Path file;
    private final Transport transport;
    private final Resolver resolver;
    private final Sleeper sleeper;
    private final BiConsumer<String, Throwable> log;
    private final Map<String, Hook> hooks = new LinkedHashMap<>();
    private final Map<String, State> states = new java.util.concurrent.ConcurrentHashMap<>();
    private final Map<String, Worker> workers = new java.util.concurrent.ConcurrentHashMap<>();
    private final AtomicLong ok = new AtomicLong();
    private final AtomicLong failedTotal = new AtomicLong();
    private final AtomicLong retried = new AtomicLong();
    private final AtomicLong dropped = new AtomicLong();
    private volatile boolean running;

    public Webhooks(Path file, Transport transport, Resolver resolver, Sleeper sleeper, BiConsumer<String, Throwable> log) {
        this.file = file;
        this.transport = transport;
        this.resolver = resolver;
        this.sleeper = sleeper;
        this.log = log;
    }

    /** The plugin's hooks with the JDK client and real lookups. */
    public static Webhooks standard(Path file, BiConsumer<String, Throwable> log) {
        return new Webhooks(file, new JdkTransport(), InetAddress::getAllByName, Thread::sleep, log);
    }

    // ---- file --------------------------------------------------------------------------------------------

    /** Reads webhooks.json. A missing file is no hooks. Returns the problems, a broken entry is skipped. */
    public synchronized List<String> load() {
        List<String> problems = new ArrayList<>();
        hooks.clear();
        if (!Files.exists(file)) {
            return problems;
        }
        try {
            JsonElement root = JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8));
            JsonArray list = root.isJsonObject() && root.getAsJsonObject().has("hooks")
                    ? root.getAsJsonObject().getAsJsonArray("hooks") : null;
            if (list == null) {
                problems.add(FILE + " must hold {\"hooks\": [...]}");
                return problems;
            }
            for (JsonElement e : list) {
                try {
                    Hook h = fromJson(e.getAsJsonObject());
                    if (hooks.containsKey(h.id())) {
                        problems.add(FILE + " has the id " + h.id() + " twice, the second was skipped");
                        continue;
                    }
                    hooks.put(h.id(), h);
                } catch (RuntimeException ex) {
                    problems.add(FILE + " has an entry that was skipped, " + ex.getMessage());
                }
            }
        } catch (IOException | RuntimeException e) {
            problems.add(FILE + " could not be read, " + e.getMessage());
        }
        return problems;
    }

    static Hook fromJson(JsonObject o) {
        String id = text(o, "id");
        String url = text(o, "url");
        if (id == null || !id.matches("[A-Za-z0-9_-]{1,32}")) {
            throw new IllegalArgumentException("id must be 1 to 32 letters, digits, dash or underscore");
        }
        checkUrl(url);
        List<String> types = new ArrayList<>();
        if (o.has("types") && o.get("types").isJsonArray()) {
            o.getAsJsonArray("types").forEach(t -> types.add(t.getAsString()));
        }
        String format = text(o, "format");
        if (format != null && !format.equals("json") && !format.equals("discord")) {
            throw new IllegalArgumentException("format of " + id + " must be json or discord");
        }
        Set<String> parsed;
        try {
            parsed = EventRing.parseTypes(types);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("unknown event type " + e.getMessage() + " in " + id);
        }
        return new Hook(id, url, parsed, text(o, "secret"), format, text(o, "created"), text(o, "createdBy"),
                o.has("viaApi") && o.get("viaApi").getAsBoolean());
    }

    private static String text(JsonObject o, String k) {
        JsonElement e = o.get(k);
        return e == null || e.isJsonNull() ? null : e.getAsString();
    }

    private void save() throws IOException {
        JsonArray list = new JsonArray();
        for (Hook h : hooks.values()) {
            JsonObject o = new JsonObject();
            o.addProperty("id", h.id());
            o.addProperty("url", h.url());
            JsonArray types = new JsonArray();
            EventRing.TYPES.stream().filter(h.types()::contains).forEach(types::add);
            o.add("types", types);
            if (h.secret() != null) {
                o.addProperty("secret", h.secret());
            }
            o.addProperty("format", h.format());
            o.addProperty("created", h.created());
            o.addProperty("createdBy", h.createdBy());
            o.addProperty("viaApi", h.viaApi());
            list.add(o);
        }
        JsonObject root = new JsonObject();
        root.add("hooks", list);
        Files.createDirectories(file.toAbsolutePath().getParent());
        Path tmp = file.resolveSibling(FILE + ".tmp");
        Files.writeString(tmp, GSON.toJson(root) + "\n", StandardCharsets.UTF_8);
        try {
            Files.setPosixFilePermissions(tmp, PosixFilePermissions.fromString("rw-------"));
        } catch (UnsupportedOperationException ignored) {
            // Windows. The folder's own permissions apply.
        }
        Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }

    // ---- management --------------------------------------------------------------------------------------

    public synchronized List<Hook> list() {
        return List.copyOf(hooks.values());
    }

    public synchronized Hook get(String id) {
        return hooks.get(id);
    }

    public State state(String id) {
        return states.computeIfAbsent(id, k -> new State());
    }

    /** Queued deliveries of a hook. */
    public int queued(String id) {
        Worker w = workers.get(id);
        return w == null ? 0 : w.queue.size();
    }

    /**
     * Adds a hook and starts its worker. The caller checked the url and the target. Throws IOException when the
     * file could not be written, then nothing changed.
     */
    public synchronized Hook add(String url, Set<String> types, String secret, String format, String by)
            throws IOException {
        String id;
        do {
            id = HexFormat.of().formatHex(bytes(4));
        } while (hooks.containsKey(id));
        Hook h = new Hook(id, url, types, secret, format, Instant.now().toString(), by, true);
        hooks.put(id, h);
        try {
            save();
        } catch (IOException e) {
            hooks.remove(id);
            throw e;
        }
        if (running) {
            startWorker(h);
        }
        return h;
    }

    public synchronized boolean remove(String id) throws IOException {
        Hook h = hooks.remove(id);
        if (h == null) {
            return false;
        }
        try {
            save();
        } catch (IOException e) {
            hooks.put(id, h);
            throw e;
        }
        Worker w = workers.remove(id);
        if (w != null) {
            w.stop();
        }
        states.remove(id);
        return true;
    }

    public synchronized int size() {
        return hooks.size();
    }

    /** 32 random bytes in hex, for a hook made without a secret. */
    public static String newSecret() {
        return HexFormat.of().formatHex(bytes(32));
    }

    private static byte[] bytes(int n) {
        byte[] b = new byte[n];
        RANDOM.nextBytes(b);
        return b;
    }

    // ---- checks ------------------------------------------------------------------------------------------

    /** Throws IllegalArgumentException unless the url is an absolute http or https URL with a host. */
    public static URI checkUrl(String url) {
        if (url == null || url.length() > 2048) {
            throw new IllegalArgumentException("url");
        }
        URI u;
        try {
            u = new URI(url);
        } catch (java.net.URISyntaxException e) {
            throw new IllegalArgumentException("url");
        }
        String scheme = u.getScheme() == null ? "" : u.getScheme().toLowerCase(Locale.ROOT);
        if (!(scheme.equals("http") || scheme.equals("https")) || u.getHost() == null || u.getHost().isEmpty()
                || u.getRawUserInfo() != null) {
            throw new IllegalArgumentException("url");
        }
        return u;
    }

    /**
     * Loopback, unspecified, private, link local, carrier grade NAT, unique local IPv6, multicast and the IPv4
     * mapped forms of those. What a hook made over the API may not reach unless webhooks.allowPrivateTargets.
     */
    public static boolean isPrivate(InetAddress a) {
        if (a.isLoopbackAddress() || a.isAnyLocalAddress() || a.isLinkLocalAddress() || a.isSiteLocalAddress()
                || a.isMulticastAddress()) {
            return true;
        }
        byte[] b = a.getAddress();
        if (a instanceof Inet4Address) {
            int b0 = b[0] & 0xff;
            int b1 = b[1] & 0xff;
            return b0 == 0 || (b0 == 100 && b1 >= 64 && b1 < 128) || (b0 == 198 && (b1 == 18 || b1 == 19))
                    || b0 >= 240 || (b0 == 192 && b1 == 0 && (b[2] & 0xff) == 0);
        }
        if (a instanceof Inet6Address) {
            if ((b[0] & 0xfe) == 0xfc) {
                return true;
            }
            boolean mapped = true;
            for (int i = 0; i < 10; i++) {
                mapped &= b[i] == 0;
            }
            if (mapped && (b[10] & 0xff) == 0xff && (b[11] & 0xff) == 0xff) {
                try {
                    return isPrivate(InetAddress.getByAddress(new byte[]{b[12], b[13], b[14], b[15]}));
                } catch (UnknownHostException e) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * Null when the host may be sent to, else private_target or unresolved. Every address a name resolves to
     * must be public.
     */
    public String targetProblem(String host) {
        String h = host.startsWith("[") && host.endsWith("]") ? host.substring(1, host.length() - 1) : host;
        try {
            InetAddress[] all = resolver.resolve(h);
            if (all == null || all.length == 0) {
                return "unresolved";
            }
            for (InetAddress a : all) {
                if (isPrivate(a)) {
                    return "private_target";
                }
            }
            return null;
        } catch (UnknownHostException e) {
            return "unresolved";
        }
    }

    // ---- delivery ----------------------------------------------------------------------------------------

    /** Starts a worker per hook. Module start. */
    public synchronized void start() {
        running = true;
        for (Hook h : hooks.values()) {
            startWorker(h);
        }
    }

    private void startWorker(Hook h) {
        Worker old = workers.remove(h.id());
        if (old != null) {
            old.stop();
        }
        Worker w = new Worker(h);
        workers.put(h.id(), w);
        w.thread = Thread.ofVirtual().name("RestAPI-webhook-" + h.id()).start(w);
    }

    /**
     * Stops every worker, after giving the queues up to drainMillis to empty, so the shutdown event of a stopping
     * server still goes out when the target answers fast. Module stop.
     */
    public void stop(long drainMillis) {
        List<Worker> all;
        synchronized (this) {
            running = false;
            all = new ArrayList<>(workers.values());
            workers.clear();
        }
        long until = System.currentTimeMillis() + drainMillis;
        for (Worker w : all) {
            w.closing = true;
        }
        for (Worker w : all) {
            while (System.currentTimeMillis() < until && (!w.queue.isEmpty() || w.busy)) {
                try {
                    Thread.sleep(20);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
            w.stop();
        }
        if (transport instanceof AutoCloseable c) {
            try {
                c.close();
            } catch (Exception e) {
                log.accept("RestAPI could not close the webhook client", e);
            }
        }
    }

    /** Queues the event for every hook that wants its type. Runs under the ring's lock, so it never blocks. */
    public void offer(EventRing.Event e) {
        if (!running || !EventsSettings.WEBHOOKS_ENABLED.get()) {
            return;
        }
        int cap = EventsSettings.WEBHOOKS_QUEUE.get();
        for (Worker w : workers.values()) {
            if (w.hook.types().contains(e.type())) {
                w.enqueue(e, cap);
            }
        }
    }

    /** Sends a test delivery now, once, without retries, and returns what the target answered. */
    public Result test(Hook h) throws InterruptedException {
        JsonObject data = new JsonObject();
        data.addProperty("hook", h.id());
        EventRing.Event e = new EventRing.Event(0, "test", null, System.currentTimeMillis(), data);
        Result r = sendOnce(h, e);
        record(h, r, false);
        return r;
    }

    /** One attempt, with the private target check for hooks made over the API. */
    Result sendOnce(Hook h, EventRing.Event e) throws InterruptedException {
        URI url = URI.create(h.url());
        if (h.viaApi() && !EventsSettings.WEBHOOKS_PRIVATE.get()) {
            String problem = targetProblem(url.getHost());
            if (problem != null) {
                return new Result(0, problem, 0);
            }
        }
        byte[] body = body(h, e);
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("Content-Type", "application/json; charset=utf-8");
        headers.put("User-Agent", "RestAPI-Webhooks");
        headers.put("X-RestAPI-Event", e.action() == null ? e.type() : e.type() + "." + e.action());
        headers.put("X-RestAPI-Delivery", UUID.randomUUID().toString());
        if (h.secret() != null && !h.secret().isEmpty()) {
            headers.put("X-RestAPI-Signature", "sha256=" + sign(h.secret(), body));
        }
        return transport.post(url, headers, body, Duration.ofSeconds(EventsSettings.WEBHOOKS_TIMEOUT.get()));
    }

    /** HMAC-SHA256 of the body with the secret as UTF-8, in lower case hex. */
    public static String sign(String secret, byte[] body) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(body));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("HmacSHA256 is missing from this JVM", e);
        }
    }

    /** The event JSON, or a Discord message that mentions nobody. */
    static byte[] body(Hook h, EventRing.Event e) {
        if (!h.discord()) {
            return COMPACT.toJson(e.toJson()).getBytes(StandardCharsets.UTF_8);
        }
        JsonObject o = new JsonObject();
        String text = discordText(e);
        if (text.length() > DISCORD_MAX) {
            text = text.substring(0, DISCORD_MAX - 3) + "...";
        }
        o.addProperty("content", text);
        JsonObject mentions = new JsonObject();
        mentions.add("parse", new JsonArray());
        o.add("allowed_mentions", mentions);
        return COMPACT.toJson(o).getBytes(StandardCharsets.UTF_8);
    }

    /** One line of plain text for the event, from messages.json. */
    static String discordText(EventRing.Event e) {
        JsonObject d = e.data();
        String player = str(d, "player");
        String world = str(d, "world");
        return switch (e.type()) {
            case "join" -> Messages.get(EventsText.DISCORD_JOIN, "player", player, "world", world);
            case "ready" -> Messages.get(EventsText.DISCORD_READY, "player", player, "world", world);
            case "leave" -> Messages.get(EventsText.DISCORD_LEAVE, "player", player);
            case "chat" -> Messages.get(EventsText.DISCORD_CHAT, "player", player, "text", str(d, "text"));
            case "death" -> {
                String who = player.isEmpty() ? str(d, "npc") : player;
                String killer = str(d, "killer").isEmpty() ? str(d, "killerNpc") : str(d, "killer");
                yield killer.isEmpty()
                        ? Messages.get(EventsText.DISCORD_DEATH, "player", who, "world", world, "cause", str(d, "cause"))
                        : Messages.get(EventsText.DISCORD_KILLED, "player", who, "killer", killer, "world", world);
            }
            case "command" -> Messages.get(EventsText.DISCORD_COMMAND, "by", str(d, "by"), "command", str(d, "command"));
            case "world" -> Messages.get("removed".equals(e.action()) ? EventsText.DISCORD_WORLD_REMOVED
                    : EventsText.DISCORD_WORLD_ADDED, "world", world);
            case "plugin" -> Messages.get(EventsText.DISCORD_PLUGIN, "plugin", str(d, "plugin"), "from",
                    str(d, "from"), "to", str(d, "to"));
            case "server" -> Messages.get("stopping".equals(e.action()) ? EventsText.DISCORD_SHUTDOWN
                    : EventsText.DISCORD_BOOT);
            case "test" -> Messages.get(EventsText.DISCORD_TEST, "id", str(d, "hook"));
            default -> Messages.get(EventsText.DISCORD_OTHER, "type", e.type());
        };
    }

    private static String str(JsonObject d, String k) {
        JsonElement e = d == null ? null : d.get(k);
        return e == null || e.isJsonNull() ? "" : e.getAsString();
    }

    private void record(Hook h, Result r, boolean count) {
        State s = state(h.id());
        s.lastStatus = r.status();
        s.lastAt = Instant.now().toString();
        s.lastError = r.error();
        if (count) {
            if (r.ok()) {
                s.delivered.incrementAndGet();
                ok.incrementAndGet();
            } else {
                s.failed.incrementAndGet();
                failedTotal.incrementAndGet();
            }
        }
    }

    /** Deliveries by result for /metrics: ok, failed, retried, dropped. */
    public Map<String, Long> totals() {
        Map<String, Long> m = new LinkedHashMap<>();
        m.put("ok", ok.get());
        m.put("failed", failedTotal.get());
        m.put("retried", retried.get());
        m.put("dropped", dropped.get());
        return m;
    }

    /** The wait before retry n, counting from 1, in milliseconds. */
    static long backoffMillis(int retry, int baseSeconds) {
        return baseSeconds * 1000L * (1L << Math.min(20, Math.max(0, retry - 1)));
    }

    private final class Worker implements Runnable {
        final Hook hook;
        final BlockingDeque<EventRing.Event> queue = new LinkedBlockingDeque<>();
        volatile boolean closing;
        volatile boolean stopped;
        volatile boolean busy;
        Thread thread;

        Worker(Hook hook) {
            this.hook = hook;
        }

        void enqueue(EventRing.Event e, int cap) {
            while (queue.size() >= cap) {
                if (queue.pollFirst() != null) {
                    dropped.incrementAndGet();
                    state(hook.id()).dropped.incrementAndGet();
                }
            }
            queue.offerLast(e);
        }

        void stop() {
            stopped = true;
            if (thread != null) {
                thread.interrupt();
            }
        }

        @Override
        public void run() {
            while (!stopped) {
                EventRing.Event e;
                try {
                    e = queue.pollFirst(1, TimeUnit.SECONDS);
                } catch (InterruptedException ex) {
                    return;
                }
                if (e == null) {
                    continue;
                }
                busy = true;
                try {
                    deliver(e);
                } catch (InterruptedException ex) {
                    return;
                } catch (RuntimeException ex) {
                    log.accept("webhook " + hook.id(), ex);
                } finally {
                    busy = false;
                }
            }
        }

        private void deliver(EventRing.Event e) throws InterruptedException {
            int retries = EventsSettings.WEBHOOKS_RETRIES.get();
            Result r = null;
            for (int attempt = 0; attempt <= retries && !stopped; attempt++) {
                if (attempt > 0) {
                    if (closing) {
                        break;
                    }
                    retried.incrementAndGet();
                    sleeper.sleep(backoffMillis(attempt, EventsSettings.WEBHOOKS_BACKOFF.get()));
                }
                r = sendOnce(hook, e);
                if (r.ok() || !r.retry()) {
                    break;
                }
            }
            if (r != null) {
                record(hook, r, true);
            }
        }
    }

    // ---- the JDK client ----------------------------------------------------------------------------------

    /**
     * The JDK client, one per Webhooks, built on the first post and closed in {@link #stop}, so a reload of RestAPI
     * leaves no selector thread behind and no client holding the old class loader. The next post after a
     * /restapi reload builds a new one.
     */
    static final class JdkTransport implements Transport, AutoCloseable {
        private HttpClient client;

        private synchronized HttpClient client() {
            if (client == null) {
                client = HttpClient.newBuilder()
                        .version(HttpClient.Version.HTTP_1_1)
                        .followRedirects(HttpClient.Redirect.NEVER)
                        .connectTimeout(Duration.ofSeconds(10))
                        .build();
            }
            return client;
        }

        @Override
        public Result post(URI url, Map<String, String> headers, byte[] body, Duration timeout) throws InterruptedException {
            long start = System.nanoTime();
            try {
                HttpRequest.Builder b = HttpRequest.newBuilder(url).timeout(timeout)
                        .POST(HttpRequest.BodyPublishers.ofByteArray(body));
                headers.forEach(b::header);
                HttpResponse<Void> r = client().send(b.build(), HttpResponse.BodyHandlers.discarding());
                return new Result(r.statusCode(), null, (System.nanoTime() - start) / 1_000_000);
            } catch (IOException e) {
                String why = e instanceof java.net.http.HttpTimeoutException ? "timeout" : "network";
                return new Result(0, why, (System.nanoTime() - start) / 1_000_000);
            } catch (IllegalArgumentException e) {
                return new Result(0, "bad_request", 0);
            }
        }

        @Override
        public synchronized void close() {
            HttpClient c = client;
            client = null;
            if (c != null) {
                c.shutdownNow();
                c.close();
            }
        }

        synchronized boolean open() {
            return client != null;
        }
    }

    /** The URL with its query and any path segment of 20 characters or more hidden, for listings. */
    public static String masked(String url) {
        try {
            URI u = new URI(url);
            String[] segs = (u.getRawPath() == null ? "" : u.getRawPath()).split("/", -1);
            for (int i = 0; i < segs.length; i++) {
                if (segs[i].length() >= 20) {
                    segs[i] = "***";
                }
            }
            return u.getScheme() + "://" + u.getRawAuthority() + String.join("/", segs)
                    + (u.getRawQuery() == null ? "" : "?***");
        } catch (java.net.URISyntaxException e) {
            return "***";
        }
    }

    /** For tests, the number of running workers. */
    int workers() {
        return workers.size();
    }
}
