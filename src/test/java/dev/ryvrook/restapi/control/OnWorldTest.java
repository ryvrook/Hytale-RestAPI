package dev.ryvrook.restapi.control;

import dev.ryvrook.restapi.http.ApiException;
import org.junit.jupiter.api.Test;

import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;

import static org.junit.jupiter.api.Assertions.*;

class OnWorldTest {
    private final Executor direct = Runnable::run;

    @Test
    void theAnswerComesBack() {
        assertEquals(42, OnWorld.call(direct, "default", 1000, () -> 42));
    }

    @Test
    void aStuckWorldIs504() {
        Executor never = r -> { };
        long start = System.currentTimeMillis();
        ApiException e = assertThrows(ApiException.class, () -> OnWorld.call(never, "default", 150, () -> 1));
        assertEquals(504, e.status());
        assertEquals("world_timeout", e.code());
        assertTrue(e.getMessage().contains("default") && e.getMessage().contains("150"), e.getMessage());
        assertTrue(System.currentTimeMillis() - start >= 140);
    }

    @Test
    void aWorldThatIsGoneIs404() {
        Executor dead = r -> {
            throw new RejectedExecutionException("stopped");
        };
        ApiException e = assertThrows(ApiException.class, () -> OnWorld.call(dead, "arena", 100, () -> 1));
        assertEquals("world_not_found", e.code());
    }

    @Test
    void errorsFromTheWorkPassThrough() {
        ApiException mine = new ApiException(400, "bad_value", "x");
        assertSame(mine, assertThrows(ApiException.class, () -> OnWorld.call(direct, "w", 100, () -> {
            throw mine;
        })));
        assertThrows(IllegalStateException.class, () -> OnWorld.call(direct, "w", 100, () -> {
            throw new IllegalStateException("boom");
        }));
    }
}
