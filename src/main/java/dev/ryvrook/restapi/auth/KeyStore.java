package dev.ryvrook.restapi.auth;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * The named keys in keys.json. A token looks like {@code rapi_1a2b3c4d_<43 characters>}, the eight hex digits are
 * the key id and the rest is 32 random bytes. Only the SHA-256 of the whole token is written to disk, the token
 * is shown once when it is made. A token of 256 random bits needs no slow hash, nobody can guess one from its
 * SHA-256.
 *
 * <p>Reads are lock free over a map swapped whole on each change. Changes are synchronized and written to disk
 * before they apply, so a key that failed to save never answers.
 */
public final class KeyStore {
    public static final String FILE = "keys.json";
    public static final String PREFIX = "rapi_";
    private static final Pattern NAME = Pattern.compile("[a-z0-9][a-z0-9_-]{0,31}");
    private static final Pattern TOKEN = Pattern.compile("rapi_([0-9a-f]{8})_[A-Za-z0-9_-]{43}");
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();

    /** A key just made, with its token. The token exists only in this object and in the reply that shows it. */
    public record Created(ApiKey key, String token) {
    }

    private final Path file;
    private final Clock clock;
    private final SecureRandom random;
    /** Keys by id. */
    private volatile Map<String, ApiKey> byId = Map.of();

    public KeyStore(Path file) {
        this(file, Clock.systemUTC(), new SecureRandom());
    }

    KeyStore(Path file, Clock clock, SecureRandom random) {
        this.file = file;
        this.clock = clock;
        this.random = random;
    }

    /**
     * Reads keys.json. Returns the problems found, each one a sentence. An entry that cannot be read is skipped,
     * the rest still load. A file that does not parse leaves the current keys in place.
     */
    public synchronized List<String> load() {
        List<String> problems = new ArrayList<>();
        if (!Files.exists(file)) {
            byId = Map.of();
            return problems;
        }
        JsonObject doc;
        try {
            JsonElement e = JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8));
            if (!e.isJsonObject()) {
                problems.add(FILE + " must hold one JSON object, the keys in use did not change");
                return problems;
            }
            doc = e.getAsJsonObject();
        } catch (IOException | RuntimeException e) {
            problems.add(FILE + " could not be read, " + e.getMessage() + ". The keys in use did not change");
            return problems;
        }
        Map<String, ApiKey> next = new LinkedHashMap<>();
        Set<String> names = new java.util.HashSet<>();
        JsonArray keys = doc.has("keys") && doc.get("keys").isJsonArray() ? doc.getAsJsonArray("keys") : new JsonArray();
        int index = 0;
        for (JsonElement e : keys) {
            index++;
            try {
                ApiKey k = fromJson(e.getAsJsonObject());
                if (!names.add(k.name())) {
                    problems.add("key " + index + " repeats the name " + k.name() + " and was skipped");
                } else if (next.putIfAbsent(k.id(), k) != null) {
                    problems.add("key " + k.name() + " repeats the id " + k.id() + " and was skipped");
                }
            } catch (RuntimeException ex) {
                problems.add("key " + index + " was skipped, " + ex.getMessage());
            }
        }
        byId = Map.copyOf(next);
        return problems;
    }

    public boolean isEmpty() {
        return byId.isEmpty();
    }

    /** Every key, sorted by name. */
    public List<ApiKey> list() {
        List<ApiKey> out = new ArrayList<>(byId.values());
        out.sort(java.util.Comparator.comparing(ApiKey::name));
        return out;
    }

    public ApiKey byName(String name) {
        for (ApiKey k : byId.values()) {
            if (k.name().equals(name)) {
                return k;
            }
        }
        return null;
    }

    /**
     * Makes a key and saves it. The name is lower case letters, digits, dash and underscore, at most 32. Throws
     * with a sentence when the name is taken or not allowed, or when the file cannot be written.
     */
    public synchronized Created create(String name, Set<Scope> scopes, String createdBy) throws IOException {
        if (name == null || !NAME.matcher(name).matches()) {
            throw new IllegalArgumentException("A key name is 1 to 32 lower case letters, digits, dashes or underscores");
        }
        if (byName(name) != null) {
            throw new IllegalArgumentException("A key named " + name + " exists already");
        }
        if (scopes == null || scopes.isEmpty()) {
            throw new IllegalArgumentException("A key needs at least one scope");
        }
        String id;
        do {
            byte[] idBytes = new byte[4];
            random.nextBytes(idBytes);
            id = HexFormat.of().formatHex(idBytes);
        } while (byId.containsKey(id));
        byte[] secret = new byte[32];
        random.nextBytes(secret);
        String token = PREFIX + id + "_" + Base64.getUrlEncoder().withoutPadding().encodeToString(secret);
        ApiKey key = new ApiKey(name, id, hash(token), scopes, clock.instant().toString(), createdBy, -1, List.of());
        Map<String, ApiKey> next = new LinkedHashMap<>(byId);
        next.put(id, key);
        save(next);
        byId = Map.copyOf(next);
        return new Created(key, token);
    }

    /** Removes a key by name and saves. False when no key has that name. */
    public synchronized boolean revoke(String name) throws IOException {
        ApiKey k = byName(name);
        if (k == null) {
            return false;
        }
        Map<String, ApiKey> next = new LinkedHashMap<>(byId);
        next.remove(k.id());
        save(next);
        byId = Map.copyOf(next);
        return true;
    }

    /**
     * The key a token belongs to, or null. The id in the token picks the key and the hashes are compared in
     * constant time, so the time an answer takes says nothing about how close a guess was.
     */
    public ApiKey authenticate(String token) {
        if (token == null) {
            return null;
        }
        var m = TOKEN.matcher(token.trim());
        if (!m.matches()) {
            return null;
        }
        ApiKey k = byId.get(m.group(1));
        if (k == null) {
            return null;
        }
        byte[] given = hash(token.trim()).getBytes(StandardCharsets.US_ASCII);
        byte[] stored = k.hash().getBytes(StandardCharsets.US_ASCII);
        return MessageDigest.isEqual(given, stored) ? k : null;
    }

    /** SHA-256 of a token in lower case hex. */
    public static String hash(String token) {
        try {
            MessageDigest d = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(d.digest(token.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("This JVM has no SHA-256", e);
        }
    }

    private void save(Map<String, ApiKey> keys) throws IOException {
        JsonObject doc = new JsonObject();
        doc.addProperty("version", 1);
        doc.addProperty("note", "Tokens are not stored, only their SHA-256. Make keys with /restapi keys create.");
        JsonArray arr = new JsonArray();
        for (ApiKey k : keys.values()) {
            arr.add(toJson(k));
        }
        doc.add("keys", arr);
        Files.createDirectories(file.toAbsolutePath().getParent());
        Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
        Files.writeString(tmp, GSON.toJson(doc) + "\n", StandardCharsets.UTF_8);
        try {
            Files.setPosixFilePermissions(tmp, PosixFilePermissions.fromString("rw-------"));
        } catch (UnsupportedOperationException | IOException e) {
            // Windows has no POSIX permissions. The hashes are safe to read anyway.
        }
        Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }

    static JsonObject toJson(ApiKey k) {
        JsonObject o = new JsonObject();
        o.addProperty("name", k.name());
        o.addProperty("id", k.id());
        o.addProperty("sha256", k.hash());
        JsonArray scopes = new JsonArray();
        Scope.describe(k.scopes()).forEach(scopes::add);
        o.add("scopes", scopes);
        o.addProperty("created", k.created());
        o.addProperty("createdBy", k.createdBy());
        o.addProperty("requestsPerMinute", k.perMinute());
        JsonArray from = new JsonArray();
        k.allowFrom().forEach(from::add);
        o.add("allowFrom", from);
        return o;
    }

    static ApiKey fromJson(JsonObject o) {
        String name = text(o, "name");
        String id = text(o, "id");
        String hash = text(o, "sha256");
        if (name == null || !NAME.matcher(name).matches()) {
            throw new IllegalArgumentException("its name is missing or not allowed");
        }
        if (id == null || !id.matches("[0-9a-f]{8}")) {
            throw new IllegalArgumentException("its id must be eight hex digits");
        }
        if (hash == null || !hash.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("its sha256 must be 64 hex digits");
        }
        List<String> scopeIds = new ArrayList<>();
        if (o.has("scopes") && o.get("scopes").isJsonArray()) {
            o.getAsJsonArray("scopes").forEach(e -> scopeIds.add(e.getAsString()));
        }
        Set<Scope> scopes = Scope.parse(scopeIds);
        if (scopes.isEmpty()) {
            throw new IllegalArgumentException("it has no scopes");
        }
        int perMinute = o.has("requestsPerMinute") && o.get("requestsPerMinute").isJsonPrimitive()
                ? o.get("requestsPerMinute").getAsInt() : -1;
        List<String> from = new ArrayList<>();
        if (o.has("allowFrom") && o.get("allowFrom").isJsonArray()) {
            o.getAsJsonArray("allowFrom").forEach(e -> from.add(e.getAsString()));
        }
        return new ApiKey(name, id, hash, scopes, text(o, "created"), text(o, "createdBy"), perMinute, from);
    }

    private static String text(JsonObject o, String key) {
        JsonElement e = o.get(key);
        return e == null || e.isJsonNull() ? null : e.getAsString();
    }
}
