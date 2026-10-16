package dev.ryvrook.restapi.players;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.hypixel.hytale.protocol.GameMode;
import dev.ryvrook.restapi.http.ApiException;
import org.junit.jupiter.api.Test;

import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class PlayerShapesTest {
    static {
        PlayersText.KICK_REASON.length();
    }

    private static JsonObject json(String s) {
        return JsonParser.parseString(s).getAsJsonObject();
    }

    @Test
    void pingIsWholeMilliseconds() {
        assertEquals(25, PlayerRead.pingMs(25_400, TimeUnit.MICROSECONDS));
        assertEquals(3, PlayerRead.pingMs(2.6, TimeUnit.MILLISECONDS));
        assertNull(PlayerRead.pingMs(Double.NaN, TimeUnit.MICROSECONDS));
        assertNull(PlayerRead.pingMs(-1, TimeUnit.MICROSECONDS));
    }

    @Test
    void sectionsParseByNameAndDefaultToAll() {
        assertEquals(EnumSet.allOf(PlayerRead.Section.class), PlayerRead.Section.parse(null));
        assertEquals(EnumSet.allOf(PlayerRead.Section.class), PlayerRead.Section.parse(List.of()));
        Set<PlayerRead.Section> two = PlayerRead.Section.parse(List.of("Hotbar", "backpack"));
        assertEquals(EnumSet.of(PlayerRead.Section.HOTBAR, PlayerRead.Section.BACKPACK), two);
        ApiException e = assertThrows(ApiException.class, () -> PlayerRead.Section.parse(List.of("pockets")));
        assertTrue(e.getMessage().contains("hotbar, storage, armor, utility, tool, backpack"), e.getMessage());
    }

    @Test
    void thePlayerShapeHasRoundedPositionAndNullsForUnknowns() {
        UUID id = UUID.randomUUID();
        JsonObject o = PlayerRead.basic(id, "Steve", "default", new double[]{1.23456, 64, -3.999}, 24);
        assertEquals(1.23, o.getAsJsonObject("position").get("x").getAsDouble());
        assertEquals(-4.0, o.getAsJsonObject("position").get("z").getAsDouble());
        assertEquals(24, o.get("pingMs").getAsInt());
        PlayerRead.addLive(o, PlayerRead.Live.NONE, true);
        assertTrue(o.get("gameMode").isJsonNull());
        assertTrue(o.getAsJsonObject("stats").get("mana").isJsonNull());
        JsonObject none = PlayerRead.basic(id, "Steve", null, null, null);
        assertTrue(none.get("position").isJsonNull());
        assertTrue(none.get("pingMs").isJsonNull());
        JsonObject live = new JsonObject();
        PlayerRead.addLive(live, new PlayerRead.Live("Creative", new PlayerRead.Stat(87.456, 100), null, null, null), false);
        assertEquals(87.46, live.getAsJsonObject("health").get("value").getAsDouble());
        assertFalse(live.has("stats"));
    }

    @Test
    void teleportTakesCoordinatesOrAPlayer() {
        PlayerActions.Destination d = PlayerActions.destination(json("{\"x\":1,\"y\":2,\"z\":3,\"world\":\"nether\",\"yaw\":90}"));
        assertEquals(1.0, d.x());
        assertEquals("nether", d.world());
        assertEquals(90.0, d.yaw());
        assertNull(d.pitch());
        assertFalse(d.toPlayer());
        assertTrue(PlayerActions.destination(json("{\"to\":\"Alex\"}")).toPlayer());
        assertEquals("missing_field", assertThrows(ApiException.class, () -> PlayerActions.destination(json("{\"x\":1,\"y\":2}"))).code());
        assertThrows(ApiException.class, () -> PlayerActions.destination(json("{\"x\":1,\"y\":2,\"z\":3,\"pitch\":120}")));
        assertThrows(ApiException.class, () -> PlayerActions.destination(json("{\"x\":1e9,\"y\":2,\"z\":3}")));
    }

    @Test
    void gameModesAreAdventureOrCreative() {
        assertEquals(GameMode.Creative, PlayerActions.gameMode("creative"));
        assertEquals(GameMode.Adventure, PlayerActions.gameMode(" Adventure "));
        assertThrows(ApiException.class, () -> PlayerActions.gameMode("Survival"));
        assertThrows(ApiException.class, () -> PlayerActions.gameMode(null));
    }
}
