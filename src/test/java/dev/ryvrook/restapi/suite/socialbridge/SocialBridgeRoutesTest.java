package dev.ryvrook.restapi.suite.socialbridge;

import com.google.gson.JsonObject;
import dev.ryvrook.restapi.auth.Scope;
import dev.ryvrook.restapi.http.Access;
import dev.ryvrook.restapi.http.Response;
import dev.ryvrook.restapi.http.Route;
import dev.ryvrook.restapi.suite.Calls;
import dev.ryvrook.socialbridge.api.Announcement;
import dev.ryvrook.socialbridge.api.LinkEvent;
import dev.ryvrook.socialbridge.api.SocialBridgeApi;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;

class SocialBridgeRoutesTest {
    static final UUID ALICE = UUID.fromString("9b2f4c1e-0000-4000-8000-000000000001");

    /** SocialBridge 0.2.0 with Alice linked and a Discord service that takes posts unless refuse is set. */
    static final class FakeBridge extends SocialBridgeApi {
        final List<String> channels = new ArrayList<>();
        final List<Announcement> posts = new ArrayList<>();
        boolean refuse;

        @Override public boolean post(String channel, Announcement a) {
            if (refuse) {
                return false;
            }
            channels.add(channel);
            posts.add(a);
            return true;
        }
        @Override public String linkedUser(String service, UUID player) {
            return DISCORD.equals(service) && ALICE.equals(player) ? "123456789012345678" : null;
        }
        @Override public UUID linkedPlayer(String service, String userId) {
            return DISCORD.equals(service) && "123456789012345678".equals(userId) ? ALICE : null;
        }
        @Override public Runnable onLink(Consumer<LinkEvent> listener) { return () -> { }; }
        @Override public String version() { return "0.2.0"; }
    }

    FakeBridge bridge;
    AtomicReference<SocialBridgeApi> api;
    Calls calls;

    @BeforeEach
    void setUp() {
        bridge = new FakeBridge();
        api = new AtomicReference<>(bridge);
        calls = new Calls(SocialBridgeRoutes.routes(api::get));
    }

    @Test
    void routesStayUnderSocialBridgeWithReadOrWriteScope() {
        for (Route r : SocialBridgeRoutes.routes(() -> null)) {
            assertTrue(r.pattern().equals("/api/socialbridge") || r.pattern().startsWith("/api/socialbridge/"));
            if (r.method().equals("GET")) {
                assertEquals(Scope.SOCIALBRIDGE, r.scope());
                assertEquals(Access.READ, r.access());
            } else {
                assertEquals(Scope.SOCIALBRIDGE_WRITE, r.scope());
                assertEquals(Access.WRITE, r.access());
            }
        }
    }

    @Test
    void summaryGivesTheVersions() {
        JsonObject o = Calls.json(calls.get("/api/socialbridge"));
        assertEquals("0.2.0", o.get("version").getAsString());
        assertEquals(1, o.get("apiVersion").getAsInt());
    }

    @Test
    void linksAnswerBothWays() {
        JsonObject o = Calls.json(calls.get("/api/socialbridge/links/" + ALICE));
        assertEquals("123456789012345678", o.get("user").getAsString());
        JsonObject back = Calls.json(calls.get("/api/socialbridge/users/123456789012345678"));
        assertEquals(ALICE.toString(), back.get("player").getAsString());
        assertEquals(404, calls.get("/api/socialbridge/links/" + UUID.randomUUID()).status());
        assertEquals(404, calls.get("/api/socialbridge/users/1").status());
    }

    @Test
    void anOfflineNameIsNotFound() {
        Response r = calls.get("/api/socialbridge/links/Nobody");
        assertEquals(404, r.status());
        assertEquals("player_not_found", Calls.json(r).get("error").getAsString());
    }

    @Test
    void postHandsAnEmbedToTheChannel() {
        Response r = calls.post("/api/socialbridge/post",
                "{\"channel\":\"Status\",\"kind\":\"restock\",\"text\":\"The market restocked\",\"title\":\"Market\",\"colour\":\"#3BA55C\"}");
        assertEquals(200, r.status());
        assertEquals(List.of("status"), bridge.channels);
        Announcement a = bridge.posts.get(0);
        assertEquals("restock", a.kind());
        assertEquals("Market", a.title());
        assertEquals(0x3BA55C, a.colour());
    }

    @Test
    void aPlainPostGoesToEvents() {
        assertEquals(200, calls.post("/api/socialbridge/post", "{\"text\":\"hello\"}").status());
        assertEquals(List.of("events"), bridge.channels);
        assertNull(bridge.posts.get(0).title());
        assertEquals(-1, bridge.posts.get(0).colour());
    }

    @Test
    void badPostsAreRefused() {
        assertEquals(400, calls.post("/api/socialbridge/post", "{}").status());
        assertEquals(400, calls.post("/api/socialbridge/post", "{\"text\":\"x\",\"colour\":\"green\"}").status());
        assertEquals(400, calls.post("/api/socialbridge/post", "{\"text\":5}").status());
        bridge.refuse = true;
        Response r = calls.post("/api/socialbridge/post", "{\"text\":\"x\"}");
        assertEquals(409, r.status());
        assertEquals("not_posted", Calls.json(r).get("error").getAsString());
    }

    @Test
    void aLongTextIsCut() {
        String text = "a".repeat(5000);
        calls.post("/api/socialbridge/post", "{\"text\":\"" + text + "\"}");
        assertEquals(SocialBridgeRoutes.MAX_TEXT, bridge.posts.get(0).text().length());
    }

    @Test
    void goneAnswers503() {
        api.set(null);
        Response r = calls.get("/api/socialbridge");
        assertEquals(503, r.status());
        assertEquals("sibling_gone", Calls.json(r).get("error").getAsString());
    }
}
