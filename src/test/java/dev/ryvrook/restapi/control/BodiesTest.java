package dev.ryvrook.restapi.control;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.ryvrook.restapi.http.ApiException;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class BodiesTest {
    private static JsonObject json(String s) {
        return JsonParser.parseString(s).getAsJsonObject();
    }

    private static void bad(String field, Runnable r) {
        ApiException e = assertThrows(ApiException.class, r::run);
        assertEquals(400, e.status());
        assertTrue(e.getMessage().startsWith(field), e.getMessage());
    }

    @Test
    void typedFieldsAreCheckedNotGuessed() {
        JsonObject o = json("{\"n\":5,\"f\":2.5,\"b\":true,\"t\":\"hi\",\"l\":[\"a\",\"b\"],\"z\":null}");
        assertEquals(5, Bodies.optInt(o, "n", 0, 10));
        assertNull(Bodies.optInt(o, "z", 0, 10));
        assertNull(Bodies.optInt(o, "missing", 0, 10));
        assertEquals(2.5, Bodies.optNumber(o, "f", 0, 24));
        assertTrue(Bodies.optBool(o, "b"));
        assertEquals("hi", Bodies.optText(o, "t", 10));
        assertEquals(List.of("a", "b"), Bodies.optNames(o, "l"));
        bad("n", () -> Bodies.optInt(o, "n", 6, 10));
        bad("f", () -> Bodies.optInt(o, "f", 0, 10));
        bad("t", () -> Bodies.optInt(o, "t", 0, 10));
        bad("n", () -> Bodies.optBool(o, "n"));
        bad("t", () -> Bodies.optText(o, "t", 1));
        bad("t", () -> Bodies.optNames(o, "t"));
        bad("f", () -> Bodies.optNumber(o, "f", 0, 1));
        assertTrue(assertThrows(ApiException.class, () -> Bodies.optInt(o, "n", 6, 10)).getMessage()
                .contains("from 6 to 10"));
    }

    @Test
    void timeTakesHourOrProgressButNotBoth() {
        assertEquals(0.25, TimeBody.dayProgress(json("{\"hour\":6}")));
        assertEquals(1.0, TimeBody.dayProgress(json("{\"hour\":24}")));
        assertEquals(0.75, TimeBody.dayProgress(json("{\"dayProgress\":0.75}")));
        assertThrows(ApiException.class, () -> TimeBody.dayProgress(json("{}")));
        assertThrows(ApiException.class, () -> TimeBody.dayProgress(json("{\"hour\":6,\"dayProgress\":0.5}")));
        assertThrows(ApiException.class, () -> TimeBody.dayProgress(json("{\"hour\":25}")));
        assertThrows(ApiException.class, () -> TimeBody.dayProgress(json("{\"dayProgress\":-0.1}")));
    }

    @Test
    void stopNeedsTheConfirmWord() {
        ApiException e = assertThrows(ApiException.class, () -> StopBody.parse(json("{}"), 0, 3600, true, 8));
        assertEquals("confirm_required", e.code());
        assertThrows(ApiException.class, () -> StopBody.parse(json("{\"confirm\":\"yes\"}"), 0, 3600, true, 8));
        assertThrows(ApiException.class, () -> StopBody.parse(json("{\"confirm\":true}"), 0, 3600, true, 8));
    }

    @Test
    void stopDefaultsAndRestart() {
        StopBody plain = StopBody.parse(json("{\"confirm\":\"stop\"}"), 10, 3600, true, 8);
        assertEquals(new StopBody(0, false, null, 10, true), plain);
        StopBody restart = StopBody.parse(json("{\"confirm\":\"stop\",\"restart\":true,\"delaySeconds\":5,\"announce\":false,\"reason\":\" Update \"}"),
                0, 3600, true, 8);
        assertEquals(new StopBody(8, true, "Update", 5, false), restart);
        assertEquals(3, StopBody.parse(json("{\"confirm\":\"stop\",\"restart\":true,\"exitCode\":3}"), 0, 3600, true, 8).exitCode());
        assertTrue(StopBody.parse(json("{\"confirm\":\"stop\",\"exitCode\":8}"), 0, 3600, true, 8).restart());
        assertThrows(ApiException.class, () -> StopBody.parse(json("{\"confirm\":\"stop\",\"delaySeconds\":61}"), 0, 60, true, 8));
        assertThrows(ApiException.class, () -> StopBody.parse(json("{\"confirm\":\"stop\",\"exitCode\":256}"), 0, 60, true, 8));
        assertEquals(60, StopBody.parse(json("{\"confirm\":\"stop\"}"), 300, 60, true, 8).delaySeconds());
    }

    @Test
    void configPatchNeedsAField() {
        ApiException e = assertThrows(ApiException.class, () -> ConfigPatch.parse(json("{}"), 32));
        assertTrue(e.getMessage().contains("maxPlayers"));
        assertEquals(new ConfigPatch(null, "Hi", 40, null, null), ConfigPatch.parse(json("{\"motd\":\"Hi\",\"maxPlayers\":40}"), 32));
        assertThrows(ApiException.class, () -> ConfigPatch.parse(json("{\"maxViewRadius\":33}"), 32));
        assertThrows(ApiException.class, () -> ConfigPatch.parse(json("{\"maxPlayers\":0}"), 32));
        assertThrows(ApiException.class, () -> ConfigPatch.parse(json("{\"name\":\"  \"}"), 32));
        assertThrows(ApiException.class, () -> ConfigPatch.parse(json("{\"requireJoinPermission\":\"yes\"}"), 32));
    }
}
