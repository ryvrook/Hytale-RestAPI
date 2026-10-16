package dev.ryvrook.restapi.players;

import com.hypixel.hytale.server.core.universe.world.World;
import dev.ryvrook.restapi.http.ApiException;
import dev.ryvrook.restapi.text.Messages;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Supplier;

/**
 * Runs a task on a world's own thread and waits for it on the request thread, never longer than
 * players.worldTimeoutMs. Anything that goes through {@code Store.getComponent} must run here, the store asserts
 * its thread. A world that does not answer in time gives 504 world_timeout. The task is not taken back, so a
 * change may still happen after the 504.
 */
public final class WorldCall {
    private WorldCall() {
    }

    /** On the world's thread with the timeout from the settings. */
    public static <T> T on(World world, Supplier<T> task) {
        return run(world::execute, world.getName(), PlayersSettings.WORLD_TIMEOUT_MS.get(), task);
    }

    /**
     * The task through the executor, waited on for at most timeoutMs. An ApiException thrown by the task reaches
     * the caller as it is, so a task can answer 404 or 409 from the world thread.
     */
    public static <T> T run(Executor executor, String worldName, long timeoutMs, Supplier<T> task) {
        CompletableFuture<T> f = new CompletableFuture<>();
        try {
            executor.execute(() -> {
                try {
                    f.complete(task.get());
                } catch (Throwable t) {
                    f.completeExceptionally(t);
                }
            });
        } catch (RejectedExecutionException | IllegalStateException e) {
            throw new ApiException(503, "world_unavailable", Messages.get(PlayersText.WORLD_GONE, "world", worldName));
        }
        try {
            return f.get(timeoutMs, TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            throw new ApiException(504, "world_timeout",
                    Messages.get(PlayersText.WORLD_TIMEOUT, "world", worldName, "ms", timeoutMs));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ApiException(503, "interrupted", Messages.get(PlayersText.ENGINE));
        } catch (ExecutionException e) {
            Throwable c = e.getCause();
            if (c instanceof RuntimeException r) {
                throw r;
            }
            if (c instanceof Error err) {
                throw err;
            }
            throw new IllegalStateException(c);
        }
    }
}
