package dev.ryvrook.restapi.audit;

import dev.ryvrook.restapi.config.CoreSettings;
import dev.ryvrook.restapi.config.Settings;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class AuditLogTest {
    @TempDir
    Path dir;

    @BeforeEach
    void defaults() {
        CoreSettings.AUDIT_ENABLED.key();
        Settings.useDefaults();
    }

    private static AuditEntry entry(String action) {
        return new AuditEntry("2026-10-16T12:00:00Z", "http", "admin", "127.0.0.1", action, 200, "write");
    }

    @Test
    void linesAreWrittenAndReadBackInOrder() throws Exception {
        AuditLog log = new AuditLog(dir, Clock.fixed(Instant.parse("2026-10-16T12:00:00Z"), ZoneOffset.UTC), m -> { });
        for (int i = 0; i < 5; i++) {
            log.record(entry("POST /api/x/" + i));
        }
        List<AuditEntry> tail = log.tail(3);
        assertEquals(3, tail.size());
        assertEquals("POST /api/x/2", tail.get(0).action());
        assertEquals("POST /api/x/4", tail.get(2).action());
        assertTrue(Files.exists(dir.resolve("audit-2026-10-16.jsonl")));
        log.close();
    }

    @Test
    void oldFilesArePruned() throws Exception {
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("audit-2026-01-01.jsonl"), "");
        Files.writeString(dir.resolve("audit-2026-10-10.jsonl"), "");
        Settings.override(CoreSettings.AUDIT_KEEP_DAYS, 30);
        AuditLog log = new AuditLog(dir, Clock.fixed(Instant.parse("2026-10-16T12:00:00Z"), ZoneOffset.UTC), m -> { });
        log.record(entry("x"));
        log.flush();
        assertFalse(Files.exists(dir.resolve("audit-2026-01-01.jsonl")));
        assertTrue(Files.exists(dir.resolve("audit-2026-10-10.jsonl")));
        log.close();
    }

    @Test
    void offWritesNothing() throws Exception {
        Settings.override(CoreSettings.AUDIT_ENABLED, false);
        AuditLog log = new AuditLog(dir, Clock.systemUTC(), m -> { });
        log.record(entry("x"));
        assertTrue(log.tail(5).isEmpty());
        log.close();
    }

    @Test
    void aCutLineIsSkipped() throws Exception {
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("audit-2026-10-16.jsonl"), "{\"time\":\"t\",\"key\":\"a\"}\n{\"time\":\"t\",\"ke");
        List<String> errors = new ArrayList<>();
        AuditLog log = new AuditLog(dir, Clock.systemUTC(), errors::add);
        assertEquals(1, log.tail(10).size());
        log.close();
    }
}
