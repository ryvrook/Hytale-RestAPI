package dev.ryvrook.restapi.rcon;

import dev.ryvrook.restapi.exec.CommandResult;
import dev.ryvrook.restapi.exec.CommandService;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Stands in for the console module's command service. help answers 120 lines, well over one packet, say echoes,
 * stop is denied, boom fails and slow times out.
 */
final class StandInCommands implements CommandService {
    record Run(String keyName, String source, String client, String line, String idempotencyKey) {
    }

    final List<Run> runs = new CopyOnWriteArrayList<>();

    static List<String> helpLines() {
        List<String> out = new ArrayList<>();
        for (int i = 1; i <= 120; i++) {
            out.add("/command" + i + " does the thing number " + i + " with a description long enough");
        }
        return out;
    }

    @Override
    public CompletableFuture<CommandResult> run(String keyName, String source, String client, String line,
                                                String idempotencyKey, Duration timeout) {
        runs.add(new Run(keyName, source, client, line, idempotencyKey));
        String id = "run" + runs.size();
        CommandResult r;
        if (denied(line)) {
            r = new CommandResult(id, CommandResult.Outcome.DENIED, List.of(), 0, false);
        } else if (line.equals("help")) {
            r = new CommandResult(id, CommandResult.Outcome.DONE, helpLines(), 3, false);
        } else if (line.startsWith("say ")) {
            r = new CommandResult(id, CommandResult.Outcome.DONE, List.of("[Server] " + line.substring(4)), 1, false);
        } else if (line.equals("boom")) {
            r = new CommandResult(id, CommandResult.Outcome.FAILED, List.of(), 1, false);
        } else if (line.equals("slow")) {
            r = new CommandResult(id, CommandResult.Outcome.TIMED_OUT, List.of("started"), timeout.toMillis(), false);
        } else {
            r = new CommandResult(id, CommandResult.Outcome.DONE, List.of(), 1, false);
        }
        return CompletableFuture.completedFuture(r);
    }

    @Override
    public boolean denied(String line) {
        String w = line.strip().split("\\s+")[0];
        return w.equals("stop") || w.equals("shutdown");
    }
}
