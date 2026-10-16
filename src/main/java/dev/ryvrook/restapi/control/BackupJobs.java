package dev.ryvrook.restapi.control;

import com.google.gson.JsonObject;

import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.IntSupplier;
import java.util.function.Supplier;

/**
 * Backup runs started through the API. One runs at a time, the engine's runBackup locks saving for the whole
 * universe anyway. Finished jobs are kept in memory, the newest control.backupJobsKept of them.
 */
final class BackupJobs {
    /** One job. state is running, done or failed. */
    static final class Job {
        final String id;
        final String key;
        final Instant started;
        volatile String state = "running";
        volatile Instant finished;
        volatile String error;
        volatile String file;

        Job(String id, String key, Instant started) {
            this.id = id;
            this.key = key;
            this.started = started;
        }

        JsonObject json() {
            JsonObject o = new JsonObject();
            o.addProperty("job", id);
            o.addProperty("state", state);
            o.addProperty("key", key);
            o.addProperty("startedAt", started.toString());
            o.addProperty("finishedAt", finished == null ? null : finished.toString());
            o.addProperty("millis", (finished == null ? Instant.now() : finished).toEpochMilli() - started.toEpochMilli());
            o.addProperty("file", file);
            o.addProperty("error", error);
            return o;
        }
    }

    private final Map<String, Job> jobs = new LinkedHashMap<>();
    private final Deque<String> finished = new ArrayDeque<>();
    private final IntSupplier keep;
    private Job running;

    BackupJobs(IntSupplier keep) {
        this.keep = keep;
    }

    /**
     * Starts a job, or returns null when one is running, which {@link #running()} then names. newest is asked for
     * the newest file in the backup folder when the run is done.
     */
    synchronized Job start(String key, Supplier<CompletableFuture<Void>> run, Supplier<String> newest) {
        if (running != null) {
            return null;
        }
        Job job = new Job(newId(), key, Instant.now());
        jobs.put(job.id, job);
        running = job;
        CompletableFuture<Void> f;
        try {
            f = run.get();
        } catch (RuntimeException e) {
            f = CompletableFuture.failedFuture(e);
        }
        f.whenComplete((v, err) -> finish(job, err, newest));
        return job;
    }

    private void finish(Job job, Throwable err, Supplier<String> newest) {
        Throwable cause = err;
        while (cause instanceof java.util.concurrent.CompletionException && cause.getCause() != null) {
            cause = cause.getCause();
        }
        String file = null;
        if (cause == null) {
            try {
                file = newest.get();
            } catch (RuntimeException ignored) {
                // The run worked, the file name is a nice to have.
            }
        }
        synchronized (this) {
            job.file = file;
            job.error = cause == null ? null : String.valueOf(cause.getMessage());
            job.finished = Instant.now();
            job.state = cause == null ? "done" : "failed";
            if (running == job) {
                running = null;
            }
            finished.addLast(job.id);
            while (finished.size() > Math.max(1, keep.getAsInt())) {
                jobs.remove(finished.removeFirst());
            }
        }
    }

    synchronized Job get(String id) {
        return jobs.get(id);
    }

    synchronized Job running() {
        return running;
    }

    private static String newId() {
        byte[] b = new byte[4];
        ThreadLocalRandom.current().nextBytes(b);
        return "backup-" + HexFormat.of().formatHex(b);
    }
}
