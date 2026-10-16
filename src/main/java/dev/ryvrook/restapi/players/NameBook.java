package dev.ryvrook.restapi.players;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The last name seen for each UUID, kept in names.json in the data folder. The engine stores players by UUID and
 * offline mode has no profile lookup, so this is how a ban or a whitelist entry for someone offline can name them.
 * Filled from the players online at each world sample and at each disconnect. Written by the scheduler when it
 * changed and at stop.
 */
public final class NameBook {
    public static final String FILE = "names.json";
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();

    /** One player as last seen. */
    public record Entry(UUID uuid, String name, long lastSeen) {
    }

    private final Path file;
    private final Map<UUID, Entry> byUuid = new ConcurrentHashMap<>();
    private volatile boolean dirty;

    public NameBook(Path file) {
        this.file = file;
    }

    /** Reads the file. A file that does not parse starts an empty book and says why. */
    public List<String> load() {
        List<String> problems = new ArrayList<>();
        byUuid.clear();
        if (!Files.exists(file)) {
            return problems;
        }
        try {
            JsonElement e = JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8));
            if (!e.isJsonObject()) {
                problems.add(FILE + " must hold one JSON object, the names start empty");
                return problems;
            }
            for (Map.Entry<String, JsonElement> x : e.getAsJsonObject().entrySet()) {
                try {
                    UUID id = UUID.fromString(x.getKey());
                    JsonObject o = x.getValue().getAsJsonObject();
                    byUuid.put(id, new Entry(id, o.get("name").getAsString(), o.has("lastSeen") ? o.get("lastSeen").getAsLong() : 0));
                } catch (RuntimeException bad) {
                    problems.add(FILE + " entry " + x.getKey() + " was skipped");
                }
            }
        } catch (IOException | RuntimeException ex) {
            problems.add(FILE + " could not be read, " + ex.getMessage());
        }
        return problems;
    }

    /** Notes a name. Cheap when nothing changed, so it can run for every online player on every sample. */
    public void remember(UUID uuid, String name, long now) {
        if (uuid == null || name == null || name.isBlank()) {
            return;
        }
        Entry old = byUuid.get(uuid);
        if (old != null && old.name().equals(name) && now - old.lastSeen() < 3_600_000L) {
            return;
        }
        byUuid.put(uuid, new Entry(uuid, name, now));
        dirty = true;
    }

    public String name(UUID uuid) {
        Entry e = byUuid.get(uuid);
        return e == null ? null : e.name();
    }

    /** The UUID last seen with this name, ignoring case. When two players held it, the one seen last wins. */
    public UUID find(String name) {
        if (name == null) {
            return null;
        }
        String n = name.toLowerCase(Locale.ROOT);
        Entry best = null;
        for (Entry e : byUuid.values()) {
            if (e.name().toLowerCase(Locale.ROOT).equals(n) && (best == null || e.lastSeen() > best.lastSeen())) {
                best = e;
            }
        }
        return best == null ? null : best.uuid();
    }

    public int size() {
        return byUuid.size();
    }

    /** Writes the file when something changed. Returns the problem, or null. */
    public String saveIfDirty() {
        if (!dirty) {
            return null;
        }
        dirty = false;
        JsonObject out = new JsonObject();
        byUuid.values().stream().sorted((a, b) -> a.uuid().compareTo(b.uuid())).forEach(e -> {
            JsonObject o = new JsonObject();
            o.addProperty("name", e.name());
            o.addProperty("lastSeen", e.lastSeen());
            out.add(e.uuid().toString(), o);
        });
        try {
            Files.createDirectories(file.toAbsolutePath().getParent());
            Path tmp = file.resolveSibling(FILE + ".tmp");
            Files.writeString(tmp, GSON.toJson(out) + "\n", StandardCharsets.UTF_8);
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            return null;
        } catch (IOException e) {
            dirty = true;
            return FILE + " could not be written, " + e.getMessage();
        }
    }
}
