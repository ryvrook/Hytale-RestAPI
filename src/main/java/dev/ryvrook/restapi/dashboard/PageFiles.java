package dev.ryvrook.restapi.dashboard;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * The dashboard page as the jar carries it under {@code restapi-dashboard/}, a copy of index.html, login.html,
 * css, js, img and vendor from the dashboard repository made by its tools/sync-to-restapi.sh. That script also
 * writes {@code files.txt}, one path per line, which is the only list of files served, and {@code VERSION} with
 * the dashboard version and commit. Everything is read into memory once, about 660 KB.
 */
public final class PageFiles {
    /** The folder in the jar. */
    public static final String RESOURCES = "restapi-dashboard/";
    static final Map<String, String> TYPES = Map.of(
            "html", "text/html; charset=utf-8",
            "css", "text/css; charset=utf-8",
            "js", "text/javascript; charset=utf-8",
            "json", "application/json; charset=utf-8",
            "svg", "image/svg+xml",
            "png", "image/png",
            "woff2", "font/woff2",
            "txt", "text/plain; charset=utf-8");

    private final Map<String, byte[]> files;
    private final String version;
    private final String commit;

    private PageFiles(Map<String, byte[]> files, String version, String commit) {
        this.files = files;
        this.version = version;
        this.commit = commit;
    }

    /** Reads every file files.txt lists. Throws when the list, VERSION or a listed file is missing. */
    public static PageFiles load(ClassLoader loader) throws IOException {
        String list = new String(read(loader, "files.txt"), StandardCharsets.UTF_8);
        Map<String, byte[]> out = new LinkedHashMap<>();
        for (String line : list.split("\n")) {
            String name = line.trim();
            if (name.isEmpty() || name.startsWith("#")) {
                continue;
            }
            if (!safe(name) || typeOf(name) == null) {
                throw new IOException("files.txt lists " + name + ", which the dashboard does not serve");
            }
            out.put(name, read(loader, name));
        }
        if (!out.containsKey("index.html") || !out.containsKey("login.html")) {
            throw new IOException("files.txt must list index.html and login.html");
        }
        String version = "unknown";
        String commit = "unknown";
        for (String line : new String(read(loader, "VERSION"), StandardCharsets.UTF_8).split("\n")) {
            String[] kv = line.trim().split("\\s+", 2);
            if (kv.length == 2 && kv[0].equals("dashboard")) {
                version = kv[1];
            } else if (kv.length == 2 && kv[0].equals("commit")) {
                commit = kv[1];
            }
        }
        return new PageFiles(Map.copyOf(out), version, commit);
    }

    private static byte[] read(ClassLoader loader, String name) throws IOException {
        try (InputStream in = loader.getResourceAsStream(RESOURCES + name)) {
            if (in == null) {
                throw new IOException("The jar has no " + RESOURCES + name);
            }
            return in.readAllBytes();
        }
    }

    /** A relative path of plain segments, no dot segments, no backslash and no percent escape. */
    static boolean safe(String name) {
        if (name.isEmpty() || name.startsWith("/") || name.contains("\\") || name.contains("%") || name.contains("//")) {
            return false;
        }
        for (String part : name.split("/")) {
            if (part.isEmpty() || part.equals(".") || part.equals("..") || !part.matches("[A-Za-z0-9._-]+")) {
                return false;
            }
        }
        return true;
    }

    /** The content type of a file name by its extension, or null for one the page does not use. */
    static String typeOf(String name) {
        int dot = name.lastIndexOf('.');
        return dot < 0 ? null : TYPES.get(name.substring(dot + 1).toLowerCase(Locale.ROOT));
    }

    /** The bytes of a file, or null when the page has none of that name. */
    public byte[] get(String name) {
        return name == null ? null : files.get(name);
    }

    public int count() {
        return files.size();
    }

    public int bytes() {
        return files.values().stream().mapToInt(b -> b.length).sum();
    }

    /** The dashboard version the copy was made from, such as 1.6.0. */
    public String version() {
        return version;
    }

    /** The dashboard commit the copy was made from. */
    public String commit() {
        return commit;
    }
}
