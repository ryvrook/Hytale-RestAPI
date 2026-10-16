package dev.ryvrook.restapi.console;

import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.command.system.CommandSender;
import dev.ryvrook.restapi.audit.AuditEntry;
import dev.ryvrook.restapi.audit.AuditSink;
import dev.ryvrook.restapi.exec.CommandResult;
import dev.ryvrook.restapi.exec.CommandResult.Outcome;
import dev.ryvrook.restapi.exec.CommandService;
import dev.ryvrook.restapi.text.Messages;
import dev.ryvrook.restapi.util.AddressRanges;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Function;
import java.util.function.LongSupplier;

/**
 * The command service behind POST /api/commands and RCON. One run goes like this.
 *
 * <ol>
 *   <li>The line loses a leading slash and is checked for length and control characters.</li>
 *   <li>The client address is checked against commands.allowFrom and the line against the {@link DenyList}.
 *       A refused line never reaches the engine.</li>
 *   <li>With an Idempotency-Key the pair of key name and Idempotency-Key is claimed. A pair claimed before hands
 *       back the first run's result, marked replayed, and runs nothing.</li>
 *   <li>The audit log gets a started line with the run id before the engine sees the command, so a crash in
 *       between still leaves a trace.</li>
 *   <li>The engine runs it as a {@link CapturingSender}. Output is collected until the command's future completes
 *       and then for commands.graceMs after the last line, at most commands.graceMaxMs, because some commands
 *       reply after their future completed. The timeout answers with what arrived so far.</li>
 *   <li>The audit log gets the outcome under the same run id.</li>
 * </ol>
 *
 * <p>Each run waits on a virtual thread of its own, the engine runs the command on its common pool.
 */
public final class CommandRunner implements CommandService {
    /** What the runner needs from the engine. The server version is {@link EngineCommands}. */
    public interface Engine {
        /** Hands the line to the engine. The future completes when the engine says the command finished. */
        CompletableFuture<Void> run(CommandSender sender, String line);

        /** The words with the command and sub command names put in the engine's own spelling. */
        List<String> canonical(List<String> words);

        /** True when the first word is a registered command or alias. */
        boolean known(String firstWord);
    }

    /** Thrown through the future when an Idempotency-Key is reused for another command. */
    public static final class IdempotencyConflict extends RuntimeException {
        public IdempotencyConflict(String message) {
            super(message, null, false, false);
        }
    }

    /**
     * A finished run as GET /api/commands/runs/{runId} shows it, with how long the engine took and how many lines
     * arrived after its future completed, the numbers behind commands.graceMs.
     *
     * @param engineMillis   from the start to the engine's future completing, -1 when it did not complete in time
     * @param lateLines      lines that arrived after the future completed
     * @param lastLateMillis how long after completion the last late line came, -1 when none
     */
    public record Run(CommandResult result, String keyName, String source, String line, long startedAt,
                      long engineMillis, int lateLines, long lastLateMillis, int droppedLines) {
    }

    private final Engine engine;
    private final DenyList deny;
    private final AuditSink audit;
    private final LongSupplier clock;
    private final Function<Message, String> plain;
    private final Idempotency<CommandResult> idempotency;
    private final Map<String, Run> runs = new LinkedHashMap<>();
    private final Map<String, Collector> active = new ConcurrentHashMap<>();
    private volatile boolean stopped;
    private volatile List<String> allowSource;
    private volatile AddressRanges allow = AddressRanges.NONE;

    public CommandRunner(Engine engine, AuditSink audit, LongSupplier millis, Function<Message, String> plain) {
        this.engine = engine;
        this.deny = new DenyList(engine::canonical);
        this.audit = audit;
        this.clock = millis;
        this.plain = plain;
        this.idempotency = new Idempotency<>(millis);
    }

    public DenyList denyList() {
        return deny;
    }

    @Override
    public boolean denied(String line) {
        return deny.denied(line, ConsoleSettings.COMMANDS_DENY.get());
    }

    /** The line without a leading slash and outer white space. */
    public static String normalize(String line) {
        String t = line == null ? "" : line.strip();
        while (t.startsWith("/")) {
            t = t.substring(1).strip();
        }
        return t;
    }

    /** Null when the line may be sent, else the sentence that says why not. */
    public static String problem(String normalized) {
        if (normalized.isEmpty()) {
            return Messages.get(ConsoleText.NO_COMMAND);
        }
        int max = ConsoleSettings.COMMANDS_MAX_LINE_CHARS.get();
        if (normalized.length() > max) {
            return Messages.get(ConsoleText.LINE_TOO_LONG, "max", max);
        }
        for (int i = 0; i < normalized.length(); i++) {
            if (Character.isISOControl(normalized.charAt(i))) {
                return Messages.get(ConsoleText.BAD_LINE);
            }
        }
        return null;
    }

    /** True when commands.allowFrom lets this client run commands. An empty list lets everyone. */
    public boolean allowedFrom(String client) {
        List<String> src = ConsoleSettings.COMMANDS_ALLOW_FROM.get();
        if (src.isEmpty()) {
            return true;
        }
        if (src != allowSource) {
            allow = AddressRanges.parse(src);
            allowSource = src;
        }
        return client != null && allow.contains(client);
    }

    @Override
    public CompletableFuture<CommandResult> run(String keyName, String source, String client, String lineIn,
                                                String idempotencyKey, Duration timeout) {
        String line = normalize(lineIn);
        String id = newId();
        long started = clock.getAsLong();
        if (stopped) {
            return CompletableFuture.completedFuture(new CommandResult(id, Outcome.FAILED,
                    List.of(Messages.get(ConsoleText.NOT_RUN)), 0, false));
        }
        String bad = problem(line);
        if (bad != null) {
            return CompletableFuture.completedFuture(new CommandResult(id, Outcome.FAILED, List.of(bad), 0, false));
        }
        if (!allowedFrom(client)) {
            audit(source, keyName, client, line, "denied run " + id + ", address not in commands.allowFrom");
            return CompletableFuture.completedFuture(new CommandResult(id, Outcome.DENIED,
                    List.of(Messages.get(ConsoleText.NOT_FROM_HERE, "client", client)), 0, false));
        }
        String entry = deny.match(line, ConsoleSettings.COMMANDS_DENY.get());
        if (entry != null) {
            audit(source, keyName, client, line, "denied run " + id + " by " + entry);
            return CompletableFuture.completedFuture(new CommandResult(id, Outcome.DENIED,
                    List.of(Messages.get(ConsoleText.DENIED, "command", line)), 0, false));
        }
        CompletableFuture<CommandResult> slot = null;
        if (idempotencyKey != null) {
            long keep = TimeUnit.SECONDS.toMillis(ConsoleSettings.COMMANDS_IDEMPOTENCY_SECONDS.get());
            Idempotency.Claim<CommandResult> claim = idempotency.claim(keyName, idempotencyKey, line, keep);
            if (claim instanceof Idempotency.Existing<CommandResult> had) {
                return had.future().thenApply(r -> {
                    audit(source, keyName, client, line, "replayed run " + r.id() + " for idempotency key " + idempotencyKey);
                    return new CommandResult(r.id(), r.outcome(), r.output(), r.millis(), true);
                });
            }
            if (claim instanceof Idempotency.Conflict<CommandResult>) {
                return CompletableFuture.failedFuture(new IdempotencyConflict(Messages.get(ConsoleText.IDEMPOTENCY_CONFLICT)));
            }
            slot = ((Idempotency.Fresh<CommandResult>) claim).future();
        }
        audit(source, keyName, client, line, "started run " + id
                + (idempotencyKey == null ? "" : " with idempotency key " + idempotencyKey));
        CompletableFuture<CommandResult> answer = slot != null ? slot : new CompletableFuture<>();
        long waitMs = Math.max(1, timeout == null ? ConsoleSettings.COMMANDS_TIMEOUT_MS.get() : timeout.toMillis());
        Thread.ofVirtual().name("RestAPI-command-" + id).start(() -> {
            try {
                Run r = execute(id, keyName, source, line, started, waitMs);
                remember(r);
                CommandResult res = r.result();
                audit(source, keyName, client, line, res.outcome().name().toLowerCase(java.util.Locale.ROOT) + " run " + id
                        + " in " + res.millis() + " ms, " + res.output().size() + " lines");
                answer.complete(res);
            } catch (Throwable t) {
                audit(source, keyName, client, line, "failed run " + id + ", " + t.getClass().getSimpleName());
                answer.complete(new CommandResult(id, Outcome.FAILED, List.of(), clock.getAsLong() - started, false));
            }
        });
        return answer;
    }

    private Run execute(String id, String keyName, String source, String line, long started, long waitMs)
            throws InterruptedException {
        Collector c = new Collector(line, ConsoleSettings.COMMANDS_MAX_OUTPUT_LINES.get(), clock);
        CapturingSender sender = new CapturingSender(keyName, ConsoleSettings.COMMANDS_PERMISSIONS.get(), plain, c::line);
        boolean known;
        try {
            List<String> w = DenyList.words(line);
            known = !w.isEmpty() && engine.known(w.get(0));
        } catch (RuntimeException e) {
            known = true;
        }
        active.put(id, c);
        try {
            CompletableFuture<Void> f;
            try {
                f = engine.run(sender, line);
            } catch (RuntimeException e) {
                f = CompletableFuture.failedFuture(e);
            }
            f.whenComplete((v, e) -> c.finish(e != null));
            long deadline = started + waitMs;
            boolean done = c.awaitDone(deadline);
            if (done) {
                c.awaitQuiet(ConsoleSettings.COMMANDS_GRACE_MS.get(), ConsoleSettings.COMMANDS_GRACE_MAX_MS.get());
            }
            long now = clock.getAsLong();
            Outcome outcome = !done ? Outcome.TIMED_OUT : c.failed() || !known ? Outcome.FAILED : Outcome.DONE;
            List<String> out = new ArrayList<>(c.lines());
            if (!known && out.isEmpty()) {
                out.add(Messages.get(ConsoleText.UNKNOWN_COMMAND, "command", DenyList.words(line).get(0)));
            }
            if (c.dropped() > 0) {
                out.add(Messages.get(ConsoleText.MORE_LINES, "count", c.dropped()));
            }
            CommandResult result = new CommandResult(id, outcome, out, now - started, false);
            return new Run(result, keyName, source, line, started, done ? c.doneAt() - started : -1, c.late(),
                    c.lastLate(), c.dropped());
        } finally {
            active.remove(id);
        }
    }

    /**
     * A console reply written on a thread. The engine names the thread that runs a command
     * {@code <pool name> -- Running: <line>}, so a reply on that thread belongs to the run of that line. This is
     * how the line of say, which goes to the console and not to the sender, reaches the answer.
     */
    public void echo(String threadName, String text) {
        if (threadName == null || active.isEmpty() || !ConsoleSettings.COMMANDS_ECHO.get()) {
            return;
        }
        for (Collector c : active.values()) {
            if (threadName.endsWith(" -- Running: " + c.line)) {
                for (String l : text.split("\r\n|\r|\n", -1)) {
                    c.line(l);
                }
                return;
            }
        }
    }

    /** A kept run for this key, or null when it is gone, too old, or another key's. */
    public Run find(String runId, String keyName) {
        long keep = TimeUnit.SECONDS.toMillis(ConsoleSettings.COMMANDS_IDEMPOTENCY_SECONDS.get());
        synchronized (runs) {
            Run r = runs.get(runId);
            if (r == null || !r.keyName().equals(keyName) || clock.getAsLong() - r.startedAt() > keep) {
                return null;
            }
            return r;
        }
    }

    private void remember(Run r) {
        int cap = ConsoleSettings.COMMANDS_RUNS_KEPT.get();
        synchronized (runs) {
            runs.put(r.result().id(), r);
            while (runs.size() > cap) {
                runs.remove(runs.keySet().iterator().next());
            }
        }
    }

    /** Takes runs again after a stop. */
    public void open() {
        stopped = false;
    }

    /** Refuses new runs. Runs still going finish and answer. */
    public void stop() {
        stopped = true;
    }

    private void audit(String source, String keyName, String client, String line, String detail) {
        audit.record(new AuditEntry(Instant.ofEpochMilli(clock.getAsLong()).toString(), source, keyName, client,
                "command " + line, 0, detail));
    }

    static String newId() {
        return "r" + Long.toHexString(ThreadLocalRandom.current().nextLong() | Long.MIN_VALUE).substring(0, 12);
    }

    /** Collects the lines of one run and wakes the waiting thread when something happens. */
    static final class Collector {
        final String line;
        private final int max;
        private final LongSupplier clock;
        private final ReentrantLock lock = new ReentrantLock();
        private final Condition changed = lock.newCondition();
        private final List<String> lines = new ArrayList<>();
        private int dropped;
        private boolean done;
        private boolean failed;
        private long doneAt = -1;
        private long lastLineAt = -1;
        private int late;
        private long lastLate = -1;

        Collector(String line, int max, LongSupplier clock) {
            this.line = line;
            this.max = max;
            this.clock = clock;
        }

        void line(String text) {
            lock.lock();
            try {
                long now = clock.getAsLong();
                if (lines.size() < max) {
                    lines.add(text);
                } else {
                    dropped++;
                }
                lastLineAt = now;
                if (done) {
                    late++;
                    lastLate = now - doneAt;
                }
                changed.signalAll();
            } finally {
                lock.unlock();
            }
        }

        void finish(boolean withError) {
            lock.lock();
            try {
                if (!done) {
                    done = true;
                    failed = withError;
                    doneAt = clock.getAsLong();
                }
                changed.signalAll();
            } finally {
                lock.unlock();
            }
        }

        /** Waits for the future until the deadline. True when it completed. */
        boolean awaitDone(long deadline) throws InterruptedException {
            lock.lock();
            try {
                while (!done) {
                    long left = deadline - clock.getAsLong();
                    if (left <= 0) {
                        return false;
                    }
                    changed.await(left, TimeUnit.MILLISECONDS);
                }
                return true;
            } finally {
                lock.unlock();
            }
        }

        /** After completion, waits until graceMs pass without a new line, at most graceMaxMs. */
        void awaitQuiet(long graceMs, long graceMaxMs) throws InterruptedException {
            lock.lock();
            try {
                long end = doneAt + graceMaxMs;
                while (true) {
                    long quietUntil = Math.max(doneAt, lastLineAt) + graceMs;
                    long until = Math.min(quietUntil, end);
                    long left = until - clock.getAsLong();
                    if (left <= 0) {
                        return;
                    }
                    changed.await(left, TimeUnit.MILLISECONDS);
                }
            } finally {
                lock.unlock();
            }
        }

        List<String> lines() {
            lock.lock();
            try {
                return List.copyOf(lines);
            } finally {
                lock.unlock();
            }
        }

        int dropped() {
            lock.lock();
            try {
                return dropped;
            } finally {
                lock.unlock();
            }
        }

        boolean failed() {
            lock.lock();
            try {
                return failed;
            } finally {
                lock.unlock();
            }
        }

        long doneAt() {
            lock.lock();
            try {
                return doneAt;
            } finally {
                lock.unlock();
            }
        }

        int late() {
            lock.lock();
            try {
                return late;
            } finally {
                lock.unlock();
            }
        }

        long lastLate() {
            lock.lock();
            try {
                return lastLate;
            } finally {
                lock.unlock();
            }
        }
    }
}
