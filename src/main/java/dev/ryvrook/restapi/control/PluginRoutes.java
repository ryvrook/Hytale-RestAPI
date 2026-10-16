package dev.ryvrook.restapi.control;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.hypixel.hytale.common.plugin.AuthorInfo;
import com.hypixel.hytale.common.plugin.PluginIdentifier;
import com.hypixel.hytale.common.plugin.PluginManifest;
import com.hypixel.hytale.common.semver.SemverRange;
import com.hypixel.hytale.server.core.HytaleServer;
import com.hypixel.hytale.server.core.HytaleServerConfig;
import com.hypixel.hytale.server.core.plugin.JavaPlugin;
import com.hypixel.hytale.server.core.plugin.PluginBase;
import com.hypixel.hytale.server.core.plugin.PluginManager;
import com.hypixel.hytale.server.core.plugin.PluginState;
import dev.ryvrook.restapi.auth.Scope;
import dev.ryvrook.restapi.http.Access;
import dev.ryvrook.restapi.http.ApiException;
import dev.ryvrook.restapi.http.Call;
import dev.ryvrook.restapi.http.Response;
import dev.ryvrook.restapi.http.Route;
import dev.ryvrook.restapi.http.RouteDoc;
import dev.ryvrook.restapi.text.Messages;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * GET /api/plugins, /api/plugins/available, /api/plugins/{id}, and POST load, unload and reload. The writes refuse
 * Ryvrook:RestAPI through {@link SelfGuard}, need control.allowPluginLoad, take the suite lock and keep their
 * answer under an Idempotency-Key. PluginManager calls run on the request thread, as the engine's own /plugin
 * command runs them on a pool thread.
 */
final class PluginRoutes {
    private PluginRoutes() {
    }

    private static final String G = ControlModule.GROUP;
    private static final String ONE = "{\"id\":\"Ryvrook:Lullaby\",\"group\":\"Ryvrook\",\"name\":\"Lullaby\",\"version\":\"1.0.1\","
            + "\"state\":\"ENABLED\",\"description\":\"...\",\"authors\":[\"ryvrook\"],\"website\":\"https://github.com/ryvrook/lullaby\","
            + "\"dependencies\":{},\"optionalDependencies\":{},\"jar\":\"mods/Lullaby-1.0.1.jar\",\"dataFolder\":\"mods/Ryvrook_Lullaby\"}";

    static List<Route> routes(ControlModule m) {
        Replays r = m.replays();
        return List.of(
                Route.of(G, "GET", "/api/plugins", Scope.READ, Access.READ,
                        "Every loaded plugin with its version, state, authors, dependencies and jar.",
                        call -> list()).doc(new RouteDoc(List.of(), null, "{\"plugins\":[" + ONE + "]}", List.of())),
                Route.of(G, "GET", "/api/plugins/available", Scope.PLUGINS, Access.READ,
                        "Plugins whose manifest the engine found in the mods folders but that are not loaded, such as a jar added after the boot.",
                        call -> available()).doc(new RouteDoc(List.of(), null,
                        "{\"plugins\":[{\"id\":\"Ryvrook:Lullaby\",\"version\":\"1.0.1\",\"description\":\"...\"}]}", List.of())),
                Route.of(G, "GET", "/api/plugins/{id}", Scope.READ, Access.READ,
                        "One loaded plugin by its Group:Name id.",
                        call -> Response.ok(json(find(call)))).doc(new RouteDoc(List.of(), null, ONE,
                        List.of("bad_plugin_id", "plugin_not_found"))),
                Route.of(G, "POST", "/api/plugins/{id}/load", Scope.PLUGINS, Access.DANGEROUS,
                        "Loads a plugin from the mods folders. boot true also adds it to the boot list in config.json like /plugin load --boot.",
                        r.wrap(PluginRoutes::load)).doc(new RouteDoc(List.of(), "{\"boot\":false}",
                        "{\"id\":\"Ryvrook:Lullaby\",\"action\":\"load\",\"state\":\"ENABLED\",\"settled\":true,\"lock\":true,\"millis\":412}",
                        List.of("bad_plugin_id", "plugin_not_found", "plugin_loaded", "plugin_refused", "self_refused", "feature_off"))),
                Route.of(G, "POST", "/api/plugins/{id}/unload", Scope.PLUGINS, Access.DANGEROUS,
                        "Unloads a plugin, and the plugins that list it under Dependencies with it. boot true also takes it off the boot list.",
                        r.wrap(PluginRoutes::unload)).doc(new RouteDoc(List.of(), "{\"boot\":false}",
                        "{\"id\":\"Ryvrook:Lullaby\",\"action\":\"unload\",\"state\":\"UNLOADED\",\"settled\":true,\"lock\":true,\"millis\":38}",
                        List.of("bad_plugin_id", "plugin_not_loaded", "plugin_refused", "self_refused", "feature_off"))),
                Route.of(G, "POST", "/api/plugins/{id}/reload", Scope.PLUGINS, Access.DANGEROUS,
                        "Reloads a plugin and waits up to control.reloadWaitSeconds for the new copy. 202 when it is still starting. Holds the suite lock meanwhile.",
                        r.wrap(PluginRoutes::reload)).doc(new RouteDoc(List.of(), null,
                        "{\"id\":\"Ryvrook:Lullaby\",\"action\":\"reload\",\"state\":\"ENABLED\",\"settled\":true,\"lock\":true,\"millis\":1250}",
                        List.of("bad_plugin_id", "plugin_not_loaded", "plugin_refused", "self_refused", "feature_off"))));
    }

    private static PluginManager manager() {
        PluginManager pm = PluginManager.get();
        if (pm == null) {
            throw new ApiException(503, "booting", Messages.get(ControlText.BOOTING));
        }
        return pm;
    }

    private static PluginIdentifier identifier(PluginId id) {
        return new PluginIdentifier(id.group(), id.name());
    }

    /** The loaded plugin, matched exactly first and then without regard to case. */
    static PluginBase loaded(PluginManager pm, PluginId id) {
        PluginBase p = pm.getPlugin(identifier(id));
        if (p != null) {
            return p;
        }
        for (PluginBase x : pm.getPlugins()) {
            PluginIdentifier pi = x.getIdentifier();
            if (pi != null && pi.getGroup().equalsIgnoreCase(id.group()) && pi.getName().equalsIgnoreCase(id.name())) {
                return x;
            }
        }
        return null;
    }

    private static PluginBase find(Call call) {
        PluginId id = PluginId.parse(call.param("id"));
        PluginBase p = loaded(manager(), id);
        if (p == null) {
            throw ApiException.notFound("plugin_not_found", Messages.get(ControlText.PLUGIN_NOT_FOUND, "id", id));
        }
        return p;
    }

    private static Response list() {
        List<PluginBase> all = new ArrayList<>(manager().getPlugins());
        all.sort(Comparator.comparing(p -> String.valueOf(p.getIdentifier())));
        JsonArray a = new JsonArray();
        for (PluginBase p : all) {
            a.add(json(p));
        }
        JsonObject o = new JsonObject();
        o.addProperty("count", a.size());
        o.add("plugins", a);
        return Response.ok(o);
    }

    private static Response available() {
        PluginManager pm = manager();
        JsonArray a = new JsonArray();
        List<Map.Entry<PluginIdentifier, PluginManifest>> entries = new ArrayList<>(pm.getAvailablePlugins().entrySet());
        entries.sort(Comparator.comparing(e -> String.valueOf(e.getKey())));
        for (Map.Entry<PluginIdentifier, PluginManifest> e : entries) {
            if (pm.getPlugin(e.getKey()) != null) {
                continue;
            }
            JsonObject o = manifest(e.getValue());
            o.addProperty("id", String.valueOf(e.getKey()));
            a.add(o);
        }
        JsonObject o = new JsonObject();
        o.addProperty("count", a.size());
        o.add("plugins", a);
        return Response.ok(o);
    }

    static JsonObject json(PluginBase p) {
        JsonObject o = new JsonObject();
        o.addProperty("id", String.valueOf(p.getIdentifier()));
        PluginManifest m = p.getManifest();
        if (m != null) {
            JsonObject man = manifest(m);
            for (String k : man.keySet()) {
                o.add(k, man.get(k));
            }
        }
        o.addProperty("state", stateOf(p));
        o.addProperty("type", p.getType() == null ? null : p.getType().name());
        if (p instanceof JavaPlugin jp && jp.getFile() != null) {
            o.addProperty("jar", jp.getFile().toString());
        }
        o.addProperty("dataFolder", p.getDataDirectory() == null ? null : p.getDataDirectory().toString());
        return o;
    }

    static JsonObject manifest(PluginManifest m) {
        JsonObject o = new JsonObject();
        o.addProperty("group", m.getGroup());
        o.addProperty("name", m.getName());
        o.addProperty("version", m.getVersion() == null ? null : m.getVersion().toString());
        o.addProperty("description", m.getDescription());
        JsonArray authors = new JsonArray();
        if (m.getAuthors() != null) {
            for (AuthorInfo a : m.getAuthors()) {
                if (a != null && a.getName() != null) {
                    authors.add(a.getName());
                }
            }
        }
        o.add("authors", authors);
        o.addProperty("website", m.getWebsite());
        o.addProperty("serverVersion", m.getServerVersion() == null ? null : m.getServerVersion().toString());
        o.add("dependencies", deps(m.getDependencies()));
        o.add("optionalDependencies", deps(m.getOptionalDependencies()));
        return o;
    }

    private static JsonObject deps(Map<PluginIdentifier, SemverRange> map) {
        JsonObject o = new JsonObject();
        if (map != null) {
            map.forEach((k, v) -> o.addProperty(String.valueOf(k), v == null ? null : v.toString()));
        }
        return o;
    }

    static String stateOf(PluginBase p) {
        PluginState s = p == null ? null : p.getState();
        return p == null ? "UNLOADED" : s == null ? "NONE" : s.name();
    }

    /** The plugin id and the shared checks of every write. */
    private static PluginId writable(Call call) {
        ControlModule.require(ControlSettings.ALLOW_PLUGIN_LOAD);
        PluginId id = PluginId.parse(call.param("id"));
        SelfGuard.check(id);
        return id;
    }

    private static SuiteLock.Held lock() {
        try {
            return SuiteLock.acquire(ControlSettings.LOCK_WAIT_SECONDS.get());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ApiException(503, "interrupted", Messages.get(ControlText.INTERRUPTED));
        }
    }

    private static Response load(Call call) {
        PluginId id = writable(call);
        boolean boot = Boolean.TRUE.equals(Bodies.optBool(call.jsonBody(), "boot"));
        PluginManager pm = manager();
        PluginBase before = loaded(pm, id);
        if (before != null) {
            throw new ApiException(409, "plugin_loaded", Messages.get(ControlText.PLUGIN_LOADED, "id", id, "state", stateOf(before)));
        }
        PluginIdentifier pid = availableId(pm, id);
        if (pid == null) {
            throw ApiException.notFound("plugin_not_found", Messages.get(ControlText.PLUGIN_NOT_FOUND, "id", id));
        }
        long start = System.currentTimeMillis();
        try (SuiteLock.Held held = lock()) {
            if (boot) {
                setBoot(pid, true);
            }
            if (!pm.load(pid) && pm.getPlugin(pid) == null) {
                throw new ApiException(409, "plugin_refused", Messages.get(ControlText.PLUGIN_REFUSED, "action", "load", "id", pid));
            }
            PluginBase after = settle(pm, pid, null);
            return answer(pid, "load", after, held.held(), start, boot);
        }
    }

    private static Response unload(Call call) {
        PluginId id = writable(call);
        boolean boot = Boolean.TRUE.equals(Bodies.optBool(call.jsonBody(), "boot"));
        PluginManager pm = manager();
        PluginBase before = loaded(pm, id);
        if (before == null) {
            throw ApiException.notFound("plugin_not_loaded", Messages.get(ControlText.PLUGIN_NOT_LOADED, "id", id));
        }
        PluginIdentifier pid = before.getIdentifier();
        long start = System.currentTimeMillis();
        try (SuiteLock.Held held = lock()) {
            if (boot) {
                setBoot(pid, false);
            }
            if (!pm.unload(pid) && pm.getPlugin(pid) == before) {
                throw new ApiException(409, "plugin_refused", Messages.get(ControlText.PLUGIN_REFUSED, "action", "unload", "id", pid));
            }
            return answer(pid, "unload", pm.getPlugin(pid), held.held(), start, boot);
        }
    }

    private static Response reload(Call call) {
        PluginId id = writable(call);
        PluginManager pm = manager();
        PluginBase before = loaded(pm, id);
        if (before == null) {
            throw ApiException.notFound("plugin_not_loaded", Messages.get(ControlText.PLUGIN_NOT_LOADED, "id", id));
        }
        PluginIdentifier pid = before.getIdentifier();
        long start = System.currentTimeMillis();
        try (SuiteLock.Held held = lock()) {
            boolean ok = pm.reload(pid);
            PluginBase after = settle(pm, pid, before);
            if (!ok && after == null) {
                throw new ApiException(409, "plugin_refused", Messages.get(ControlText.PLUGIN_REFUSED, "action", "reload", "id", pid));
            }
            return answer(pid, "reload", after, held.held(), start, false);
        }
    }

    /** The id as the engine spells it among the available manifests, or null. */
    private static PluginIdentifier availableId(PluginManager pm, PluginId id) {
        for (PluginIdentifier k : pm.getAvailablePlugins().keySet()) {
            if (k.getGroup().equalsIgnoreCase(id.group()) && k.getName().equalsIgnoreCase(id.name())) {
                return k;
            }
        }
        return null;
    }

    /**
     * Polls every 100 ms until the plugin is a new instance in ENABLED, up to control.reloadWaitSeconds. The
     * engine's reload can answer false while the new copy still loads and finish on its own a moment later.
     */
    private static PluginBase settle(PluginManager pm, PluginIdentifier pid, PluginBase old) {
        long until = System.currentTimeMillis() + TimeUnit.SECONDS.toMillis(ControlSettings.RELOAD_WAIT_SECONDS.get());
        PluginBase now = pm.getPlugin(pid);
        while (System.currentTimeMillis() < until) {
            if (now != null && now != old && now.getState() == PluginState.ENABLED) {
                return now;
            }
            if (now != null && now != old && now.getState() == PluginState.FAILED) {
                return now;
            }
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
            now = pm.getPlugin(pid);
        }
        return now;
    }

    private static Response answer(PluginIdentifier pid, String action, PluginBase after, boolean lock, long start, boolean boot) {
        String state = stateOf(after);
        boolean settled = "unload".equals(action) ? after == null : "ENABLED".equals(state);
        JsonObject o = new JsonObject();
        o.addProperty("id", String.valueOf(pid));
        o.addProperty("action", action);
        o.addProperty("state", state);
        o.addProperty("settled", settled);
        o.addProperty("boot", boot);
        o.addProperty("lock", lock);
        o.addProperty("millis", System.currentTimeMillis() - start);
        if (after != null && after.getManifest() != null && after.getManifest().getVersion() != null) {
            o.addProperty("version", after.getManifest().getVersion().toString());
        }
        return Response.json(settled ? 200 : 202, o);
    }

    /** /plugin load --boot does the same, setBoot then save when something changed. */
    private static void setBoot(PluginIdentifier pid, boolean on) {
        HytaleServer s = HytaleServer.get();
        HytaleServerConfig c = s == null ? null : s.getConfig();
        if (c == null) {
            return;
        }
        HytaleServerConfig.setBoot(c, pid, on);
        if (c.consumeHasChanged()) {
            try {
                HytaleServerConfig.save(c).get(10, TimeUnit.SECONDS);
            } catch (Exception e) {
                throw new ApiException(500, "save_failed", Messages.get(ControlText.SAVE_FAILED));
            }
        }
    }
}
