package dev.ryvrook.restapi.config;

import dev.ryvrook.restapi.Modules;
import dev.ryvrook.restapi.siblings.SiblingSettings;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * docs/settings/&lt;section&gt;.md is written from the declared settings, one file per first part of the key, so
 * modules built side by side never touch the same file. The test fails when a file and the code disagree, when a
 * file has no section left, or when a key has no row in a README table or in docs/readme/&lt;module&gt;.md. Run
 * {@code RESTAPI_WRITE_DOCS=1 ./gradlew test} to write the files again.
 */
class SettingsDocTest {
    private static final Path DIR = Path.of("docs", "settings");
    private static final Path README = Path.of("README.md");
    private static final Path README_PARTS = Path.of("docs", "readme");

    static void loadAll() {
        Modules.load(SettingsDocTest.class.getClassLoader(), p -> { });
        CoreSettings.HTTP_PORT.key();
        SiblingSettings.CHECK_SECONDS.key();
    }

    /** Section name to its markdown. */
    static Map<String, String> sections() {
        loadAll();
        Map<String, StringBuilder> out = new LinkedHashMap<>();
        for (Setting<?> s : Settings.all()) {
            String head = s.key().substring(0, s.key().indexOf('.'));
            StringBuilder b = out.computeIfAbsent(head, h -> new StringBuilder()
                    .append("# ").append(h).append(" settings\n\n")
                    .append("Keys under `").append(h).append("` in `mods/Ryvrook_RestAPI/RestAPI.json`. ")
                    .append("Written by SettingsDocTest, do not edit by hand.\n\n")
                    .append("| Key | Default | Applies | Meaning |\n|---|---|---|---|\n"));
            b.append("| `").append(s.key()).append("` | `").append(Settings.toJson(s.fallback())).append("` | ")
                    .append(s.restart() ? "next start" : "reload").append(" | ")
                    .append(s.description().replace("|", "\\|"));
            if (s.kind() == Setting.Kind.INT) {
                b.append(" Range ").append(s.min()).append(" to ").append(s.max()).append('.');
            }
            b.append(" |\n");
        }
        Map<String, String> done = new LinkedHashMap<>();
        out.forEach((k, v) -> done.put(k, v.toString()));
        return done;
    }

    @Test
    void theSettingsFilesMatchTheCode() throws Exception {
        Map<String, String> expected = sections();
        boolean write = "1".equals(System.getenv("RESTAPI_WRITE_DOCS"));
        if (write) {
            Files.createDirectories(DIR);
        }
        for (Map.Entry<String, String> e : expected.entrySet()) {
            Path file = DIR.resolve(e.getKey() + ".md");
            if (write) {
                Files.writeString(file, e.getValue());
            }
            assertTrue(Files.exists(file), file + " is missing, run RESTAPI_WRITE_DOCS=1 ./gradlew test");
            assertEquals(e.getValue(), Files.readString(file), file + " is out of date, run RESTAPI_WRITE_DOCS=1 ./gradlew test");
        }
        try (Stream<Path> files = Files.list(DIR)) {
            for (Path f : files.toList()) {
                String name = f.getFileName().toString().replace(".md", "");
                if (!expected.containsKey(name)) {
                    if (write) {
                        Files.delete(f);
                    } else {
                        fail(f + " has no settings left, delete it or run RESTAPI_WRITE_DOCS=1 ./gradlew test");
                    }
                }
            }
        }
    }

    @Test
    void everyKeyHasAReadmeRow() throws Exception {
        loadAll();
        StringBuilder text = new StringBuilder(Files.readString(README));
        if (Files.isDirectory(README_PARTS)) {
            try (Stream<Path> parts = Files.list(README_PARTS)) {
                for (Path p : parts.toList()) {
                    text.append('\n').append(Files.readString(p));
                }
            }
        }
        String all = text.toString();
        List<String> missing = new ArrayList<>();
        for (Setting<?> s : Settings.all()) {
            boolean row = all.lines().anyMatch(l -> l.startsWith("|") && l.contains("`" + s.key() + "`"));
            boolean family = s.key().startsWith("siblings.") && all.contains("`siblings.<id>`");
            if (!row && !family && !sectionRow(all, s.key())) {
                missing.add(s.key());
            }
        }
        assertTrue(missing.isEmpty(), "README has no row for " + String.join(", ", missing)
                + " and no section row pointing at docs/settings");
    }

    /**
     * The README may list a whole section in one table row (a row that names the section in a code span) when
     * docs/settings/<section>.md, written by this test, holds the row for the key itself.
     */
    private static boolean sectionRow(String readme, String key) throws Exception {
        String section = key.substring(0, key.indexOf('.'));
        boolean named = readme.lines().anyMatch(l -> l.startsWith("|") && l.contains("`" + section + "`"));
        Path doc = Path.of("docs", "settings", section + ".md");
        return named && Files.isRegularFile(doc)
                && Files.readString(doc).lines().anyMatch(l -> l.startsWith("|") && l.contains("`" + key + "`"));
    }

    @Test
    void descriptionsFollowTheWritingRules() {
        loadAll();
        for (Setting<?> s : Settings.all()) {
            String d = s.description();
            assertFalse(d.contains("\u2014"), s.key() + " has an em dash");
            assertFalse(d.contains(";"), s.key() + " has a semicolon");
            assertFalse(d.contains("!"), s.key() + " has an exclamation mark");
            assertTrue(d.endsWith("."), s.key() + " should end with a full stop");
        }
    }
}
