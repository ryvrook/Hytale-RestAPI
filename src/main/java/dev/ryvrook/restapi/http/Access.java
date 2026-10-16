package dev.ryvrook.restapi.http;

/** What a route does, which decides what the audit log keeps and how the docs mark it. */
public enum Access {
    /** Reads state and changes nothing. Audited only with audit.logReads. */
    READ,
    /** Changes something that can be undone, such as a kick or a message. Always audited. */
    WRITE,
    /** Changes something that is hard to undo, such as a command, a ban, an unload or a stop. Always audited. */
    DANGEROUS
}
