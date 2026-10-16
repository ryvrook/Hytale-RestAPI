package dev.ryvrook.restapi.players;

import dev.ryvrook.restapi.http.ApiException;
import org.junit.jupiter.api.Test;

import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;

import static org.junit.jupiter.api.Assertions.*;

class WorldCallTest {
    @Test
    void theTaskRunsOnTheExecutorAndItsAnswerComesBack() {
        Executor direct = Runnable::run;
        assertEquals(42, WorldCall.run(direct, "default", 1000, () -> 42));
    }

    @Test
    void aWorldThatNeverRunsTheTaskGives504() {
        Executor never = r -> { };
        long start = System.nanoTime();
        ApiException e = assertThrows(ApiException.class, () -> WorldCall.run(never, "default", 150, () -> 1));
        assertEquals(504, e.status());
        assertEquals("world_timeout", e.code());
        assertTrue(e.getMessage().contains("default"));
        assertTrue((System.nanoTime() - start) / 1_000_000 < 2_000, "the wait is bounded");
    }

    @Test
    void anApiErrorFromTheWorldThreadReachesTheCallerUnchanged() {
        Executor direct = Runnable::run;
        ApiException e = assertThrows(ApiException.class, () -> WorldCall.run(direct, "default", 1000, () -> {
            throw new ApiException(409, "not_in_world", "x");
        }));
        assertEquals(409, e.status());
        assertEquals("not_in_world", e.code());
        assertThrows(IllegalStateException.class, () -> WorldCall.run(direct, "w", 1000, () -> {
            throw new IllegalStateException("boom");
        }));
    }

    @Test
    void aRejectedTaskGives503() {
        Executor closed = r -> {
            throw new RejectedExecutionException();
        };
        ApiException e = assertThrows(ApiException.class, () -> WorldCall.run(closed, "nether", 1000, () -> 1));
        assertEquals(503, e.status());
        assertEquals("world_unavailable", e.code());
    }
}
