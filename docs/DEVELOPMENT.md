# RestAPI development notes

RestAPI is built against Hytale server 0.7.1 with Java 25 and boots on 0.7.2. Plugin id `Ryvrook:RestAPI`, main
class `dev.ryvrook.restapi.RestApiPlugin`, data folder `mods/Ryvrook_RestAPI`, jar `RestAPI-<version>.jar`. This
file is the maintainer's reference: the build, the packages, the request pipeline, the threads, the suite
detection, and the engine facts the next modules rely on, each one read with javap on `Server-0.7.1.jar` unless
it says it was seen on a running server.

## Build and test

```
export JAVA_HOME=/opt/homebrew/opt/openjdk@25/libexec/openjdk.jdk/Contents/Home
./gradlew build
RESTAPI_WRITE_DOCS=1 ./gradlew test     writes docs/settings/<section>.md again
```

The server artifact comes from `maven.hytale.com` as `compileOnly`, channel and version in `gradle.properties`.
The same jar is on the test classpath for Gson and the logger. No test starts an engine. The version is set once
in `build.gradle.kts` and reaches the manifest through `processResources`.

## Packages

| Package | What lives there |
|---|---|
| root | `RestApiPlugin` (lifecycle, `/restapi` work), `RestApi` (what modules and units get), `RestModule`, `Modules` (module classes by name) |
| `config` | `Setting`, `Settings` (the registry, load, write back, refuse on a bad value), `CoreSettings` |
| `text` | `Messages` (messages.json), `CommandText` |
| `auth` | `Scope`, `ApiKey`, `KeyStore` (keys.json, SHA-256 of each token) |
| `http` | `Request`, `Response`, `Call`, `Route`, `RouteDoc`, `Access`, `ApiException`, `Router` (groups), `Gate` (the pipeline), `RateLimiter`, `Sse`, `HttpHost` (the JDK server) |
| `audit` | `AuditEntry`, `AuditSink`, `AuditLog` |
| `server` | `CoreModule` (`/api/health`, `/api/server`), `ServerFacts`, `WorldSampler`, `WorldSample`, `Health` |
| `siblings` | `Sibling`, `Mode`, `SiblingSettings`, `PluginProbe`, `EngineProbe`, `SiblingUnit`, `Presence`, `Siblings` |
| `command` | `RestApiCommand`, `Perms`, `Replies` |
| `exec` | `CommandService` and `CommandResult`, the contract the console module implements and RCON uses |
| `util` | `AddressRanges` (CIDR sets, no DNS), `Versions` |

Later modules each get a package of their own and are listed by class name in `Modules.CLASSES`, so a build with
any of them missing still works and adding one never edits a shared file. A module declares its settings and
text in its own classes and touches them in its constructor, before `RestAPI.json` and `messages.json` are read.
Suite route groups live under `dev.ryvrook.restapi.suite.<id>` and are named in `Sibling`.

A module has four calls. `setup(plugin)` runs in the plugin's setup, the only time the engine takes ECS systems
and event listeners. `routes(api)` returns its routes, every one naming the module's group. `start(api)` runs
after they are mounted, `stop()` at shutdown and before a reload starts it again. Modules share services through
`RestApi.provide` and `RestApi.service`, such as the console module's `CommandService` that RCON runs commands
through. A route carries a `RouteDoc` with query parameters, examples and error codes for the OpenAPI document.

## The request pipeline

`HttpHost` turns a `com.sun.net.httpserver` exchange into a `Request` and writes the `Response`. It refuses
bodies over `http.maxBodyBytes` with 413 from `Content-Length` or while reading, and answers 503 with
`Retry-After: 1` when `http.maxInFlight` requests already run. `sun.net.httpserver.maxReqTime` is set from
`http.requestSeconds` when nobody set it before. The JDK reads it once per JVM, so Atlas or Chronicle Stats may
have set it first.

`Gate` does the rest in this order, and the tests drive it without a socket.

1. Strip `http.basePath`. Outside it is 404.
2. CORS preflight. Only origins in `http.corsOrigins` get headers, anything else gets 403 on preflight and no
   CORS headers on a normal request.
3. Client address. The peer, unless the peer is in `http.trustedProxies`, then `X-Forwarded-For` is walked
   from the right to the first hop that is not a trusted proxy. A hop that is not an IP literal stops the walk.
4. Key. `Authorization: Bearer`, else `X-API-Key` when `keys.acceptXApiKey` is on. The id in the token picks
   the key, the SHA-256 is compared with `MessageDigest.isEqual`.
5. No valid key on a route that is not open, or a wrong key anywhere: the address limiter
   (`limits.authFailuresPerMinute`) and 401 with `WWW-Authenticate`. The answer is the same for paths that exist
   and paths that do not. A wrong key is audited.
6. `allowFrom` of the key, else 403.
7. Per key token bucket, `requestsPerMinute` of the key or `limits.requestsPerMinute`, else 429 with
   `Retry-After`.
8. Route match. 404, or 405 with `Allow`. A literal segment beats a `{param}` segment at the same place. HEAD is
   answered by the GET route.
9. Scope, else 403 `missing_scope`, audited.
10. Handler. `ApiException` becomes its status and code. Anything else becomes 500 `internal_error` with no
    exception text, and the cause goes to the server log.
11. Audit for `WRITE` and `DANGEROUS` routes, and for reads with `audit.logReads`.

Every answer carries `Cache-Control: no-store` and `X-Content-Type-Options: nosniff`.

A handler that answers `Response.stream(stream)` gets Server Sent Events. `HttpHost` gives back the in flight
slot, takes one of `http.maxStreams` stream slots or answers 503, sends the headers with
`X-Accel-Buffering: no` so nginx does not buffer, and runs the stream on the request's virtual thread until it
returns or the client leaves. `Sse.format` keeps a name or id on one line and splits data on line breaks.

Settings docs are one file per section under `docs/settings/`, and README rows may also live in
`docs/readme/<module>.md`, so modules built side by side never edit the same doc file. The lead folds the
module rows into the README at release.

## Threads

| Thread | What runs there |
|---|---|
| JDK HTTP server, one virtual thread per request | `Gate` and every handler. Never blocks on a world thread without a timeout |
| `RestAPI-scheduler-1` and `-2` | World samples, sibling checks, `/restapi` work that reads or writes files |
| `RestAPI-audit` | Appends to the audit file and prunes old ones |
| World threads | `WorldSampler.read` through `world.execute` |
| Common pool | `/restapi` sub commands start here (`CommandBase.executeSync`) and hand off to the scheduler |

Replies to a player go back through `world.execute` on that player's world, as in the siblings.

## Suite detection

`Siblings` asks `PluginManager.getPlugin(new PluginIdentifier("Ryvrook", name))` for each of the seven mods at
start, on `/restapi reload` and every `siblings.checkSeconds`. It never touches a class of the mod for that. A mod
is bound when its state is `ENABLED`, its manifest version is at or above the minimum in `Sibling` (or below it
under `always`), and `siblings.<id>` is not `never`. Binding loads the unit class by name, calls `bind`, checks
that every route sits under `/api/<id>`, names the group `suite.<id>` and needs the mod's scope, and mounts the
group. Anything else unmounts it.

The plugin object is kept at bind. A different object later means the mod was reloaded. The unit's classes were
linked against the old copy, so the group stays down with a reason that names `/plugin reload Ryvrook:RestAPI`.
TrackerAPI 1.1.0 and Coinage 1.2.0 reload every plugin that lists them, RestAPI lists all seven under
`OptionalDependencies`, so for those two RestAPI comes back on its own under the suite reload rule in
TrackerAPI's `docs/DEVELOPMENT.md`. Listing them also makes the engine load the seven before RestAPI and stop
RestAPI first, seen in the smoke run.

Static hooks for a unit to bind from, read from the sibling sources on 2026-10-16: `TrackerApi.onEnable(Consumer)`
and `onDisable(Runnable)`, the same pair on `CoinageApi` and `AtlasApi`. Dominion has `ApiHolder.get()`, Feats
`FeatsApi.get()`, Chronicle Stats `ChronicleApi.get()`, all without enable hooks. Lullaby has no API.

## Reload

`/restapi reload` reads the three files again in place, remounts the modules, checks the siblings, moves the HTTP
server when the bind or port changed and leaves it alone otherwise. A `RestAPI.json` with a bad value changes
nothing. RestAPI never calls `PluginManager.reload` on itself, and the command module must keep any command
that reloads RestAPI on its deny list.

## Engine facts

### Server and worlds

- `HytaleServer.get()` has `getConfig()`, `getBoot()` (an Instant), `isBooted()`, `isShuttingDown()`,
  `shutdownServer()` and `shutdownServer(ShutdownReason)`, `getPluginManager()`, `getCommandManager()`.
- `isBooting()` reads a flag that `boot()` sets with `getAndSet(true)` and never clears. It is still true after
  "Hytale Server Booted". RestAPI reads only `isBooted()` and `isShuttingDown()`. Seen on the smoke run, where
  the first build answered `booting` 15 seconds after the boot.
- `HytaleServerConfig` getters used: `getServerName()`, `getMotd()`, `getPassword()`, `getMaxPlayers()`,
  `getMaxViewRadius()`.
- `ManifestUtil.getVersion()`, `getImplementationRevisionId()`, `getPatchline()` give `0.7.2`, the revision hash
  and `release` on the client's bundled server.
- `Universe.get().getWorlds()` is a view of a ConcurrentHashMap, `getPlayers()` the values of one. Both are read
  off the world threads. `getWorld(UUID)` and `getWorld(String)`, `getPlayerCount()`.
- `World` extends `TickingThread`. `getTps()` is the target (30), not a measurement. The engine's `/world perf`
  works out the rate as `1e9 / max(getBufferedTickLengthMetricSet().getAverage(i), getTickStepNanos())`, period
  index 0 is 10 seconds, 1 one minute, 2 five minutes. `WorldSample.tps` uses the same formula.
- `getChunkStore().getLoadedChunksCount()` and `getEntityStore().getStore().getEntityCount()` are read on the
  world thread, as Chronicle Stats does.
- There is no restart API. A restart is a shutdown with an exit code the host supervisor acts on.
  `ShutdownReason(int, String)` is public. Codes 0 shutdown, 1 crash, 8 update, 9 mod error, 130 SIGINT.

### Native remote access

No class or string in the jar matches rcon, telnet, jmx or a query port. The console is
`core.console.ConsoleModule`, a daemon thread named `ConsoleThread` reading stdin through jline. It strips one
leading `/` and calls `CommandManager.get().handleCommand(ConsoleSender.INSTANCE, line)`. End of input shuts the
server down. The command line options (`core.Options`) have no remote console, query or management option, only
`--bind` for the game port, `--boot-command`, the backup options and the rest. The JVM's own JMX can be switched on
with the standard `-Dcom.sun.management.jmxremote` flags, that is the JVM, not Hytale.

### Commands with captured output

- `CommandManager.get().handleCommand(CommandSender, String)` returns `CompletableFuture<Void>` and can be
  called from any thread. It runs the command on `ForkJoinPool.commonPool()`. An unknown command sends
  `server.modules.command.notFound` to the sender and completes. Every run logs `"%s executed command: %s"` at
  INFO with the sender's `getUsername()`, so a sender named `RestAPI:<key>` names the key in the engine log.
- `AbstractWorldCommand` and `AbstractPlayerCommand` hop to the world thread and the future completes after the
  world task. Some commands reply after the future completes (`/backup` replies from `thenAccept`, plugin
  commands from their own threads), so the capture needs a short grace window after completion, not verified
  how long. A player command sent by a non player fails with `server.commands.errors.playerOrArg`, use the
  forms that take a player argument.
- `CommandSender` extends `IMessageReceiver` and `PermissionHolder` with `getUsername()`, `getUuid()`,
  `sendMessage(Message)`, `hasPermission(String)` and `hasPermission(String, boolean)`. `ConsoleSender` has a
  protected constructor and `INSTANCE`, uuid `new UUID(0, 0)`, name `Console`, every permission true. A capturing
  sender can subclass it or implement `CommandSender` with its own permission answers.
- `MessageUtil.formatMessageToPlainString(message.getFormattedMessage())` resolves translation keys and fills
  parameters.
- The engine parses a comma inside a `STRING` argument as a list and refuses it with "You have specified a list
  of argument values for an argument that does not accept a list". Seen on the smoke run with
  `/restapi keys create grafana read,metrics`. `GREEDY_STRING` takes the rest of the line with commas.
- `withOptionalArg` is given as `--name value`.

### Players and moderation

- Off the world thread: `PlayerRef.getUuid()`, `getUsername()`, `getWorldUuid()`, `isValid()`, `getTransform()`
  (position is `getTransform().getPosition()`), `getPacketHandler()`, `sendMessage(Message)`,
  `hasPermission(String)`, `getComponentConcurrent(ComponentType)`. Ping is
  `getPacketHandler().getPingInfo(PongType.Direct).getPingMetricSet()` in microseconds.
- On the world thread: anything through `Store.getComponent`, which asserts the thread. Health through
  `EntityStatMap` and `DefaultEntityStatTypes.getHealth()`, game mode through `Player.getGameMode()` and the
  static `Player.setGameMode(Ref, GameMode, ComponentAccessor)` (only `Adventure` and `Creative`), inventory
  through `InventoryComponent.getCombined(...)`, give through `Player.giveItem(ItemStack, Ref, accessor)`,
  teleport by putting `Teleport.createForPlayer(World, Transform)` on the entity, kill through
  `DeathComponent.tryAddComponent` with a `Damage` of cause `COMMAND` as the `/kill` command does.
- Kick: `ConnectionRegistry.get().disconnect(UUID, Message)`, any thread.
- Bans: `AccessControlModule.get()` with `ban(Ban)` (kicks when in effect), `unban(UUID)`, `isBanned(UUID)` and
  `getBanProvider().getBans()`. `bans.json`. There is no ban list command in the engine.
- Whitelist: since 0.7.1 it is the permission `hytale.server.join`, switched by `RequireJoinPermission` in
  `config.json`, with `allowJoin(UUID)`, `disallowJoin(UUID)` and `getUsersWithJoinGrant()`. `whitelist.json` is
  migrated once and renamed.
- Permissions: `PermissionsModule.get()` with `addUserToGroup`, `removeUserFromGroup`, `getGroupsForUser`,
  `getAllRegisteredGroups`. Op is the group `hytale:Admin`. These do file IO under locks, call them off the world
  thread.
- Broadcast: `Universe.get().sendMessage(Message)`, any thread. Titles through `EventTitleUtil`.

### Plugins

`PluginManager.get()` has `getPlugins()`, `getPlugin(PluginIdentifier)`, `getAvailablePlugins()`, `load(id)`,
`unload(id)`, `reload(id)`. `PluginState` is NONE, SETUP, START, ENABLED, SHUTDOWN, DISABLED, FAILED. `reload` can
answer false while the new copy is still loading, TrackerAPI polls `getPlugin` for up to 15 seconds. The engine
has no plugin enable or disable event, only `PluginSetupEvent`, so plugin state is polled.

### Events and the log

- `PlayerConnectEvent` (async), `PlayerReadyEvent` (world thread), `PlayerDisconnectEvent` (network thread),
  `PlayerChatEvent` (async, cancellable, take it at `EventPriority.LAST` and skip cancelled ones), `AddWorldEvent`,
  `RemoveWorldEvent`, `BootEvent`, `ShutdownEvent`. Deaths have no bus event, only the ECS system
  `DeathSystems.OnDeathSystem`, which must be registered in setup. Commands have no event, the INFO line above
  covers every sender.
- The logger backend is `HytaleLoggerBackend`, not a java.util.logging tree. `HytaleLoggerBackend.subscribe(
  CopyOnWriteArrayList<LogRecord>)` passes every root record to `list.add`. Pass a subclass whose `add` copies
  into a bounded ring and returns true without storing, and unsubscribe at shutdown. Console replies are
  `RawLogRecord`s with ANSI codes. System.out and System.err are replaced and arrive as records too.
- Log files are `logs/<yyyy-MM-dd_HH-mm-ss>_server.log`.

### Backups

`Universe.get().runBackup()` returns a future and fails when no backup directory is configured (`Backup` in
`config.json` or the `--backup*` options). One world saves with `ChunkSavingSystems.saveChunksInWorld` started on
the world thread plus `WorldConfigSaveSystem.saveWorldConfigAndResources(World)`, as `/world save` does.

## Headless smoke test

The script used for 0.1.0 boots a scratch server with `--assets <Assets.zip> --auth-mode offline --disable-sentry
--bind 127.0.0.1:<port>` and stdin from a named pipe, waits for `RestAPI .* ready` and `Hytale Server Booted`, reads
the first key from the log, curls the API, then pipes `/restapi keys create`, `keys list`, `status`, `reload`,
`audit tail` and `stop`. Do not pass `--bare` and do not pass `--mods` for the same folder.

Runs on 2026-10-16 on the client's bundled 0.7.2 server, alone and with TrackerAPI 1.1.0, Coinage 1.2.0,
Dominion 0.6.2, Feats 1.6.0, Chronicle Stats 1.4.0, Atlas 1.7.0 and Lullaby 1.0.1.

- Log: `RestAPI made the key admin with all. ... Key rapi_...`, then
  `RestAPI 0.1.0 ready, HTTP API on http://127.0.0.1:8125 with 2 routes, 1 key, 0 of 7 suite mods bound`.
- `GET /api/health` without a key answered 200 `{"status":"ok","restapi":"0.1.0"}`. `GET /api/server` with the key
  answered 200 with version 0.7.2, one world at 30 TPS and the seven siblings. Without a key 401, with a wrong key
  401, an unknown path with a key 404, `POST /api/server` 405, `X-API-Key` 200.
- With the seven siblings every one showed as installed at its version with "this build of RestAPI has no routes
  for it yet". `Shut down plugin Ryvrook:RestAPI` came first at stop and no exception was logged.
