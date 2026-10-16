package dev.ryvrook.restapi.siblings;

import dev.ryvrook.restapi.auth.Scope;
import dev.ryvrook.restapi.http.Access;
import dev.ryvrook.restapi.http.Response;
import dev.ryvrook.restapi.http.Route;
import dev.ryvrook.restapi.http.Router;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class SiblingsTest {
    private final Map<Sibling, PluginProbe.Found> installed = new EnumMap<>(Sibling.class);
    private final Map<Sibling, Mode> modes = new EnumMap<>(Sibling.class);
    private final Router router = new Router();
    private final List<String> log = new ArrayList<>();
    private final List<String> calls = new ArrayList<>();

    private final class Unit implements SiblingUnit {
        private final Sibling s;

        Unit(Sibling s) {
            this.s = s;
        }

        @Override
        public List<Route> bind(dev.ryvrook.restapi.RestApi api) {
            calls.add("bind " + s.id());
            return List.of(Route.of(s.group(), "GET", "/api/" + s.id() + "/status", s.scope(), Access.READ, "status",
                    c -> Response.text(200, s.id())));
        }

        @Override
        public void unbind() {
            calls.add("unbind " + s.id());
        }
    }

    private Siblings make() {
        return new Siblings(installed::get, router, Unit::new, s -> modes.getOrDefault(s, Mode.AUTO), () -> null, log::add);
    }

    private static PluginProbe.Found running(String version) {
        return new PluginProbe.Found(new Object(), version, "ENABLED");
    }

    @Test
    void nothingInstalledMountsNothing() {
        Siblings s = make();
        s.check();
        assertTrue(router.routes().isEmpty());
        assertTrue(s.table().stream().noneMatch(Presence::bound));
        assertEquals("not installed", s.presence(Sibling.DOMINION).reason());
        assertTrue(log.isEmpty(), "absent siblings in auto stay quiet");
    }

    @Test
    void aRunningSiblingMountsItsGroup() {
        installed.put(Sibling.DOMINION, running("0.7.0"));
        Siblings s = make();
        s.check();
        Presence p = s.presence(Sibling.DOMINION);
        assertTrue(p.bound());
        assertEquals(1, p.routes());
        assertTrue(router.mounted("suite.dominion"));
        assertEquals(List.of("bind dominion"), calls);
        assertTrue(log.getFirst().contains("bound Dominion 0.7.0"), log.getFirst());
        s.check();
        assertEquals(List.of("bind dominion"), calls, "a second check with nothing changed binds nothing");
    }

    @Test
    void goingAwayUnmountsTheGroup() {
        installed.put(Sibling.COINAGE, running("1.3.0"));
        Siblings s = make();
        s.check();
        installed.remove(Sibling.COINAGE);
        s.check();
        assertFalse(router.mounted("suite.coinage"));
        assertEquals(List.of("bind coinage", "unbind coinage"), calls);
        assertEquals("not installed", s.presence(Sibling.COINAGE).reason());
    }

    @Test
    void anOldVersionBindsOnlyUnderAlways() {
        installed.put(Sibling.FEATS, running("1.6.0"));
        Siblings s = make();
        s.check();
        assertFalse(s.presence(Sibling.FEATS).bound());
        assertEquals("version 1.6.0 is older than 1.7.0", s.presence(Sibling.FEATS).reason());
        modes.put(Sibling.FEATS, Mode.ALWAYS);
        s.check();
        assertTrue(s.presence(Sibling.FEATS).bound());
        assertTrue(s.presence(Sibling.FEATS).reason().contains("always"));
    }

    @Test
    void neverKeepsTheRoutesAwayAndUnbindsOnChange() {
        installed.put(Sibling.ATLAS, running("1.8.0"));
        Siblings s = make();
        s.check();
        assertTrue(router.mounted("suite.atlas"));
        modes.put(Sibling.ATLAS, Mode.NEVER);
        s.check();
        assertFalse(router.mounted("suite.atlas"));
        assertEquals("siblings.atlas is never", s.presence(Sibling.ATLAS).reason());
    }

    @Test
    void aSiblingNotYetEnabledWaits() {
        installed.put(Sibling.LULLABY, new PluginProbe.Found(new Object(), "1.1.0", "START"));
        Siblings s = make();
        s.check();
        assertEquals("its state is START", s.presence(Sibling.LULLABY).reason());
    }

    @Test
    void anOldCopyReloadedUnderAlwaysNamesTheReload() {
        modes.put(Sibling.DOMINION, Mode.ALWAYS);
        installed.put(Sibling.DOMINION, running("0.6.2"));
        Siblings s = make();
        s.check();
        installed.put(Sibling.DOMINION, running("0.6.2"));
        s.check();
        Presence p = s.presence(Sibling.DOMINION);
        assertFalse(p.bound());
        assertTrue(p.reason().contains("/plugin reload Ryvrook:RestAPI"), p.reason());
        assertEquals(List.of("bind dominion", "unbind dominion"), calls);
        assertTrue(log.getLast().contains("/plugin reload"), "an old copy is not reloading RestAPI, so it is logged");
    }

    @Test
    void aReloadOfASiblingWithHooksStaysQuiet() {
        List<String> quiet = new ArrayList<>();
        installed.put(Sibling.DOMINION, running("0.7.0"));
        Siblings s = new Siblings(installed::get, router, Unit::new, x -> Mode.AUTO, () -> null, log::add, quiet::add);
        s.check();
        assertEquals(1, log.size());
        installed.put(Sibling.DOMINION, new PluginProbe.Found(new Object(), "0.7.0", "SHUTDOWN"));
        s.check();
        installed.put(Sibling.DOMINION, running("0.7.0"));
        s.check();
        Presence p = s.presence(Sibling.DOMINION);
        assertFalse(p.bound());
        assertTrue(p.reason().contains("reloads RestAPI in a moment"), p.reason());
        assertEquals(1, log.size(), "the reload on its way back logs nothing at info");
        assertEquals(1, quiet.size());
    }

    @Test
    void hooksStartAtTheVersionInTheTable() {
        assertTrue(Sibling.FEATS.reloadsDependents("1.7.0"));
        assertTrue(Sibling.FEATS.reloadsDependents("1.10.0"));
        assertFalse(Sibling.FEATS.reloadsDependents("1.6.0"));
        assertFalse(Sibling.FEATS.reloadsDependents(null));
        for (Sibling x : Sibling.values()) {
            assertFalse(dev.ryvrook.restapi.util.Versions.below(x.minimum(), x.hooksSince()),
                    x + " binds at its minimum through hooks");
        }
    }

    @Test
    void anAlwaysSiblingThatIsMissingIsLogged() {
        modes.put(Sibling.TRACKER, Mode.ALWAYS);
        Siblings s = make();
        s.check();
        assertEquals(1, log.size());
        assertTrue(log.getFirst().contains("TrackerAPI"), log.getFirst());
        s.check();
        assertEquals(1, log.size(), "the same reason is logged once");
    }

    @Test
    void aBuildWithoutTheUnitSaysSo() {
        installed.put(Sibling.CHRONICLE, running("1.5.0"));
        Siblings s = new Siblings(installed::get, router, x -> null, x -> Mode.AUTO, () -> null, log::add);
        s.check();
        assertEquals("this build of RestAPI has no routes for it yet", s.presence(Sibling.CHRONICLE).reason());
    }

    @Test
    void aUnitMayNotServeOutsideItsPathOrScope() {
        installed.put(Sibling.COINAGE, running("1.3.0"));
        SiblingUnit rogue = api -> List.of(Route.of(Sibling.COINAGE.group(), "GET", "/api/players", Sibling.COINAGE.scope(),
                Access.READ, "x", c -> Response.text(200, "")));
        Siblings s = new Siblings(installed::get, router, x -> rogue, x -> Mode.AUTO, () -> null, log::add);
        s.check();
        assertFalse(s.presence(Sibling.COINAGE).bound());
        assertTrue(router.routes().isEmpty());
    }

    @Test
    void aWriteScopeIsAcceptedOnlyWhereTheSiblingHasOne() {
        installed.put(Sibling.COINAGE, running("1.3.0"));
        installed.put(Sibling.FEATS, running("1.7.0"));
        SiblingUnit coinage = api -> List.of(Route.of("suite.coinage", "POST", "/api/coinage/give", Scope.COINAGE_WRITE,
                Access.DANGEROUS, "x", c -> Response.text(200, "")));
        SiblingUnit feats = api -> List.of(Route.of("suite.feats", "POST", "/api/feats/x", Scope.COINAGE_WRITE,
                Access.DANGEROUS, "x", c -> Response.text(200, "")));
        Siblings s = new Siblings(installed::get, router, x -> x == Sibling.COINAGE ? coinage : feats, x -> Mode.AUTO,
                () -> null, log::add);
        s.check();
        assertTrue(s.presence(Sibling.COINAGE).bound());
        assertFalse(s.presence(Sibling.FEATS).bound());
    }

    @Test
    void aUnitThatThrowsIsRetriedOnTheNextCheck() {
        installed.put(Sibling.TRACKER, running("1.1.0"));
        int[] tries = {0};
        SiblingUnit flaky = api -> {
            if (tries[0]++ == 0) {
                throw new IllegalStateException("not ready");
            }
            return List.of(Route.of("suite.tracker", "GET", "/api/tracker/kinds", Scope.TRACKER, Access.READ, "x",
                    c -> Response.text(200, "")));
        };
        Siblings s = new Siblings(installed::get, router, x -> flaky, x -> Mode.AUTO, () -> null, log::add);
        s.check();
        assertTrue(s.presence(Sibling.TRACKER).reason().contains("not ready"));
        s.check();
        assertTrue(s.presence(Sibling.TRACKER).bound());
    }

    @Test
    void listenersHearEveryChange() {
        installed.put(Sibling.ATLAS, running("1.8.0"));
        Siblings s = make();
        List<Presence> heard = new ArrayList<>();
        s.listen(heard::add);
        s.check();
        s.check();
        installed.remove(Sibling.ATLAS);
        s.check();
        List<Presence> atlas = heard.stream().filter(p -> p.sibling() == Sibling.ATLAS).toList();
        assertEquals(2, atlas.size());
        assertTrue(atlas.get(0).bound());
        assertFalse(atlas.get(1).bound());
    }

    @Test
    void closeUnbindsEverything() {
        installed.put(Sibling.ATLAS, running("1.8.0"));
        installed.put(Sibling.LULLABY, running("1.1.0"));
        Siblings s = make();
        s.check();
        s.close();
        assertTrue(router.routes().isEmpty());
    }

    @Test
    void modesParseAndUnknownWordsReadAsAuto() {
        assertEquals(Mode.ALWAYS, Mode.parse(" Always "));
        assertEquals(Mode.NEVER, Mode.parse("never"));
        assertEquals(Mode.AUTO, Mode.parse("sometimes"));
        assertFalse(Mode.known("sometimes"));
    }

    @Test
    void everySiblingHasItsOwnPathScopeAndGroup() {
        java.util.Set<String> seen = new java.util.HashSet<>();
        for (Sibling s : Sibling.values()) {
            assertTrue(seen.add(s.id()));
            assertEquals("suite." + s.id(), s.group());
            assertTrue(s.unitClass().startsWith("dev.ryvrook.restapi.suite." + s.id() + "."), s.unitClass());
        }
    }
}
