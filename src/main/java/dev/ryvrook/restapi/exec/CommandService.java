package dev.ryvrook.restapi.exec;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;

/**
 * Runs console commands for the HTTP command routes and for RCON, so both share one deny list, one idempotency
 * store and one audit trail. The console module provides it through {@code RestApi.provide}, RCON and anything
 * else find it with {@code RestApi.service(CommandService.class)}.
 */
public interface CommandService {
    /**
     * Runs one command line as the named key. A command on the deny list completes with
     * {@link CommandResult.Outcome#DENIED} and never reaches the engine. A second call with the same non null
     * idempotency key within the retention window returns the first result without running the command again.
     *
     * @param keyName        the key or RCON login that asks, written to the audit log and the engine log
     * @param source         http or rcon
     * @param client         the client address
     * @param line           the command without a leading slash
     * @param idempotencyKey the client's Idempotency-Key, or null
     * @param timeout        how long to collect output before answering with what arrived
     */
    CompletableFuture<CommandResult> run(String keyName, String source, String client, String line,
                                         String idempotencyKey, Duration timeout);

    /** True when the line would be refused by the deny list. */
    boolean denied(String line);
}
