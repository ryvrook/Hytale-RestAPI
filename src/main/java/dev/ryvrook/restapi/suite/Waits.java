package dev.ryvrook.restapi.suite;

import dev.ryvrook.restapi.http.ApiException;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Waits on a sibling future from the request thread, never longer than suite.waitMs. Siblings complete these on
 * their own IO threads, so the HTTP virtual thread only parks.
 */
public final class Waits {
    private Waits() {
    }

    public static <T> T await(CompletableFuture<T> future, String mod, int waitMs) {
        try {
            return future.get(waitMs, TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            throw new ApiException(504, "sibling_timeout", SuiteText.get(SuiteText.TIMEOUT, "mod", mod, "ms", waitMs));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ApiException(503, "sibling_failed", SuiteText.get(SuiteText.SIBLING_FAILED, "mod", mod));
        } catch (ExecutionException e) {
            if (e.getCause() instanceof ApiException a) {
                throw a;
            }
            throw new IllegalStateException(mod + " failed a request, " + e.getCause(), e.getCause());
        }
    }
}
