package dev.ryvrook.restapi.suite;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.ryvrook.restapi.http.ApiException;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;

/**
 * A JSON file another suite mod writes, such as Chronicle's export/stats.json or Lullaby's stats.json. Read again
 * only when its modified time or size changed, so a busy dashboard costs one stat call per request. The siblings
 * write these files through a temporary file and a move, so a read never sees half a file.
 */
public final class JsonFile {
    private record Cached(FileTime modified, long size, JsonObject doc) {
    }

    private final Path file;
    private final String mod;
    private volatile Cached cached;

    public JsonFile(Path file, String mod) {
        this.file = file;
        this.mod = mod;
    }

    public Path path() {
        return file;
    }

    /** The file as an object, or null when it does not exist. 503 file_broken when it does not parse. */
    public JsonObject readOrNull() {
        FileTime modified;
        long size;
        try {
            modified = Files.getLastModifiedTime(file);
            size = Files.size(file);
        } catch (NoSuchFileException e) {
            return null;
        } catch (IOException e) {
            throw broken();
        }
        Cached c = cached;
        if (c != null && c.modified().equals(modified) && c.size() == size) {
            return c.doc();
        }
        try {
            JsonElement e = JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8));
            if (!e.isJsonObject()) {
                throw broken();
            }
            JsonObject doc = e.getAsJsonObject();
            cached = new Cached(modified, size, doc);
            return doc;
        } catch (NoSuchFileException e) {
            return null;
        } catch (IOException | RuntimeException e) {
            if (e instanceof ApiException a) {
                throw a;
            }
            throw broken();
        }
    }

    /** The file as an object, 503 file_missing when it does not exist yet. */
    public JsonObject read() {
        JsonObject o = readOrNull();
        if (o == null) {
            throw new ApiException(503, "file_missing", SuiteText.get(SuiteText.FILE_MISSING, "mod", mod, "file", name()));
        }
        return o;
    }

    /** The modified time in epoch millis, or 0. */
    public long modifiedAt() {
        try {
            return Files.getLastModifiedTime(file).toMillis();
        } catch (IOException e) {
            return 0;
        }
    }

    private ApiException broken() {
        return new ApiException(503, "file_broken", SuiteText.get(SuiteText.FILE_BROKEN, "mod", mod, "file", name()));
    }

    private String name() {
        Path parent = file.getParent();
        return parent == null ? file.getFileName().toString() : parent.getFileName() + "/" + file.getFileName();
    }
}
