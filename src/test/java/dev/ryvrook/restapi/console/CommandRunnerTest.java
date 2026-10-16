package dev.ryvrook.restapi.console;

import com.hypixel.hytale.server.core.Message;
import dev.ryvrook.restapi.audit.AuditEntry;
import dev.ryvrook.restapi.config.Settings;
import dev.ryvrook.restapi.exec.CommandResult;
import dev.ryvrook.restapi.exec.CommandResult.Outcome;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.*;

class CommandRunnerTest {
    private final FakeEngine engine = new FakeEngine();
    private final List<AuditEntry> audit = new CopyOnWriteArrayList<>();
    private CommandRunner runner;

    @BeforeEach
    void setUp() {
        ConsoleSettings.COMMANDS_ENABLED.key();
        Settings.useDefaults();
        Settings.override(ConsoleSettings.COMMANDS_GRACE_MS, 150);
        runner = new CommandRunner(engine, audit::add, System::currentTimeMillis, Message::getRawText);
    }

    private CommandResult run(String line, String idem) {
        return runner.run("admin", "http", "127.0.0.1", line, idem, Duration.ofSeconds(2)).join();
    }

    @Test
    void outputComesBackAsPlainLines() {
        CommandResult r = run("/say hello", null);
        assertEquals(Outcome.DONE, r.outcome());
        assertEquals(List.of("[RestAPI:admin] hello"), r.output());
        assertFalse(r.replayed());
        assertTrue(r.id().matches("r[0-9a-f]{12}"), r.id());
        assertEquals(List.of("say hello"), engine.ran);
        assertEquals(List.of("help line 1", "help line 2"), run("help", null).output());
    }

    @Test
    void theStartedLineIsAuditedBeforeTheEngineSeesTheCommand() {
        List<Integer> seen = new CopyOnWriteArrayList<>();
        engine.beforeRun = () -> seen.add(audit.size());
        CommandResult r = run("help", "abc");
        assertEquals(List.of(1), seen);
        assertEquals(2, audit.size());
        assertTrue(audit.get(0).detail().startsWith("started run " + r.id() + " with idempotency key abc"));
        assertEquals("command help", audit.get(0).action());
        assertEquals("done run " + r.id() + " in " + r.millis() + " ms, 2 lines", audit.get(1).detail());
        assertEquals("http", audit.get(1).source());
        assertEquals("admin", audit.get(1).key());
    }

    @Test
    void rconRunsWriteTheStartedAndResultLinesUnderTheirOwnSource() {
        CommandResult r = runner.run("admin", "rcon", "127.0.0.1", "help", null, Duration.ofSeconds(2)).join();
        assertEquals(2, audit.size());
        assertEquals("rcon", audit.get(0).source());
        assertEquals("started run " + r.id(), audit.get(0).detail());
        assertEquals("rcon", audit.get(1).source());
        assertTrue(audit.get(1).detail().startsWith("done run " + r.id()));
    }

    @Test
    void aLateReplyInsideTheGraceWindowIsKept() {
        CommandResult r = run("late", null);
        assertEquals(List.of("late reply"), r.output());
        CommandRunner.Run kept = runner.find(r.id(), "admin");
        assertEquals(1, kept.lateLines());
        assertTrue(kept.lastLateMillis() >= 50, "came about 100 ms after completion, was " + kept.lastLateMillis());
    }

    @Test
    void aGraceOfZeroMissesTheLateReply() {
        Settings.override(ConsoleSettings.COMMANDS_GRACE_MS, 0);
        assertEquals(List.of(), run("late", null).output());
    }

    @Test
    void theTimeoutAnswersWithWhatArrived() {
        CommandResult r = runner.run("admin", "http", "127.0.0.1", "slow", null, Duration.ofMillis(200)).join();
        assertEquals(Outcome.TIMED_OUT, r.outcome());
        assertTrue(r.millis() >= 190 && r.millis() < 1_500, "took " + r.millis());
        assertEquals(-1, runner.find(r.id(), "admin").engineMillis());
    }

    @Test
    void deniedLinesNeverReachTheEngine() {
        for (String line : List.of("stop", "/shutdown", "plugin reload Ryvrook:RestAPI", "sudo * stop", "op add Steve")) {
            CommandResult r = run(line, null);
            assertEquals(Outcome.DENIED, r.outcome(), line);
        }
        assertTrue(engine.ran.isEmpty());
        assertTrue(runner.denied("pl r ryvrook:restapi"));
        assertFalse(runner.denied("say hi"));
        assertTrue(audit.get(0).detail().contains("by stop"), audit.get(0).detail());
    }

    @Test
    void aRetryWithTheSameKeyIsReplayedAndRunsNothing() {
        CommandResult first = run("say once", "key-1");
        CommandResult again = run("say once", "key-1");
        assertEquals(1, engine.ran.size());
        assertTrue(again.replayed());
        assertEquals(first.id(), again.id());
        assertEquals(first.output(), again.output());
        assertTrue(audit.get(audit.size() - 1).detail().startsWith("replayed run " + first.id()));
        run("say once", "key-2");
        assertEquals(2, engine.ran.size());
        CompletionException e = assertThrows(CompletionException.class,
                () -> runner.run("admin", "http", "127.0.0.1", "say twice", "key-1", Duration.ofSeconds(1)).join());
        assertInstanceOf(CommandRunner.IdempotencyConflict.class, e.getCause());
    }

    @Test
    void aRetryDuringTheRunWaitsForIt() {
        Settings.override(ConsoleSettings.COMMANDS_GRACE_MS, 0);
        engine.behaviour = (s, line) -> CompletableFuture.runAsync(() -> {
            FakeEngine.sleep(300);
            s.sendMessage(Message.raw("finished"));
        });
        CompletableFuture<CommandResult> a = runner.run("admin", "http", "127.0.0.1", "help", "same", Duration.ofSeconds(2));
        CompletableFuture<CommandResult> b = runner.run("admin", "rcon", "127.0.0.1", "help", "same", Duration.ofSeconds(2));
        assertEquals(List.of("finished"), a.join().output());
        assertTrue(b.join().replayed());
        assertEquals(List.of("finished"), b.join().output());
        assertEquals(1, engine.ran.size());
    }

    @Test
    void commandsAreOnlyTakenFromAllowedAddresses() {
        CommandResult r = runner.run("admin", "http", "10.0.0.5", "say hi", null, Duration.ofSeconds(1)).join();
        assertEquals(Outcome.DENIED, r.outcome());
        assertTrue(engine.ran.isEmpty());
        assertTrue(runner.allowedFrom("::1"));
        Settings.override(ConsoleSettings.COMMANDS_ALLOW_FROM, List.of());
        assertTrue(runner.allowedFrom("10.0.0.5"));
    }

    @Test
    void unknownAndFailingCommandsAreFailed() {
        CommandResult unknown = run("nothing here", null);
        assertEquals(Outcome.FAILED, unknown.outcome());
        assertEquals(List.of("Command not found nothing"), unknown.output());
        assertEquals(Outcome.FAILED, run("boom", null).outcome());
        engine.behaviour = (s, l) -> {
            throw new IllegalStateException("no manager");
        };
        assertEquals(Outcome.FAILED, run("help", null).outcome());
    }

    @Test
    void outputIsCappedWithACountOfWhatWasLeftOut() {
        Settings.override(ConsoleSettings.COMMANDS_MAX_OUTPUT_LINES, 5);
        CommandResult r = run("many", null);
        assertEquals(6, r.output().size());
        assertEquals("15 more lines were left out, see commands.maxOutputLines.", r.output().get(5));
        assertEquals(15, runner.find(r.id(), "admin").droppedLines());
    }

    @Test
    void consoleRepliesOnTheCommandsOwnThreadAreEchoed() {
        engine.behaviour = (s, line) -> CompletableFuture.runAsync(() ->
                runner.echo("ForkJoinPool.commonPool-worker-1 -- Running: " + line, "[RestAPI:admin] hi"));
        assertEquals(List.of("[RestAPI:admin] hi"), run("echo hi", null).output());
        engine.behaviour = (s, line) -> CompletableFuture.runAsync(() ->
                runner.echo("ForkJoinPool.commonPool-worker-1 -- Running: something else", "not ours"));
        assertEquals(List.of(), run("echo hi", null).output());
        Settings.override(ConsoleSettings.COMMANDS_ECHO, false);
        engine.behaviour = (s, line) -> CompletableFuture.runAsync(() ->
                runner.echo("w -- Running: " + line, "[RestAPI:admin] hi"));
        assertEquals(List.of(), run("echo hi", null).output());
    }

    @Test
    void badLinesAndAStoppedServiceRunNothing() {
        assertEquals(Outcome.FAILED, run("  ", null).outcome());
        assertEquals(Outcome.FAILED, run("say a\nstop", null).outcome());
        Settings.override(ConsoleSettings.COMMANDS_MAX_LINE_CHARS, 16);
        assertEquals(Outcome.FAILED, run("say " + "x".repeat(20), null).outcome());
        runner.stop();
        assertEquals(Outcome.FAILED, run("help", null).outcome());
        runner.open();
        assertEquals(Outcome.DONE, run("help", null).outcome());
        assertEquals(1, engine.ran.size());
    }

    @Test
    void runsAreOnlyFoundByTheKeyThatMadeThem() {
        CommandResult r = run("help", null);
        assertNotNull(runner.find(r.id(), "admin"));
        assertNull(runner.find(r.id(), "panel"));
        assertNull(runner.find("rnothing", "admin"));
    }
}
