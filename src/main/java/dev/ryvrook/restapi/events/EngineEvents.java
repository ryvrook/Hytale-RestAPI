package dev.ryvrook.restapi.events;

import com.google.gson.JsonObject;
import com.hypixel.hytale.component.Archetype;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.query.Query;
import com.hypixel.hytale.event.EventPriority;
import com.hypixel.hytale.event.EventRegistry;
import com.hypixel.hytale.logger.backend.HytaleLoggerBackend;
import com.hypixel.hytale.server.core.event.events.BootEvent;
import com.hypixel.hytale.server.core.event.events.ShutdownEvent;
import com.hypixel.hytale.server.core.event.events.player.PlayerChatEvent;
import com.hypixel.hytale.server.core.event.events.player.PlayerConnectEvent;
import com.hypixel.hytale.server.core.event.events.player.PlayerDisconnectEvent;
import com.hypixel.hytale.server.core.event.events.player.PlayerReadyEvent;
import com.hypixel.hytale.server.core.modules.entity.component.TransformComponent;
import com.hypixel.hytale.server.core.modules.entity.damage.Damage;
import com.hypixel.hytale.server.core.modules.entity.damage.DamageCause;
import com.hypixel.hytale.server.core.modules.entity.damage.DeathComponent;
import com.hypixel.hytale.server.core.modules.entity.damage.DeathSystems;
import com.hypixel.hytale.server.core.plugin.PluginBase;
import com.hypixel.hytale.server.core.plugin.PluginManager;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.events.AddWorldEvent;
import com.hypixel.hytale.server.core.universe.world.events.RemoveWorldEvent;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.npc.entities.NPCEntity;
import org.joml.Vector3d;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BiConsumer;
import java.util.logging.LogRecord;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Turns engine happenings into events. Bus listeners and the death system are registered once in the plugin's
 * setup, the only time the engine takes them, and check the settings when they fire, so /restapi reload can
 * switch a type on or off. Commands have no engine event. Every run logs {@code "<sender> executed command:
 * <line>"} at INFO, so commands come from a log subscription of this module's own. Plugins have no enable event,
 * so their states are polled.
 *
 * <p>Threads. Connect and chat arrive on the async event threads, ready and deaths on the world thread,
 * disconnect on a network thread, the log line on whichever thread logged it. Each handler only reads values and
 * publishes, publishing never blocks.
 */
public final class EngineEvents {
    /** Where events go: type, action, data. */
    @FunctionalInterface
    public interface Sink {
        void publish(String type, String action, JsonObject data);
    }

    private static final Pattern COMMAND_LINE = Pattern.compile("^(.+?) executed command: (.*)$", Pattern.DOTALL);
    private static final String COMMAND_FORMAT = "%s executed command: %s";

    private final Sink sink;
    private final BiConsumer<String, Throwable> onError;
    private volatile CommandTap tap;

    public EngineEvents(Sink sink, BiConsumer<String, Throwable> onError) {
        this.sink = sink;
        this.onError = onError;
    }

    // ---- setup -------------------------------------------------------------------------------------------

    /** Plugin setup. Each registration is guarded, one that fails on another server version leaves the rest. */
    public List<String> register(PluginBase plugin) {
        List<String> failed = new ArrayList<>();
        EventRegistry bus = plugin.getEventRegistry();
        guard(failed, "join", () -> bus.registerAsyncGlobal(PlayerConnectEvent.class, f -> f.thenApply(this::onConnect)));
        guard(failed, "ready", () -> bus.registerGlobal(PlayerReadyEvent.class, this::onReady));
        guard(failed, "leave", () -> bus.registerGlobal(PlayerDisconnectEvent.class, this::onDisconnect));
        guard(failed, "chat", () -> bus.registerAsyncGlobal(EventPriority.LAST, PlayerChatEvent.class,
                f -> f.thenApply(this::onChat)));
        guard(failed, "world added", () -> bus.registerGlobal(EventPriority.LAST, AddWorldEvent.class, this::onAddWorld));
        guard(failed, "world removed", () -> bus.registerGlobal(EventPriority.LAST, RemoveWorldEvent.class, this::onRemoveWorld));
        guard(failed, "boot", () -> bus.register(BootEvent.class, e -> publish("server", "booted", new JsonObject())));
        guard(failed, "shutdown", () -> bus.register(ShutdownEvent.class, e -> publish("server", "stopping", new JsonObject())));
        guard(failed, "death", () -> plugin.getEntityStoreRegistry().registerSystem(new DeathWatch()));
        return failed;
    }

    private static void guard(List<String> failed, String what, Runnable r) {
        try {
            r.run();
        } catch (RuntimeException | LinkageError e) {
            failed.add(what);
        }
    }

    private void publish(String type, String action, JsonObject data) {
        try {
            sink.publish(type, action, data);
        } catch (RuntimeException | LinkageError e) {
            onError.accept(type, e);
        }
    }

    // ---- players -----------------------------------------------------------------------------------------

    private PlayerConnectEvent onConnect(PlayerConnectEvent event) {
        try {
            PlayerRef p = event.getPlayerRef();
            if (p != null) {
                World w = event.getWorld();
                JsonObject d = player(p.getUsername(), p.getUuid());
                d.addProperty("world", w == null ? null : w.getName());
                publish("join", null, d);
            }
        } catch (RuntimeException | LinkageError e) {
            onError.accept("join", e);
        }
        return event;
    }

    /** World thread. Fires on every world entry, so it is its own type next to join. */
    private void onReady(PlayerReadyEvent event) {
        try {
            Ref<EntityStore> ref = event.getPlayerRef();
            if (ref == null || !ref.isValid()) {
                return;
            }
            Store<EntityStore> store = ref.getStore();
            PlayerRef p = store.getComponent(ref, PlayerRef.getComponentType());
            if (p == null) {
                return;
            }
            JsonObject d = player(p.getUsername(), p.getUuid());
            d.addProperty("world", worldName(store));
            publish("ready", null, d);
        } catch (RuntimeException | LinkageError e) {
            onError.accept("ready", e);
        }
    }

    private void onDisconnect(PlayerDisconnectEvent event) {
        try {
            PlayerRef p = event.getPlayerRef();
            if (p == null) {
                return;
            }
            JsonObject d = player(p.getUsername(), p.getUuid());
            Object reason = event.getDisconnectReason();
            d.addProperty("reason", reason == null ? null : reason.toString());
            publish("leave", null, d);
        } catch (RuntimeException | LinkageError e) {
            onError.accept("leave", e);
        }
    }

    /** Async, LAST, so every chat plugin had its say. Cancelled messages are left out. */
    private PlayerChatEvent onChat(PlayerChatEvent event) {
        try {
            if (event.isCancelled() || !EventsSettings.CHAT.get()) {
                return event;
            }
            PlayerRef p = event.getSender();
            if (p == null) {
                return event;
            }
            String text = event.getContent();
            JsonObject d = player(p.getUsername(), p.getUuid());
            d.addProperty("length", text == null ? 0 : text.length());
            if (EventsSettings.CHAT_TEXT.get()) {
                d.addProperty("text", text);
            }
            publish("chat", null, d);
        } catch (RuntimeException | LinkageError e) {
            onError.accept("chat", e);
        }
        return event;
    }

    static JsonObject player(String name, java.util.UUID uuid) {
        JsonObject d = new JsonObject();
        d.addProperty("player", name);
        d.addProperty("uuid", uuid == null ? null : uuid.toString());
        return d;
    }

    // ---- worlds ------------------------------------------------------------------------------------------

    private void onAddWorld(AddWorldEvent event) {
        if (event.isCancelled()) {
            return;
        }
        world("added", event.getWorld());
    }

    private void onRemoveWorld(RemoveWorldEvent event) {
        if (event.isCancelled() && event.getRemovalReason() != RemoveWorldEvent.RemovalReason.EXCEPTIONAL) {
            return;
        }
        world("removed", event.getWorld());
    }

    private void world(String action, World w) {
        try {
            if (w == null) {
                return;
            }
            JsonObject d = new JsonObject();
            d.addProperty("world", w.getName());
            publish("world", action, d);
        } catch (RuntimeException | LinkageError e) {
            onError.accept("world", e);
        }
    }

    static String worldName(Store<EntityStore> store) {
        try {
            EntityStore es = store.getExternalData();
            World w = es == null ? null : es.getWorld();
            return w == null ? null : w.getName();
        } catch (RuntimeException | LinkageError e) {
            return null;
        }
    }

    // ---- deaths ------------------------------------------------------------------------------------------

    /**
     * Fires once when an entity gets its DeathComponent, the same hook TrackerAPI, Feats and Chronicle Stats use.
     * World thread. Players always, creatures only with events.npcDeaths.
     */
    public final class DeathWatch extends DeathSystems.OnDeathSystem {
        @Override
        public Query<EntityStore> getQuery() {
            return Archetype.empty();
        }

        @Override
        public void onComponentAdded(Ref<EntityStore> ref, DeathComponent death, Store<EntityStore> store,
                                     CommandBuffer<EntityStore> buffer) {
            if (!EventsSettings.DEATHS.get()) {
                return;
            }
            try {
                death(ref, death, store);
            } catch (RuntimeException | LinkageError e) {
                onError.accept("death", e);
            }
        }
    }

    private void death(Ref<EntityStore> ref, DeathComponent death, Store<EntityStore> store) {
        if (ref == null || !ref.isValid()) {
            return;
        }
        PlayerRef victim = store.getComponent(ref, PlayerRef.getComponentType());
        JsonObject d;
        if (victim != null) {
            d = player(victim.getUsername(), victim.getUuid());
        } else {
            if (!EventsSettings.NPC_DEATHS.get()) {
                return;
            }
            NPCEntity npc = store.getComponent(ref, NPCEntity.getComponentType());
            if (npc == null) {
                return;
            }
            d = new JsonObject();
            d.addProperty("npc", npc.getNPCTypeId());
        }
        d.addProperty("world", worldName(store));
        d.addProperty("cause", cause(death));
        try {
            TransformComponent t = store.getComponent(ref, TransformComponent.getComponentType());
            Vector3d pos = t == null ? null : t.getPosition();
            if (pos != null) {
                JsonObject at = new JsonObject();
                at.addProperty("x", Math.round(pos.x * 100) / 100.0);
                at.addProperty("y", Math.round(pos.y * 100) / 100.0);
                at.addProperty("z", Math.round(pos.z * 100) / 100.0);
                d.add("position", at);
            }
        } catch (RuntimeException | LinkageError ignored) {
            // The death still counts without its place.
        }
        killer(d, death, store, ref);
        publish("death", victim != null ? "player" : "npc", d);
    }

    @SuppressWarnings("deprecation")
    private static String cause(DeathComponent death) {
        try {
            DamageCause c = death.getDeathCause();
            if (c != null) {
                return c.getId();
            }
            Damage info = death.getDeathInfo();
            DamageCause byIndex = info == null ? null : DamageCause.getAssetMap().getAsset(info.getDamageCauseIndex());
            return byIndex == null ? null : byIndex.getId();
        } catch (RuntimeException | LinkageError e) {
            return null;
        }
    }

    private static void killer(JsonObject d, DeathComponent death, Store<EntityStore> store, Ref<EntityStore> victim) {
        try {
            Damage info = death.getDeathInfo();
            if (info == null || !(info.getSource() instanceof Damage.EntitySource src)) {
                return;
            }
            Ref<EntityStore> k = src.getRef();
            if (k == null || !k.isValid() || k.getStore() != store || k.equals(victim)) {
                return;
            }
            PlayerRef p = store.getComponent(k, PlayerRef.getComponentType());
            if (p != null) {
                d.addProperty("killer", p.getUsername());
                d.addProperty("killerUuid", String.valueOf(p.getUuid()));
                return;
            }
            NPCEntity npc = store.getComponent(k, NPCEntity.getComponentType());
            if (npc != null) {
                d.addProperty("killerNpc", npc.getNPCTypeId());
            }
        } catch (RuntimeException | LinkageError ignored) {
            // Leave the killer out.
        }
    }

    // ---- commands from the log ---------------------------------------------------------------------------

    /** Module start. Subscribes to the engine log for command lines. */
    public void startCommandTap() {
        stopCommandTap();
        CommandTap t = new CommandTap();
        try {
            HytaleLoggerBackend.subscribe(t);
            tap = t;
        } catch (RuntimeException | LinkageError e) {
            onError.accept("command", e);
        }
    }

    /** Module stop. */
    public void stopCommandTap() {
        CommandTap t = tap;
        tap = null;
        if (t != null) {
            try {
                HytaleLoggerBackend.unsubscribe(t);
            } catch (RuntimeException | LinkageError e) {
                onError.accept("command", e);
            }
        }
    }

    /**
     * The list the logger backend calls add on for every root record. It keeps nothing, it only looks for the
     * command line, so the cost per log line is one string check.
     */
    private final class CommandTap extends CopyOnWriteArrayList<LogRecord> {
        @Override
        public boolean add(LogRecord r) {
            try {
                if (r != null && EventsSettings.COMMANDS.get()) {
                    String[] parsed = commandOf(r.getMessage(), r.getParameters());
                    if (parsed != null) {
                        JsonObject d = new JsonObject();
                        d.addProperty("by", parsed[0]);
                        d.addProperty("command", parsed[1]);
                        publish("command", null, d);
                    }
                }
            } catch (RuntimeException | LinkageError ignored) {
                // Never let a log line fail because of this.
            }
            return true;
        }

        @Override
        public boolean equals(Object o) {
            return this == o;
        }

        @Override
        public int hashCode() {
            return System.identityHashCode(this);
        }
    }

    /**
     * Sender and command line from the engine's log record, either as the format with two parameters or as the
     * text already formatted. A leading slash is dropped. Null for any other line.
     */
    static String[] commandOf(String message, Object[] params) {
        if (message == null || !message.contains("executed command: ")) {
            return null;
        }
        String by;
        String line;
        if (COMMAND_FORMAT.equals(message) && params != null && params.length >= 2) {
            by = Objects.toString(params[0]);
            line = Objects.toString(params[1]);
        } else {
            Matcher m = COMMAND_LINE.matcher(message.strip());
            if (!m.matches()) {
                return null;
            }
            by = m.group(1);
            line = m.group(2);
        }
        line = line.strip();
        if (line.startsWith("/")) {
            line = line.substring(1);
        }
        return line.isEmpty() ? null : new String[]{by.strip(), line};
    }

    // ---- plugins -----------------------------------------------------------------------------------------

    private volatile Map<String, String[]> lastPlugins;

    /** Scheduler thread. The first call takes the baseline and publishes nothing. */
    public void pollPlugins() {
        Map<String, String[]> now = pluginStates();
        Map<String, String[]> before = lastPlugins;
        lastPlugins = now;
        if (before == null) {
            return;
        }
        for (PluginChange c : diff(before, now)) {
            JsonObject d = new JsonObject();
            d.addProperty("plugin", c.id());
            d.addProperty("version", c.version());
            d.addProperty("from", c.from());
            d.addProperty("to", c.to());
            publish("plugin", c.to().toLowerCase(java.util.Locale.ROOT), d);
        }
    }

    /** Forgets the baseline, at module stop, so a later start does not report what changed in between. */
    public void resetPlugins() {
        lastPlugins = null;
    }

    /** Plugin id to {state, version}. */
    static Map<String, String[]> pluginStates() {
        Map<String, String[]> out = new LinkedHashMap<>();
        PluginManager pm = PluginManager.get();
        if (pm == null) {
            return out;
        }
        for (PluginBase p : pm.getPlugins()) {
            try {
                String version = p.getManifest() == null || p.getManifest().getVersion() == null
                        ? null : p.getManifest().getVersion().toString();
                out.put(String.valueOf(p.getIdentifier()), new String[]{String.valueOf(p.getState()), version});
            } catch (RuntimeException | LinkageError ignored) {
                // A plugin half loaded. The next poll sees it.
            }
        }
        return out;
    }

    /** One plugin whose state changed. NONE stands for absent. */
    record PluginChange(String id, String version, String from, String to) {
    }

    static List<PluginChange> diff(Map<String, String[]> before, Map<String, String[]> after) {
        List<PluginChange> out = new ArrayList<>();
        for (Map.Entry<String, String[]> e : after.entrySet()) {
            String[] old = before.get(e.getKey());
            String from = old == null ? "NONE" : old[0];
            if (!from.equals(e.getValue()[0])) {
                out.add(new PluginChange(e.getKey(), e.getValue()[1], from, e.getValue()[0]));
            }
        }
        for (Map.Entry<String, String[]> e : before.entrySet()) {
            if (!after.containsKey(e.getKey())) {
                out.add(new PluginChange(e.getKey(), e.getValue()[1], e.getValue()[0], "NONE"));
            }
        }
        return out;
    }
}
