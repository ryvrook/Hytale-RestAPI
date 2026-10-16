package dev.ryvrook.restapi.players;

import com.google.gson.JsonObject;
import dev.ryvrook.restapi.http.ApiException;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class ModerationTest {
    static {
        PlayersText.KICK_REASON.length();
    }

    @Test
    void groupDiffAddsAndRemovesOnlyWhatChanged() {
        Moderation.GroupChange c = Moderation.diff(Set.of("Default", "hytale:Builder"), List.of("default", "hytale:WorldEditor", "hytale:WorldEditor"));
        assertEquals(List.of("hytale:WorldEditor"), c.add());
        assertEquals(List.of("hytale:Builder"), c.remove());
        Moderation.GroupChange none = Moderation.diff(Set.of("Default"), List.of("Default"));
        assertTrue(none.add().isEmpty());
        assertTrue(none.remove().isEmpty());
    }

    @Test
    void protectedGroupsAreRefusedWhateverTheCase() {
        ApiException e = assertThrows(ApiException.class, () -> Moderation.checkProtected("HYTALE:ADMIN", List.of("hytale:Admin")));
        assertEquals(403, e.status());
        assertEquals("group_protected", e.code());
        assertDoesNotThrow(() -> Moderation.checkProtected("Default", List.of("hytale:Admin")));
        assertDoesNotThrow(() -> Moderation.checkProtected("hytale:Admin", List.of()));
    }

    @Test
    void groupsMustBeRegistered() {
        assertEquals("hytale:Builder", Moderation.known("HYTALE:builder", Set.of("Default", "hytale:Builder")));
        ApiException e = assertThrows(ApiException.class, () -> Moderation.known("Mods", Set.of("Default")));
        assertEquals(404, e.status());
        assertEquals("group_not_found", e.code());
    }

    @Test
    void banLengthFollowsMaxBanMinutes() {
        Instant now = Instant.parse("2026-10-16T12:00:00Z");
        assertNull(Moderation.until(null, now, 0));
        assertEquals(Instant.parse("2026-10-16T13:00:00Z"), Moderation.until(60, now, 0));
        assertEquals(Instant.parse("2026-10-16T13:00:00Z"), Moderation.until(60, now, 60));
        assertEquals("ban_too_long", assertThrows(ApiException.class, () -> Moderation.until(null, now, 60)).code());
        assertEquals("ban_too_long", assertThrows(ApiException.class, () -> Moderation.until(61, now, 60)).code());
    }

    @Test
    void theReasonNamesTheKeyOnlyWhenAsked() {
        assertEquals("Griefing", Moderation.reasonFor("Griefing", PlayersText.KICK_REASON, "panel", false));
        assertEquals("Griefing (API key panel)", Moderation.reasonFor("Griefing", PlayersText.KICK_REASON, "panel", true));
        assertEquals("You were kicked by the server staff.", Moderation.reasonFor(" ", PlayersText.KICK_REASON, "panel", false));
        assertEquals("Banned by the server staff.", Moderation.reasonFor(null, PlayersText.BAN_REASON, "panel", false));
    }

    @Test
    void aBanViewSaysWhetherItIsPermanent() {
        UUID id = UUID.randomUUID();
        JsonObject o = new Moderation.BanView(id, "Steve", Moderation.API_AUTHOR, "panel", "Griefing",
                Instant.parse("2026-10-16T12:00:00Z"), null, true).json();
        assertEquals(id.toString(), o.get("uuid").getAsString());
        assertTrue(o.get("permanent").getAsBoolean());
        assertTrue(o.get("until").isJsonNull());
        assertEquals("panel", o.get("key").getAsString());
        JsonObject timed = new Moderation.BanView(id, null, null, null, null, null, Instant.parse("2026-10-17T12:00:00Z"), false).json();
        assertFalse(timed.get("permanent").getAsBoolean());
        assertEquals("2026-10-17T12:00:00Z", timed.get("until").getAsString());
    }
}
