package dev.ryvrook.restapi.console;

import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

class IdempotencyTest {
    private final AtomicLong now = new AtomicLong(1_000);
    private final Idempotency<String> store = new Idempotency<>(now::get);

    @Test
    void theFirstClaimRunsAndARetryGetsTheSameFuture() {
        var first = store.claim("admin", "k1", "say hi", 60_000);
        assertInstanceOf(Idempotency.Fresh.class, first);
        var again = store.claim("admin", "k1", "say hi", 60_000);
        assertInstanceOf(Idempotency.Existing.class, again);
        assertFalse(((Idempotency.Existing<String>) again).future().isDone(), "a retry during the run waits for it");
        ((Idempotency.Fresh<String>) first).future().complete("done");
        assertEquals("done", ((Idempotency.Existing<String>) again).future().join());
    }

    @Test
    void anotherKeyNameIsAnotherPair() {
        assertInstanceOf(Idempotency.Fresh.class, store.claim("admin", "k1", "say hi", 60_000));
        assertInstanceOf(Idempotency.Fresh.class, store.claim("panel", "k1", "say hi", 60_000));
    }

    @Test
    void theSamePairForAnotherCommandIsAConflict() {
        store.claim("admin", "k1", "say hi", 60_000);
        var c = store.claim("admin", "k1", "say bye", 60_000);
        assertInstanceOf(Idempotency.Conflict.class, c);
        assertEquals("say hi", ((Idempotency.Conflict<String>) c).firstCommand());
    }

    @Test
    void aPairIsForgottenAfterTheWindowFromWhenItFinished() {
        var first = (Idempotency.Fresh<String>) store.claim("admin", "k1", "say hi", 10_000);
        now.addAndGet(50_000);
        assertInstanceOf(Idempotency.Existing.class, store.claim("admin", "k1", "say hi", 10_000),
                "a run still going is never forgotten");
        first.future().complete("done");
        now.addAndGet(9_999);
        assertInstanceOf(Idempotency.Existing.class, store.claim("admin", "k1", "say hi", 10_000));
        now.addAndGet(1);
        assertInstanceOf(Idempotency.Fresh.class, store.claim("admin", "k1", "say hi", 10_000));
    }

    @Test
    void clearForgetsEverything() {
        store.claim("admin", "k1", "say hi", 10_000);
        store.clear();
        assertEquals(0, store.size());
    }
}
