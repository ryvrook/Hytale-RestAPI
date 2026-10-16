package dev.ryvrook.restapi.auth;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.EnumSet;

import static org.junit.jupiter.api.Assertions.*;

class KeyStoreTest {
    @TempDir
    Path dir;

    @Test
    void aTokenIsShownOnceAndOnlyItsHashIsStored() throws Exception {
        KeyStore s = new KeyStore(dir.resolve("keys.json"));
        KeyStore.Created made = s.create("grafana", EnumSet.of(Scope.METRICS), "console");
        assertTrue(made.token().matches("rapi_[0-9a-f]{8}_[A-Za-z0-9_-]{43}"), made.token());
        String file = Files.readString(dir.resolve("keys.json"));
        assertFalse(file.contains(made.token().substring(14)), "the secret is never written");
        assertTrue(file.contains(KeyStore.hash(made.token())));
    }

    @Test
    void authenticateFindsTheKeyAndRefusesNearMisses() throws Exception {
        KeyStore s = new KeyStore(dir.resolve("keys.json"));
        String token = s.create("panel", EnumSet.of(Scope.READ), "console").token();
        assertEquals("panel", s.authenticate(token).name());
        assertEquals("panel", s.authenticate("  " + token + " ").name());
        char last = token.charAt(token.length() - 1);
        String changed = token.substring(0, token.length() - 1) + (last == 'A' ? 'B' : 'A');
        assertNull(s.authenticate(changed));
        assertNull(s.authenticate(null));
        assertNull(s.authenticate("changeme"));
        assertNull(s.authenticate(token.replace("rapi_", "rapx_")));
    }

    @Test
    void keysSurviveARestart() throws Exception {
        Path file = dir.resolve("keys.json");
        KeyStore a = new KeyStore(file);
        String token = a.create("ops", EnumSet.of(Scope.READ, Scope.PLAYERS), "Steve").token();
        KeyStore b = new KeyStore(file);
        assertTrue(b.load().isEmpty());
        ApiKey k = b.authenticate(token);
        assertEquals(EnumSet.of(Scope.READ, Scope.PLAYERS), EnumSet.copyOf(k.scopes()));
        assertEquals("Steve", k.createdBy());
        assertEquals(-1, k.perMinute());
    }

    @Test
    void namesAreCheckedAndUnique() throws Exception {
        KeyStore s = new KeyStore(dir.resolve("keys.json"));
        s.create("one", EnumSet.of(Scope.READ), "c");
        assertThrows(IllegalArgumentException.class, () -> s.create("one", EnumSet.of(Scope.READ), "c"));
        assertThrows(IllegalArgumentException.class, () -> s.create("Bad Name", EnumSet.of(Scope.READ), "c"));
        assertThrows(IllegalArgumentException.class, () -> s.create("x".repeat(33), EnumSet.of(Scope.READ), "c"));
        assertThrows(IllegalArgumentException.class, () -> s.create("none", EnumSet.noneOf(Scope.class), "c"));
    }

    @Test
    void revokeRemovesTheKeyAtOnce() throws Exception {
        KeyStore s = new KeyStore(dir.resolve("keys.json"));
        String token = s.create("old", EnumSet.of(Scope.READ), "c").token();
        assertTrue(s.revoke("old"));
        assertNull(s.authenticate(token));
        assertFalse(s.revoke("old"));
        assertTrue(new KeyStore(dir.resolve("keys.json")).load().isEmpty());
    }

    @Test
    void aBrokenEntryIsSkippedAndTheRestLoad() throws Exception {
        Path file = dir.resolve("keys.json");
        KeyStore a = new KeyStore(file);
        String token = a.create("good", EnumSet.of(Scope.READ), "c").token();
        String json = Files.readString(file).replace("\"keys\": [", "\"keys\": [{\"name\": \"bad\", \"id\": \"zz\"},");
        Files.writeString(file, json);
        KeyStore b = new KeyStore(file);
        var problems = b.load();
        assertEquals(1, problems.size());
        assertTrue(problems.get(0).contains("key 1"), problems.get(0));
        assertNotNull(b.authenticate(token));
    }

    @Test
    void aFileThatDoesNotParseKeepsTheKeysInUse() throws Exception {
        Path file = dir.resolve("keys.json");
        KeyStore s = new KeyStore(file);
        String token = s.create("keep", EnumSet.of(Scope.READ), "c").token();
        Files.writeString(file, "{ not json");
        assertFalse(s.load().isEmpty());
        assertNotNull(s.authenticate(token));
    }
}
