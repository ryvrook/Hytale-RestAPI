package dev.ryvrook.restapi.server;

import dev.ryvrook.restapi.siblings.Mode;
import dev.ryvrook.restapi.siblings.Presence;
import dev.ryvrook.restapi.siblings.Sibling;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class HealthTest {
    @Test
    void okOnlyWhileRunningWithEveryWorldAnswering() {
        assertEquals("ok", Health.status("running", List.of()));
        assertEquals("stalled", Health.status("running", List.of("default")));
        assertEquals("booting", Health.status("booting", List.of()));
        assertEquals("stopping", Health.status("stopping", List.of()));
        assertEquals(200, Health.httpStatus("ok"));
        assertEquals(503, Health.httpStatus("stalled"));
        assertEquals(503, Health.httpStatus("booting"));
    }

    @Test
    void aWorldIsStalledWhenItsSampleWaitsTooLong() {
        WorldSample w = WorldSample.empty("default").waiting(1_000);
        assertFalse(w.stalled(10_000, 15_000));
        assertTrue(w.stalled(17_000, 15_000));
        assertFalse(WorldSample.empty("x").stalled(1_000_000, 15_000));
    }

    @Test
    void tpsFollowsTheEnginesFormula() {
        long step = 1_000_000_000L / 30;
        assertEquals(30.0, WorldSample.tps(10_000_000, step), 0.01, "a fast tick still counts as one step");
        assertEquals(20.0, WorldSample.tps(50_000_000, step), 0.01);
        assertEquals(-1, WorldSample.tps(0, step));
        assertEquals(-1, WorldSample.tps(Double.NaN, step));
    }

    @Test
    void theSiblingsSectionListsEveryMod() {
        var a = Health.siblings(List.of(
                new Presence(Sibling.DOMINION, Mode.AUTO, true, "0.6.2", "ENABLED", true, 9, null),
                new Presence(Sibling.LULLABY, Mode.NEVER, false, null, null, false, 0, "siblings.lullaby is never")));
        var d = a.get(0).getAsJsonObject();
        assertEquals("dominion", d.get("id").getAsString());
        assertEquals("Ryvrook:Dominion", d.get("plugin").getAsString());
        assertEquals("/api/dominion", d.get("path").getAsString());
        assertEquals(9, d.get("routes").getAsInt());
        assertEquals("never", a.get(1).getAsJsonObject().get("mode").getAsString());
    }

    @Test
    void worldJsonReportsAge() {
        WorldSample w = new WorldSample("default", 3, 30, 29.876, 12.345, 40.0, 900, 1200, 9_000, 0);
        var o = Health.world(w, 10_000, 15_000);
        assertEquals(1_000, o.get("sampledAgoMs").getAsLong());
        assertEquals(29.88, o.get("tps").getAsDouble());
        assertFalse(o.get("stalled").getAsBoolean());
    }
}
