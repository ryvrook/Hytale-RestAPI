package dev.ryvrook.restapi.config;

import dev.ryvrook.restapi.Modules;
import dev.ryvrook.restapi.siblings.SiblingSettings;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

/**
 * docs/SETTINGS.md is written from the declared settings. The test fails when the file and the code disagree, or
 * when a key has no row in a README table. Run {@code RESTAPI_WRITE_DOCS=1 ./gradlew test} to write the file again.
 */
class SettingsDocTest {
    private static final Path DOC = Path.of("docs", "SETTINGS.md");
    private static final Path README = Path.of("README.md");

    static String markdown() {
        Modules.load(SettingsDocTest.class.getClassLoader(), p -> { });
        CoreSettings.HTTP_PORT.key();
        SiblingSettings.CHECK_SECONDS.key();
        StringBuilder out = new StringBuilder();
        out.append("# RestAPI settings\n\n");
        out.append("Every key of `mods/Ryvrook_RestAPI/RestAPI.json` with its default. Keys are dotted here and nested in the file, ")
                .append("`http.port` is `{\"http\": {\"port\": 8125}}`. A missing key is added with its default at the next start or ")
                .append("`/restapi reload`. A file with a wrong value is refused whole and the values in use stay. At the first start a refused file ")
                .append("keeps the HTTP API down. This file is written by SettingsDocTest, do not edit it by hand.\n");
        String section = null;
        for (Setting<?> s : Settings.all()) {
            String head = s.key().substring(0, s.key().indexOf('.'));
            if (!head.equals(section)) {
                section = head;
                out.append("\n## ").append(head).append("\n\n| Key | Default | Applies | Meaning |\n|---|---|---|---|\n");
            }
            out.append("| `").append(s.key()).append("` | `").append(Settings.toJson(s.fallback())).append("` | ")
                    .append(s.restart() ? "next start" : "reload").append(" | ")
                    .append(s.description().replace("|", "\\|"));
            if (s.kind() == Setting.Kind.INT) {
                out.append(" Range ").append(s.min()).append(" to ").append(s.max()).append('.');
            }
            out.append(" |\n");
        }
        return out.toString();
    }

    @Test
    void theSettingsDocumentMatchesTheCode() throws Exception {
        String expected = markdown();
        if ("1".equals(System.getenv("RESTAPI_WRITE_DOCS"))) {
            Files.writeString(DOC, expected);
        }
        assertTrue(Files.exists(DOC), "docs/SETTINGS.md is missing, run RESTAPI_WRITE_DOCS=1 ./gradlew test");
        assertEquals(expected, Files.readString(DOC), "docs/SETTINGS.md is out of date, run RESTAPI_WRITE_DOCS=1 ./gradlew test");
    }

    @Test
    void everyKeyHasAReadmeRow() throws Exception {
        markdown();
        String readme = Files.readString(README);
        List<String> missing = new ArrayList<>();
        for (Setting<?> s : Settings.all()) {
            boolean row = readme.lines().anyMatch(l -> l.startsWith("|") && l.contains("`" + s.key() + "`"));
            boolean family = s.key().startsWith("siblings.") && readme.contains("`siblings.<id>`");
            if (!row && !family) {
                missing.add(s.key());
            }
        }
        assertTrue(missing.isEmpty(), "README has no row for " + missing.stream().collect(Collectors.joining(", ")));
    }

    @Test
    void descriptionsFollowTheWritingRules() {
        markdown();
        for (Setting<?> s : Settings.all()) {
            String d = s.description();
            assertFalse(d.contains("—"), s.key() + " has an em dash");
            assertFalse(d.contains(";"), s.key() + " has a semicolon");
            assertFalse(d.contains("!"), s.key() + " has an exclamation mark");
            assertTrue(d.endsWith("."), s.key() + " should end with a full stop");
        }
    }
}
