package dev.ryvrook.restapi.players;

import com.google.gson.JsonObject;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.math.vector.Rotation3f;
import com.hypixel.hytale.math.vector.Transform;
import com.hypixel.hytale.protocol.GameMode;
import com.hypixel.hytale.protocol.packets.interface_.EventTitleStyle;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.asset.type.item.config.Item;
import com.hypixel.hytale.server.core.console.ConsoleSender;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.inventory.container.ItemContainer;
import com.hypixel.hytale.server.core.inventory.transaction.ClearTransaction;
import com.hypixel.hytale.server.core.inventory.transaction.ItemStackTransaction;
import com.hypixel.hytale.server.core.io.ConnectionRegistry;
import com.hypixel.hytale.server.core.modules.entity.damage.Damage;
import com.hypixel.hytale.server.core.modules.entity.damage.DamageCause;
import com.hypixel.hytale.server.core.modules.entity.damage.DeathComponent;
import com.hypixel.hytale.server.core.modules.entity.teleport.Teleport;
import com.hypixel.hytale.server.core.modules.entitystats.EntityStatMap;
import com.hypixel.hytale.server.core.modules.entitystats.EntityStatValue;
import com.hypixel.hytale.server.core.modules.entitystats.asset.DefaultEntityStatTypes;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.Universe;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.core.util.EventTitleUtil;
import dev.ryvrook.restapi.http.ApiException;
import dev.ryvrook.restapi.text.Messages;
import org.joml.Vector3d;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * The changes to one player and the broadcasts. Kick, message, title and refer go through the connection and are
 * safe from the request thread. Everything that touches the entity runs on the player's world thread through
 * {@link WorldCall}, the same way the engine's own /give, /kill and /tp do it.
 */
public final class PlayerActions {
    private PlayerActions() {
    }

    /**
     * Where a teleport goes. Either coordinates with an optional world and rotation, or another player. Yaw and
     * pitch are in degrees as in /tp, the engine keeps radians.
     */
    record Destination(Double x, Double y, Double z, String world, Double yaw, Double pitch, String to) {
        boolean toPlayer() {
            return to != null;
        }
    }

    static final double COORD_LIMIT = 30_000_000;

    /** Reads {x, y, z, world?, yaw?, pitch?} or {to}. 400 when neither form is complete. */
    static Destination destination(JsonObject body) {
        String to = Fields.text(body, "to", false, 64);
        if (to != null && !to.isEmpty()) {
            return new Destination(null, null, null, null, null, null, to);
        }
        if (!Fields.has(body, "x") || !Fields.has(body, "y") || !Fields.has(body, "z")) {
            throw ApiException.badRequest("missing_field", Messages.get(PlayersText.TELEPORT));
        }
        return new Destination(Fields.number(body, "x", null, -COORD_LIMIT, COORD_LIMIT),
                Fields.number(body, "y", null, -COORD_LIMIT, COORD_LIMIT),
                Fields.number(body, "z", null, -COORD_LIMIT, COORD_LIMIT),
                Fields.text(body, "world", false, 128),
                Fields.has(body, "yaw") ? Fields.number(body, "yaw", null, -360, 360) : null,
                Fields.has(body, "pitch") ? Fields.number(body, "pitch", null, -90, 90) : null, null);
    }

    /** Adventure or Creative, ignoring case. */
    static GameMode gameMode(String s) {
        if (s != null) {
            for (GameMode g : GameMode.VALUES) {
                if (g.name().equalsIgnoreCase(s.trim())) {
                    return g;
                }
            }
        }
        throw ApiException.badRequest("invalid_field", Messages.get(PlayersText.MODE));
    }

    static EventTitleStyle style(boolean major) {
        return major ? EventTitleStyle.Major : EventTitleStyle.Default;
    }

    static Message colored(String text, String color) {
        Message m = Message.raw(text);
        return color == null ? m : m.color(color);
    }

    private static int maxText() {
        return PlayersSettings.MAX_TEXT_LENGTH.get();
    }

    // Engine side.

    static World worldOrConflict(Lookup.Target t) {
        World w = PlayerRead.worldOf(t.online());
        if (w == null) {
            throw new ApiException(409, "not_in_world", Messages.get(PlayersText.NOT_IN_WORLD, "player", t.label()));
        }
        return w;
    }

    /** World thread, the entity or 409. */
    static Ref<EntityStore> refOrConflict(Lookup.Target t) {
        Ref<EntityStore> ref = PlayerRead.ref(t.online());
        if (ref == null) {
            throw new ApiException(409, "not_in_world", Messages.get(PlayersText.NOT_IN_WORLD, "player", t.label()));
        }
        return ref;
    }

    static JsonObject who(Lookup.Target t) {
        JsonObject o = new JsonObject();
        o.addProperty("name", t.name());
        o.addProperty("uuid", t.uuid().toString());
        return o;
    }

    static JsonObject kick(Lookup.Target t, JsonObject body, String keyName) {
        String reason = Fields.text(body, "reason", false, maxText());
        String shown = Moderation.reasonFor(reason, PlayersText.KICK_REASON, keyName, PlayersSettings.KICK_NAMES_KEY.get());
        int closed = ConnectionRegistry.get().disconnect(t.uuid(), Message.raw(shown));
        JsonObject o = who(t);
        o.addProperty("connectionsClosed", closed);
        o.addProperty("reason", shown);
        return o;
    }

    static JsonObject message(Lookup.Target t, JsonObject body) {
        String text = Fields.text(body, "text", true, maxText());
        String color = Fields.color(body);
        t.online().sendMessage(colored(text, color));
        JsonObject o = who(t);
        o.addProperty("sent", true);
        return o;
    }

    static JsonObject title(Lookup.Target t, JsonObject body) {
        String title = Fields.text(body, "title", true, maxText());
        String sub = Fields.text(body, "subtitle", false, maxText());
        double fadeIn = Fields.number(body, "fadeIn", 1.5, 0, 60);
        double stay = Fields.number(body, "stay", 4.0, 0, 600);
        double fadeOut = Fields.number(body, "fadeOut", 1.5, 0, 60);
        boolean major = Fields.bool(body, "major", true);
        EventTitleUtil.showEventTitleToPlayer(t.online(), Message.raw(title), Message.raw(sub == null ? "" : sub), style(major),
                null, (float) stay, (float) fadeIn, (float) fadeOut);
        JsonObject o = who(t);
        o.addProperty("sent", true);
        return o;
    }

    static JsonObject teleport(Lookup.Target t, Destination d, Lookup lookup) {
        World from = worldOrConflict(t);
        World target;
        Transform where;
        if (d.toPlayer()) {
            Lookup.Target other = lookup.online(d.to());
            World ow = worldOrConflict(other);
            Transform read = WorldCall.on(ow, () -> {
                refOrConflict(other);
                Transform tr = other.online().getTransform();
                return tr == null ? null : new Transform(new Vector3d(tr.getPosition()), new Rotation3f(tr.getRotation()));
            });
            if (read == null) {
                throw new ApiException(409, "not_in_world", Messages.get(PlayersText.NOT_IN_WORLD, "player", other.label()));
            }
            target = ow;
            where = read;
        } else {
            if (d.world() != null && !d.world().isEmpty()) {
                target = Universe.get().getWorld(d.world());
                if (target == null) {
                    throw ApiException.notFound("world_not_found", Messages.get(PlayersText.WORLD_NOT_FOUND, "world", d.world()));
                }
            } else {
                target = from;
            }
            Transform now = t.online().getTransform();
            Rotation3f rot = now == null || now.getRotation() == null ? new Rotation3f() : new Rotation3f(now.getRotation());
            if (d.yaw() != null) {
                rot = new Rotation3f(rot.pitch(), (float) Math.toRadians(d.yaw()), rot.roll());
            }
            if (d.pitch() != null) {
                rot = new Rotation3f((float) Math.toRadians(d.pitch()), rot.yaw(), rot.roll());
            }
            where = new Transform(new Vector3d(d.x(), d.y(), d.z()), rot);
        }
        if (target != from && !PlayersSettings.TELEPORT_ACROSS_WORLDS.get()) {
            throw new ApiException(409, "other_world", Messages.get(PlayersText.OTHER_WORLD));
        }
        World dest = target;
        Transform goal = where;
        WorldCall.on(from, () -> {
            Ref<EntityStore> ref = refOrConflict(t);
            ref.getStore().addComponent(ref, Teleport.getComponentType(), Teleport.createForPlayer(dest, goal));
            return null;
        });
        JsonObject o = who(t);
        o.addProperty("world", dest.getName());
        JsonObject p = new JsonObject();
        p.addProperty("x", PlayerRead.round(goal.getPosition().x, 2));
        p.addProperty("y", PlayerRead.round(goal.getPosition().y, 2));
        p.addProperty("z", PlayerRead.round(goal.getPosition().z, 2));
        o.add("position", p);
        return o;
    }

    /** The item asset with this id, or 404 item_not_found. */
    static String itemId(String id) {
        Item item = Item.getAssetMap().getAsset(id);
        if (item == null) {
            throw ApiException.notFound("item_not_found", Messages.get(PlayersText.ITEM, "item", id));
        }
        return item.getId();
    }

    static JsonObject give(Lookup.Target t, JsonObject body) {
        String id = itemId(Fields.text(body, "item", true, 256));
        int amount = Fields.integer(body, "amount", 1, 1, PlayersSettings.MAX_GIVE_AMOUNT.get());
        World w = worldOrConflict(t);
        int remainder = WorldCall.on(w, () -> {
            Ref<EntityStore> ref = refOrConflict(t);
            ItemStackTransaction tx = Player.giveItem(new ItemStack(id, amount), ref, ref.getStore());
            ItemStack rest = tx == null ? null : tx.getRemainder();
            return rest == null || ItemStack.isEmpty(rest) ? 0 : rest.getQuantity();
        });
        JsonObject o = who(t);
        o.addProperty("item", id);
        o.addProperty("given", amount - remainder);
        o.addProperty("remainder", remainder);
        return o;
    }

    static JsonObject clear(Lookup.Target t, JsonObject body) {
        Set<PlayerRead.Section> sections = PlayerRead.Section.parse(Fields.strings(body, "sections", false));
        World w = worldOrConflict(t);
        JsonObject removed = WorldCall.on(w, () -> {
            Ref<EntityStore> ref = refOrConflict(t);
            Store<EntityStore> store = ref.getStore();
            JsonObject per = new JsonObject();
            for (PlayerRead.Section s : sections) {
                ItemContainer c = PlayerRead.container(store, ref, s);
                if (c == null) {
                    continue;
                }
                ClearTransaction tx = c.clear();
                int items = 0;
                if (tx != null && tx.getItems() != null) {
                    for (ItemStack st : tx.getItems()) {
                        if (st != null && !ItemStack.isEmpty(st)) {
                            items += st.getQuantity();
                        }
                    }
                }
                per.addProperty(s.id, items);
            }
            return per;
        });
        JsonObject o = who(t);
        o.add("removed", removed);
        return o;
    }

    static JsonObject gameMode(Lookup.Target t, JsonObject body) {
        GameMode mode = gameMode(Fields.text(body, "mode", true, 32));
        World w = worldOrConflict(t);
        WorldCall.on(w, () -> {
            Ref<EntityStore> ref = refOrConflict(t);
            Player.setGameMode(ref, mode, ref.getStore());
            return null;
        });
        JsonObject o = who(t);
        o.addProperty("gameMode", mode.name());
        return o;
    }

    static JsonObject heal(Lookup.Target t) {
        World w = worldOrConflict(t);
        double[] after = WorldCall.on(w, () -> {
            Ref<EntityStore> ref = refOrConflict(t);
            EntityStatMap stats = ref.getStore().getComponent(ref, EntityStatMap.getComponentType());
            if (stats == null) {
                return null;
            }
            int health = DefaultEntityStatTypes.getHealth();
            stats.maximizeStatValue(health);
            EntityStatValue v = stats.get(health);
            return v == null ? null : new double[]{v.get(), v.getMax()};
        });
        JsonObject o = who(t);
        o.add("health", after == null ? null : PlayerRead.stat(new PlayerRead.Stat(after[0], after[1])));
        return o;
    }

    /** DamageCause.COMMAND is deprecated in 0.7.1 but is what the engine's own /kill passes. */
    @SuppressWarnings("deprecation")
    static JsonObject kill(Lookup.Target t) {
        World w = worldOrConflict(t);
        WorldCall.on(w, () -> {
            Ref<EntityStore> ref = refOrConflict(t);
            Damage d = new Damage(new Damage.CommandSource(ConsoleSender.INSTANCE, "kill"), DamageCause.COMMAND, Float.MAX_VALUE);
            DeathComponent.tryAddComponent(ref.getStore(), ref, d);
            return null;
        });
        JsonObject o = who(t);
        o.addProperty("killed", true);
        return o;
    }

    static JsonObject refer(Lookup.Target t, JsonObject body) {
        String host = Fields.text(body, "host", true, 253);
        int port = Fields.integer(body, "port", null, 1, 65_535);
        t.online().referToServer(host, port);
        JsonObject o = who(t);
        o.addProperty("host", host);
        o.addProperty("port", port);
        return o;
    }

    /** The online players of one world, or of every world when the name is empty. 404 for an unknown world. */
    static List<PlayerRef> audience(String world) {
        Universe u = Universe.get();
        if (world == null || world.isEmpty()) {
            return new ArrayList<>(u.getPlayers());
        }
        World w = u.getWorld(world);
        if (w == null) {
            throw ApiException.notFound("world_not_found", Messages.get(PlayersText.WORLD_NOT_FOUND, "world", world));
        }
        List<PlayerRef> out = new ArrayList<>();
        for (PlayerRef p : u.getPlayers()) {
            if (w.equals(PlayerRead.worldOf(p))) {
                out.add(p);
            }
        }
        return out;
    }

    static JsonObject broadcast(JsonObject body) {
        String text = Fields.text(body, "text", true, maxText());
        String color = Fields.color(body);
        String world = Fields.text(body, "world", false, 128);
        boolean prefix = Fields.bool(body, "prefix", true);
        List<PlayerRef> to = audience(world);
        Message m = colored(prefix ? Messages.get(PlayersText.BROADCAST, "text", text) : text, color);
        for (PlayerRef p : to) {
            p.sendMessage(m);
        }
        JsonObject o = new JsonObject();
        o.addProperty("recipients", to.size());
        o.addProperty("world", world == null || world.isEmpty() ? null : world);
        return o;
    }

    static JsonObject broadcastTitle(JsonObject body) {
        String title = Fields.text(body, "title", true, maxText());
        String sub = Fields.text(body, "subtitle", false, maxText());
        String world = Fields.text(body, "world", false, 128);
        double fadeIn = Fields.number(body, "fadeIn", 1.5, 0, 60);
        double stay = Fields.number(body, "stay", 4.0, 0, 600);
        double fadeOut = Fields.number(body, "fadeOut", 1.5, 0, 60);
        boolean major = Fields.bool(body, "major", true);
        List<PlayerRef> to = audience(world);
        Message main = Message.raw(title);
        Message second = Message.raw(sub == null ? "" : sub);
        for (PlayerRef p : to) {
            EventTitleUtil.showEventTitleToPlayer(p, main, second, style(major), null, (float) stay, (float) fadeIn, (float) fadeOut);
        }
        JsonObject o = new JsonObject();
        o.addProperty("recipients", to.size());
        o.addProperty("world", world == null || world.isEmpty() ? null : world);
        return o;
    }

}
