package dev.ryvrook.restapi.players;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class NameBookTest {
    @TempDir
    Path dir;

    @Test
    void namesSurviveASaveAndALoad() {
        Path f = dir.resolve(NameBook.FILE);
        NameBook b = new NameBook(f);
        UUID a = UUID.randomUUID();
        b.remember(a, "Steve", 1_000);
        assertNull(b.saveIfDirty());
        assertTrue(Files.exists(f));
        NameBook again = new NameBook(f);
        assertTrue(again.load().isEmpty());
        assertEquals("Steve", again.name(a));
        assertEquals(a, again.find("steve"));
        assertNull(again.find("alex"));
    }

    @Test
    void theLastPlayerSeenWithANameWins() {
        NameBook b = new NameBook(dir.resolve(NameBook.FILE));
        UUID old = UUID.randomUUID();
        UUID now = UUID.randomUUID();
        b.remember(old, "Steve", 1_000);
        b.remember(now, "Steve", 2_000);
        assertEquals(now, b.find("STEVE"));
    }

    @Test
    void nothingIsWrittenWhenNothingChanged() {
        Path f = dir.resolve(NameBook.FILE);
        NameBook b = new NameBook(f);
        UUID a = UUID.randomUUID();
        b.remember(a, "Steve", 1_000);
        b.saveIfDirty();
        b.remember(a, "Steve", 2_000);
        assertNull(b.saveIfDirty());
        b.remember(null, "x", 1);
        b.remember(a, " ", 1);
        assertEquals(1, b.size());
    }

    @Test
    void aBrokenFileStartsEmptyAndSaysWhy() throws Exception {
        Path f = dir.resolve(NameBook.FILE);
        Files.writeString(f, "[1]");
        NameBook b = new NameBook(f);
        assertEquals(1, b.load().size());
        Files.writeString(f, "{\"not-a-uuid\": {\"name\": \"x\"}, \"" + UUID.randomUUID() + "\": {\"name\": \"Alex\", \"lastSeen\": 5}}");
        assertEquals(1, b.load().size());
        assertEquals(1, b.size());
    }
}
