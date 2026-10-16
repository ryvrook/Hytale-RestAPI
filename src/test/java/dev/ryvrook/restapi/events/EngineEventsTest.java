package dev.ryvrook.restapi.events;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class EngineEventsTest {
    @Test
    void commandLinesParseInBothShapes() {
        assertArrayEquals(new String[]{"Console", "say hello"},
                EngineEvents.commandOf("%s executed command: %s", new Object[]{"Console", "say hello"}));
        assertArrayEquals(new String[]{"Steve", "tp Alex"},
                EngineEvents.commandOf("Steve executed command: /tp Alex", null));
        assertArrayEquals(new String[]{"RestAPI:grafana", "who"},
                EngineEvents.commandOf("RestAPI:grafana executed command: who\n", null));
        assertNull(EngineEvents.commandOf("Steve joined", null));
        assertNull(EngineEvents.commandOf(null, null));
        assertNull(EngineEvents.commandOf("Steve executed command: /", null));
    }

    @Test
    void pluginDiffNamesEveryChange() {
        Map<String, String[]> before = new LinkedHashMap<>();
        before.put("Ryvrook:Lullaby", new String[]{"ENABLED", "1.0.1"});
        before.put("Ryvrook:Feats", new String[]{"ENABLED", "1.6.0"});
        before.put("Ryvrook:Atlas", new String[]{"ENABLED", "1.7.0"});
        Map<String, String[]> after = new LinkedHashMap<>();
        after.put("Ryvrook:Lullaby", new String[]{"DISABLED", "1.0.1"});
        after.put("Ryvrook:Atlas", new String[]{"ENABLED", "1.7.0"});
        after.put("Ryvrook:Coinage", new String[]{"SETUP", "1.2.0"});
        List<EngineEvents.PluginChange> d = EngineEvents.diff(before, after);
        assertEquals(List.of(
                new EngineEvents.PluginChange("Ryvrook:Lullaby", "1.0.1", "ENABLED", "DISABLED"),
                new EngineEvents.PluginChange("Ryvrook:Coinage", "1.2.0", "NONE", "SETUP"),
                new EngineEvents.PluginChange("Ryvrook:Feats", "1.6.0", "ENABLED", "NONE")), d);
        assertTrue(EngineEvents.diff(after, after).isEmpty());
    }

    @Test
    void playerFieldsAreNameAndUuid() {
        var o = EngineEvents.player("Steve", new java.util.UUID(0, 1));
        assertEquals("Steve", o.get("player").getAsString());
        assertEquals("00000000-0000-0000-0000-000000000001", o.get("uuid").getAsString());
    }
}
