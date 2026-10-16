package dev.ryvrook.restapi.text;

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
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Every sentence a person reads in game or in the console, keyed by a stable id and editable in messages.json next
 * to RestAPI.json. The same design as Coinage and Dominion. A text class declares
 * {@code static final String KEY_MADE = Messages.define("keys.made", "Made the key {name}.")} and code reads
 * {@code Messages.get(CommandText.KEY_MADE, "name", name)}. Missing ids are added to the file with their
 * defaults, reworded ones are kept, unknown ones are kept for a later version.
 *
 * <p>API answers are not here. Their error codes are for scripts and stay fixed.
 */
public final class Messages {
    private Messages() {
    }

    public static final String FILE = "messages.json";
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
    private static final Map<String, String> DEFAULTS = Collections.synchronizedMap(new LinkedHashMap<>());
    private static volatile Map<String, String> custom = Map.of();

    /** Registers a default and returns its id. */
    public static String define(String id, String text) {
        if (id == null || id.isBlank() || text == null) {
            throw new IllegalArgumentException("A message needs an id and a default text");
        }
        String old = DEFAULTS.putIfAbsent(id, text);
        if (old != null && !old.equals(text)) {
            throw new IllegalStateException("Message " + id + " is defined twice with different text");
        }
        return id;
    }

    /** The sentence with its {name} placeholders filled from name and value pairs, in one pass. */
    public static String get(String id, Object... pairs) {
        String text = raw(id);
        if (pairs == null || pairs.length == 0 || text.indexOf('{') < 0) {
            return text;
        }
        Map<String, String> values = new HashMap<>();
        for (int i = 0; i + 1 < pairs.length; i += 2) {
            values.putIfAbsent(String.valueOf(pairs[i]), pairs[i + 1] == null ? "" : String.valueOf(pairs[i + 1]));
        }
        StringBuilder out = new StringBuilder(text.length() + 16);
        int at = 0;
        while (at < text.length()) {
            int open = text.indexOf('{', at);
            int close = open < 0 ? -1 : text.indexOf('}', open + 1);
            if (close < 0) {
                break;
            }
            String v = values.get(text.substring(open + 1, close));
            if (v == null) {
                out.append(text, at, open + 1);
                at = open + 1;
            } else {
                out.append(text, at, open).append(v);
                at = close + 1;
            }
        }
        return out.append(text, at, text.length()).toString();
    }

    /** The sentence with its placeholders left in. An id never defined prints as itself. */
    public static String raw(String id) {
        String c = custom.get(id);
        if (c != null) {
            return c;
        }
        String d = DEFAULTS.get(id);
        return d != null ? d : String.valueOf(id);
    }

    public static List<String> ids() {
        synchronized (DEFAULTS) {
            return new ArrayList<>(DEFAULTS.keySet());
        }
    }

    public static String defaultOf(String id) {
        return DEFAULTS.get(id);
    }

    /**
     * Reads the file, adds missing ids and writes it back when any were missing. Returns the problems found. A
     * file that does not parse keeps the wording in use.
     */
    public static List<String> load(Path file) {
        List<String> problems = new ArrayList<>();
        JsonObject doc = new JsonObject();
        if (Files.exists(file)) {
            try {
                JsonElement e = JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8));
                if (!e.isJsonObject()) {
                    problems.add(FILE + " must hold one JSON object, the wording in use did not change");
                    return problems;
                }
                doc = e.getAsJsonObject();
            } catch (IOException | RuntimeException e) {
                problems.add(FILE + " could not be read, " + e.getMessage() + ". The wording in use did not change");
                return problems;
            }
        }
        Map<String, String> next = new HashMap<>();
        for (Map.Entry<String, JsonElement> e : doc.entrySet()) {
            if (e.getValue().isJsonPrimitive() && e.getValue().getAsJsonPrimitive().isString()) {
                String v = e.getValue().getAsString();
                if (!v.equals(DEFAULTS.get(e.getKey()))) {
                    next.put(e.getKey(), v);
                }
            } else {
                problems.add(FILE + " entry " + e.getKey() + " is not text and was ignored");
            }
        }
        custom = Map.copyOf(next);
        boolean missing = false;
        for (String id : ids()) {
            if (!doc.has(id)) {
                missing = true;
                break;
            }
        }
        if (missing || !Files.exists(file)) {
            Map<String, String> out = new TreeMap<>();
            for (String id : ids()) {
                out.put(id, raw(id));
            }
            for (Map.Entry<String, JsonElement> e : doc.entrySet()) {
                if (!out.containsKey(e.getKey()) && e.getValue().isJsonPrimitive()) {
                    out.put(e.getKey(), e.getValue().getAsString());
                }
            }
            try {
                Files.createDirectories(file.toAbsolutePath().getParent());
                Path tmp = file.resolveSibling(FILE + ".tmp");
                Files.writeString(tmp, GSON.toJson(out) + "\n", StandardCharsets.UTF_8);
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (IOException e) {
                problems.add(FILE + " could not be written, " + e.getMessage());
            }
        }
        return problems;
    }

    /** Back to the defaults, for tests. */
    public static void reset() {
        custom = Map.of();
    }
}
