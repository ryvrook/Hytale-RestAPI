package dev.ryvrook.restapi.exec;

import java.util.List;

/**
 * What a command run produced.
 *
 * @param id       a run id for the audit log and for repeated answers
 * @param outcome  what happened
 * @param output   the lines sent to the capturing sender, as plain text
 * @param millis   time from the start to the answer
 * @param replayed true when this is the stored answer of an earlier call with the same idempotency key
 */
public record CommandResult(String id, Outcome outcome, List<String> output, long millis, boolean replayed) {
    public enum Outcome {
        /** The command future completed and the grace window passed. */
        DONE,
        /** The command threw or the engine reported an error. */
        FAILED,
        /** The timeout passed first. The command may still be running. */
        TIMED_OUT,
        /** The deny list refused it. */
        DENIED
    }

    public CommandResult {
        output = List.copyOf(output);
    }
}
