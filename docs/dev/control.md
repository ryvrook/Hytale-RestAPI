# Control module, engine facts

Package `dev.ryvrook.restapi.control`, group `control`. Every engine call below was read with javap on
`Server-0.7.1.jar` on 2026-10-16 and run on the headless smoke server (bundled 0.7.2) unless it says otherwise.

## Classes

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

## Plugins

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

## Worlds

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

## Server

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

## Not verified

- `World.setPaused` with players online. `WorldPauseCommand` checks the player count first and answers
  `server.commands.pause.tooManyPlayers`. RestAPI does not, and no player was online on the smoke run.
- `Universe.loadWorld` of a world saved earlier and unload with players in it. The smoke run had one world.
- `PluginManager.load` of a jar dropped into `mods/` after the boot.
