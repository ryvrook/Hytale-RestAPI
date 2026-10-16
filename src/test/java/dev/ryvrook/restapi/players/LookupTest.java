package dev.ryvrook.restapi.players;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class LookupTest {
    @TempDir
    Path dir;

    private static final UUID STEVE = UUID.fromString("8d2f7c1e-4a3b-4c5d-9e8f-0a1b2c3d4e5f");
    private static final UUID ALEX = UUID.fromString("00000000-0000-0000-0000-00000000000a");

    @Test
    void uuidsParseWithAndWithoutDashes() {
        assertEquals(STEVE, Lookup.parseUuid("8d2f7c1e-4a3b-4c5d-9e8f-0a1b2c3d4e5f"));
        assertEquals(STEVE, Lookup.parseUuid("8d2f7c1e4a3b4c5d9e8f0a1b2c3d4e5f"));
        assertNull(Lookup.parseUuid("Steve"));
        assertNull(Lookup.parseUuid("8d2f7c1e-4a3b"));
        assertNull(Lookup.parseUuid(null));
    }

    @Test
    void onlineNamesMatchFirstIgnoringCase() {
        NameBook book = new NameBook(dir.resolve("n.json"));
        book.remember(ALEX, "Steve", 1);
        Lookup.Found f = Lookup.resolve("steve", Map.of(STEVE, "Steve"), book);
        assertEquals(STEVE, f.uuid());
        assertTrue(f.online());
    }

    @Test
    void offlinePlayersResolveByUuidOrBySeenName() {
        NameBook book = new NameBook(dir.resolve("n.json"));
        book.remember(ALEX, "Alex", 1);
        Lookup.Found byName = Lookup.resolve("ALEX", Map.of(), book);
        assertEquals(ALEX, byName.uuid());
        assertEquals("Alex", byName.name());
        assertFalse(byName.online());
        Lookup.Found byId = Lookup.resolve(STEVE.toString(), Map.of(), book);
        assertEquals(STEVE, byId.uuid());
        assertNull(byId.name());
        assertNull(Lookup.resolve("Nobody", Map.of(), book));
        assertNull(Lookup.resolve(" ", Map.of(), book));
        assertTrue(Lookup.resolve(STEVE.toString(), Map.of(STEVE, "Steve"), book).online());
    }

    @Test
    void aTargetLabelFallsBackToTheUuid() {
        assertEquals(STEVE.toString(), new Lookup.Target(STEVE, null, null).label());
        assertEquals("Steve", new Lookup.Target(STEVE, "Steve", null).label());
    }
}
