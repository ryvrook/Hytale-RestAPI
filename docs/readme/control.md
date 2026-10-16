# Plugins, worlds and server control

These rows go into the README under "Endpoints" and "Settings". They come from the `control` module, which
`control.enabled` switches off as a whole. `GET /api/health` and `GET /api/server` stay either way.

## Endpoints

| Method and path | Scope | What it does |
|---|---|---|
| `GET /api/plugins` | `read` | Every loaded plugin with id, version, state, description, authors, website, dependencies, jar and data folder |
| `GET /api/plugins/available` | `plugins` | Manifests the engine found in the mods folders that are not loaded, such as a jar added after the boot |
| `GET /api/plugins/{id}` | `read` | One plugin by its `Group:Name` id, such as `Ryvrook:Lullaby` |
| `POST /api/plugins/{id}/load` | `plugins` | Loads a plugin. `{"boot": true}` also puts it on the boot list in `config.json`, as `/plugin load --boot` does |
| `POST /api/plugins/{id}/unload` | `plugins` | Unloads a plugin and the plugins that name it under `Dependencies`. `{"boot": true}` takes it off the boot list |
| `POST /api/plugins/{id}/reload` | `plugins` | Reloads a plugin and waits up to `control.reloadWaitSeconds` for the new copy. 202 while it still starts |
| `GET /api/worlds` | `read` | Every loaded world with players, measured TPS, tick times, chunks, entities, ticking and paused |
| `GET /api/worlds/{world}` | `read` | The same plus hour, day progress, moon phase, forced weather, seed, save path, day and night length and the weather names |
| `PUT /api/worlds/{world}/time` | `server` | `{"hour": 6}` from 0 to 24 or `{"dayProgress": 0.25}` from 0 to 1, as `/time` does |
| `PUT /api/worlds/{world}/weather` | `server` | `{"weather": "Zone1_Rain"}` forces a weather, `{"weather": null}` hands it back to the environment |
| `POST /api/worlds/{world}/save` | `server` | Saves the world's chunks, config and resources, as `/world save` does |
| `PUT /api/worlds/{world}/ticking` | `server` | `{"ticking": false}` stops the world tick, `{"paused": true}` pauses it as `/pause` does |
| `POST /api/worlds/{world}/load` | `server` | Loads a saved world from the universe folder |
| `POST /api/worlds/{world}/unload` | `server` | Removes a loaded world, only with `control.allowWorldUnload`. Never the default world or the last one |
| `GET /api/server/config` | `read` | Name, MOTD, max players, view radius, password set or not, the join permission switch, default world, backup settings, log levels |
| `PATCH /api/server/config` | `server` | Changes `name`, `motd`, `maxPlayers`, `maxViewRadius` or `requireJoinPermission` and saves `config.json` |
| `POST /api/server/save` | `server` | Saves every world, or those named in `{"worlds": [...]}`, with a result per world |
| `POST /api/server/backup` | `server` | Starts a backup zip and answers 202 with a job id. 409 when no backup folder is set |
| `GET /api/server/backup/{job}` | `server` | `running`, `done` with the zip file, or `failed` with the reason |
| `GET /api/server/stop` | `server` | The stop counting down, if there is one |
| `POST /api/server/stop` | `server` | Stops the server, see below |
| `POST /api/server/stop/cancel` | `server` | Calls off the countdown and tells the players |

A plugin id has a colon in it. curl sends it as it is, `curl -X POST -H "Authorization: Bearer $KEY"
http://127.0.0.1:8125/api/plugins/Ryvrook:Lullaby/reload`, and `%3A` works too.

RestAPI refuses to load, unload or reload itself through these routes, with 403 `self_refused`, whatever the
key and the settings say. A plugin that reloads itself through its own API pulls the floor out from under the
request that asked. For RestAPI's own files run `/restapi reload`, and to reload the jar run
`/plugin reload Ryvrook:RestAPI` on the server console.

Load, unload and reload take the lock that TrackerAPI and Coinage hold while they reload the mods that depend
on them, so a reload through the API never runs in the middle of theirs. A reload of TrackerAPI or Coinage
through the API reloads RestAPI too, a few seconds after the answer, since RestAPI lists them as optional
dependencies. The answer arrives first, then the API is gone for a moment while RestAPI starts again.

Load, unload, reload, ticking, world load and unload and the stop take an `Idempotency-Key` header. A retry
with the same key name and header within `control.idempotencySeconds` gets the first answer with
`"replayed": true` and runs nothing, so a script that lost the answer can safely ask again.

## Stopping and restarting

```
curl -X POST -H "Authorization: Bearer $KEY" -H "Content-Type: application/json" \
  -d '{"confirm": "stop", "delaySeconds": 60, "restart": true, "reason": "Updating to 0.7.2"}' \
  http://127.0.0.1:8125/api/server/stop
```

`confirm` must be the word `stop`. `delaySeconds` counts down, and with `announce` (default
`control.stopAnnounce`) players read "The server restarts in 1 minute." at the start and again at 30, 10 and the
last five seconds. The reason goes to chat once and is the message players see when they are disconnected.
`exitCode` sets the process exit code, 0 by default. One stop can count down at a time, and
`POST /api/server/stop/cancel` calls it off.

Hytale has no restart of its own. A restart is a stop with an exit code that whatever runs the server reacts
to. `"restart": true` stops with `control.restartExitCode`, 8 by default, which is the code the engine itself
uses for an update. Under systemd:

```
[Service]
ExecStart=/usr/bin/java -jar HytaleServer.jar --assets Assets.zip
Restart=on-failure
RestartSec=5
```

`Restart=on-failure` starts it again on any code other than 0, so 8 restarts and a plain stop with 0 stays
down. Without systemd a wrapper script does the same:

```
#!/bin/sh
while true; do
  java -jar HytaleServer.jar --assets Assets.zip
  [ $? -eq 8 ] || break
  sleep 5
done
```

Panels such as Pterodactyl decide on their own what an exit means. Check yours before you rely on code 8.

A pending stop survives `/restapi reload`. It does not survive a reload or unload of the RestAPI plugin, which
drops the countdown.

## Backups

`POST /api/server/backup` runs the engine's backup, the same as `/backup`. It needs a backup folder, either
`--backup-dir <path>` on the command line or `Backup.Directory` in `config.json`, and answers 409
`backup_not_configured` without one. The engine locks saving, saves every world and writes a zip into that
folder. The job id in the answer and in the `Location` header leads to the result.

## Settings

| Key | Default | What it does |
|---|---|---|
| `control.enabled` | `true` | Serve the plugin, world and server control routes |
| `control.allowStop` | `true` | Let `POST /api/server/stop` stop the server |
| `control.allowPluginLoad` | `true` | Let the plugin routes load, unload and reload. RestAPI itself is always refused |
| `control.allowWorldUnload` | `false` | Let `POST /api/worlds/{world}/unload` remove a world |
| `control.allowConfigEdit` | `true` | Let `PATCH /api/server/config` write `config.json` |
| `control.worldTimeoutMs` | `5000` | How long a request waits for a world thread before 504 `world_timeout` |
| `control.saveTimeoutSeconds` | `60` | How long a save may run before the answer says it is still running |
| `control.reloadWaitSeconds` | `15` | How long a plugin reload waits for the new copy |
| `control.lockWaitSeconds` | `30` | How long a plugin route waits for the suite lock before it goes ahead without it |
| `control.stopDelaySeconds` | `0` | Countdown when a stop gives no `delaySeconds` |
| `control.stopMaxDelaySeconds` | `3600` | Longest countdown a stop may ask for |
| `control.stopAnnounce` | `true` | Tell players about the countdown when a stop gives no `announce` |
| `control.restartExitCode` | `8` | Exit code of a stop with `restart` true |
| `control.backupJobsKept` | `20` | Finished backup jobs kept for `GET /api/server/backup/{job}` |
| `control.idempotencySeconds` | `600` | How long a dangerous route keeps its answer for a retry with the same `Idempotency-Key` |

For a server that scripts may read but never change, set `control.enabled` to false, or give the keys `read`
and nothing else.
