package dev.ryvrook.restapi.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The registry of every key in RestAPI.json. Each area declares its keys as static constants in a class of its
 * own, such as {@link CoreSettings}, so the builders of later modules add settings without touching a shared
 * file. Keys are dotted paths and the file nests them, {@code http.port} is {@code {"http": {"port": 8125}}}.
 *
 * <p>Static on purpose, like Messages. The file is one per server and settings are read from the HTTP threads,
 * the command threads and the world threads. Reads are lock free, the value map is swapped whole on each load.
 */
public final class Settings {
    private Settings() {
    }

    /** The file name in the data folder. */
    public static final String FILE = "RestAPI.json";

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
    private static final Map<String, Setting<?>> DEFINED = Collections.synchronizedMap(new LinkedHashMap<>());
    private static volatile Map<String, Object> values = Map.of();

    /** What a load found. When problems is not empty nothing changed and the previous values stay in use. */
    public record LoadReport(boolean fileRead, int added, List<String> problems) {
        public boolean ok() {
            return problems.isEmpty();
        }
    }

    public static Setting<Boolean> bool(String key, boolean fallback, String description) {
        return define(new Setting<>(key, Setting.Kind.BOOL, fallback, 0, 0, false, description));
    }

    public static Setting<Integer> integer(String key, int fallback, int min, int max, String description) {
        return define(new Setting<>(key, Setting.Kind.INT, fallback, min, max, false, description));
    }

    public static Setting<String> text(String key, String fallback, String description) {
        return define(new Setting<>(key, Setting.Kind.TEXT, fallback, 0, 0, false, description));
    }

    public static Setting<List<String>> list(String key, List<String> fallback, String description) {
        return define(new Setting<>(key, Setting.Kind.LIST, List.copyOf(fallback), 0, 0, false, description));
    }

    /** The same setting, marked as read at server start only. For bind addresses and thread counts. */
    public static <T> Setting<T> restart(Setting<T> s) {
        Setting<T> marked = new Setting<>(s.key(), s.kind(), s.fallback(), s.min(), s.max(), true, s.description());
        DEFINED.put(s.key(), marked);
        return marked;
    }

    private static <T> Setting<T> define(Setting<T> s) {
        if (s.key() == null || s.key().isBlank() || s.key().startsWith(".") || s.key().endsWith(".")) {
            throw new IllegalArgumentException("A setting needs a dotted key, got " + s.key());
        }
        if (s.description() == null || s.description().isBlank()) {
            throw new IllegalArgumentException("Setting " + s.key() + " needs a description");
        }
        Setting<?> old = DEFINED.putIfAbsent(s.key(), s);
        if (old != null) {
            throw new IllegalStateException("Setting " + s.key() + " is defined twice");
        }
        synchronized (DEFINED) {
            for (String other : DEFINED.keySet()) {
                if (!other.equals(s.key()) && (other.startsWith(s.key() + ".") || s.key().startsWith(other + "."))) {
                    DEFINED.remove(s.key());
                    throw new IllegalStateException("Setting " + s.key() + " clashes with " + other);
                }
            }
        }
        return s;
    }

    @SuppressWarnings("unchecked")
    static <T> T value(Setting<T> s) {
        Object v = values.get(s.key());
        return v == null ? s.fallback() : (T) v;
    }

    /** Every setting in the order its class declared it. */
    public static List<Setting<?>> all() {
        synchronized (DEFINED) {
            return new ArrayList<>(DEFINED.values());
        }
    }

    /**
     * Reads the file and swaps in its values. Keys missing from the file get their defaults and are written back,
     * keys the file holds that no setting knows are kept as they are. A file that does not parse or holds a value
     * of the wrong type changes nothing and lists every problem, so a typo never opens a port by accident.
     */
    public static LoadReport load(Path file) {
        List<String> problems = new ArrayList<>();
        JsonObject doc = new JsonObject();
        boolean read = false;
        if (Files.exists(file)) {
            try {
                JsonElement parsed = JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8));
                if (!parsed.isJsonObject()) {
                    problems.add(FILE + " must hold one JSON object");
                    return new LoadReport(false, 0, problems);
                }
                doc = parsed.getAsJsonObject();
                read = true;
            } catch (IOException | RuntimeException e) {
                problems.add(FILE + " could not be read, " + e.getMessage());
                return new LoadReport(false, 0, problems);
            }
        }
        Map<String, Object> next = new HashMap<>();
        int added = 0;
        for (Setting<?> s : all()) {
            JsonElement raw = find(doc, s.key());
            if (raw == null || raw.isJsonNull()) {
                next.put(s.key(), s.fallback());
                put(doc, s.key(), toJson(s.fallback()));
                added++;
                continue;
            }
            try {
                next.put(s.key(), s.coerce(plain(raw)));
            } catch (IllegalArgumentException e) {
                problems.add(e.getMessage());
            }
        }
        if (!problems.isEmpty()) {
            return new LoadReport(read, 0, problems);
        }
        values = Map.copyOf(next);
        if (added > 0) {
            try {
                write(file, doc);
            } catch (IOException e) {
                problems.add(FILE + " could not be written, " + e.getMessage());
            }
        }
        return new LoadReport(read, added, problems);
    }

    /** Puts every setting back to its default. For tests, and for a server without a data folder. */
    public static void useDefaults() {
        Map<String, Object> next = new HashMap<>();
        for (Setting<?> s : all()) {
            next.put(s.key(), s.fallback());
        }
        values = Map.copyOf(next);
    }

    /** Sets one value in memory without touching the file. For tests only. */
    public static <T> void override(Setting<T> s, T value) {
        Map<String, Object> next = new HashMap<>(values);
        next.put(s.key(), value);
        values = Map.copyOf(next);
    }

    /** The whole file as the defaults would write it, for docs/SETTINGS.md and the first start. */
    public static JsonObject defaultsJson() {
        JsonObject doc = new JsonObject();
        for (Setting<?> s : all()) {
            put(doc, s.key(), toJson(s.fallback()));
        }
        return doc;
    }

    static JsonElement find(JsonObject doc, String key) {
        String[] parts = key.split("\\.");
        JsonElement at = doc;
        for (String p : parts) {
            if (at == null || !at.isJsonObject()) {
                return null;
            }
            at = at.getAsJsonObject().get(p);
        }
        return at;
    }

    static void put(JsonObject doc, String key, JsonElement value) {
        String[] parts = key.split("\\.");
        JsonObject at = doc;
        for (int i = 0; i < parts.length - 1; i++) {
            JsonElement child = at.get(parts[i]);
            if (child == null || !child.isJsonObject()) {
                child = new JsonObject();
                at.add(parts[i], child);
            }
            at = child.getAsJsonObject();
        }
        at.add(parts[parts.length - 1], value);
    }

    /** A JSON value as Boolean, Number, String or List, the shapes {@link Setting#coerce} expects. */
    static Object plain(JsonElement e) {
        if (e.isJsonArray()) {
            List<Object> out = new ArrayList<>();
            for (JsonElement x : e.getAsJsonArray()) {
                out.add(x.isJsonNull() ? null : plain(x));
            }
            return out;
        }
        if (e.isJsonPrimitive()) {
            JsonPrimitive p = e.getAsJsonPrimitive();
            if (p.isBoolean()) {
                return p.getAsBoolean();
            }
            if (p.isNumber()) {
                return p.getAsBigDecimal().doubleValue();
            }
            return p.getAsString();
        }
        return e;
    }

    static JsonElement toJson(Object v) {
        if (v instanceof List<?> list) {
            JsonArray a = new JsonArray();
            for (Object o : list) {
                a.add(String.valueOf(o));
            }
            return a;
        }
        if (v instanceof Boolean b) {
            return new JsonPrimitive(b);
        }
        if (v instanceof Number n) {
            return new JsonPrimitive(n);
        }
        return new JsonPrimitive(String.valueOf(v));
    }

    private static void write(Path file, JsonObject doc) throws IOException {
        Files.createDirectories(file.toAbsolutePath().getParent());
        Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
        Files.writeString(tmp, GSON.toJson(doc) + "\n", StandardCharsets.UTF_8);
        Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }
}
