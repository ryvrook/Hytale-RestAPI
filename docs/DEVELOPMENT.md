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
| `players` | Players, bans, whitelist, groups and broadcasts, see "Players module" below |
| `console` | `POST /api/commands`, the deny list, idempotency, the log ring and its stream, see "Console module" |
| `control` | Plugins, worlds, server config, backups and the stop, see "Control module" |
| `events` | The event ring and stream, webhooks and `/metrics`, see "Events module" |
| `docs` | `OpenApi` (the document from `Router.routes()`), `DocsRoutes` and the page under `src/main/resources/restapi-docs/` |
| `rcon` | The Source RCON listener, see "RCON module" |

Each module has a package of its own and is listed by class name in `Modules.CLASSES`, so a build with
any of them missing still works and adding one never edits a shared file. A module declares its settings and
text in its own classes and touches them in its constructor, before `RestAPI.json` and `messages.json` are read.
Suite route groups live under `dev.ryvrook.restapi.suite.<id>` and are named in `Sibling`.

A module has four calls. `setup(plugin)` runs in the plugin's setup, the only time the engine takes ECS systems
and event listeners. `routes(api)` returns its routes, every one naming the module's group. `start(api)` runs
after they are mounted, `stop()` at shutdown and before a reload starts it again. Modules share services through
`RestApi.provide` and `RestApi.service`, such as the console module's `CommandService` that RCON runs commands
through and the events module's `Gate.Observer` that counts requests for `/metrics`. Two more calls are optional.
`status()` gives lines for `/restapi status`, and `lines(topic)` answers a `/restapi <name> <topic>` sub command
such as `/restapi rcon status`. A route carries a `RouteDoc` with query parameters, examples, error codes and,
when the example is not enough to infer them, the media type (`produces`) and sentences for its path parameters.

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
    exception text, and the cause goes to the server log. That includes `AssertionError`, which the engine's
    store checks throw when a component is read off its thread. Without the catch the request hangs.
11. Audit for `WRITE` and `DANGEROUS` routes, and for reads with `audit.logReads`.
12. The observer, when the events module runs, gets the method, the matched pattern (null when none matched)
    and the status of every answer, and a call for each request turned away in step 5.

Every answer carries `Cache-Control: no-store` and `X-Content-Type-Options: nosniff`.

A handler that answers `Response.stream(stream)` gets Server Sent Events. `HttpHost` gives back the in flight
slot, takes one of `http.maxStreams` stream slots or answers 503, sends the headers with
`X-Accel-Buffering: no` so nginx does not buffer, and runs the stream on the request's virtual thread until it
returns or the client leaves. `Sse.format` keeps a name or id on one line and splits data on line breaks.

Settings docs are one file per section under `docs/settings/`, written by the tests with
`RESTAPI_WRITE_DOCS=1`, so a module never edits another module's doc file.

## Threads

| Thread | What runs there |
|---|---|
| JDK HTTP server, one virtual thread per request | `Gate` and every handler. Never blocks on a world thread without a timeout |
| `RestAPI-scheduler-1` and `-2` | World samples, sibling checks, `/restapi` work that reads or writes files |
| `RestAPI-audit` | Appends to the audit file and prunes old ones |
| World threads | `WorldSampler.read` through `world.execute` |
| Common pool | `/restapi` sub commands start here (`CommandBase.executeSync`) and hand off to the scheduler. Commands run through the API run here too, the engine puts them there |
| `RestAPI-command-<run id>`, virtual | Waits for one command run, its grace window and the audit lines |
| `RestAPI-webhook-<id>`, virtual | One per webhook, delivers its queue in order |
| `RestAPI-rcon-accept` and `RestAPI-rcon-<n>`, virtual | The RCON accept loop and one per connection |
| `RestAPI-relink` | A daemon platform thread, only after a reload of an older sibling without a dependents pass. Waits three seconds and reloads RestAPI under the suite lock |
| `RestAPI-stop` | A platform thread that is not a daemon. Counts down a stop and calls `shutdownServer`, since the plugin's shutdown stops the scheduler |
| Logger threads | `LogTap.add` and the events module's command tap copy a line and return |

Replies to a player go back through `world.execute` on that player's world, as in the siblings.

## Suite detection

`Siblings` asks `PluginManager.getPlugin(new PluginIdentifier("Ryvrook", name))` for each of the eight mods at
start, on `/restapi reload` and every `siblings.checkSeconds`. It never touches a class of the mod for that. A mod
is bound when its state is `ENABLED`, its manifest version is at or above the minimum in `Sibling` (or below it
under `always`), and `siblings.<id>` is not `never`. Binding loads the unit class by name, calls `bind`, checks
that every route sits under `/api/<id>`, names the group `suite.<id>` and needs the mod's scope, and mounts the
group. Anything else unmounts it.

The plugin object is kept at bind. A different object later means the mod was reloaded. The unit's classes were
linked against the old copy, so the group stays down until RestAPI itself is reloaded. Every suite mod from the
version in `Sibling.hooksSince` on (TrackerAPI 1.1.0, Coinage 1.2.0, Dominion 0.7.0, Feats 1.7.0, Chronicle Stats
1.5.0, Atlas 1.8.0, Lullaby 1.1.0, SocialBridge 0.2.0) reloads every plugin that lists it two seconds after its
own start, under `ryvrook.dependents.lock` and in dependency order. RestAPI lists all eight under `OptionalDependencies` at those
versions, so it comes back on its own under the suite reload rule in TrackerAPI's `docs/DEVELOPMENT.md`. The
unbind on the way there (state `SHUTDOWN`, then a new plugin object) is logged at FINE only, through the second
log consumer of `Siblings`. Listing them also makes the engine load them before RestAPI and stop RestAPI first,
seen in the smoke run.

An older copy bound under `always` may have neither the hooks nor a dependents pass. For those `Siblings` calls
`onStaleLink` once per RestAPI instance and `SelfReload` reloads RestAPI on a daemon thread
of its own (`RestAPI-relink`), three seconds later, under `ryvrook.dependents.lock`, and only when the plugin
manager still holds the instance that asked. When a pass of another sibling reloaded RestAPI meanwhile, the relink
finds a new instance and does nothing. RestAPI lists SocialBridge too, so a pass that reloads both reloads
SocialBridge first. Everything the thread needs is built before it sleeps.

Nothing on the stop path loads a sibling class for the first time. `shutdown` calls `unbind` on the bound units
only, and those touch the hook classes `bind` already loaded. During shutdown the plugin manager holds its write
lock, and a first load through a sibling's class loader there deadlocks.

The engine has no plugin enabled event, so presence is still a poll of the plugin manager. The API object each
unit reads comes from the sibling's static `onEnable(Consumer)` hook and is dropped by `onDisable(Runnable)`, both
taken back with `removeHooks` in `unbind`. No unit polls `get()` for a sibling at or above `hooksSince`.

## Reload

`/restapi reload` reads the three files again in place, remounts the modules, checks the siblings, moves the HTTP
server when the bind or port changed and leaves it alone otherwise. A `RestAPI.json` with a bad value changes
nothing. RestAPI calls `PluginManager.reload` on itself only from `SelfReload`, after a sibling without a
dependents pass was reloaded, and the command module must keep any command that reloads RestAPI on its deny list.

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
  commands from their own threads), so the capture needs a short grace window after completion. The console
  module notes below have the measurements, late lines landed within 3 ms. A player command sent by a non player fails with `server.commands.errors.playerOrArg`, use the
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
  `getAllRegisteredGroups`. There is no op flag. `/op` adds the group `hytale:Admin`, whose permission is `*`, and
  RestAPI reads op from the provider's group strings. These do file IO under locks, call them off the world
  thread.
- Rotations are radians inside the engine. `/tp` takes degrees and converts, the API does the same.
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

## Modules

Each module's maintainer notes, written while it was built and checked on smoke runs.


### Players module

Package `dev.ryvrook.restapi.players`, group `players`, 28 routes with every switch on. Read with javap on
`Server-0.7.1.jar` and seen on a headless smoke run on the client's bundled 0.7.2 server on 2026-10-16, unless a
line says otherwise.

#### Classes

| Class | What it does |
|---|---|
| `PlayersModule` | Routes, paging, the 30 second name sweep on the scheduler, the disconnect listener registered in setup |
| `PlayersSettings`, `PlayersText` | `players.*` settings and `players.*` messages |
| `Lookup` | `{player}` to a UUID, a name and the `PlayerRef` when online |
| `NameBook` | `names.json`, the last name seen per UUID |
| `WorldCall` | Runs a task on a world thread, waits `players.worldTimeoutMs`, 504 `world_timeout` after |
| `PlayerRead` | List, detail and inventory |
| `PlayerActions` | Kick, message, title, teleport, give, clear, game mode, heal, kill, refer, broadcasts |
| `Moderation` | Bans, whitelist, groups |
| `Fields` | Typed body fields with 400 `missing_field` or `invalid_field` |

#### Threads

On the request thread: `PlayerRef.getUuid`, `getUsername`, `getWorldUuid`, `getTransform`, `getHeadRotation`,
`getLanguage`, `getPacketHandler`, `sendMessage`, `referToServer` (it only writes a packet), the title packets
through `EventTitleUtil` (`PacketHandler.writeNoCache`), `ConnectionRegistry.disconnect`, every
`AccessControlModule` and `PermissionsModule` call, `PlayerStorage.getPlayers()` and `Item.getAssetMap()`.

On the world thread through `WorldCall`: `Store.getComponent` for `Player`, `EntityStatMap` and the inventory
components, `Player.giveItem`, `Player.setGameMode`, `EntityStatMap.maximizeStatValue`, the `Teleport` component
and `DeathComponent.tryAddComponent`. The entity ref is `PlayerRef.getReference()`, checked with `isValid()` on
the world thread. A player whose ref is not valid is between worlds and gets 409 `not_in_world`.

A world task that passes the timeout is not taken back. The 504 answer says a change may still land.

#### Engine facts

- Rotations are radians inside the engine. `/tp` takes degrees and multiplies by 0.017453292, so the API takes
  yaw and pitch in degrees and converts, and the detail answers head rotation in degrees.
  `Rotation3f(x, y, z)` is pitch, yaw, roll.
- A teleport is `store.addComponent(ref, Teleport.getComponentType(), Teleport.createForPlayer(world, transform))`
  on the player's current world thread, as `TeleportOtherToPlayerCommand` does. The target world goes in the
  component, so one call covers both cases.
- Inventory sections come from `InventoryComponent.getComponentTypeById(id)` with the section ids hotbar -1,
  storage -2, armor -3, utility -5, tool -8, backpack -9, and `ItemContainer.forEach((slot, stack) -> ...)`.
  `ItemContainer.clear()` answers a `ClearTransaction` whose `getItems()` are the stacks it removed.
- `Player.giveItem(stack, ref, store)` answers an `ItemStackTransaction`, its `getRemainder()` is what did not fit.
- Kill is `new Damage(new Damage.CommandSource(ConsoleSender.INSTANCE, "kill"), DamageCause.COMMAND, Float.MAX_VALUE)`
  as `/kill` does. `DamageCause.COMMAND` is deprecated in 0.7.1 but it is what the engine still uses.
- `EventTitleUtil.showEventTitleToPlayer(ref, title, subtitle, boolean, ...)` is deprecated for removal. The
  overload with `EventTitleStyle` (`Default`, `Major`) is used. The floats are duration, fade in, fade out, and
  the defaults are 4.0, 1.5 and 1.5.
- Ping is `getPingInfo(PongType.Direct).getPingMetricSet().getAverage(ONE_MINUTE_INDEX)` in `PingInfo.TIME_UNIT`.
- `AccessControlModule.ban(Ban)` answers false for a ban that is not in effect or when the provider already holds
  one, and kicks the target through `ConnectionRegistry` when it adds one. A `Ban` with a null `expiresOn` is
  permanent. `Ban.withMetadata("RestApiKey", Codec.STRING, key)` is stored in `bans.json` under `Metadata`, seen
  on the smoke run, so the ban list names the key that made it.
- `setJoinPermissionRequired(boolean)` sets `RequireJoinPermission` and saves `config.json` through
  `HytaleServerConfig.save`, its future completes after the save. The smoke run read `true` and then `false` back
  from `config.json`. `allowJoin(UUID)` writes the permission `hytale.server.join` into `permissions.json`.
- Groups registered on a fresh 0.7.2 server: `hytale:Admin` (parent `hytale:ServerEditor`, permission `*`),
  `hytale:ServerEditor`, `hytale:WorldEditor`, `hytale:Builder`, `hytale:Adventurer`, `hytale:None`. A user with
  no stored group reads as `hytale:Adventurer`. `/op` maps to `hytale:Admin`.
  `addUserToGroup` and `removeUserFromGroup` write `permissions.json` at once.
- `PlayerStorage.getPlayers()` lists UUIDs only. Offline mode has no profile lookup by name, which is why
  `names.json` exists.

#### Not verified

Every route that needs an online player (list with a player in it, detail with stats, inventory, message, title,
teleport, give, clear, game mode, heal, kill, refer, broadcast with recipients) was not run, a headless server
cannot take a client join. Their engine calls copy what the engine's own commands do, read with javap. The first
run with a client should cover them before 1.0.0.


### Console module

Package `dev.ryvrook.restapi.console`. `ConsoleModule` mounts group `console` and provides `CommandService` from
start to stop. `CommandRunner` is the service, `EngineCommands` the only class that calls `CommandManager`,
`CapturingSender` the sender, `DenyList` and `Idempotency` the checks, `LogTap` the log subscription and `LogRing`
the copy of the log. Everything but `EngineCommands` and the subscribe calls in `LogTap` runs in tests against a
stand in engine.

#### Engine facts, read with javap on Server-0.7.1.jar and seen on smoke runs on 0.7.2

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

#### The grace window, measured

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

#### Decisions

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


### Control module

Package `dev.ryvrook.restapi.control`, group `control`. Every engine call below was read with javap on
`Server-0.7.1.jar` on 2026-10-16 and run on the headless smoke server (bundled 0.7.2) unless it says otherwise.

#### Classes

| Class | What it holds |
|---|---|
| `ControlModule` | The module. Keeps the pending stop, the backup jobs and the idempotency answers across `/restapi reload`, and wraps every handler so an engine `AssertionError` becomes a 500 |
| `ControlSettings`, `ControlText` | Section `control` and the ids `control.*` |
| `PluginRoutes`, `WorldRoutes`, `ServerRoutes` | The routes of 4.3, 4.2 and the server half of 4.2 |
| `SelfGuard`, `PluginId` | `Group:Name` parsing and the refusal of `Ryvrook:RestAPI` in any letter case |
| `SuiteLock` | The `ryvrook.dependents.lock` lock from TrackerAPI's reload rule |
| `OnWorld` | Runs work on a world thread and waits with a timeout, 504 `world_timeout` after it |
| `Replays` | `Idempotency-Key` for the dangerous routes, in memory |
| `Stopper`, `StopBody` | The stop countdown and its body |
| `BackupJobs` | Backup runs started through the API |
| `Bodies`, `ConfigPatch`, `TimeBody` | Body parsing, pure and tested |

#### Plugins

- `PluginManager.get()` with `getPlugins()`, `getPlugin(PluginIdentifier)`, `getAvailablePlugins()` (a map of
  identifier to `PluginManifest`), `load`, `unload`, `reload`, all called from the request's virtual thread.
  The engine's `/plugin` command calls them from the common pool, so no world thread is involved.
- `getAvailablePlugins()` answered an empty list on the smoke run with the seven suite jars and 114 plugins
  loaded. It holds manifests found but not loaded, the loaded ones are filtered out by RestAPI as well.
- `/plugin load --boot` is `HytaleServerConfig.setBoot(config, id, true)`, then `consumeHasChanged()` and
  `HytaleServerConfig.save(config)`. The API does the same for `boot: true` on load and unload.
- `JavaPlugin.getFile()` is the jar path. Built in plugins report the server jar.
- `reload(id)` of Lullaby 1.0.1 through the API on the smoke run: one "Lullaby restored the vanilla slumber
  systems", one "Enabled plugin Ryvrook:Lullaby", answered 200 after 128 ms with the lock held. The same
  request with the same `Idempotency-Key` answered the stored body with `replayed: true` and the log showed no
  second start.
- A reload of TrackerAPI or Coinage through the API also reloads RestAPI, since their dependents pass reloads
  every plugin that lists them and RestAPI lists all seven under `OptionalDependencies`. The route releases the
  suite lock before it answers, and TrackerAPI's pass waits two seconds and then takes the lock, so the answer
  goes out first. Not run on the smoke server.

#### Worlds

- Time. `WorldTimeResource` from `world.getEntityStore().getStore().getResource(WorldTimeResource.getResourceType())`
  on the world thread. `/time <hour>` calls `setDayTime(hour / 24, world, store)`, which throws for a value
  outside 0 to 1 and moves to the next day when the new time is before the current one. Read back with
  `getCurrentHour()`, `getDayProgress()`, `getMoonPhase()`, `getGameTime()`. On the smoke run hour 6 read back
  as hour 6, progress 0.25.
- Weather. `WeatherSetCommand.setForcedWeather` is protected static, so RestAPI does what it does: the
  resource's `setForcedWeather(id)`, then `WorldConfig.setForcedWeather(id)` and `markChanged()`, all on the world
  thread. Null clears it, as `/weather reset`. `getForcedWeatherIndex()` is 0 when nothing is forced. Names come
  from `Weather.getAssetMap().getAssetMap().keySet()`. `Zone1_Rain` set and cleared on the smoke run.
- Pause and ticking. `World.setPaused` and `setTicking` on the world thread, as `WorldPauseCommand` (a world
  command) does. `setTicking` also writes `WorldConfig.setTicking` and marks the config changed.
- Save. `/world save` runs `ChunkSavingSystems.saveChunksInWorld(chunkStore, world)` and
  `WorldConfigSaveSystem.saveWorldConfigAndResources(world)`. The second one calls `Store.saveAllResources`,
  which asserts the store thread: called from the request thread on the first smoke run it threw
  `AssertionError: Data can only be accessed from a store thread!`. Both now start inside one
  `supplyAsync(..., world)`.
- Load and unload. `Universe.isWorldLoadable(name)` then `loadWorld(name)` (a future), and `removeWorld(name)`
  which answers a boolean. The engine's `/world remove` refuses the default world from
  `HytaleServerConfig.getDefaults().getWorld()` and the last world, RestAPI refuses the same two.
- Lists read `World.getName()`, `isAlive()`, `isTicking()`, `isPaused()`, `getPlayerCount()`, `getTick()`,
  `getSavePath()`, `getDaytimeDurationSeconds()`, `getWorldConfig()` off the world thread, plus the core
  module's samples for TPS, chunks and entities.

#### Server

- `HytaleServerConfig` setters `setServerName`, `setMotd`, `setMaxPlayers`, `setMaxViewRadius` (synchronized,
  calls `markChanged`), `setRequireJoinPermission` (what `AccessControlModule.setJoinPermissionRequired` calls
  before it saves), then `HytaleServerConfig.save(config)`. `MAX_VIEW_RADIUS_LIMIT` caps the view radius.
- `BackupConfig` is `com.hypixel.hytale.server.core.config.BackupConfig`, `getDirectory()` null means not
  configured. `--backup-dir` sets the directory while `isConfigured()` still answers false, so RestAPI checks
  the directory. `Universe.runBackup()` returns a future. On the smoke run it finished in 75 ms on a fresh
  universe and left `backups/2026-10-16_13-10-54.zip`, named in local time.
- A `PATCH` with `motd` and `maxPlayers` was in `config.json` as `MOTD` and `MaxPlayers` right after the answer.
- Stop. `HytaleServer.shutdownServer(ShutdownReason)`. `ShutdownReason.SHUTDOWN` is code 0, any other code is
  `new ShutdownReason(code, name)`, and `withMessage(Message)` sets what players see when they are
  disconnected. RestAPI calls it from a thread of its own named `RestAPI-stop`, since the plugin's shutdown stops
  RestAPI's scheduler that the countdown runs on. The process exit code is the reason's code: a 5 second stop
  with the seven suite jars exited with 0 five seconds after the answer, and a 2 second stop with
  `restart: true` and RestAPI alone exited with 8. Both left the `server stop` audit line, written before
  `shutdownServer`, and `Shut down plugin Ryvrook:RestAPI` came after it.

#### Not verified

- `World.setPaused` with players online. `WorldPauseCommand` checks the player count first and answers
  `server.commands.pause.tooManyPlayers`. RestAPI does not, and no player was online on the smoke run.
- `Universe.loadWorld` of a world saved earlier and unload with players in it. The smoke run had one world.
- `PluginManager.load` of a jar dropped into `mods/` after the boot.


### Events module

Package `dev.ryvrook.restapi.events`, group `events`.

#### Classes

| Class | What it does |
|---|---|
| `EventsModule` | Registers the engine hooks in `setup`, mounts the routes, starts the webhook workers, the log subscription and the plugin poll in `start`, stops them in `stop` |
| `EventsSettings`, `EventsText` | Sections `events`, `webhooks`, `metrics` and the `events.*` ids of messages.json |
| `EventRing` | The last `events.ring` events, rising ids, resume with `since`, one bounded queue per open stream |
| `EngineEvents` | Bus listeners, the death system, the command log subscription and the plugin poll |
| `EventRoutes` | `GET /api/events` (SSE) and `GET /api/events/recent` |
| `Webhooks`, `WebhookRoutes` | webhooks.json, one worker per hook, HMAC, retries, the private target check |
| `Metrics` | Prometheus text writer with no engine in it |
| `MetricsRoute` | `GET /metrics` |
| `RequestCounters` | `restapi_requests_total` and `restapi_auth_failures_total`, fed by the gate through `Gate.Observer` |

#### Engine facts

Read with javap on Server-0.7.1.jar and seen on the headless smoke on 2026-10-16 (client's bundled 0.7.2).

- `PlayerConnectEvent` is `IAsyncEvent<Void>`, taken with `registerAsyncGlobal`. `getPlayerRef()` and `getWorld()`.
- `PlayerReadyEvent` extends `PlayerEvent<String>` with `getPlayerRef()` as an entity `Ref`, world thread. The
  name and uuid come from `store.getComponent(ref, PlayerRef.getComponentType())`. It fires on every world entry,
  so it is its own type `ready` next to `join`.
- `PlayerDisconnectEvent` extends `PlayerRefEvent` with `getPlayerRef()` and `getDisconnectReason()`.
- `PlayerChatEvent` is async and cancellable, taken at `EventPriority.LAST`, cancelled ones skipped.
- `AddWorldEvent` (cancellable) and `RemoveWorldEvent` (cancellable, `getRemovalReason()`) both extend
  `WorldEvent` with `getWorld()`. Taken at LAST. A cancelled removal is skipped unless the reason is
  EXCEPTIONAL, the TrackerAPI rule. Seen on the smoke: `world add smokeworld` and `world remove smokeworld`
  each gave one event, the default world gave `added` at boot and `removed` at stop.
- `BootEvent` and `ShutdownEvent` are `IEvent<Void>`, taken with `register(Class, Consumer)`. Seen: `booted`
  about a second after the default world was added, `stopping` right after the `stop` command, before the
  plugins shut down, so a webhook queue gets the two second drain in `stop`.
- Deaths: `DeathSystems.OnDeathSystem` with `Archetype.empty()`, registered through
  `getEntityStoreRegistry().registerSystem` in setup. World thread. Cause from `DeathComponent.getDeathCause()`,
  else the `DamageCause` asset map by index. The killer from `Damage.EntitySource.getRef()` when it is a valid
  ref of the same store and not the victim. Registration was accepted on 0.7.2. A death could not be caused
  headless, no player can join, so the death path is checked by reading only.
- Commands: the `CommandManager` logs `<sender> executed command: <line>` at INFO for every run. A list passed
  to `HytaleLoggerBackend.subscribe` gets `add(LogRecord)` for every root record. `CommandTap.add` keeps
  nothing and returns true. `commandOf` reads the record both as the format `%s executed command: %s` with two
  parameters and as formatted text. Seen: `Console executed command: say hello from the smoke` became
  `{"by":"Console","command":"say hello from the smoke"}`. Unsubscribed in `stop`.
- Plugins: no enable event in 0.7.1. `PluginManager.get().getPlugins()` with `getIdentifier()`, `getState()`
  and `getManifest().getVersion()` every `events.pluginPollSeconds`. An unloaded plugin leaves the list, so its
  `to` is `NONE`. Seen: `plugin unload Ryvrook:Lullaby` gave ENABLED to NONE within two seconds, `plugin load`
  NONE to ENABLED. The first poll is the baseline. RestAPI's own START to ENABLED shows because the first poll
  runs inside `start`. 107 core plugins are ENABLED on a bare 0.7.2 server.

#### Ring and ids

Ids start at the module's construction time in milliseconds times 1000 and count up, about 1.79e15 in 2026,
under 2^53 so JavaScript keeps them exact. An id below the base is from an earlier run, an id at or above the
next one is unknown. Both resume as a gap with the whole ring. `subscribe` takes the replay and registers the
stream queue under one lock, so nothing is lost or doubled between the two. A stream queue holds 1024 events,
a reader that falls that far behind gets an `overflow` event and is closed. The ring and open streams survive
`/restapi reload`. At a server stop `HttpHost.stop` interrupts the stream threads.

#### Webhooks

One virtual thread per hook named `RestAPI-webhook-<id>`, not one per delivery, so a target sees events in
order. The JDK `HttpClient` is pinned to HTTP/1.1. With the default version it sent `Upgrade: h2c` on plain
http, and against `nc -l` the body never arrived. Redirects are never followed. A hook made over the API is
marked `viaApi` in the file and checked for private targets at creation and before every delivery, which
catches a name that resolves to the LAN later. The check and the client resolve separately, so a name that
changes between the two within milliseconds is not caught. Hand written hooks skip the check.

#### Verification

Tests in `src/test/java/dev/ryvrook/restapi/events/`: the ring, the stream through a fake sink, recent with its
checks, webhooks through a fake transport (retries and their waits, no retry on 404, queue overflow drops the
oldest, private targets at creation and delivery), the JDK client against a local `HttpServer` (signature,
no redirect), the HMAC against RFC 4231 case 2, the Prometheus format with a line validator, the command line
parser and the plugin diff. promtool is not installed here, the validator checks the line shapes promtool
checks on one exposition.

Headless smoke with a copy of the smoke script, HTTP on 8134, game on 5534, with Lullaby 1.0.1.

- `/metrics` without a key 401, with one 200 `text/plain; version=0.0.4`.
- A webhook to `http://127.0.0.1:9134` answered 400 `private_target`, accepted after
  `webhooks.allowPrivateTargets` true and `/restapi reload`.
- `nc -d -l 127.0.0.1 9134` received a POST with `X-RestAPI-Signature: sha256=1187...13fb5`, and the HMAC of
  the received body with the secret from the create answer matched.
- A Discord format hook got `{"content":"Console ran /say hello from the smoke","allowed_mentions":{"parse":[]}}`
  and eight more, the test route answered `{"ok":true,"status":204}`.
- `curl -N /api/events` stayed open across `/restapi reload` and printed command, world, plugin and server
  events. `Last-Event-ID` resumed after the given id. `Last-Event-ID: 5` gave a `gap` event and the ring.


### RCON module

Package `dev.ryvrook.restapi.rcon`. `RconModule` (group `rcon`, no routes) opens the port in `start` when
`rcon.enabled` is on and closes it with every connection in `stop`, so `/restapi reload` applies a new bind or
port and drops the open connections. `RconServer` accepts on one virtual thread named `RestAPI-rcon-accept` and
runs each connection as an `RconSession` on a virtual thread `RestAPI-rcon-<n>`. `Packet` is the codec, pure and
tested against byte arrays.

The session looks up `RestApi.service(CommandService.class)` for every command, not once at start, so it works
whichever order the console and RCON modules start in and after the console module restarts on a reload. When
the service is missing the reply says the console module is not running and nothing runs.

#### Protocol as built

Read from the Minecraft wiki and the Valve talk page, then checked against mcrcon 0.7.2 (`mcrcon.c`, built from
github.com/Tiiffi/mcrcon at b5951e9) and a headless smoke run.

- A packet is int32 length, int32 id, int32 type, the body, two null bytes, little endian. The length counts
  id, type, body and both nulls, so it is at least 10. A request may carry 4096 body bytes. A length under 10 or
  over 4106 closes the connection, and on a connection that has not logged in it counts as a failed login.
- Login is type 3 with the key as the body. The answer is type 2 with the request id, or id -1 when refused.
  Before a login every packet answers type 2 id -1, as Minecraft does for a command.
- A command is type 2. The reply is type 0 with the same id. A body over 4086 bytes goes out as several type 0
  packets with the same id, cut after a line break when one sits in the second half of the piece and never inside
  a UTF-8 character. 4086 keeps the length field at 4096 or less. Valve's description gives 4096 as the largest
  packet and mcrcon refuses a length over 4096 with "invalid packet size", so the 4096 body bytes Minecraft sends
  in one piece would break it.
- An empty type 0 is echoed back with its id and an empty body. Any other type answers type 0
  `Unknown request <hex type>`. Packets are handled strictly in order, so either one sent after a command marks
  the end of a split reply.
- mcrcon sends every packet with the id `0xBADC0DE` and reads exactly one packet per command, both seen in its
  source. A long reply therefore shows only its first piece in mcrcon.

#### No idempotency key on RCON

The evaluation planned an idempotency key built from the connection and the request id. mcrcon uses the same
id for every packet, so that key would replay the first answer for every later command on the connection.
Inside one TCP connection a packet never arrives twice, and a client that reconnects cannot be told apart from
one that repeats a command on purpose. RCON passes no idempotency key. The command service's run id and audit
lines are the trace for a lost answer.

#### Login checks

A key logs in when it holds the `commands` scope, its own `allowFrom` is empty or holds the address, and
`commands.allowFrom` holds the address. `commands.allowFrom` and `commands.timeoutMs` belong to the console
module and are read by key name through `Settings.all()`, so this package builds without it. Without the console
module the defaults are loopback and 5000 ms. Before each command the key is looked up again by name. A revoked
key or one that lost the scope gets one reply saying so and the connection closes.

Logins are audited with source `rcon`, the key name, the address, action `login` and detail `ok`. Refused
logins are audited under `audit.logAuthFailures` with the reason (`wrong key`, `key lacks the commands scope`,
`address not in allowFrom`, `address not in commands.allowFrom`) and the key name when the token matched one,
else `unknown`. Command runs are audited by the command service, which gets source `rcon`.

Failed logins go into a `RateLimiter` per address with `rcon.authFailuresPerMinute` per minute. The failure that
empties the bucket closes the connection and new connections from that address are closed on accept until the
bucket has a token again. Connections over `rcon.maxConnections` are closed on accept with one log line.
`rcon.idleSeconds` is the socket read timeout.

#### Smoke on 2026-10-16

Client's bundled 0.7.2 server, RestAPI with a stand in for the console module that runs lines through
`CommandManager.get().handleCommand` with a capturing sender named `RestAPI:<key>`, HTTP on 8136 and RCON on
25586. Alone and with the seven sibling jars.

- `RestAPI RCON on 127.0.0.1:25586, the password is any key with the commands scope`, then the ready line, again
  after `/restapi reload`.
- `mcrcon -p <admin key> "say hello from rcon"` exit 0, engine log
  `RestAPI:admin executed command: say hello from rcon`. Two commands on one mcrcon connection both ran.
- `help` with the seven siblings is 4812 bytes. The Java client got it in 2 packets, the largest length field
  4066, and joined them into 113 lines. Alone it is 3947 bytes in one packet.
- A wrong key and a key with only `read` both gave `Authentication failed!` and exit 1, audited as `wrong key` and
  `key lacks the commands scope`.
- `stop` answered `stop is on the deny list and did not run.`, `/api/health` still answered 200 after it, and
  `plugin reload Ryvrook:RestAPI` was refused the same way. Neither reached the engine log.
- `stop` from the console ended the server with exit code 0, no exception in the log.

### Suite route groups

One unit class per sibling under `dev.ryvrook.restapi.suite.<id>`, named in `Sibling`, is the only code that
imports that sibling. `Siblings` loads it by name after the presence check and mounts what `bind` returns as group
`suite.<id>`. Shared helpers sit in `dev.ryvrook.restapi.suite` and import no sibling: `Json`, `Limits`, `Names`
(UUID, then online players through `Universe.getPlayerByUsername(name, NameMatching.EXACT_IGNORE_CASE)`, then the
lookups a unit adds), `Waits` (a sibling future with `suite.waitMs`, 504 `sibling_timeout`), `JsonFile` (a sibling's
file, parsed again only when its modified time or size changed), `Replays` (Idempotency-Key answers) and `Folders`
(the sibling's data folder from `PluginBase.getDataDirectory()`, else `mods/Ryvrook_<Name>`). `SuiteModule` serves
`GET /api/suite` and touches `SuiteSettings` and `SuiteText` so their keys exist before the files are read.

Each unit builds its routes in a static `routes(...)` over a supplier of the sibling API and the limits, so the
tests drive every route over a stand in: a subclass of `TrackerApi`, `CoinageApi` and `AtlasApi` (their
constructors are protected), proxies for the sibling interfaces (`Counters`, `Journal`, `Accounts`, `Ledger`,
`DominionApi`, `TerritoryProvider`), real `Faction`, `Member`, `Currency.coins()`, `Transaction` and `StatDef`
objects, and `FeatsSource` and `ChronicleSource` interfaces because `FeatsApi` and `ChronicleApi` are final classes
only their plugin makes. No suite test touches `SuiteSettings`, the units read it in `bind` only, so the settings
docs stay the same whatever order the tests run in.

#### Binding, per sibling, read from the jars on 2026-10-16

| Sibling | Bound from | Read at each request |
|---|---|---|
| TrackerAPI 1.1.0 | `TrackerApi.onEnable` and `onDisable`, taken back with `removeHooks` in `unbind` | the API object the hook handed out last |
| Coinage 1.3.0 (API 5) | `CoinageApi.onEnable`, `onDisable`, `removeHooks` | the same |
| Dominion 0.7.0 (API 2) | `DominionApi.onEnable`, `onDisable`, `removeHooks` | the same |
| Feats 1.7.0 (API 2) | `FeatsApi.onEnable`, `onDisable`, `removeHooks`, inside `FeatsBridge` | the same |
| Chronicle Stats 1.5.0 (API 2) | `ChronicleApi.onEnable`, `onDisable`, `removeHooks`, inside `ChronicleBridge` | the same |
| Atlas 1.8.0 (VERSION 2) | `AtlasApi.onEnable`, `onDisable`, `removeHooks` | the same, `SessionApi.get()` for hidden players |
| Lullaby 1.1.0 (API 1) | `LullabyApi.onEnable`, `onDisable`, `removeHooks` | the same, `ignored.json` for the opt out count |
| SocialBridge 0.2.0 (API 1) | `SocialBridgeApi.onEnable`, `onDisable`, `removeHooks` | the same |

`onEnable` runs at once when the API is up, so `bind` registers the hooks, checks that one fired, and removes
them again before it throws when none did. The registry reports the reason and tries again on the next check. A
route whose API went away answers 503 `sibling_gone` until the registry unbinds the group.

Each call that came with the version above is wrapped so an older copy bound under `always` still serves what it
can. A static hook or a default method that is missing throws `NoSuchMethodError` (an abstract method of
`AtlasApi` missing in the implementing class throws `AbstractMethodError`, a class missing altogether
`NoClassDefFoundError`), and the unit falls back: `DominionApi.get()` and `FeatsApi.get()` and `ChronicleApi.get()`
on every request instead of the hooks, `Initiator.plugin("RestAPI")` instead of `Initiator.apiKey`, the ranked
`top` instead of `factions()`, the siege banners instead of `sieges()`, the export files instead of `FeatsApi.top`
and Chronicle's boards and totals, `atlas.json` instead of `publicUrl()`. Routes with no old path answer 501
`not_supported`. Lullaby 1.0.1 has no `LullabyApi` class, `bind` turns the `NoClassDefFoundError` into a reason.
`CoinageApi.API_VERSION` is read through reflection on the live class, the field is a compile time constant that
javac would inline with the version of the jar RestAPI was built against.

#### Threads

Every route runs on the HTTP virtual thread. All eight APIs say they are safe from any thread and read memory,
except these, which complete a future on the sibling's own IO thread and are waited on for `suite.waitMs`:
`Counters.load`, `nameOf` and `uuidOf` (TrackerAPI names index), `Journal.find` (newest first, at most four at
once inside TrackerAPI), `Ledger.search` (Coinage journal files) and `ChronicleApi.totalsAsync`. A leaderboard asks
`nameOf` for every row at once and leaves a name null when its lookup did not finish in time. `Reports.days` reads
a closed day's small file on the calling thread the first time.

The recent events ring is one `subscribeKinds("RestAPI", kinds, ASYNC, ...)` on TrackerAPI's dispatch thread. The
listener only appends to a deque per kind under one lock, `suite.tracker.recentPerKind` long, read on every add.
The high volume kinds (`EventKind.highVolume()`) are left out unless `suite.tracker.recentHighVolume` is on, and
nothing is subscribed with 0. The subscription is closed in `unbind` and in the disable hook.

#### What each group reads

- TrackerAPI. Counters through `Counters.all` for a loaded player, `load` for an offline one. Leaderboards through
  `boardKeys` and `top`, capped at 1000 by TrackerAPI. Snapshots, `Stats`, `isTracked`, `Journal.kinds`. The
  journal route builds `Journal.Query` from the query string, the box needs all six bounds, `next` is the oldest
  `at` minus one when the page was full. Events become JSON through their record components, so a field TrackerAPI
  adds shows up without a change here.
- Coinage. `Currencies.resolve` (null is the primary), `Accounts.get`, `byHandle`, `uuidOf`, `shared`, `players`,
  `all`, `top`, `rank`, `Ledger.history`, `search`, `find`, `stats`, `Reports.netWorth`, `topNetWorth`, `today`,
  `days`. `Accounts.player(uuid)` is never called, it makes an account. The writes call `Ledger.apply` with
  `Initiator.apiKey(<key name>)`, `plugin("RestAPI")`, `overdraft(false)` and the reason `restapi:<key>[:<reason>]`.
  Coinage 1.3.0's `ReportRules.source` counts a move under the key name. The ledger search passes `initiator`
  through `Query.initiator(name)`, a case insensitive match on `Transaction.initiator().name()`. Amounts go through `Currency.parse`.
  A `Result` that failed answers with the failure in lower case and Coinage's own sentence, 409 for funds, limits
  and locks, 404, 400, 403 or 503 for the others.
- Dominion. `top(Integer.MAX_VALUE)` is the ranking order of the normal factions, `factions()` adds the safe and
  war zones (sorted by tag), which the list puts after the ranked ones and relations and names resolve against.
  Claims come from `territoryProvider().territories(world)` per loaded world, chunk coordinates through
  `ChunkUtil.xOfChunkIndex` and `zOfChunkIndex` (high and low 32 bits). Sieges come from `sieges()` and
  `siegeHistory(factionId, limit)` as `SiegeInfo`, the phase written in lower case. The staff routes call
  `disband`, `setLeader`, `setPower`, `unclaim` and `unclaimAll` with `by` set to `RestAPI:<key name>` and answer
  `Result.success()` and `message()`. Warp passwords are never read.
- Feats. `feats`, `points`, `unlocked`, `progress`, `title`, `rank`, `milestones`, `firsts`, `stats` and `top`
  (live ranking from the last export with online players merged, cached 5 s inside Feats). The recent unlocks and
  the rarity per feat come from `export/feats.json` (newest 50, `rarity` per feat).
- Chronicle Stats. `stats`, `totalsAsync`, `window`, `perId`, `worldKey`, `isTracked`. A counter key is grouped
  under its own definition or the longest prefix definition it starts with. `StatDef` refuses a windowed stat
  that is not board eligible and SUM. `boardKeys`, `board` (no world) or `boardAsync` (a world, waited on for
  `suite.waitMs`), at most `pageSizeMax` 100 rows, with Chronicle's public privacy view. `serverTotals`, `online`,
  `recentRecords`, `seriesIds` and `series(id, resolution, from, to)`. The series are raw, RestAPI splits them as
  the dashboard does, `players.*` and `server.*` are activity, the rest performance behind
  `suite.chronicle.performanceSeries`. A NaN in a point is written as null.
- Atlas. `layers`, `MarkerLayer.markers(world)`, point, circle, polygon and polyline fields, `publicUrl`,
  `publicUrlSet`, `renderStatus` and `worlds`. `atlas.json` gives `bind`, `port` and `basePath` for the probe:
  with `suite.atlas.probe` the summary asks `http://<bind or 127.0.0.1>:<port><basePath>/api/health` with a 1.5
  second limit and keeps `ok`, `version` and `status` of the public answer.
- Lullaby. `version`, `apiVersion`, `mode`, `statuses`, `worlds`, `status(world)` and `nightsSkipped()`. Only the
  length of `Ignored` in `ignored.json` is read from a file. The rule Lullaby 1.0.1 used to be copied into
  `SleepRule` is gone, `SleepStatus.needed` is Lullaby's own answer.
- SocialBridge. `version`, `API_VERSION` through reflection, `linkedUser` and `linkedPlayer` for the Discord
  service, and `post(channel, Announcement)`. A post with a title or footer becomes an embed, text is cut at 2000
  characters and titles at 256, and false from `post` (no service running or the channel off) answers 409
  `not_posted`. SocialBridge files the post under the kind `api.<kind>`.

## Headless smoke test

The scripts boot a scratch server with `--assets <Assets.zip> --auth-mode offline --disable-sentry
--bind 127.0.0.1:<port>` and stdin from a named pipe, wait for `RestAPI .* ready` and `Hytale Server Booted`,
read the first key from the log, curl the API and pipe `/restapi` commands and `stop` into the console. Do not
pass `--bare` and do not pass `--mods` for the same folder. With the seven suite jars on one machine next to
other smokes, move Atlas and Chronicle Stats off 8123 and 8124 in their own files and give RestAPI its own
`http.port` and `rcon.port`.

Runs on 2026-10-16 on the client's bundled 0.7.2 server, after all seven module branches were merged, alone and
with TrackerAPI 1.1.0, Coinage 1.2.0, Dominion 0.6.2, Feats 1.6.0, Chronicle Stats 1.4.0, Atlas 1.7.0 and
Lullaby 1.0.1, HTTP on 8138 and RCON on 25588.

- Alone: `RestAPI 0.1.0 ready, HTTP API on http://127.0.0.1:8138 with 70 routes, 1 key, 0 of 7 suite mods bound`.
  With the seven: 120 routes and `7 of 7 suite mods bound`. `/openapi.json` listed 70 and 120 operations, the
  same as `restapi_routes` in `/metrics`.
- `say` with an `Idempotency-Key` sent twice answered `replayed: false` then `true` with the same run id, and the
  engine log held one `RestAPI:admin executed command` line. `stop` and `plugin reload Ryvrook:RestAPI` through
  `POST /api/commands` answered 403 `command_denied`, `POST /api/plugins/Ryvrook:RestAPI/reload` 403
  `self_refused`.
- `GET /api/console/stream` printed the say line, `GET /api/events` stayed open across `/restapi reload` and
  printed the command events from both sides of it. `/metrics` held `restapi_requests_total` per route pattern.
- mcrcon ran `say` and got `stop is on the deny list and did not run.` for `stop`. Both RCON runs left their
  `started` and result audit lines with source `rcon`.
- Plugins listed 107 alone and 114 with the seven. `PUT /api/worlds/default/time` with hour 6 read back as hour
  6, progress 0.25. A reload of Lullaby through the API answered 200 in 119 ms with the lock held and logged one
  `Enabled plugin Ryvrook:Lullaby`.
- One GET per suite group answered 200. `siblings.dominion` never and a reload gave 404 on `/api/dominion`, auto
  gave 200 again. A Coinage give sent twice with one `Idempotency-Key` answered the same transaction with
  `replayed: true`. `plugin reload Ryvrook:TrackerAPI` restarted RestAPI with `7 of 7 suite mods bound` and every
  group answered 200.
- Alone the console `stop` exited 0. With the seven a `POST /api/server/stop` with 5 seconds answered 202, left
  the `server stop` audit line and the process exited 0.

Run of 0.2.0 on 2026-10-16 on the bundled 0.7.2 server with TrackerAPI 1.1.0, Coinage 1.3.0, Dominion 0.7.0,
Feats 1.7.0, Chronicle Stats 1.5.0, Atlas 1.8.0, Lullaby 1.1.0 and SocialBridge 0.1.0, RestAPI on 8731, Atlas on
8732, Chronicle on 8733, game port 5731.

- `RestAPI 0.2.0 ready, HTTP API on http://127.0.0.1:8731 with 131 routes, 1 key, 7 of 7 suite mods bound`, alone
  70 routes and 0 of 7. `/openapi.json` listed 131 and 70 operations. `GET /api/suite` showed every mod bound at
  the versions above with `hooks` true, 10, 13, 15, 6, 9, 6 and 2 routes.
- `POST /api/dominion/unclaim` on wilderness answered 409 with Dominion's "Nobody owns this land.", power for a
  player Dominion never saw 409 "That player is not known", disband of an unknown tag 404. Sieges answered
  `sidesKnown: true`. `/api/feats/top` answered `source: api`. Chronicle listed 46 boards and 3 series
  (`players.online` as activity, two `jvm.*` as performance), a week board warming, minute points of
  `players.online` with empty buckets as null. `/api/atlas/render` gave a budget of 1200 a minute, `worlds` the
  default world. `/api/lullaby` gave mode `native` with `needed` and `missing` per world.
- A Coinage give with an `Idempotency-Key` sent twice answered transaction 1 both times with the initiator
  `{"type": "api_key", "name": "admin"}`, and `ledger?initiator=admin` found it.
- `plugin reload` of Dominion, Feats, Coinage and Lullaby each logged "<mod> was reloaded, reloading
  Ryvrook:RestAPI" once and RestAPI came back with 131 routes and 7 of 7 bound. No "RestAPI has no" line and no
  RestAPI warning in the whole log. `/restapi reload` kept 131 routes, the console `stop` exited 0.
