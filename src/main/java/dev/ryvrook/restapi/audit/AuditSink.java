package dev.ryvrook.restapi.audit;

/** Where audit entries go. The log file in the plugin, a list in tests. */
@FunctionalInterface
public interface AuditSink {
    AuditSink NONE = e -> { };

    void record(AuditEntry entry);
}
