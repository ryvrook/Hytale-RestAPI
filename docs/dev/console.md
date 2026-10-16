## Console module

Package `dev.ryvrook.restapi.console`. `ConsoleModule` mounts group `console` and provides `CommandService` from
start to stop. `CommandRunner` is the service, `EngineCommands` the only class that calls `CommandManager`,
`CapturingSender` the sender, `DenyList` and `Idempotency` the checks, `LogTap` the log subscription and `LogRing`
the copy of the log. Everything but `EngineCommands` and the subscribe calls in `LogTap` runs in tests against a
stand in engine.

### Engine facts, read with javap on Server-0.7.1.jar and seen on smoke runs on 0.7.2

- `CommandManager.handleCommand(sender, line)` puts the work on `ForkJoinPool.commonPool()` and returns at once.
  The worker renames its thread to `<old name> -- Running: <line>` for the run, logs `"%s sent command: %s"` at
  FINE, takes the text up to the first space, lower cases it and calls `resolveCommand`, which tries the name map
  and then the alias map. An unknown command sends `server.modules.command.notFound` ("Command not found! x") and
  completes normally. `runCommand` logs `"%s executed command: %s"` at INFO with `getUsername()`, parses, and on
  an exception sends `server.modules.command.error` and completes exceptionally. A parse error sends its messages
  and completes normally. `handleCommand` does not strip a leading slash, RestAPI does.
- `say` (alias `broadcast`) sends its line to every player and to `ConsoleSender.INSTANCE`, never to the sender.
  The console sender writes it through `HytaleLoggerBackend.rawLog`, which reaches subscribers as a record at
  `Level.ALL` on the thread that ran the command. `CommandRunner.echo` matches that thread name to give `say`
  its output line. Seen: `{"output":["RestAPI:admin: hello"]}`.
- `stop` has the alias `shutdown`. `plugin` has `plugins` and `pl`, its sub commands `reload` has `r` and
  `manage` has `m`. `sudo <player> <command>` (alias `su`) calls `handleCommand` again as that player, so the deny
  list looks inside it. Command macros from `builtin.commandmacro` run their own lines and are not looked into.
- `AbstractCommand` has `getName()`, `getAliases()`, `getDescription()` (a translation key such as
  `server.commands.say.desc`), `getPermission()` and `getSubCommands()`. `getCommandRegistration()` is a fastutil
  map without locks, read once at request time with a retry on a concurrent change. 183 root commands on a bare
  0.7.2 server, 224 with the seven suite mods.
- `HytaleLoggerBackend.subscribe(list)` checks `subscribers.contains(list)` before adding and `unsubscribe` calls
  `remove(list)`, both by `equals`. Two empty `CopyOnWriteArrayList`s are equal, so a second subscriber with a list
  that stores nothing would be skipped, and one unsubscribe could remove another's list. `LogTap` overrides
  `equals` and `hashCode` to identity. Any other subscriber in RestAPI has to do the same.
- The subscription sees records from every sub logger. Seen on the smoke run: `WorldGenerator`, `QUICTransport`,
  `CommandManager`, `PluginManager`, `World|default|M` and the raw console replies all arrived. That settles the
  open question in the engine notes.

### The grace window, measured

Smoke runs on 2026-10-16 with `commands.graceMs` 3000 and `graceMaxMs` 5000, alone and with the seven suite mods
and `--backup-dir` set. `GET /api/commands/runs/{runId}` reports `lateLines` and `lastLateMillis`.

| Command | Engine millis | Lines after completion | Last one after |
|---|---|---|---|
| `help`, `say`, `plugin list`, `version`, `world list`, `who` | 0 to 14 | 0 | |
| `backup` with `--backup-dir` | 58 | 0, both lines came before completion | |
| `backup` without it | 0 | 0 | |
| `trackerapi status` | 2 | 0 | |
| `coinage status` | 1 | 10 | 3 ms |
| `restapi status` | 1 | 11 | 0 ms |
| `plugin reload Ryvrook:Lullaby` | 21 | 0 | |

Late lines come from plugin commands that answer from their own executor, and they landed within 3 ms. The
default of 300 ms quiet time, at most 2000 ms, leaves room for a busy server and costs 300 ms on every answer.
Lower `commands.graceMs` for scripts that run many commands.

### Decisions

- The run's audit lines carry the source (`http` or `rcon`), the key, the client, `command <line>` as action and a
  detail of `started run <id> [with idempotency key k]`, then `<outcome> run <id> in <n> ms, <n> lines`, or
  `denied run <id> by <entry>`, or `replayed run <id> for idempotency key k`. The Gate adds its own line for the
  route afterwards.
- `restapi keys` is on the fixed deny list next to `restapi reload`, so a key with the commands scope cannot make a
  key with more scopes through the command route.
- `commands.allowFrom` is checked inside `CommandRunner.run`, so RCON gets it too, and again in the route so HTTP
  answers 403 `address_not_allowed` instead of a denied result.
- An Idempotency-Key reused for a different line completes the future exceptionally with
  `CommandRunner.IdempotencyConflict`. The HTTP route answers 409. A batch stores each line as `<key>#<index>`.
- A timed out result is stored under its Idempotency-Key like any other, so a retry gets `timed_out` again and
  the command is not started twice.
- A run is found by `GET /api/commands/runs/{runId}` only with the key that made it.
- Key tokens are cut to `rapi_<id>_...` in the ring and in command output, with no setting to turn it off. The
  module subscribes in setup, so the first key printed at start is in the ring, cut down. The reply of
  `/restapi keys create` was seen cut down in the stream on the smoke run.
- A stop, and so a `/restapi reload`, closes the ring, which ends open streams. The ring and its ids stay, so a
  client resumes with `Last-Event-ID`.
