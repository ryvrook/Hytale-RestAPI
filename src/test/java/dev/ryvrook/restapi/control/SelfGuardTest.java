package dev.ryvrook.restapi.control;

import dev.ryvrook.restapi.http.ApiException;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class SelfGuardTest {
    @Test
    void pluginIdsAreGroupColonName() {
        assertEquals(new PluginId("Ryvrook", "Lullaby"), PluginId.parse("Ryvrook:Lullaby"));
        assertEquals(new PluginId("Ryvrook", "Lullaby"), PluginId.parse(" Ryvrook:Lullaby "));
        assertEquals("Ryvrook:Lullaby", PluginId.parse("Ryvrook:Lullaby").toString());
        for (String bad : new String[]{null, "", "Lullaby", ":Lullaby", "Ryvrook:", "a:b:c", "a/b:c"}) {
            ApiException e = assertThrows(ApiException.class, () -> PluginId.parse(bad), String.valueOf(bad));
            assertEquals(400, e.status());
            assertEquals("bad_plugin_id", e.code());
        }
    }

    @Test
    void restApiIsRefusedInAnyCase() {
        for (String self : new String[]{"Ryvrook:RestAPI", "ryvrook:restapi", "RYVROOK:RESTAPI", " Ryvrook:RestApi"}) {
            PluginId id = PluginId.parse(self);
            assertTrue(SelfGuard.isSelf(id), self);
            ApiException e = assertThrows(ApiException.class, () -> SelfGuard.check(id));
            assertEquals(403, e.status());
            assertEquals("self_refused", e.code());
        }
    }

    @Test
    void otherPluginsPass() {
        for (String other : new String[]{"Ryvrook:Lullaby", "Ryvrook:RestAPI2", "Other:RestAPI", "Ryvrook:TrackerAPI"}) {
            PluginId id = PluginId.parse(other);
            assertFalse(SelfGuard.isSelf(id), other);
            assertDoesNotThrow(() -> SelfGuard.check(id));
        }
        assertFalse(SelfGuard.isSelf(null));
    }
}
