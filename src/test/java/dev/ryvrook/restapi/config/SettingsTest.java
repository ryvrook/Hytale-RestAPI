package dev.ryvrook.restapi.config;

import com.google.gson.JsonParser;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class SettingsTest {
    @TempDir
    Path dir;

    @BeforeEach
    void defaults() {
        CoreSettings.HTTP_PORT.key();
        Settings.useDefaults();
    }

    @Test
    void aMissingFileIsWrittenWithEveryDefault() throws Exception {
        Path file = dir.resolve("RestAPI.json");
        Settings.LoadReport r = Settings.load(file);
        assertTrue(r.ok());
        assertFalse(r.fileRead());
        var doc = JsonParser.parseString(Files.readString(file)).getAsJsonObject();
        assertEquals("127.0.0.1", doc.getAsJsonObject("http").get("bind").getAsString());
        assertEquals(8125, doc.getAsJsonObject("http").get("port").getAsInt());
    }

    @Test
    void valuesFromTheFileApplyAndUnknownKeysAreKept() throws Exception {
        Path file = dir.resolve("RestAPI.json");
        Files.writeString(file, "{\"http\": {\"port\": 9000, \"corsOrigins\": [\"https://a.example\"]}, \"mine\": 1}");
        assertTrue(Settings.load(file).ok());
        assertEquals(9000, CoreSettings.HTTP_PORT.get());
        assertEquals(List.of("https://a.example"), CoreSettings.HTTP_CORS_ORIGINS.get());
        String written = Files.readString(file);
        assertTrue(written.contains("\"mine\": 1"));
        assertTrue(written.contains("\"bind\": \"127.0.0.1\""), "missing keys are added");
    }

    @Test
    void aBadValueChangesNothing() throws Exception {
        Path file = dir.resolve("RestAPI.json");
        Files.writeString(file, "{\"http\": {\"port\": 9001}}");
        Settings.load(file);
        Files.writeString(file, "{\"http\": {\"port\": 70000, \"bind\": \"0.0.0.0\", \"enabled\": \"yes\"}}");
        Settings.LoadReport r = Settings.load(file);
        assertEquals(2, r.problems().size(), r.problems().toString());
        assertTrue(r.problems().get(0).contains("http.enabled must be true or false"), r.problems().toString());
        assertEquals(9001, CoreSettings.HTTP_PORT.get());
        assertEquals("127.0.0.1", CoreSettings.HTTP_BIND.get(), "a file with problems never opens the bind");
    }

    @Test
    void aFileThatDoesNotParseChangesNothing() throws Exception {
        Path file = dir.resolve("RestAPI.json");
        Files.writeString(file, "{ \"http\": ");
        assertFalse(Settings.load(file).ok());
        assertEquals(8125, CoreSettings.HTTP_PORT.get());
    }

    @Test
    void wholeNumbersOnly() {
        assertThrows(IllegalArgumentException.class, () -> CoreSettings.HTTP_PORT.coerce(80.5));
        assertEquals(80, CoreSettings.HTTP_PORT.coerce(80.0));
    }

    @Test
    void keysCannotRepeatOrNest() {
        assertThrows(IllegalStateException.class, () -> Settings.bool("http.port", true, "x"));
        assertThrows(IllegalStateException.class, () -> Settings.bool("http.port.deeper", true, "x"));
        assertThrows(IllegalArgumentException.class, () -> Settings.bool("test.nodescription", true, " "));
    }

    @Test
    void restartSettingsAreMarked() {
        assertTrue(CoreSettings.HTTP_REQUEST_SECONDS.restart());
        assertFalse(CoreSettings.HTTP_PORT.restart());
    }
}
