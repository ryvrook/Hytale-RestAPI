package dev.ryvrook.restapi.dashboard;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.Supplier;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * The game icons of the page, read from the server's Assets.zip through java.util.zip. The archive is about 4 GB,
 * so it is never read whole. Opening it reads the central directory once, the index keeps the entries of the three
 * icon folders by name, and each icon is read on its own when asked for. The kinds and folders are the ones the
 * dashboard's server.js serves.
 *
 * <p>Thread safe. ZipFile reads entries from several threads at once.
 */
public final class AssetZip implements Closeable {
    /** Kind in the URL to the folder in the archive. */
    public static final Map<String, String> KINDS = kinds();
    /** A name as the URL may give it, without the .png. Anything else is refused before the archive is asked. */
    public static final Pattern NAME = Pattern.compile("[A-Za-z0-9_-]{1,100}");
    /** No icon in the archive is anywhere near this, a bigger entry is refused. */
    static final long MAX_ENTRY_BYTES = 4L << 20;

    private final Path file;
    private final ZipFile zip;
    private final Map<String, Map<String, ZipEntry>> index;
    private final byte[] indexJson;
    private final byte[] indexGzip;
    private final String indexTag;

    private AssetZip(Path file, ZipFile zip, Map<String, Map<String, ZipEntry>> index) {
        this.file = file;
        this.zip = zip;
        this.index = index;
        JsonObject o = new JsonObject();
        long crc = 0;
        for (Map.Entry<String, Map<String, ZipEntry>> e : index.entrySet()) {
            JsonArray names = new JsonArray();
            for (Map.Entry<String, ZipEntry> n : e.getValue().entrySet()) {
                names.add(n.getKey());
                crc = crc * 31 + n.getValue().getCrc();
            }
            o.add(e.getKey(), names);
        }
        this.indexJson = (o + "\n").getBytes(StandardCharsets.UTF_8);
        this.indexGzip = gzip(indexJson);
        this.indexTag = "\"i-" + Long.toHexString(crc ^ indexJson.length) + "\"";
    }

    private static Map<String, String> kinds() {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("items", "Common/Icons/ItemsGenerated/");
        m.put("markers", "Common/UI/WorldMap/MapMarkers/");
        m.put("models", "Common/Icons/ModelsGenerated/");
        return Map.copyOf(m);
    }

    /** Opens an archive and builds the index. Throws when it is not a readable zip. */
    public static AssetZip open(Path file) throws IOException {
        ZipFile zip = new ZipFile(file.toFile());
        try {
            Map<String, Map<String, ZipEntry>> index = new LinkedHashMap<>();
            for (String kind : List.of("items", "markers", "models")) {
                index.put(kind, new TreeMap<>());
            }
            Map<String, String> byFolder = new HashMap<>();
            KINDS.forEach((k, v) -> byFolder.put(v, k));
            Enumeration<? extends ZipEntry> all = zip.entries();
            while (all.hasMoreElements()) {
                ZipEntry e = all.nextElement();
                String name = e.getName();
                if (e.isDirectory() || !name.endsWith(".png")) {
                    continue;
                }
                int slash = name.lastIndexOf('/');
                String kind = slash < 0 ? null : byFolder.get(name.substring(0, slash + 1));
                if (kind == null) {
                    continue;
                }
                String bare = name.substring(slash + 1, name.length() - 4);
                if (NAME.matcher(bare).matches()) {
                    index.get(kind).put(bare, e);
                }
            }
            return new AssetZip(file, zip, index);
        } catch (RuntimeException e) {
            zip.close();
            throw new IOException(e.getMessage(), e);
        }
    }

    /**
     * The archive to use. The setting when it names one, relative to the server folder. Empty tries the archive
     * the engine loaded its base pack from, then Assets.zip in the server folder and in the folder above it. Null
     * when none of them is a file.
     */
    public static Path find(String setting, Path workingDirectory, Supplier<Path> enginePack) {
        if (setting != null && !setting.isBlank()) {
            Path p = workingDirectory.resolve(setting.trim()).normalize();
            return Files.isRegularFile(p) ? p : null;
        }
        List<Path> tried = new ArrayList<>();
        Path engine = null;
        try {
            engine = enginePack == null ? null : enginePack.get();
        } catch (RuntimeException | LinkageError e) {
            // Off a running server, or an engine without the call.
        }
        if (engine != null && engine.getFileName() != null && engine.getFileName().toString().toLowerCase(java.util.Locale.ROOT).endsWith(".zip")) {
            tried.add(engine);
        }
        tried.add(workingDirectory.resolve("Assets.zip"));
        Path parent = workingDirectory.toAbsolutePath().getParent();
        if (parent != null) {
            tried.add(parent.resolve("Assets.zip"));
        }
        for (Path p : tried) {
            if (Files.isRegularFile(p)) {
                return p;
            }
        }
        return null;
    }

    public Path file() {
        return file;
    }

    /** Icons in the index, all kinds together. */
    public int count() {
        return index.values().stream().mapToInt(Map::size).sum();
    }

    /** The names per kind as JSON, the body of /dashboard/assets/index.json. */
    public byte[] indexJson() {
        return indexJson;
    }

    /** The same body gzipped, for a browser that takes it. */
    public byte[] indexGzip() {
        return indexGzip;
    }

    private static byte[] gzip(byte[] in) {
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream(in.length / 4 + 64);
        try (java.util.zip.GZIPOutputStream gz = new java.util.zip.GZIPOutputStream(out)) {
            gz.write(in);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
        return out.toByteArray();
    }

    public String indexTag() {
        return indexTag;
    }

    /** True for a kind the archive is asked about and a name of the allowed shape. */
    public static boolean valid(String kind, String name) {
        return kind != null && KINDS.containsKey(kind) && name != null && NAME.matcher(name).matches();
    }

    /** The ETag of an icon, from the entry's CRC, or null when the archive has none of that name. */
    public String tag(String kind, String name) {
        ZipEntry e = entry(kind, name);
        return e == null ? null : "\"" + Long.toHexString(e.getCrc()) + "\"";
    }

    /** The PNG bytes of an icon, or null when the archive has none of that name. */
    public byte[] read(String kind, String name) throws IOException {
        ZipEntry e = entry(kind, name);
        if (e == null) {
            return null;
        }
        if (e.getSize() > MAX_ENTRY_BYTES) {
            throw new IOException(e.getName() + " is larger than an icon can be");
        }
        try (InputStream in = zip.getInputStream(e)) {
            byte[] b = in.readNBytes((int) MAX_ENTRY_BYTES + 1);
            if (b.length > MAX_ENTRY_BYTES) {
                throw new IOException(e.getName() + " is larger than an icon can be");
            }
            return b;
        }
    }

    private ZipEntry entry(String kind, String name) {
        if (!valid(kind, name)) {
            return null;
        }
        Map<String, ZipEntry> names = index.get(kind);
        return names == null ? null : names.get(name);
    }

    @Override
    public void close() throws IOException {
        zip.close();
    }
}
