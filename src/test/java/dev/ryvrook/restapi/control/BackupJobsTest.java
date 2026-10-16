package dev.ryvrook.restapi.control;

import org.junit.jupiter.api.Test;

import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.*;

class BackupJobsTest {
    @Test
    void oneJobAtATimeAndItsOutcome() {
        BackupJobs jobs = new BackupJobs(() -> 20);
        CompletableFuture<Void> run = new CompletableFuture<>();
        BackupJobs.Job job = jobs.start("ops", () -> run, () -> "backups/a.zip");
        assertNotNull(job);
        assertEquals("running", job.json().get("state").getAsString());
        assertTrue(job.id.startsWith("backup-"));
        assertNull(jobs.start("ops", CompletableFuture::new, () -> null), "a second job waits for the first");
        assertSame(job, jobs.running());
        run.complete(null);
        assertEquals("done", jobs.get(job.id).json().get("state").getAsString());
        assertEquals("backups/a.zip", jobs.get(job.id).json().get("file").getAsString());
        assertNull(jobs.running());
        assertNotNull(jobs.start("ops", () -> CompletableFuture.completedFuture(null), () -> null));
    }

    @Test
    void aFailureKeepsTheReason() {
        BackupJobs jobs = new BackupJobs(() -> 20);
        BackupJobs.Job job = jobs.start("ops",
                () -> CompletableFuture.failedFuture(new IllegalStateException("Backup directory not configured")), () -> null);
        assertEquals("failed", job.json().get("state").getAsString());
        assertEquals("Backup directory not configured", job.json().get("error").getAsString());
        BackupJobs.Job thrown = jobs.start("ops", () -> {
            throw new IllegalStateException("no universe");
        }, () -> null);
        assertEquals("failed", thrown.state);
    }

    @Test
    void onlyTheNewestFinishedJobsAreKept() {
        BackupJobs jobs = new BackupJobs(() -> 2);
        BackupJobs.Job first = jobs.start("a", () -> CompletableFuture.completedFuture(null), () -> null);
        BackupJobs.Job second = jobs.start("a", () -> CompletableFuture.completedFuture(null), () -> null);
        BackupJobs.Job third = jobs.start("a", () -> CompletableFuture.completedFuture(null), () -> null);
        assertNull(jobs.get(first.id));
        assertNotNull(jobs.get(second.id));
        assertNotNull(jobs.get(third.id));
    }
}
