package dev.ryvrook.restapi.console;

import dev.ryvrook.restapi.text.Messages;

/**
 * The sentences of the command and console routes. The error codes next to them stay fixed for scripts, the
 * sentences can be reworded in messages.json.
 */
public final class ConsoleText {
    private ConsoleText() {
    }

    public static final String NO_COMMAND = Messages.define("console.error.noCommand",
            "The body needs a command, such as {\"command\": \"help\"}.");
    public static final String LINE_TOO_LONG = Messages.define("console.error.lineTooLong",
            "The command is longer than commands.maxLineChars, {max} characters.");
    public static final String BAD_LINE = Messages.define("console.error.badLine",
            "A command is one line of text without control characters.");
    public static final String DENIED = Messages.define("console.error.denied",
            "{command} is on the deny list and was not run.");
    public static final String NOT_FROM_HERE = Messages.define("console.error.address",
            "Commands are not accepted from {client}, see commands.allowFrom.");
    public static final String IDEMPOTENCY_REQUIRED = Messages.define("console.error.idempotencyRequired",
            "Send an Idempotency-Key header with every command, commands.requireIdempotencyKey is on.");
    public static final String IDEMPOTENCY_BAD = Messages.define("console.error.idempotencyBad",
            "An Idempotency-Key is 1 to 128 visible ASCII characters.");
    public static final String IDEMPOTENCY_CONFLICT = Messages.define("console.error.idempotencyConflict",
            "This Idempotency-Key already belongs to another command from this key. Use a new one for a new command.");
    public static final String TIMEOUT_BAD = Messages.define("console.error.timeout",
            "timeoutMs must be a whole number from 100 to {max}.");
    public static final String BATCH_EMPTY = Messages.define("console.error.batchEmpty",
            "The body needs a commands list with at least one command.");
    public static final String BATCH_TOO_LONG = Messages.define("console.error.batchTooLong",
            "A batch holds at most {max} commands, see commands.batchMax.");
    public static final String RUN_NOT_FOUND = Messages.define("console.error.runNotFound",
            "No run {run} is kept. Runs are kept for commands.idempotencySeconds.");
    public static final String LEVEL_BAD = Messages.define("console.error.level",
            "level must be one of SEVERE, WARNING, INFO, CONFIG, FINE, FINER or FINEST.");
    public static final String SINCE_BAD = Messages.define("console.error.since",
            "since is a line id or an ISO time such as 2026-10-16T12:00:00Z.");
    public static final String LINES_BAD = Messages.define("console.error.lines",
            "lines must be a whole number from {min} to {max}.");
    public static final String NOT_RUN = Messages.define("console.error.stopped",
            "The command service is stopping, nothing was run.");

    public static final String MORE_LINES = Messages.define("console.output.more",
            "{count} more lines were left out, see commands.maxOutputLines.");
    public static final String UNKNOWN_COMMAND = Messages.define("console.output.unknown",
            "{command} is not a command on this server.");
}
