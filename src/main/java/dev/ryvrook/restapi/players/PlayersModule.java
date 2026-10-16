package dev.ryvrook.restapi.players;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.hypixel.hytale.server.core.event.events.player.PlayerDisconnectEvent;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.Universe;
import dev.ryvrook.restapi.RestApi;
import dev.ryvrook.restapi.RestApiPlugin;
import dev.ryvrook.restapi.RestModule;
import dev.ryvrook.restapi.auth.Scope;
import dev.ryvrook.restapi.http.Access;
import dev.ryvrook.restapi.http.Call;
import dev.ryvrook.restapi.http.Response;
import dev.ryvrook.restapi.http.Route;
import dev.ryvrook.restapi.http.RouteDoc;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;

/** Players, moderation and broadcasts, section 4.4 of the plan. Group {@code players}. */
public final class PlayersModule implements RestModule {
    public static final String GROUP = "players";
    private static final List<String> COMMON = List.of("player_not_found");

    /** The module that is running, for the disconnect listener registered once in setup. */
    private static volatile PlayersModule current;

    private volatile NameBook book;
    private volatile Lookup lookup;
    private volatile Moderation moderation;
    private ScheduledFuture<?> task;
    private RestApi api;

    public PlayersModule() {
        PlayersSettings.ENABLED.key();
        PlayersText.KICK_REASON.length();
    }

    @Override
    public String group() {
        return GROUP;
    }

    @Override
    public void setup(RestApiPlugin plugin) {
        plugin.getEventRegistry().registerGlobal(PlayerDisconnectEvent.class, e -> {
            PlayersModule m = current;
            PlayerRef p = e.getPlayerRef();
            if (m != null && m.book != null && p != null && PlayersSettings.REMEMBER_NAMES.get()) {
                m.book.remember(p.getUuid(), p.getUsername(), System.currentTimeMillis());
            }
        });
    }

    @Override
    public List<Route> routes(RestApi api) {
        this.api = api;
        NameBook b = new NameBook(api.dataDirectory().resolve(NameBook.FILE));
        for (String p : b.load()) {
            api.logger().at(Level.WARNING).log("RestAPI %s", p);
        }
        book = b;
        lookup = new Lookup(b);
        moderation = new Moderation(b);
        if (!PlayersSettings.ENABLED.get()) {
            return List.of();
        }
        List<Route> out = new ArrayList<>(reads());
        if (!PlayersSettings.READ_ONLY.get()) {
            out.addAll(writes());
        }
        return out;
    }

    @Override
    public void start(RestApi api) {
        current = this;
        task = api.scheduler().scheduleWithFixedDelay(this::sweep, 2, 30, TimeUnit.SECONDS);
    }

    @Override
    public void stop() {
        if (task != null) {
            task.cancel(false);
            task = null;
        }
        if (current == this) {
            current = null;
        }
        sweep();
    }

    /** Scheduler thread. Notes the names online and writes names.json when it changed. */
    void sweep() {
        NameBook b = book;
        if (b == null) {
            return;
        }
        try {
            Universe u = Universe.get();
            if (u != null && PlayersSettings.REMEMBER_NAMES.get()) {
                long now = System.currentTimeMillis();
                for (PlayerRef p : u.getPlayers()) {
                    b.remember(p.getUuid(), p.getUsername(), now);
                }
            }
        } catch (RuntimeException | LinkageError ignored) {
            // The universe is starting or stopping, the next sweep catches up.
        }
        String problem = b.saveIfDirty();
        if (problem != null && api != null) {
            api.logger().at(Level.WARNING).log("RestAPI %s", problem);
        }
    }

    private static RouteDoc docq(List<RouteDoc.Param> query, String request, String response, String... errors) {
        List<String> e = new ArrayList<>(COMMON);
        e.addAll(List.of(errors));
        return new RouteDoc(query, request, response, e);
    }

    private static RouteDoc doc(String request, String response, String... errors) {
        return docq(List.of(), request, response, errors);
    }

    private static Route read(String pattern, Scope scope, String summary, Route.Handler h, RouteDoc d) {
        return Route.of(GROUP, "GET", pattern, scope, Access.READ, summary, h).doc(d);
    }

    private static Route change(String method, String pattern, Access access, String summary, Route.Handler h, RouteDoc d) {
        return Route.of(GROUP, method, pattern, Scope.PLAYERS, access, summary, h).doc(d);
    }

    private static final String WHO = "{\"name\":\"Steve\",\"uuid\":\"8d2f7c1e-4a3b-4c5d-9e8f-0a1b2c3d4e5f\"";

    List<Route> reads() {
        List<RouteDoc.Param> page = List.of(new RouteDoc.Param("limit", "Entries per page, players.pageSize by default"),
                new RouteDoc.Param("offset", "Entries to skip"));
        return List.of(
                read("/api/players", Scope.READ,
                        "Online players with world, position, ping, game mode and health. A world that does not answer in time is listed in worldsLate.",
                        call -> Response.ok(PlayerRead.list(call.query("world", null))),
                        docq(List.of(new RouteDoc.Param("world", "Only players in this world")), null,
                                "{\"count\":1,\"players\":[" + WHO + ",\"online\":true,\"world\":\"default\",\"position\":{\"x\":12.5,\"y\":64,\"z\":-3.2},\"pingMs\":24,\"gameMode\":\"Adventure\",\"health\":{\"value\":100,\"max\":100}}],\"worldsLate\":[]}",
                                "world_not_found")),
                read("/api/players/offline", Scope.READ,
                        "Every player the server stores, in UUID order with the last name seen, paged with limit and offset.",
                        this::offline,
                        docq(page, null, "{\"total\":2,\"limit\":100,\"offset\":0,\"players\":[" + WHO + ",\"online\":false}]}")),
                read("/api/players/{player}", Scope.READ,
                        "One player by name or UUID. Online players add head rotation, language and stats, everyone gets groups, op, ban and join grant.",
                        call -> Response.ok(PlayerRead.detail(lookup.find(call.param("player")), moderation)),
                        doc(null, WHO + ",\"online\":true,\"world\":\"default\",\"gameMode\":\"Adventure\",\"groups\":[\"Default\"],\"op\":false,\"banned\":false,\"joinGrant\":true}",
                                "world_timeout")),
                read("/api/players/{player}/inventory", Scope.PLAYERS,
                        "The inventory of an online player by section, hotbar, storage, armor, utility, tool and backpack, with item id, quantity and durability.",
                        call -> Response.ok(PlayerRead.inventory(lookup.online(call.param("player")))),
                        doc(null, WHO + ",\"sections\":{\"hotbar\":{\"capacity\":9,\"items\":[{\"slot\":0,\"item\":\"Tool_Pickaxe_Iron\",\"quantity\":1,\"durability\":180,\"maxDurability\":200}]}}}",
                                "player_offline", "not_in_world", "world_timeout")),
                read("/api/players/{player}/groups", Scope.READ, "The permission groups of a player.",
                        call -> Response.ok(moderation.groupsOf(lookup.find(call.param("player")))),
                        doc(null, WHO + ",\"groups\":[\"Default\"]}")),
                read("/api/bans", Scope.READ, "Every ban with target, author, API key, reason, start and end, newest first.",
                        this::bans,
                        docq(page, null, "{\"total\":1,\"limit\":100,\"offset\":0,\"bans\":[{\"uuid\":\"8d2f7c1e-4a3b-4c5d-9e8f-0a1b2c3d4e5f\",\"name\":\"Steve\",\"key\":\"admin\",\"reason\":\"Griefing\",\"since\":\"2026-10-16T12:00:00Z\",\"until\":null,\"permanent\":true,\"inEffect\":true}]}")),
                read("/api/whitelist", Scope.READ, "Whether joining needs the join permission, and the players who hold it.",
                        call -> Response.ok(moderation.whitelist()),
                        doc(null, "{\"enabled\":false,\"players\":[{\"uuid\":\"8d2f7c1e-4a3b-4c5d-9e8f-0a1b2c3d4e5f\",\"name\":\"Steve\"}]}")),
                read("/api/permissions/groups", Scope.READ, "The permission groups with parent and permissions, and which ones the API may not change.",
                        call -> Response.ok(moderation.groups()),
                        doc(null, "{\"groups\":[{\"name\":\"hytale:Admin\",\"protected\":true,\"parent\":null,\"permissions\":[\"*\"]}]}")));
    }

    List<Route> writes() {
        List<Route> out = new ArrayList<>(List.of(
                change("POST", "/api/players/{player}/kick", Access.WRITE, "Disconnects an online player with a reason.",
                        call -> Response.ok(PlayerActions.kick(lookup.find(call.param("player")), call.jsonBody(), call.keyName())),
                        doc("{\"reason\":\"Back in five minutes\"}", WHO + ",\"connectionsClosed\":1,\"reason\":\"Back in five minutes\"}")),
                change("POST", "/api/players/{player}/message", Access.WRITE, "Sends a chat line to one player, with an optional hex color.",
                        call -> Response.ok(PlayerActions.message(lookup.online(call.param("player")), call.jsonBody())),
                        doc("{\"text\":\"Your build is ready\",\"color\":\"#55ff55\"}", WHO + ",\"sent\":true}", "player_offline", "missing_field", "invalid_field")),
                change("POST", "/api/players/{player}/title", Access.WRITE, "Shows a title and subtitle to one player. Times are in seconds.",
                        call -> Response.ok(PlayerActions.title(lookup.online(call.param("player")), call.jsonBody())),
                        doc("{\"title\":\"Welcome\",\"subtitle\":\"Read /rules\",\"fadeIn\":1.5,\"stay\":4,\"fadeOut\":1.5}", WHO + ",\"sent\":true}", "player_offline", "missing_field")),
                change("POST", "/api/players/{player}/teleport", Access.WRITE,
                        "Moves an online player to coordinates, optionally in another world with yaw and pitch in degrees, or to another player.",
                        call -> Response.ok(PlayerActions.teleport(lookup.online(call.param("player")),
                                PlayerActions.destination(call.jsonBody()), lookup)),
                        doc("{\"x\":0,\"y\":80,\"z\":0,\"world\":\"default\"}", WHO + ",\"world\":\"default\",\"position\":{\"x\":0,\"y\":80,\"z\":0}}",
                                "player_offline", "world_not_found", "other_world", "not_in_world", "world_timeout")),
                change("POST", "/api/players/{player}/give", Access.WRITE,
                        "Gives an item, up to players.maxGiveAmount. What did not fit comes back as remainder.",
                        call -> Response.ok(PlayerActions.give(lookup.online(call.param("player")), call.jsonBody())),
                        doc("{\"item\":\"Ingredient_Bar_Iron\",\"amount\":16}", WHO + ",\"item\":\"Ingredient_Bar_Iron\",\"given\":16,\"remainder\":0}",
                                "player_offline", "item_not_found", "invalid_field", "world_timeout")),
                change("PUT", "/api/players/{player}/gamemode", Access.WRITE, "Sets the game mode to Adventure or Creative.",
                        call -> Response.ok(PlayerActions.gameMode(lookup.online(call.param("player")), call.jsonBody())),
                        doc("{\"mode\":\"Creative\"}", WHO + ",\"gameMode\":\"Creative\"}", "player_offline", "invalid_field", "world_timeout")),
                change("POST", "/api/players/{player}/heal", Access.WRITE, "Fills the health of an online player.",
                        call -> Response.ok(PlayerActions.heal(lookup.online(call.param("player")))),
                        doc(null, WHO + ",\"health\":{\"value\":100,\"max\":100}}", "player_offline", "world_timeout")),
                change("POST", "/api/bans", Access.DANGEROUS,
                        "Bans a player by name or UUID, for minutes or for good, and kicks them when online. Banning someone already banned answers created false.",
                        this::ban,
                        doc("{\"player\":\"Steve\",\"reason\":\"Griefing\",\"minutes\":1440}",
                                "{\"uuid\":\"8d2f7c1e-4a3b-4c5d-9e8f-0a1b2c3d4e5f\",\"name\":\"Steve\",\"key\":\"admin\",\"reason\":\"Griefing\",\"permanent\":false,\"inEffect\":true,\"created\":true}",
                                "missing_field", "ban_too_long")),
                change("DELETE", "/api/bans/{player}", Access.WRITE, "Lifts a ban.",
                        call -> Response.ok(moderation.unban(lookup.find(call.param("player")))),
                        doc(null, WHO + ",\"banned\":false}", "not_banned")),
                change("PUT", "/api/whitelist", Access.WRITE,
                        "Turns the join permission requirement on or off and saves config.json.",
                        call -> Response.ok(moderation.whitelist(Fields.bool(call.jsonBody(), "enabled", null))),
                        doc("{\"enabled\":true}", "{\"enabled\":true,\"players\":[]}", "missing_field", "save_failed")),
                change("POST", "/api/whitelist", Access.WRITE, "Gives a player the join permission.",
                        call -> Response.ok(moderation.allow(lookup.find(Fields.text(call.jsonBody(), "player", true, 64)), true)),
                        doc("{\"player\":\"Steve\"}", WHO + ",\"joinGrant\":true,\"changed\":true}", "missing_field")),
                change("DELETE", "/api/whitelist/{player}", Access.WRITE, "Takes the join permission from a player.",
                        call -> Response.ok(moderation.allow(lookup.find(call.param("player")), false)),
                        doc(null, WHO + ",\"joinGrant\":false,\"changed\":true}")),
                change("PUT", "/api/players/{player}/groups", Access.DANGEROUS,
                        "Puts a player in exactly these permission groups. Groups in players.protectedGroups are refused.",
                        call -> Response.ok(moderation.setGroups(lookup.find(call.param("player")), Fields.strings(call.jsonBody(), "groups", true))),
                        doc("{\"groups\":[\"Default\",\"hytale:Builder\"]}", WHO + ",\"groups\":[\"Default\",\"hytale:Builder\"],\"added\":[\"hytale:Builder\"],\"removed\":[]}",
                                "group_not_found", "group_protected", "missing_field")),
                change("POST", "/api/players/{player}/groups/{group}", Access.DANGEROUS, "Adds a player to one permission group.",
                        call -> Response.ok(moderation.changeGroup(lookup.find(call.param("player")), call.param("group"), true)),
                        doc(null, WHO + ",\"groups\":[\"Default\",\"hytale:Builder\"],\"changed\":true}", "group_not_found", "group_protected")),
                change("DELETE", "/api/players/{player}/groups/{group}", Access.DANGEROUS, "Takes a player out of one permission group.",
                        call -> Response.ok(moderation.changeGroup(lookup.find(call.param("player")), call.param("group"), false)),
                        doc(null, WHO + ",\"groups\":[\"Default\"],\"changed\":true}", "group_not_found", "group_protected")),
                change("POST", "/api/broadcast", Access.WRITE,
                        "Sends a chat line to every online player, or to one world. The players.broadcast line in messages.json wraps it unless prefix is false.",
                        call -> Response.ok(PlayerActions.broadcast(call.jsonBody())),
                        doc("{\"text\":\"Restart in 10 minutes\",\"color\":\"#ffaa00\"}", "{\"recipients\":12,\"world\":null}", "missing_field", "world_not_found")),
                change("POST", "/api/broadcast/title", Access.WRITE, "Shows a title to every online player, or to one world.",
                        call -> Response.ok(PlayerActions.broadcastTitle(call.jsonBody())),
                        doc("{\"title\":\"Event starts\",\"subtitle\":\"Meet at spawn\"}", "{\"recipients\":12,\"world\":null}", "missing_field", "world_not_found"))));
        if (PlayersSettings.ALLOW_CLEAR.get()) {
            out.add(change("POST", "/api/players/{player}/clear", Access.DANGEROUS,
                    "Empties inventory sections of an online player, every section when none are named.",
                    call -> Response.ok(PlayerActions.clear(lookup.online(call.param("player")), call.jsonBody())),
                    doc("{\"sections\":[\"hotbar\",\"storage\"]}", WHO + ",\"removed\":{\"hotbar\":12,\"storage\":40}}", "player_offline", "invalid_field", "world_timeout")));
        }
        if (PlayersSettings.ALLOW_KILL.get()) {
            out.add(change("POST", "/api/players/{player}/kill", Access.DANGEROUS, "Kills an online player the way /kill does.",
                    call -> Response.ok(PlayerActions.kill(lookup.online(call.param("player")))),
                    doc(null, WHO + ",\"killed\":true}", "player_offline", "world_timeout")));
        }
        if (PlayersSettings.ALLOW_REFER.get()) {
            out.add(change("POST", "/api/players/{player}/refer", Access.WRITE, "Sends an online player to another server by host and port.",
                    call -> Response.ok(PlayerActions.refer(lookup.online(call.param("player")), call.jsonBody())),
                    doc("{\"host\":\"lobby.example.com\",\"port\":5520}", WHO + ",\"host\":\"lobby.example.com\",\"port\":5520}", "player_offline", "missing_field")));
        }
        return out;
    }

    /** limit and offset from the query, within players.maxPageSize. */
    static int[] page(Call call) {
        int limit = number(call.query("limit", null), PlayersSettings.PAGE_SIZE.get(), 1, PlayersSettings.MAX_PAGE_SIZE.get(), "limit");
        int offset = number(call.query("offset", null), 0, 0, Integer.MAX_VALUE, "offset");
        return new int[]{limit, offset};
    }

    static int number(String raw, int fallback, int min, int max, String name) {
        if (raw == null || raw.isBlank()) {
            return fallback;
        }
        try {
            long v = Long.parseLong(raw.trim());
            if (v < min || v > max) {
                throw Fields.range(name, min, max);
            }
            return (int) v;
        } catch (NumberFormatException e) {
            throw Fields.type(name, "a whole number");
        }
    }

    private Response offline(Call call) throws IOException {
        int[] p = page(call);
        Universe u = Universe.get();
        Set<UUID> stored = new TreeSet<>(u.getPlayerStorage().getPlayers());
        Set<UUID> online = new java.util.HashSet<>();
        for (PlayerRef r : u.getPlayers()) {
            online.add(r.getUuid());
        }
        List<UUID> all = new ArrayList<>(stored);
        JsonArray list = new JsonArray();
        for (int i = p[1]; i < all.size() && list.size() < p[0]; i++) {
            UUID id = all.get(i);
            JsonObject o = new JsonObject();
            o.addProperty("uuid", id.toString());
            o.addProperty("name", book.name(id));
            o.addProperty("online", online.contains(id));
            list.add(o);
        }
        JsonObject out = new JsonObject();
        out.addProperty("total", all.size());
        out.addProperty("limit", p[0]);
        out.addProperty("offset", p[1]);
        out.add("players", list);
        return Response.ok(out);
    }

    private Response bans(Call call) {
        int[] p = page(call);
        List<Moderation.BanView> all = moderation.bans();
        JsonArray list = new JsonArray();
        for (int i = p[1]; i < all.size() && list.size() < p[0]; i++) {
            list.add(all.get(i).json());
        }
        JsonObject out = new JsonObject();
        out.addProperty("total", all.size());
        out.addProperty("limit", p[0]);
        out.addProperty("offset", p[1]);
        out.add("bans", list);
        return Response.ok(out);
    }

    private Response ban(Call call) {
        JsonObject body = call.jsonBody();
        String who = Fields.text(body, "player", true, 64);
        String reason = Fields.text(body, "reason", false, PlayersSettings.MAX_TEXT_LENGTH.get());
        Integer minutes = Fields.has(body, "minutes") ? Fields.integer(body, "minutes", null, 1, 52_560_000) : null;
        Lookup.Target t = lookup.find(who);
        JsonObject o = moderation.ban(t, reason, minutes, call.keyName());
        return Response.json(o.get("created").getAsBoolean() ? 201 : 200, o);
    }
}
