package dev.ryvrook.restapi.dashboard;

import com.google.gson.JsonObject;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.asset.AssetModule;
import dev.ryvrook.restapi.RestApi;
import dev.ryvrook.restapi.RestModule;
import dev.ryvrook.restapi.audit.AuditEntry;
import dev.ryvrook.restapi.command.Replies;
import dev.ryvrook.restapi.config.CoreSettings;
import dev.ryvrook.restapi.config.Setting;
import dev.ryvrook.restapi.config.Settings;
import dev.ryvrook.restapi.http.ApiException;
import dev.ryvrook.restapi.http.Route;
import dev.ryvrook.restapi.siblings.Sibling;
import dev.ryvrook.restapi.suite.Folders;
import dev.ryvrook.restapi.suite.JsonFile;
import dev.ryvrook.restapi.text.Messages;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.logging.Level;

/**
 * The admin dashboard, served on RestAPI's own listener under /dashboard. Off by default, turned on with
 * /restapi dashboard on, which writes dashboard.enabled and mounts the routes without a reload. While off the
 * group holds no route at all.
 *
 * <p>The session store lives as long as the plugin, so /restapi reload keeps the sessions. Turning the page off,
 * by the command or by a reload that finds dashboard.enabled false, ends them. Assets.zip is opened at start when
 * the page is on and closed at stop, and so is the client that passes requests to Chronicle Stats.
 */
public final class DashboardModule implements RestModule {
    private final SessionStore sessions = SessionStore.live();
    private volatile RestApi api;
    private volatile PageFiles page;
    private volatile AssetZip assets;
    private volatile String assetsProblem;
    private volatile ChroniclePass chronicle;
    private volatile JsonFile chronicleConfig;
    private volatile JsonFile atlasConfig;

    public DashboardModule() {
        DashboardSettings.ENABLED.key();
        DashboardText.ROUTE_PAGE.length();
    }

    @Override
    public String group() {
        return DashboardRoutes.GROUP;
    }

    @Override
    public List<Route> routes(RestApi api) throws Exception {
        this.api = api;
        if (!DashboardSettings.ENABLED.get()) {
            sessions.endAll();
            return List.of();
        }
        if (page == null) {
            page = PageFiles.load(DashboardModule.class.getClassLoader());
        }
        chronicleConfig = new JsonFile(Folders.of(api, Sibling.CHRONICLE).resolve("chronicle.json"), Sibling.CHRONICLE.pluginName());
        atlasConfig = new JsonFile(Folders.of(api, Sibling.ATLAS).resolve("atlas.json"), Sibling.ATLAS.pluginName());
        ChroniclePass pass = new ChroniclePass(this::chronicleBase, lazyHttp(), System::currentTimeMillis);
        chronicle = pass;
        return new DashboardRoutes(page, sessions, () -> assets, pass, api.gate(), api.audit(), Clock.systemUTC(),
                this::imageOrigins, api.version()).routes();
    }

    @Override
    public void start(RestApi api) {
        this.api = api;
        if (!DashboardSettings.ENABLED.get() || api.router().routes().stream().noneMatch(r -> r.group().equals(group()))) {
            api.gate().credentials(null);
            return;
        }
        openAssets(api);
        api.gate().credentials(sessions);
    }

    @Override
    public void stop() {
        RestApi a = api;
        if (a != null) {
            a.gate().credentials(null);
        }
        AssetZip z = assets;
        assets = null;
        if (z != null) {
            try {
                z.close();
            } catch (IOException e) {
                // Closing a file that was only read.
            }
        }
        ChroniclePass p = chronicle;
        chronicle = null;
        if (p != null) {
            p.close();
        }
    }

    private void openAssets(RestApi api) {
        Path file = AssetZip.find(DashboardSettings.ASSETS.get(), Path.of("").toAbsolutePath(), DashboardModule::enginePack);
        if (file == null) {
            assetsProblem = null;
            return;
        }
        try {
            long t = System.nanoTime();
            assets = AssetZip.open(file);
            assetsProblem = null;
            api.logger().at(Level.INFO).log("RestAPI dashboard reads game icons from %s, %d icons indexed in %d ms",
                    file, assets.count(), (System.nanoTime() - t) / 1_000_000);
        } catch (IOException | RuntimeException e) {
            assetsProblem = e.getMessage();
            api.logger().at(Level.WARNING).log("RestAPI dashboard could not read %s, %s. The page shows names without game icons",
                    file, e.getMessage());
        }
    }

    /** Where the engine loaded its base asset pack from, an Assets.zip on a normal server. */
    private static Path enginePack() {
        AssetModule m = AssetModule.get();
        return m == null || m.getBaseAssetPack() == null ? null : m.getBaseAssetPack().getPackLocation();
    }

    /** The client is made on the first request, so a page that never shows a bust starts no thread. */
    private static ChroniclePass.Upstream lazyHttp() {
        return new ChroniclePass.Upstream() {
            private ChroniclePass.Upstream real;
            private boolean closed;

            private synchronized ChroniclePass.Upstream real() throws IOException {
                if (closed) {
                    throw new IOException("closed");
                }
                if (real == null) {
                    real = ChroniclePass.http();
                }
                return real;
            }

            @Override
            public ChroniclePass.Answer get(String url, String ifNoneMatch, boolean asset) throws IOException, InterruptedException {
                return real().get(url, ifNoneMatch, asset);
            }

            @Override
            public synchronized void close() {
                closed = true;
                if (real != null) {
                    real.close();
                }
            }
        };
    }

    // ---- Chronicle Stats and Atlas ------------------------------------------------------------------------

    /** Why the Chronicle Stats pass through is off, or null while it is on. */
    private String chronicleReason() {
        RestApi a = api;
        if (!DashboardSettings.CHRONICLE.get()) {
            return Messages.get(DashboardText.REASON_SETTING);
        }
        if (a == null || !a.router().mounted(Sibling.CHRONICLE.group())) {
            return Messages.get(DashboardText.REASON_UNBOUND);
        }
        return chronicleBase() == null ? Messages.get(DashboardText.REASON_WEB) : null;
    }

    /**
     * Chronicle's own address over loopback with a trailing slash, from the bind, port and basePath of its
     * chronicle.json, or null while the pass through is off, Chronicle is not bound, its web server is off or
     * the file does not read.
     */
    String chronicleBase() {
        RestApi a = api;
        JsonFile f = chronicleConfig;
        if (!DashboardSettings.CHRONICLE.get() || a == null || f == null || !a.router().mounted(Sibling.CHRONICLE.group())) {
            return null;
        }
        JsonObject cfg = read(f);
        if (cfg == null || !bool(cfg, "webEnabled", true) || number(cfg, "port", 0) <= 0) {
            return null;
        }
        return base(text(cfg, "bind", ""), number(cfg, "port", 0), text(cfg, "basePath", "")) + "/";
    }

    /** The Atlas origins the page may load mini map tiles from, while Atlas is bound. */
    String imageOrigins() {
        RestApi a = api;
        JsonFile f = atlasConfig;
        if (a == null || f == null || !a.router().mounted(Sibling.ATLAS.group())) {
            return "";
        }
        JsonObject cfg = read(f);
        if (cfg == null) {
            return "";
        }
        Set<String> out = new LinkedHashSet<>();
        String pub = text(cfg, "publicUrl", "");
        String o = origin(pub);
        if (o != null) {
            out.add(o);
        }
        int port = number(cfg, "port", 0);
        if (port > 0) {
            String b = origin(base(text(cfg, "bind", ""), port, ""));
            if (b != null) {
                out.add(b);
            }
        }
        return String.join(" ", out);
    }

    private static JsonObject read(JsonFile f) {
        try {
            return f.readOrNull();
        } catch (ApiException e) {
            return null;
        }
    }

    static String base(String bind, int port, String basePath) {
        String path = basePath == null ? "" : DashboardRoutes.trimSlash(basePath.trim());
        if (!path.isEmpty() && !path.startsWith("/")) {
            path = "/" + path;
        }
        return "http://" + DashboardRoutes.hostPart(bind) + ":" + port + path;
    }

    /** scheme://host[:port] of an http or https address, or null. Nothing that could widen the policy passes. */
    static String origin(String url) {
        if (url == null || url.isBlank()) {
            return null;
        }
        try {
            URI u = new URI(url.trim());
            String scheme = u.getScheme() == null ? null : u.getScheme().toLowerCase(java.util.Locale.ROOT);
            String auth = u.getRawAuthority();
            if (!("http".equals(scheme) || "https".equals(scheme)) || auth == null || !auth.matches("[A-Za-z0-9.\\-:\\[\\]]+")) {
                return null;
            }
            return scheme + "://" + auth;
        } catch (java.net.URISyntaxException e) {
            return null;
        }
    }

    private static String text(JsonObject o, String key, String fallback) {
        try {
            return o.has(key) && o.get(key).isJsonPrimitive() ? o.get(key).getAsString() : fallback;
        } catch (RuntimeException e) {
            return fallback;
        }
    }

    private static int number(JsonObject o, String key, int fallback) {
        try {
            return o.has(key) && o.get(key).isJsonPrimitive() ? o.get(key).getAsInt() : fallback;
        } catch (RuntimeException e) {
            return fallback;
        }
    }

    private static boolean bool(JsonObject o, String key, boolean fallback) {
        try {
            return o.has(key) && o.get(key).isJsonPrimitive() ? o.get(key).getAsBoolean() : fallback;
        } catch (RuntimeException e) {
            return fallback;
        }
    }

    // ---- /restapi status and /restapi dashboard ----------------------------------------------------------

    @Override
    public List<String> status() {
        if (!DashboardSettings.ENABLED.get()) {
            return List.of(Messages.get(DashboardText.STATUS_OFF, "url", DashboardRoutes.pageUrl()));
        }
        return List.of(Messages.get(DashboardText.STATUS_ON, "url", DashboardRoutes.pageUrl(),
                "writes", DashboardSettings.WRITES.get() ? "on" : "off", "sessions", count(sessions.count(), "session")));
    }

    @Override
    public List<String> lines(String topic) {
        if (!"status".equals(topic)) {
            return List.of();
        }
        List<String> out = new ArrayList<>(status());
        if (DashboardSettings.ENABLED.get()) {
            AssetZip z = assets;
            out.add(z != null ? Messages.get(DashboardText.STATUS_ASSETS, "file", z.file(), "count", z.count())
                    : Messages.get(DashboardText.STATUS_NO_ASSETS));
            String why = chronicleReason();
            out.add(why == null ? Messages.get(DashboardText.STATUS_CHRONICLE, "url", chronicleBase())
                    : Messages.get(DashboardText.STATUS_NO_CHRONICLE, "reason", why));
            PageFiles p = page;
            if (p != null) {
                out.add(Messages.get(DashboardText.STATUS_PAGE, "version", p.version(), "commit", p.commit(),
                        "files", p.count(), "kb", (p.bytes() + 512) / 1024));
            }
        }
        return out;
    }

    @Override
    public List<Message> act(String topic, String by, Runnable remount) {
        RestApi a = api;
        if (a == null) {
            return List.of();
        }
        return switch (topic) {
            case "on" -> turn(a, true, by, remount);
            case "off" -> turn(a, false, by, remount);
            case "status" -> {
                audit(a, by, "dashboard status", "ok");
                List<Message> out = new ArrayList<>();
                for (String line : lines("status")) {
                    out.add(Replies.line(line, Replies.MUTED));
                }
                yield out;
            }
            case "login" -> login(a, by);
            case "logout" -> {
                int n = sessions.endAll();
                audit(a, by, "dashboard logout", n + " ended");
                yield List.of(Replies.line(Messages.get(DashboardText.LOGGED_OUT, "sessions", count(n, "session")), Replies.OK));
            }
            case "writes on" -> writes(a, true, by);
            case "writes off" -> writes(a, false, by);
            default -> List.of();
        };
    }

    private List<Message> turn(RestApi a, boolean on, String by, Runnable remount) {
        String url = DashboardRoutes.pageUrl();
        if (DashboardSettings.ENABLED.get() == on) {
            audit(a, by, "dashboard " + (on ? "on" : "off"), "unchanged");
            return List.of(Replies.line(on ? Messages.get(DashboardText.ALREADY_ON, "url", url)
                    : Messages.get(DashboardText.ALREADY_OFF), Replies.MUTED));
        }
        if (!save(a, DashboardSettings.ENABLED, on)) {
            return List.of(Replies.line(Messages.get(DashboardText.SAVE_FAILED, "reason", saveProblem), Replies.ERROR));
        }
        int open = on ? 0 : sessions.count();
        remount.run();
        audit(a, by, "dashboard " + (on ? "on" : "off"), "ok");
        List<Message> out = new ArrayList<>();
        if (on) {
            boolean mounted = a.router().routes().stream().anyMatch(r -> r.group().equals(group()));
            out.add(mounted ? Replies.line(Messages.get(DashboardText.ON, "url", url), Replies.OK)
                    : Replies.line(Messages.get(DashboardText.NOT_MOUNTED), Replies.ERROR));
            if (!CoreSettings.HTTP_ENABLED.get()) {
                out.add(Replies.line(Messages.get(DashboardText.HTTP_DOWN), Replies.ERROR));
            }
        } else {
            out.add(Replies.line(Messages.get(DashboardText.OFF, "sessions", count(open, "session")), Replies.OK));
        }
        return out;
    }

    private List<Message> login(RestApi a, String by) {
        if (!DashboardSettings.ENABLED.get()) {
            return List.of(Replies.line(Messages.get(DashboardText.LOGIN_OFF), Replies.ERROR));
        }
        String token = sessions.issue(by);
        String link = DashboardRoutes.apiRoot() + "/dashboard/login?t=" + token;
        audit(a, by, "dashboard login link", "valid " + DashboardSettings.LINK_MINUTES.get() + " minutes");
        return List.of(Replies.line(Messages.get(DashboardText.LINK, "minutes", DashboardSettings.LINK_MINUTES.get()), Replies.OK),
                Message.raw(link).color(Replies.PREFIX).link(link));
    }

    private List<Message> writes(RestApi a, boolean on, String by) {
        if (DashboardSettings.WRITES.get() == on) {
            audit(a, by, "dashboard writes " + (on ? "on" : "off"), "unchanged");
            return List.of(Replies.line(Messages.get(DashboardText.WRITES_SAME, "state", on ? "on" : "off"), Replies.MUTED));
        }
        if (!save(a, DashboardSettings.WRITES, on)) {
            return List.of(Replies.line(Messages.get(DashboardText.SAVE_FAILED, "reason", saveProblem), Replies.ERROR));
        }
        audit(a, by, "dashboard writes " + (on ? "on" : "off"), "ok");
        return List.of(Replies.line(Messages.get(on ? DashboardText.WRITES_ON : DashboardText.WRITES_OFF), Replies.OK));
    }

    private volatile String saveProblem;

    private boolean save(RestApi a, Setting<Boolean> s, boolean value) {
        try {
            Settings.persist(a.dataDirectory().resolve(Settings.FILE), s, value);
            return true;
        } catch (IOException | IllegalArgumentException e) {
            saveProblem = e.getMessage();
            a.logger().at(Level.WARNING).log("RestAPI could not write %s to RestAPI.json, %s", s.key(), e.getMessage());
            return false;
        }
    }

    private static void audit(RestApi a, String by, String action, String detail) {
        a.audit().record(new AuditEntry(Instant.now().toString(), "command", by, by, action, 0, detail));
    }

    private static String count(int n, String word) {
        return n + " " + word + (n == 1 ? "" : "s");
    }
}
