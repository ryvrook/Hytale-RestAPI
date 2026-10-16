package dev.ryvrook.restapi.audit;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.ryvrook.restapi.config.CoreSettings;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.regex.Pattern;

/**
 * The audit log, one JSON object per line in {@code audit/audit-<UTC date>.jsonl}. A single writer thread
 * appends, so the HTTP threads never wait on the disk. Files older than audit.keepDays are deleted when the
 * day changes and at start.
 */
public final class AuditLog implements AuditSink {
    private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();
    private static final Pattern NAME = Pattern.compile("audit-(\\d{4}-\\d{2}-\\d{2})\\.jsonl");

    private final Path dir;
    private final Clock clock;
    private final Consumer<String> onError;
    private final ExecutorService writer = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "RestAPI-audit");
        t.setDaemon(true);
        return t;
    });
    private LocalDate prunedFor;

    public AuditLog(Path dir, Clock clock, Consumer<String> onError) {
        this.dir = dir;
        this.clock = clock;
        this.onError = onError;
    }

    @Override
    public void record(AuditEntry e) {
        if (!CoreSettings.AUDIT_ENABLED.get()) {
            return;
        }
        try {
            writer.execute(() -> append(e));
        } catch (java.util.concurrent.RejectedExecutionException ignored) {
            // Shutting down. The entry is lost, the server is stopping anyway.
        }
    }

    private void append(AuditEntry e) {
        LocalDate day = LocalDate.ofInstant(clock.instant(), ZoneOffset.UTC);
        try {
            Files.createDirectories(dir);
            if (!day.equals(prunedFor)) {
                prunedFor = day;
                prune(day);
            }
            Files.writeString(dir.resolve("audit-" + day + ".jsonl"), GSON.toJson(e) + "\n", StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException ex) {
            onError.accept("RestAPI could not write the audit log, " + ex.getMessage());
        }
    }

    private void prune(LocalDate today) throws IOException {
        LocalDate oldest = today.minusDays(CoreSettings.AUDIT_KEEP_DAYS.get() - 1L);
        try (DirectoryStream<Path> files = Files.newDirectoryStream(dir, "audit-*.jsonl")) {
            for (Path f : files) {
                var m = NAME.matcher(f.getFileName().toString());
                if (m.matches() && LocalDate.parse(m.group(1)).isBefore(oldest)) {
                    Files.deleteIfExists(f);
                }
            }
        }
    }

    /** Waits for pending lines, for /restapi audit tail and tests. */
    public void flush() {
        try {
            writer.submit(() -> { }).get(5, TimeUnit.SECONDS);
        } catch (Exception ignored) {
            // A slow disk. The tail shows what is there.
        }
    }

    /** The newest n entries, oldest first, from today's file and the ones before it. */
    public List<AuditEntry> tail(int n) throws IOException {
        flush();
        Deque<AuditEntry> out = new ArrayDeque<>();
        if (!Files.isDirectory(dir)) {
            return List.of();
        }
        List<Path> files = new ArrayList<>();
        try (DirectoryStream<Path> s = Files.newDirectoryStream(dir, "audit-*.jsonl")) {
            s.forEach(files::add);
        }
        files.sort(java.util.Comparator.comparing(Path::toString).reversed());
        for (Path f : files) {
            List<String> lines = Files.readAllLines(f, StandardCharsets.UTF_8);
            for (int i = lines.size() - 1; i >= 0 && out.size() < n; i--) {
                try {
                    JsonObject o = JsonParser.parseString(lines.get(i)).getAsJsonObject();
                    out.addFirst(GSON.fromJson(o, AuditEntry.class));
                } catch (RuntimeException ignored) {
                    // A line cut short by a crash. Skip it.
                }
            }
            if (out.size() >= n) {
                break;
            }
        }
        return List.copyOf(out);
    }

    public void close() {
        writer.shutdown();
        try {
            writer.awaitTermination(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
