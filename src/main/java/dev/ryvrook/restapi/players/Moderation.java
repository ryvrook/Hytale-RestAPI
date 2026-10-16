package dev.ryvrook.restapi.players;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.server.core.modules.accesscontrol.AccessControlModule;
import com.hypixel.hytale.server.core.modules.accesscontrol.ban.Ban;
import com.hypixel.hytale.server.core.permissions.PermissionsModule;
import com.hypixel.hytale.server.core.permissions.provider.PermissionProvider;
import dev.ryvrook.restapi.http.ApiException;
import dev.ryvrook.restapi.text.Messages;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * Bans, the whitelist and permission groups. Every engine call here runs on the request thread, the engine does
 * its file IO under its own locks and none of it touches a world. Bans go through {@code AccessControlModule}
 * (bans.json), the whitelist is the join permission switched by RequireJoinPermission in config.json, groups go
 * through {@code PermissionsModule} (permissions.json).
 */
public final class Moderation {
    /** The UUID the engine's console uses, given as the ban author for bans made over the API. */
    static final UUID API_AUTHOR = new UUID(0, 0);
    /** Ban metadata key holding the API key that made the ban. */
    static final String META_KEY = "RestApiKey";
    /** The group /op adds. */
    static final String OP_GROUP = "hytale:Admin";

    private final NameBook book;

    public Moderation(NameBook book) {
        this.book = book;
    }

    /** What a change to a player's groups adds and removes. */
    record GroupChange(List<String> add, List<String> remove) {
    }

    /** The groups to add and remove to go from current to wanted, matched without regard to case. */
    static GroupChange diff(Collection<String> current, Collection<String> wanted) {
        List<String> add = new ArrayList<>();
        List<String> remove = new ArrayList<>();
        Set<String> cur = lowerSet(current);
        Set<String> want = lowerSet(wanted);
        for (String w : new LinkedHashSet<>(wanted)) {
            if (!cur.contains(w.toLowerCase(Locale.ROOT)) && add.stream().noneMatch(w::equalsIgnoreCase)) {
                add.add(w);
            }
        }
        for (String c : current) {
            if (!want.contains(c.toLowerCase(Locale.ROOT))) {
                remove.add(c);
            }
        }
        return new GroupChange(add, remove);
    }

    private static Set<String> lowerSet(Collection<String> in) {
        Set<String> out = new LinkedHashSet<>();
        for (String s : in) {
            out.add(s.toLowerCase(Locale.ROOT));
        }
        return out;
    }

    /** 403 group_protected when the group is listed in players.protectedGroups. */
    static void checkProtected(String group, List<String> protectedGroups) {
        for (String p : protectedGroups) {
            if (p.equalsIgnoreCase(group)) {
                throw new ApiException(403, "group_protected", Messages.get(PlayersText.GROUP_PROTECTED, "group", group));
            }
        }
    }

    /** The registered group with this name, matched without regard to case, or 404 group_not_found. */
    static String known(String group, Collection<String> registered) {
        for (String r : registered) {
            if (r.equalsIgnoreCase(group)) {
                return r;
            }
        }
        throw ApiException.notFound("group_not_found", Messages.get(PlayersText.GROUP_UNKNOWN, "group", group));
    }

    /**
     * When a ban of the given minutes ends, null for a permanent one. With players.maxBanMinutes above 0 a ban
     * must name minutes up to it.
     */
    static Instant until(Integer minutes, Instant now, int maxMinutes) {
        if (minutes == null) {
            if (maxMinutes > 0) {
                throw ApiException.badRequest("ban_too_long", Messages.get(PlayersText.BAN_PERMANENT, "max", maxMinutes));
            }
            return null;
        }
        if (maxMinutes > 0 && minutes > maxMinutes) {
            throw ApiException.badRequest("ban_too_long", Messages.get(PlayersText.BAN_PERMANENT, "max", maxMinutes));
        }
        return now.plusSeconds(60L * minutes);
    }

    /** The reason a player reads, with the key name when players.kickNamesKey is on. */
    static String reasonFor(String given, String fallbackId, String keyName, boolean namesKey) {
        String r = given == null || given.isBlank() ? Messages.get(fallbackId) : given;
        return namesKey ? Messages.get(PlayersText.KICK_BY_KEY, "reason", r, "key", keyName) : r;
    }

    /** A ban as the API answers it. */
    record BanView(UUID target, String name, UUID by, String key, String reason, Instant since, Instant until,
                   boolean inEffect) {
        JsonObject json() {
            JsonObject o = new JsonObject();
            o.addProperty("uuid", target.toString());
            o.addProperty("name", name);
            o.addProperty("by", by == null ? null : by.toString());
            o.addProperty("key", key);
            o.addProperty("reason", reason);
            o.addProperty("since", since == null ? null : since.toString());
            o.addProperty("until", until == null ? null : until.toString());
            o.addProperty("permanent", until == null);
            o.addProperty("inEffect", inEffect);
            return o;
        }
    }

    BanView view(Ban b) {
        String key = null;
        try {
            key = b.getMetadata(META_KEY, Codec.STRING);
        } catch (RuntimeException ignored) {
            // A ban made elsewhere has no key.
        }
        return new BanView(b.getTarget(), book == null ? null : book.name(b.getTarget()), b.getBy(), key, b.getReason(),
                b.getTimestamp(), b.getExpiresOn(), b.isInEffect());
    }

    // Bans

    List<BanView> bans() {
        List<BanView> out = new ArrayList<>();
        for (Ban b : AccessControlModule.get().getBanProvider().getBans()) {
            out.add(view(b));
        }
        out.sort(Comparator.comparing(BanView::since, Comparator.nullsLast(Comparator.reverseOrder())));
        return out;
    }

    /** Bans the player and kicks them when online. Answers the ban and whether it was new. */
    JsonObject ban(Lookup.Target t, String reason, Integer minutes, String keyName) {
        AccessControlModule acm = AccessControlModule.get();
        Instant now = Instant.now();
        Instant until = until(minutes, now, PlayersSettings.MAX_BAN_MINUTES.get());
        String shown = reasonFor(reason, PlayersText.BAN_REASON, keyName, PlayersSettings.KICK_NAMES_KEY.get());
        Ban ban = new Ban(t.uuid(), API_AUTHOR, now, until, shown).withMetadata(META_KEY, Codec.STRING, keyName);
        boolean made = acm.ban(ban);
        Ban stored = acm.getBanProvider().getBan(t.uuid());
        JsonObject o = (stored != null ? view(stored) : view(ban)).json();
        if (o.get("name").isJsonNull() && t.name() != null) {
            o.addProperty("name", t.name());
        }
        o.addProperty("created", made);
        return o;
    }

    /** Lifts the ban, or 404 not_banned. */
    JsonObject unban(Lookup.Target t) {
        if (!AccessControlModule.get().unban(t.uuid())) {
            throw ApiException.notFound("not_banned", Messages.get(PlayersText.NOT_BANNED, "player", t.label()));
        }
        JsonObject o = new JsonObject();
        o.addProperty("uuid", t.uuid().toString());
        o.addProperty("name", t.name());
        o.addProperty("banned", false);
        return o;
    }

    // Whitelist

    JsonObject whitelist() {
        AccessControlModule acm = AccessControlModule.get();
        JsonObject o = new JsonObject();
        o.addProperty("enabled", acm.isJoinPermissionRequired());
        JsonArray players = new JsonArray();
        new TreeSet<>(acm.getUsersWithJoinGrant()).forEach(u -> players.add(person(u)));
        o.add("players", players);
        return o;
    }

    /** Switches RequireJoinPermission and waits for config.json to be saved. */
    JsonObject whitelist(boolean enabled) {
        AccessControlModule acm = AccessControlModule.get();
        if (acm.isJoinPermissionRequired() != enabled) {
            try {
                acm.setJoinPermissionRequired(enabled).get(10, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new ApiException(503, "interrupted", Messages.get(PlayersText.ENGINE));
            } catch (Exception e) {
                throw new ApiException(500, "save_failed", Messages.get(PlayersText.ENGINE));
            }
        }
        return whitelist();
    }

    JsonObject allow(Lookup.Target t, boolean allow) {
        AccessControlModule acm = AccessControlModule.get();
        boolean changed = allow ? acm.allowJoin(t.uuid()) : acm.disallowJoin(t.uuid());
        JsonObject o = person(t.uuid());
        if (o.get("name").isJsonNull() && t.name() != null) {
            o.addProperty("name", t.name());
        }
        o.addProperty("joinGrant", allow);
        o.addProperty("changed", changed);
        return o;
    }

    // Groups

    JsonObject groups() {
        PermissionsModule pm = PermissionsModule.get();
        PermissionProvider first = pm.getFirstPermissionProvider();
        JsonArray list = new JsonArray();
        for (String g : new TreeSet<>(pm.getAllRegisteredGroups())) {
            JsonObject o = new JsonObject();
            o.addProperty("name", g);
            o.addProperty("protected", PlayersSettings.PROTECTED_GROUPS.get().stream().anyMatch(g::equalsIgnoreCase));
            if (first != null) {
                o.addProperty("parent", first.getGroupParent(g));
                JsonArray perms = new JsonArray();
                Set<String> p = first.getGroupPermissions(g);
                if (p != null) {
                    new TreeSet<>(p).forEach(perms::add);
                }
                o.add("permissions", perms);
            }
            list.add(o);
        }
        JsonObject out = new JsonObject();
        out.add("groups", list);
        return out;
    }

    JsonObject groupsOf(Lookup.Target t) {
        JsonObject o = new JsonObject();
        o.addProperty("uuid", t.uuid().toString());
        o.addProperty("name", t.name());
        JsonArray a = new JsonArray();
        new TreeSet<>(PermissionsModule.get().getGroupsForUser(t.uuid())).forEach(a::add);
        o.add("groups", a);
        return o;
    }

    /** PUT, the player ends up in exactly these groups. Protected groups may not be added or removed. */
    JsonObject setGroups(Lookup.Target t, List<String> wanted) {
        PermissionsModule pm = PermissionsModule.get();
        Set<String> registered = pm.getAllRegisteredGroups();
        List<String> resolved = new ArrayList<>();
        for (String w : wanted) {
            resolved.add(known(w, registered));
        }
        GroupChange c = diff(pm.getGroupsForUser(t.uuid()), resolved);
        List<String> prot = PlayersSettings.PROTECTED_GROUPS.get();
        c.add().forEach(g -> checkProtected(g, prot));
        c.remove().forEach(g -> checkProtected(g, prot));
        c.remove().forEach(g -> pm.removeUserFromGroup(t.uuid(), g));
        c.add().forEach(g -> pm.addUserToGroup(t.uuid(), g));
        JsonObject o = groupsOf(t);
        o.add("added", strings(c.add()));
        o.add("removed", strings(c.remove()));
        return o;
    }

    JsonObject changeGroup(Lookup.Target t, String group, boolean add) {
        PermissionsModule pm = PermissionsModule.get();
        String g = known(group, pm.getAllRegisteredGroups());
        checkProtected(g, PlayersSettings.PROTECTED_GROUPS.get());
        boolean member = pm.getGroupsForUser(t.uuid()).stream().anyMatch(g::equalsIgnoreCase);
        if (add && !member) {
            pm.addUserToGroup(t.uuid(), g);
        } else if (!add && member) {
            pm.removeUserFromGroup(t.uuid(), g);
        }
        JsonObject o = groupsOf(t);
        o.addProperty("changed", add != member);
        return o;
    }

    // Shared

    /** Groups, op, ban and join grant for the player detail. */
    void describe(UUID uuid, JsonObject o) {
        try {
            Set<String> groups = PermissionsModule.get().getGroupsForUser(uuid);
            JsonArray a = new JsonArray();
            new TreeSet<>(groups).forEach(a::add);
            o.add("groups", a);
            o.addProperty("op", groups.stream().anyMatch(OP_GROUP::equalsIgnoreCase));
        } catch (RuntimeException e) {
            o.add("groups", null);
        }
        AccessControlModule acm = AccessControlModule.get();
        Ban b = acm.getBanProvider().getBan(uuid);
        o.addProperty("banned", b != null && b.isInEffect());
        o.add("ban", b == null ? null : view(b).json());
        o.addProperty("joinGrant", acm.getUsersWithJoinGrant().contains(uuid));
    }

    JsonObject person(UUID u) {
        JsonObject o = new JsonObject();
        o.addProperty("uuid", u.toString());
        o.addProperty("name", book == null ? null : book.name(u));
        return o;
    }

    static JsonArray strings(Collection<String> in) {
        JsonArray a = new JsonArray();
        in.forEach(a::add);
        return a;
    }
}
