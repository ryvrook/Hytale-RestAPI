package dev.ryvrook.restapi.control;

import dev.ryvrook.restapi.http.ApiException;
import dev.ryvrook.restapi.text.Messages;

import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Runs work on a world thread and waits for it from the request thread, never longer than the timeout. A world
 * is an Executor, which is all this needs, so the tests run it on any executor. Past the timeout the answer is
 * 504 world_timeout and the work may still run later on the world thread.
 */
final class OnWorld {
    private OnWorld() {
    }

    static <T> T call(Executor world, String name, long timeoutMs, Callable<T> work) {
        CompletableFuture<T> f = new CompletableFuture<>();
        try {
            world.execute(() -> {
                try {
                    f.complete(work.call());
                } catch (Throwable t) {
                    f.completeExceptionally(t);
                }
            });
        } catch (RejectedExecutionException e) {
            throw ApiException.notFound("world_not_found", Messages.get(ControlText.WORLD_NOT_FOUND, "world", name));
        }
        try {
            return f.get(timeoutMs, TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            throw new ApiException(504, "world_timeout", Messages.get(ControlText.WORLD_TIMEOUT, "world", name, "ms", timeoutMs));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ApiException(503, "interrupted", Messages.get(ControlText.INTERRUPTED));
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof ApiException a) {
                throw a;
            }
            if (cause instanceof RuntimeException r) {
                throw r;
            }
            if (cause instanceof Error err) {
                throw err;
            }
            throw new IllegalStateException(cause);
        }
    }
}
