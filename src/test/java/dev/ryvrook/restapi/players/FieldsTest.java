package dev.ryvrook.restapi.players;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.ryvrook.restapi.http.ApiException;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class FieldsTest {
    private static JsonObject json(String s) {
        PlayersText.KICK_REASON.length();
        return JsonParser.parseString(s).getAsJsonObject();
    }

    @Test
    void textIsTrimmedAndLimited() {
        assertEquals("hello", Fields.text(json("{\"t\":\"  hello \"}"), "t", true, 10));
        assertNull(Fields.text(json("{}"), "t", false, 10));
        ApiException e = assertThrows(ApiException.class, () -> Fields.text(json("{}"), "t", true, 10));
        assertEquals("missing_field", e.code());
        assertEquals("The body needs t.", e.getMessage());
        e = assertThrows(ApiException.class, () -> Fields.text(json("{\"t\":\"12345678901\"}"), "t", true, 10));
        assertEquals("invalid_field", e.code());
        assertThrows(ApiException.class, () -> Fields.text(json("{\"t\":5}"), "t", true, 10));
        assertThrows(ApiException.class, () -> Fields.text(json("{\"t\":\"   \"}"), "t", true, 10));
    }

    @Test
    void numbersAreCheckedForTypeAndRange() {
        assertEquals(5, Fields.integer(json("{\"n\":5}"), "n", null, 1, 10));
        assertEquals(3, Fields.integer(json("{}"), "n", 3, 1, 10));
        assertEquals(400, assertThrows(ApiException.class, () -> Fields.integer(json("{\"n\":11}"), "n", null, 1, 10)).status());
        assertThrows(ApiException.class, () -> Fields.integer(json("{\"n\":1.5}"), "n", null, 1, 10));
        assertThrows(ApiException.class, () -> Fields.integer(json("{\"n\":\"5\"}"), "n", null, 1, 10));
        assertThrows(ApiException.class, () -> Fields.integer(json("{}"), "n", null, 1, 10));
        assertEquals(-2.5, Fields.number(json("{\"x\":-2.5}"), "x", null, -10, 10));
        ApiException e = assertThrows(ApiException.class, () -> Fields.number(json("{\"x\":20}"), "x", null, -10, 10));
        assertEquals("x must be between -10 and 10.", e.getMessage());
    }

    @Test
    void boolsListsAndColors() {
        assertTrue(Fields.bool(json("{\"b\":true}"), "b", null));
        assertFalse(Fields.bool(json("{}"), "b", false));
        assertThrows(ApiException.class, () -> Fields.bool(json("{\"b\":\"true\"}"), "b", null));
        assertEquals(List.of("a", "b"), Fields.strings(json("{\"l\":[\"a\",\" b \",\"\"]}"), "l", true));
        assertNull(Fields.strings(json("{}"), "l", false));
        assertThrows(ApiException.class, () -> Fields.strings(json("{\"l\":[1]}"), "l", true));
        assertEquals("#ffaa00", Fields.color(json("{\"color\":\"#FFAA00\"}")));
        assertNull(Fields.color(json("{}")));
        assertThrows(ApiException.class, () -> Fields.color(json("{\"color\":\"red\"}")));
    }
}
