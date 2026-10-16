package dev.ryvrook.restapi.players;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.math.vector.Rotation3f;
import com.hypixel.hytale.math.vector.Transform;
import com.hypixel.hytale.metrics.metric.HistoricMetric;
import com.hypixel.hytale.protocol.GameMode;
import com.hypixel.hytale.protocol.packets.connection.PongType;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.inventory.InventoryComponent;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.inventory.container.ItemContainer;
import com.hypixel.hytale.server.core.io.PacketHandler;
import com.hypixel.hytale.server.core.modules.entitystats.EntityStatMap;
import com.hypixel.hytale.server.core.modules.entitystats.EntityStatValue;
import com.hypixel.hytale.server.core.modules.entitystats.asset.DefaultEntityStatTypes;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.Universe;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import dev.ryvrook.restapi.http.ApiException;
import dev.ryvrook.restapi.text.Messages;
import org.joml.Vector3d;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * The read side of players. What {@code PlayerRef} answers off the world thread (name, UUID, world, position,
 * head rotation, ping, language) is read on the request thread. Game mode, stats and inventory go through
 * {@link WorldCall}, one call per world for a list.
 */
public final class PlayerRead {
    private PlayerRead() {
    }

    /** A stat as value and max. */
    record Stat(double value, double max) {
    }

    /** What the world thread reads for one player. Null fields were not readable. */
    record Live(String gameMode, Stat health, Stat stamina, Stat mana, Stat oxygen) {
        static final Live NONE = new Live(null, null, null, null, null);
    }

    /** The inventory sections the API names, with the engine's section ids. */
    enum Section {
        HOTBAR("hotbar", InventoryComponent.HOTBAR_SECTION_ID),
        STORAGE("storage", InventoryComponent.STORAGE_SECTION_ID),
        ARMOR("armor", InventoryComponent.ARMOR_SECTION_ID),
        UTILITY("utility", InventoryComponent.UTILITY_SECTION_ID),
        TOOL("tool", InventoryComponent.TOOLS_SECTION_ID),
        BACKPACK("backpack", InventoryComponent.BACKPACK_SECTION_ID);

        final String id;
        final int engineId;

        Section(String id, int engineId) {
            this.id = id;
            this.engineId = engineId;
        }

        static String known() {
            return java.util.Arrays.stream(values()).map(s -> s.id).collect(Collectors.joining(", "));
        }

        /** Section names to sections, every section for null or an empty list. 400 on an unknown name. */
        static Set<Section> parse(List<String> names) {
            if (names == null || names.isEmpty()) {
                return EnumSet.allOf(Section.class);
            }
            EnumSet<Section> out = EnumSet.noneOf(Section.class);
            for (String n : names) {
                Section found = null;
                for (Section s : values()) {
                    if (s.id.equalsIgnoreCase(n.trim())) {
                        found = s;
                    }
                }
                if (found == null) {
                    throw ApiException.badRequest("invalid_field", Messages.get(PlayersText.SECTION, "section", n, "known", known()));
                }
                out.add(found);
            }
            return out;
        }
    }

    /** A ping average in the metric's unit as whole milliseconds, null when there is no sample yet. */
    static Integer pingMs(double average, TimeUnit unit) {
        if (Double.isNaN(average) || average < 0) {
            return null;
        }
        return (int) Math.round(average * unit.toNanos(1) / 1_000_000.0);
    }

    static double round(double v, int places) {
        double f = Math.pow(10, places);
        return Math.round(v * f) / f;
    }

    static JsonObject stat(Stat s) {
        if (s == null) {
            return null;
        }
        JsonObject o = new JsonObject();
        o.addProperty("value", round(s.value(), 2));
        o.addProperty("max", round(s.max(), 2));
        return o;
    }

    /** The fields every player answer starts with. */
    static JsonObject basic(UUID uuid, String name, String world, double[] position, Integer ping) {
        JsonObject o = new JsonObject();
        o.addProperty("name", name);
        o.addProperty("uuid", uuid.toString());
        o.addProperty("online", true);
        o.addProperty("world", world);
        if (position != null) {
            JsonObject p = new JsonObject();
            p.addProperty("x", round(position[0], 2));
            p.addProperty("y", round(position[1], 2));
            p.addProperty("z", round(position[2], 2));
            o.add("position", p);
        } else {
            o.add("position", null);
        }
        o.addProperty("pingMs", ping);
        return o;
    }

    static void addLive(JsonObject o, Live live, boolean allStats) {
        o.addProperty("gameMode", live.gameMode());
        o.add("health", stat(live.health()));
        if (allStats) {
            JsonObject stats = new JsonObject();
            stats.add("health", stat(live.health()));
            stats.add("stamina", stat(live.stamina()));
            stats.add("mana", stat(live.mana()));
            stats.add("oxygen", stat(live.oxygen()));
            o.add("stats", stats);
        }
    }

    // Engine side.

    static String worldName(PlayerRef p) {
        UUID w = p.getWorldUuid();
        Universe u = Universe.get();
        World world = w == null || u == null ? null : u.getWorld(w);
        return world == null ? null : world.getName();
    }

    static World worldOf(PlayerRef p) {
        UUID w = p.getWorldUuid();
        Universe u = Universe.get();
        return w == null || u == null ? null : u.getWorld(w);
    }

    static double[] position(PlayerRef p) {
        Transform t = p.getTransform();
        if (t == null || t.getPosition() == null) {
            return null;
        }
        Vector3d v = t.getPosition();
        return new double[]{v.x, v.y, v.z};
    }

    static Integer ping(PlayerRef p) {
        try {
            PacketHandler h = p.getPacketHandler();
            if (h == null) {
                return null;
            }
            PacketHandler.PingInfo info = h.getPingInfo(PongType.Direct);
            HistoricMetric m = info == null ? null : info.getPingMetricSet();
            return m == null ? null : pingMs(m.getAverage(PacketHandler.PingInfo.ONE_MINUTE_INDEX), PacketHandler.PingInfo.TIME_UNIT);
        } catch (RuntimeException e) {
            return null;
        }
    }

    static JsonObject basic(PlayerRef p) {
        return basic(p.getUuid(), p.getUsername(), worldName(p), position(p), ping(p));
    }

    /** World thread. The entity ref of a player in that world, or null. */
    static Ref<EntityStore> ref(PlayerRef p) {
        Ref<EntityStore> ref = p.getReference();
        return ref == null || !ref.isValid() ? null : ref;
    }

    /** World thread. */
    static Live live(PlayerRef p) {
        Ref<EntityStore> ref = ref(p);
        if (ref == null) {
            return Live.NONE;
        }
        Store<EntityStore> store = ref.getStore();
        String mode = null;
        Player pl = store.getComponent(ref, Player.getComponentType());
        if (pl != null) {
            GameMode g = pl.getGameMode();
            mode = g == null ? null : g.name();
        }
        EntityStatMap stats = store.getComponent(ref, EntityStatMap.getComponentType());
        if (stats == null) {
            return new Live(mode, null, null, null, null);
        }
        return new Live(mode, stat(stats, DefaultEntityStatTypes.getHealth()), stat(stats, DefaultEntityStatTypes.getStamina()),
                stat(stats, DefaultEntityStatTypes.getMana()), stat(stats, DefaultEntityStatTypes.getOxygen()));
    }

    private static Stat stat(EntityStatMap stats, int index) {
        EntityStatValue v = stats.get(index);
        return v == null ? null : new Stat(v.get(), v.getMax());
    }

    /** GET /api/players. One world call per world, a world that does not answer leaves its players' live fields null. */
    static JsonObject list(String worldFilter) {
        Universe u = Universe.get();
        List<PlayerRef> players = u == null ? List.of() : new ArrayList<>(u.getPlayers());
        players.sort(Comparator.comparing(p -> String.valueOf(p.getUsername()), String.CASE_INSENSITIVE_ORDER));
        Map<World, List<PlayerRef>> byWorld = new LinkedHashMap<>();
        List<PlayerRef> between = new ArrayList<>();
        if (worldFilter != null && !worldFilter.isBlank() && (u == null || u.getWorld(worldFilter) == null)) {
            throw ApiException.notFound("world_not_found", Messages.get(PlayersText.WORLD_NOT_FOUND, "world", worldFilter));
        }
        for (PlayerRef p : players) {
            World w = worldOf(p);
            if (worldFilter != null && !worldFilter.isBlank() && (w == null || !w.getName().equalsIgnoreCase(worldFilter))) {
                continue;
            }
            if (w == null) {
                between.add(p);
            } else {
                byWorld.computeIfAbsent(w, k -> new ArrayList<>()).add(p);
            }
        }
        Map<UUID, Live> lives = new LinkedHashMap<>();
        JsonArray late = new JsonArray();
        for (Map.Entry<World, List<PlayerRef>> e : byWorld.entrySet()) {
            try {
                Map<UUID, Live> got = WorldCall.on(e.getKey(), () -> {
                    Map<UUID, Live> m = new LinkedHashMap<>();
                    for (PlayerRef p : e.getValue()) {
                        m.put(p.getUuid(), live(p));
                    }
                    return m;
                });
                lives.putAll(got);
            } catch (ApiException ex) {
                late.add(e.getKey().getName());
            }
        }
        JsonArray list = new JsonArray();
        for (PlayerRef p : players) {
            boolean listed = between.contains(p) || byWorld.values().stream().anyMatch(l -> l.contains(p));
            if (!listed) {
                continue;
            }
            JsonObject o = basic(p);
            addLive(o, lives.getOrDefault(p.getUuid(), Live.NONE), false);
            list.add(o);
        }
        JsonObject out = new JsonObject();
        out.addProperty("count", list.size());
        out.add("players", list);
        out.add("worldsLate", late);
        return out;
    }

    /** GET /api/players/{player}. Offline players get what the server keeps about them. */
    static JsonObject detail(Lookup.Target t, Moderation moderation) {
        JsonObject o;
        PlayerRef p = t.online();
        if (p != null) {
            o = basic(p);
            Rotation3f head = p.getHeadRotation();
            if (head != null) {
                JsonObject h = new JsonObject();
                h.addProperty("yaw", round(Math.toDegrees(head.yaw()), 1));
                h.addProperty("pitch", round(Math.toDegrees(head.pitch()), 1));
                o.add("headRotation", h);
            }
            o.addProperty("language", p.getLanguage());
            World w = worldOf(p);
            Live live = w == null ? Live.NONE : WorldCall.on(w, () -> live(p));
            addLive(o, live, true);
        } else {
            o = new JsonObject();
            o.addProperty("name", t.name());
            o.addProperty("uuid", t.uuid().toString());
            o.addProperty("online", false);
        }
        moderation.describe(t.uuid(), o);
        return o;
    }

    /** GET /api/players/{player}/inventory. */
    static JsonObject inventory(Lookup.Target t) {
        PlayerRef p = t.online();
        World w = worldOf(p);
        if (w == null) {
            throw new ApiException(409, "not_in_world", Messages.get(PlayersText.NOT_IN_WORLD, "player", t.label()));
        }
        return WorldCall.on(w, () -> {
            Ref<EntityStore> ref = ref(p);
            if (ref == null) {
                throw new ApiException(409, "not_in_world", Messages.get(PlayersText.NOT_IN_WORLD, "player", t.label()));
            }
            Store<EntityStore> store = ref.getStore();
            JsonObject out = new JsonObject();
            out.addProperty("name", t.name());
            out.addProperty("uuid", t.uuid().toString());
            JsonObject sections = new JsonObject();
            for (Section s : Section.values()) {
                ItemContainer c = container(store, ref, s);
                if (c == null) {
                    continue;
                }
                JsonObject sec = new JsonObject();
                sec.addProperty("capacity", c.getCapacity());
                JsonArray items = new JsonArray();
                c.forEach((slot, stack) -> {
                    if (stack != null && !ItemStack.isEmpty(stack)) {
                        items.add(item(slot, stack));
                    }
                });
                sec.add("items", items);
                sections.add(s.id, sec);
            }
            out.add("sections", sections);
            return out;
        });
    }

    /** World thread. */
    static ItemContainer container(Store<EntityStore> store, Ref<EntityStore> ref, Section s) {
        var type = InventoryComponent.getComponentTypeById(s.engineId);
        if (type == null) {
            return null;
        }
        InventoryComponent inv = store.getComponent(ref, type);
        return inv == null ? null : inv.getInventory();
    }

    static JsonObject item(short slot, ItemStack stack) {
        JsonObject o = new JsonObject();
        o.addProperty("slot", slot);
        o.addProperty("item", stack.getItemId());
        o.addProperty("quantity", stack.getQuantity());
        if (stack.getMaxDurability() > 0) {
            o.addProperty("durability", round(stack.getDurability(), 2));
            o.addProperty("maxDurability", round(stack.getMaxDurability(), 2));
        }
        return o;
    }
}
