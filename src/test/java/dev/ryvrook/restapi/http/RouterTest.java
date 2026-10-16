package dev.ryvrook.restapi.http;

import dev.ryvrook.restapi.auth.Scope;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class RouterTest {
    private static Route get(String group, String pattern) {
        return Route.of(group, "GET", pattern, Scope.READ, Access.READ, "test", c -> Response.text(200, pattern));
    }

    @Test
    void matchesLiteralAndParameterSegments() {
        Router r = new Router();
        r.mount("players", List.of(get("players", "/api/players"), get("players", "/api/players/{player}")));
        Router.Found f = (Router.Found) r.match("GET", "/api/players/Steve");
        assertEquals("/api/players/{player}", f.route().pattern());
        assertEquals(Map.of("player", "Steve"), f.params());
        assertEquals("/api/players", ((Router.Found) r.match("GET", "/api/players/")).route().pattern());
    }

    @Test
    void aLiteralSegmentWinsOverAParameter() {
        Router r = new Router();
        r.mount("players", List.of(get("players", "/api/players/{player}"), get("players", "/api/players/online")));
        assertEquals("/api/players/online", ((Router.Found) r.match("GET", "/api/players/online")).route().pattern());
    }

    @Test
    void anotherMethodOnAKnownPathIs405WithAllow() {
        Router r = new Router();
        r.mount("core", List.of(get("core", "/api/server")));
        Router.Match m = r.match("POST", "/api/server");
        assertEquals(new Router.WrongMethod(Set.of("GET")), m);
        assertInstanceOf(Router.NotFound.class, r.match("GET", "/api/nothing"));
    }

    @Test
    void headIsAnsweredByGet() {
        Router r = new Router();
        r.mount("core", List.of(get("core", "/api/health")));
        assertInstanceOf(Router.Found.class, r.match("HEAD", "/api/health"));
    }

    @Test
    void groupsMountAndUnmountAsAUnit() {
        Router r = new Router();
        r.mount("core", List.of(get("core", "/api/server")));
        r.mount("suite.dominion", List.of(get("suite.dominion", "/api/dominion/factions"),
                get("suite.dominion", "/api/dominion/factions/{tag}")));
        assertEquals(3, r.routes().size());
        assertTrue(r.unmount("suite.dominion"));
        assertEquals(1, r.routes().size());
        assertInstanceOf(Router.NotFound.class, r.match("GET", "/api/dominion/factions"));
        assertFalse(r.unmount("suite.dominion"));
    }

    @Test
    void twoGroupsCannotServeTheSamePath() {
        Router r = new Router();
        r.mount("a", List.of(get("a", "/api/x/{id}")));
        assertThrows(IllegalStateException.class, () -> r.mount("b", List.of(get("b", "/api/x/{name}"))));
        assertEquals(1, r.routes().size());
    }

    @Test
    void aRouteMustNameTheGroupItIsMountedIn() {
        Router r = new Router();
        assertThrows(IllegalStateException.class, () -> r.mount("a", List.of(get("b", "/api/x"))));
    }

    @Test
    void emptySegmentsDoNotBindParameters() {
        Router r = new Router();
        r.mount("p", List.of(get("p", "/api/players/{player}/kick")));
        assertInstanceOf(Router.NotFound.class, r.match("GET", "/api/players//kick"));
    }
}
