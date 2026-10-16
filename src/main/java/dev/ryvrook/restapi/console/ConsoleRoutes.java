package dev.ryvrook.restapi.console;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import dev.ryvrook.restapi.auth.Scope;
import dev.ryvrook.restapi.exec.CommandResult;
import dev.ryvrook.restapi.http.Access;
import dev.ryvrook.restapi.http.ApiException;
import dev.ryvrook.restapi.http.Call;
import dev.ryvrook.restapi.http.Response;
import dev.ryvrook.restapi.http.Route;
import dev.ryvrook.restapi.http.RouteDoc;
import dev.ryvrook.restapi.http.Sse;
import dev.ryvrook.restapi.text.Messages;

import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletionException;
import java.util.function.Supplier;
import java.util.logging.Level;

/** The routes of section 4.5, commands under the commands scope and the log under the console scope. */
public final class ConsoleRoutes {
    static final String GROUP = "console";
    /** Lines a stream sends in one go before it looks for new ones again. */
    private static final int STREAM_BATCH = 500;

    private final CommandRunner runner;
    private final LogRing ring;
    private final Supplier<JsonArray> commandList;

    /**
     * @param runner      the command service, or null when commands.enabled is off
     * @param ring        the log ring, or null when console.enabled is off
     * @param commandList the registered commands for GET /api/commands/list
     */
    public ConsoleRoutes(CommandRunner runner, LogRing ring, Supplier<JsonArray> commandList) {
        this.runner = runner;
        this.ring = ring;
        this.commandList = commandList;
    }

    public List<Route> routes() {
        List<Route> out = new ArrayList<>();
        if (runner != null) {
            out.add(Route.of(GROUP, "POST", "/api/commands", Scope.COMMANDS, Access.DANGEROUS,
                    "Runs one console command and answers with its output. Send an Idempotency-Key header so a retry never runs it twice.",
                    this::run).doc(new RouteDoc(List.of(),
                    "{\"command\": \"say hello\", \"timeoutMs\": 5000}",
                    "{\"runId\": \"r8f3a01c2d4e5\", \"outcome\": \"done\", \"output\": [\"[RestAPI:admin] hello\"], \"millis\": 312, \"replayed\": false}",
                    List.of("command_denied", "address_not_allowed", "idempotency_conflict", "idempotency_key_required",
                            "invalid_idempotency_key", "invalid_command", "invalid_timeout"))));
            out.add(Route.of(GROUP, "POST", "/api/commands/batch", Scope.COMMANDS, Access.DANGEROUS,
                    "Runs several console commands one after the other and answers with one result per command, in order.",
                    this::batch).doc(new RouteDoc(List.of(),
                    "{\"commands\": [\"say one\", \"say two\"], \"stopOnError\": true, \"timeoutMs\": 5000}",
                    "{\"results\": [{\"runId\": \"r8f3a01c2d4e5\", \"outcome\": \"done\", \"output\": [], \"millis\": 300, \"replayed\": false}], \"stopped\": false}",
                    List.of("address_not_allowed", "batch_empty", "batch_too_long", "idempotency_conflict",
                            "idempotency_key_required", "invalid_idempotency_key", "invalid_command", "invalid_timeout"))));
            out.add(Route.of(GROUP, "GET", "/api/commands/runs/{runId}", Scope.COMMANDS, Access.READ,
                    "A finished run of this key while it is kept, with how long the engine took and how many lines came late.",
                    this::runById).doc(new RouteDoc(List.of(), null,
                    "{\"runId\": \"r8f3a01c2d4e5\", \"command\": \"help\", \"outcome\": \"done\", \"output\": [], \"millis\": 310, \"replayed\": false, \"source\": \"http\", \"startedAt\": \"2026-10-16T12:00:00Z\", \"engineMillis\": 9, \"lateLines\": 0, \"lastLateMillis\": -1, \"droppedLines\": 0}",
                    List.of("run_not_found"))));
            out.add(Route.of(GROUP, "GET", "/api/commands/deny", Scope.COMMANDS, Access.READ,
                    "The deny list in force, the fixed entries and those from commands.deny.",
                    call -> denyList()).doc(new RouteDoc(List.of(), null,
                    "{\"fixed\": [\"stop\", \"shutdown\"], \"configured\": [\"op\", \"whitelist clear\"]}", List.of())));
            out.add(Route.of(GROUP, "GET", "/api/commands/list", Scope.READ, Access.READ,
                    "Every registered command with its aliases, description, permission and sub commands.",
                    call -> {
                        JsonObject o = new JsonObject();
                        o.add("commands", commandList.get());
                        return Response.ok(o);
                    }).doc(new RouteDoc(List.of(), null,
                    "{\"commands\": [{\"name\": \"help\", \"aliases\": [], \"description\": \"Shows help\", \"permission\": null, \"subcommands\": []}]}",
                    List.of())));
        }
        if (ring != null) {
            out.add(Route.of(GROUP, "GET", "/api/console", Scope.CONSOLE, Access.READ,
                    "The newest lines of the server log that RestAPI keeps in memory, without colour codes.",
                    this::tail).doc(new RouteDoc(List.of(
                            new RouteDoc.Param("lines", "How many lines, the newest ones. Default console.defaultLines."),
                            new RouteDoc.Param("level", "Lowest level, such as WARNING. Default all that is kept."),
                            new RouteDoc.Param("since", "Only lines after this line id, or at and after this ISO time.")),
                    null,
                    "{\"lines\": [{\"id\": 41, \"time\": \"2026-10-16T12:00:00Z\", \"level\": \"INFO\", \"logger\": \"HytaleServer\", \"message\": \"Hytale Server Booted\"}], \"lastId\": 41}",
                    List.of("invalid_level", "invalid_since", "invalid_lines"))));
            out.add(Route.of(GROUP, "GET", "/api/console/stream", Scope.CONSOLE, Access.READ,
                    "The server log as Server Sent Events, the kept lines first and then every new one as event log. Last-Event-ID resumes.",
                    this::stream).doc(new RouteDoc(List.of(
                            new RouteDoc.Param("level", "Lowest level, such as WARNING."),
                            new RouteDoc.Param("lines", "Kept lines to send first. Default console.defaultLines, 0 sends only new lines.")),
                    null, null, List.of("invalid_level", "invalid_lines"))));
        }
        return out;
    }

    // ---- commands ------------------------------------------------------------------------------------------

    private Response run(Call call) {
        checkAddress(call);
        JsonObject body = call.jsonBody();
        String command = text(body, "command");
        Duration timeout = timeout(body);
        String idem = idempotencyKey(call);
        String line = CommandRunner.normalize(command);
        String bad = CommandRunner.problem(line);
        if (bad != null) {
            throw ApiException.badRequest("invalid_command", bad);
        }
        CommandResult r = await(runner.run(call.keyName(), "http", call.client(), line, idem, timeout));
        return answer(r);
    }

    private Response batch(Call call) {
        checkAddress(call);
        JsonObject body = call.jsonBody();
        JsonElement list = body.get("commands");
        if (list == null || !list.isJsonArray() || list.getAsJsonArray().isEmpty()) {
            throw ApiException.badRequest("batch_empty", Messages.get(ConsoleText.BATCH_EMPTY));
        }
        int max = ConsoleSettings.COMMANDS_BATCH_MAX.get();
        if (list.getAsJsonArray().size() > max) {
            throw ApiException.badRequest("batch_too_long", Messages.get(ConsoleText.BATCH_TOO_LONG, "max", max));
        }
        List<String> lines = new ArrayList<>();
        for (JsonElement e : list.getAsJsonArray()) {
            if (!e.isJsonPrimitive() || !e.getAsJsonPrimitive().isString()) {
                throw ApiException.badRequest("invalid_command", Messages.get(ConsoleText.NO_COMMAND));
            }
            String line = CommandRunner.normalize(e.getAsString());
            String bad = CommandRunner.problem(line);
            if (bad != null) {
                throw ApiException.badRequest("invalid_command", bad);
            }
            lines.add(line);
        }
        boolean stopOnError = body.has("stopOnError") && body.get("stopOnError").isJsonPrimitive()
                && body.get("stopOnError").getAsBoolean();
        Duration timeout = timeout(body);
        String idem = idempotencyKey(call);
        JsonArray results = new JsonArray();
        boolean stopped = false;
        for (int i = 0; i < lines.size(); i++) {
            CommandResult r = await(runner.run(call.keyName(), "http", call.client(), lines.get(i),
                    idem == null ? null : idem + "#" + i, timeout));
            results.add(json(r));
            if (stopOnError && r.outcome() != CommandResult.Outcome.DONE && i < lines.size() - 1) {
                stopped = true;
                break;
            }
        }
        JsonObject o = new JsonObject();
        o.add("results", results);
        o.addProperty("stopped", stopped);
        return Response.ok(o);
    }

    private Response runById(Call call) {
        String id = call.param("runId");
        CommandRunner.Run r = runner.find(id, call.keyName());
        if (r == null) {
            throw ApiException.notFound("run_not_found", Messages.get(ConsoleText.RUN_NOT_FOUND, "run", id));
        }
        JsonObject o = json(r.result());
        o.addProperty("command", r.line());
        o.addProperty("source", r.source());
        o.addProperty("startedAt", Instant.ofEpochMilli(r.startedAt()).toString());
        o.addProperty("engineMillis", r.engineMillis());
        o.addProperty("lateLines", r.lateLines());
        o.addProperty("lastLateMillis", r.lastLateMillis());
        o.addProperty("droppedLines", r.droppedLines());
        return Response.ok(o);
    }

    private Response denyList() {
        JsonObject o = new JsonObject();
        JsonArray fixed = new JsonArray();
        DenyList.fixed().forEach(fixed::add);
        JsonArray configured = new JsonArray();
        ConsoleSettings.COMMANDS_DENY.get().forEach(configured::add);
        o.add("fixed", fixed);
        o.add("configured", configured);
        return Response.ok(o);
    }

    private void checkAddress(Call call) {
        if (!runner.allowedFrom(call.client())) {
            throw new ApiException(403, "address_not_allowed",
                    Messages.get(ConsoleText.NOT_FROM_HERE, "client", call.client()));
        }
    }

    private static String text(JsonObject body, String name) {
        JsonElement e = body.get(name);
        if (e == null || !e.isJsonPrimitive() || !e.getAsJsonPrimitive().isString()) {
            throw ApiException.badRequest("invalid_command", Messages.get(ConsoleText.NO_COMMAND));
        }
        return e.getAsString();
    }

    static Duration timeout(JsonObject body) {
        int max = ConsoleSettings.COMMANDS_MAX_TIMEOUT_MS.get();
        JsonElement e = body.get("timeoutMs");
        if (e == null || e.isJsonNull()) {
            return Duration.ofMillis(Math.min(max, ConsoleSettings.COMMANDS_TIMEOUT_MS.get()));
        }
        try {
            double d = e.getAsDouble();
            if (d == Math.rint(d) && d >= 100 && d <= max) {
                return Duration.ofMillis((long) d);
            }
        } catch (RuntimeException ignored) {
            // Answered below.
        }
        throw ApiException.badRequest("invalid_timeout", Messages.get(ConsoleText.TIMEOUT_BAD, "max", max));
    }

    /** The Idempotency-Key header, null when absent, or 400 when it is malformed or required and missing. */
    static String idempotencyKey(Call call) {
        String k = call.request().header("idempotency-key");
        if (k == null || k.isEmpty()) {
            if (ConsoleSettings.COMMANDS_REQUIRE_IDEMPOTENCY.get()) {
                throw ApiException.badRequest("idempotency_key_required", Messages.get(ConsoleText.IDEMPOTENCY_REQUIRED));
            }
            return null;
        }
        if (k.length() > 128) {
            throw ApiException.badRequest("invalid_idempotency_key", Messages.get(ConsoleText.IDEMPOTENCY_BAD));
        }
        for (int i = 0; i < k.length(); i++) {
            char c = k.charAt(i);
            if (c < 0x21 || c > 0x7e) {
                throw ApiException.badRequest("invalid_idempotency_key", Messages.get(ConsoleText.IDEMPOTENCY_BAD));
            }
        }
        return k;
    }

    private static CommandResult await(java.util.concurrent.CompletableFuture<CommandResult> f) {
        try {
            return f.join();
        } catch (CompletionException e) {
            if (e.getCause() instanceof CommandRunner.IdempotencyConflict c) {
                throw new ApiException(409, "idempotency_conflict", c.getMessage());
            }
            throw e;
        }
    }

    static JsonObject json(CommandResult r) {
        JsonObject o = new JsonObject();
        o.addProperty("runId", r.id());
        o.addProperty("outcome", r.outcome().name().toLowerCase(Locale.ROOT));
        JsonArray out = new JsonArray();
        r.output().forEach(out::add);
        o.add("output", out);
        o.addProperty("millis", r.millis());
        o.addProperty("replayed", r.replayed());
        return o;
    }

    /** 200 when the command ran, 202 when the timeout passed first, 403 with the error fields when denied. */
    static Response answer(CommandResult r) {
        JsonObject o = json(r);
        return switch (r.outcome()) {
            case DONE, FAILED -> Response.ok(o);
            case TIMED_OUT -> Response.json(202, o);
            case DENIED -> {
                o.addProperty("error", "command_denied");
                o.addProperty("message", r.output().isEmpty() ? "" : r.output().get(0));
                yield Response.json(403, o);
            }
        };
    }

    // ---- console -------------------------------------------------------------------------------------------

    private Response tail(Call call) {
        Level level = level(call);
        int n = lines(call, 1);
        long sinceId = 0;
        long sinceTime = 0;
        String since = call.query("since", "").trim();
        if (!since.isEmpty()) {
            if (since.chars().allMatch(Character::isDigit)) {
                try {
                    sinceId = Long.parseLong(since);
                } catch (NumberFormatException e) {
                    throw ApiException.badRequest("invalid_since", Messages.get(ConsoleText.SINCE_BAD));
                }
            } else {
                try {
                    sinceTime = Instant.parse(since).toEpochMilli();
                } catch (DateTimeParseException e) {
                    throw ApiException.badRequest("invalid_since", Messages.get(ConsoleText.SINCE_BAD));
                }
            }
        }
        JsonArray out = new JsonArray();
        for (LogRing.Line l : ring.tail(n, level, sinceId, sinceTime)) {
            out.add(json(l));
        }
        JsonObject o = new JsonObject();
        o.add("lines", out);
        o.addProperty("lastId", ring.lastId());
        return Response.ok(o);
    }

    private Response stream(Call call) {
        Level level = level(call);
        String resume = call.request().header("last-event-id");
        long from;
        if (resume != null && resume.trim().chars().allMatch(Character::isDigit) && !resume.isBlank()) {
            from = Long.parseLong(resume.trim());
        } else {
            int first = lines(call, 0);
            List<LogRing.Line> kept = first == 0 ? List.of() : ring.tail(first, level, 0, 0);
            from = kept.isEmpty() ? ring.lastId() : kept.get(0).id() - 1;
        }
        long start = from;
        return Response.stream(sink -> pump(sink, level, start));
    }

    /** Sends lines after the id until the client leaves or the ring closes for a stop or reload. */
    void pump(Sse.Sink sink, Level level, long after) throws Exception {
        long last = after;
        long keepAlive = ConsoleSettings.CONSOLE_KEEP_ALIVE_SECONDS.get() * 1000L;
        while (!ring.closed()) {
            long top = ring.lastId();
            List<LogRing.Line> batch = ring.after(last, level, STREAM_BATCH);
            for (LogRing.Line l : batch) {
                sink.event("log", Long.toString(l.id()), json(l).toString());
                last = l.id();
            }
            if (batch.size() == STREAM_BATCH) {
                continue;
            }
            last = Math.max(last, top);
            if (!ring.await(last, keepAlive)) {
                sink.comment("keep alive");
            }
        }
    }

    private Level level(Call call) {
        String raw = call.query("level", "").trim();
        if (raw.isEmpty()) {
            return null;
        }
        Level l = LogRing.parseLevel(raw);
        if (l == null) {
            throw ApiException.badRequest("invalid_level", Messages.get(ConsoleText.LEVEL_BAD));
        }
        return l;
    }

    private int lines(Call call, int min) {
        int max = ring.capacity();
        String raw = call.query("lines", "").trim();
        if (raw.isEmpty()) {
            return Math.min(max, ConsoleSettings.CONSOLE_DEFAULT_LINES.get());
        }
        try {
            int n = Integer.parseInt(raw);
            if (n >= min && n <= max) {
                return n;
            }
        } catch (NumberFormatException ignored) {
            // Answered below.
        }
        throw ApiException.badRequest("invalid_lines", Messages.get(ConsoleText.LINES_BAD, "min", min, "max", max));
    }

    static JsonObject json(LogRing.Line l) {
        JsonObject o = new JsonObject();
        o.addProperty("id", l.id());
        o.addProperty("time", Instant.ofEpochMilli(l.time()).toString());
        o.addProperty("level", l.level());
        o.addProperty("logger", l.logger());
        o.addProperty("message", l.message());
        return o;
    }
}
