package dev.ryvrook.restapi.dashboard;

import dev.ryvrook.restapi.auth.Scope;
import dev.ryvrook.restapi.http.Gate;
import dev.ryvrook.restapi.http.Request;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

class SessionStoreTest {
    private final AtomicLong now = new AtomicLong(1_000_000);
    private final AtomicBoolean writes = new AtomicBoolean(false);
    private final SessionStore store = new SessionStore(now::get, () -> 12 * 3600_000L, () -> 10 * 60_000L,
            writes::get, () -> 600);

    @Test
    void aLinkWorksOnceAndOpensASessionInTheNameOfWhoAsked() {
        String token = store.issue("Steve");
        assertTrue(token.matches("rdl_[A-Za-z0-9_-]{43}"), token);
        assertEquals(1, store.pendingLinks());
        SessionStore.Opened o = store.redeem(token);
        assertNotNull(o);
        assertTrue(o.cookie().matches("[A-Za-z0-9_-]{43}"));
        assertEquals("dashboard:Steve", o.session().principal());
        assertNull(store.redeem(token), "a link works once");
        assertEquals(0, store.pendingLinks());
        assertEquals(o.session(), store.resolve(o.cookie()));
        assertEquals(1, store.count());
    }

    @Test
    void anExpiredOrUnknownLinkOpensNothing() {
        String token = store.issue("console");
        now.addAndGet(10 * 60_000L + 1);
        assertNull(store.redeem(token));
        assertNull(store.redeem("rdl_" + "A".repeat(43)));
        assertNull(store.redeem("rapi_00000000_" + "A".repeat(43)));
        assertNull(store.redeem(null));
        assertEquals(0, store.count());
    }

    @Test
    void theStoreKeepsNoValueABrowserCouldReplay() throws Exception {
        String token = store.issue("Steve");
        SessionStore.Opened o = store.redeem(token);
        assertEquals(SessionStore.digest(o.cookie()), o.session().digest());
        assertNotEquals(o.cookie(), o.session().digest());
        assertFalse(o.session().toString().contains(o.cookie()));
    }

    @Test
    void aSessionEndsAfterTheIdleLimitAndUseKeepsItAlive() {
        SessionStore.Opened o = store.redeem(store.issue("Steve"));
        now.addAndGet(11 * 3600_000L);
        assertNotNull(store.resolve(o.cookie()), "used within the idle limit");
        now.addAndGet(11 * 3600_000L);
        assertNotNull(store.resolve(o.cookie()), "the last use counts, not the login");
        now.addAndGet(12 * 3600_000L + 1);
        assertNull(store.resolve(o.cookie()));
        assertEquals(0, store.count());
    }

    @Test
    void aSessionEndsAfterSevenDaysWhateverHappens() {
        SessionStore.Opened o = store.redeem(store.issue("Steve"));
        for (int i = 0; i < 14; i++) {
            now.addAndGet(12 * 3600_000L);
            assertNotNull(store.resolve(o.cookie()), "half day " + (i + 1));
        }
        now.addAndGet(1);
        assertNull(store.resolve(o.cookie()));
    }

    @Test
    void logoutEndsOneAndEndAllEndsEveryOneWithTheLinks() {
        SessionStore.Opened a = store.redeem(store.issue("a"));
        SessionStore.Opened b = store.redeem(store.issue("b"));
        String unused = store.issue("c");
        assertTrue(store.end(a.cookie()));
        assertFalse(store.end(a.cookie()));
        assertNull(store.resolve(a.cookie()));
        assertNotNull(store.resolve(b.cookie()));
        assertEquals(1, store.endAll());
        assertNull(store.resolve(b.cookie()));
        assertNull(store.redeem(unused), "turning the page off forgets the links too");
    }

    @Test
    void asCredentialsItStandsForAKeyWithEveryScopeAndItsOwnLimit() {
        SessionStore.Opened o = store.redeem(store.issue("Steve"));
        Request req = Request.of("GET", "/api/server", "127.0.0.1").with("Cookie", "theme=dark; restapi_session=" + o.cookie());
        Gate.Credentials.Found f = store.find(req);
        assertTrue(f.present());
        assertEquals("dashboard:Steve", f.key().name());
        assertTrue(f.key().scopes().containsAll(java.util.EnumSet.allOf(Scope.class)));
        assertEquals(600, f.key().perMinute());
        assertTrue(f.key().id().startsWith("s"));
        assertFalse(f.writes());
        writes.set(true);
        assertTrue(store.find(req).writes());

        assertFalse(store.find(Request.of("GET", "/", "127.0.0.1")).present());
        Gate.Credentials.Found stale = store.find(Request.of("GET", "/", "127.0.0.1").with("Cookie", "restapi_session=nope"));
        assertTrue(stale.present());
        assertNull(stale.key());
    }

    @Test
    void theCookieIsHttpOnlyStrictAndScopedToTheBasePath() {
        String c = SessionStore.cookie("v", "/restapi", false);
        assertEquals("restapi_session=v; Path=/restapi; HttpOnly; SameSite=Strict; Max-Age=604800", c);
        assertTrue(SessionStore.cookie("v", "/", true).endsWith("; Secure"));
        assertTrue(SessionStore.clearCookie("/", false).contains("Max-Age=0"));
    }
}
