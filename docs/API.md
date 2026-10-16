# RestAPI routes in detail

The README lists every route in short. This file has the long form, module by module, with request bodies,
what each answer holds, the error codes, and how the parts that need more than a table work. Settings are in
[SETTINGS.md](SETTINGS.md) and `settings/`. `/openapi.json` on a running server is the machine readable form of
the routes mounted at that moment.

## Players and moderation

The `players` module lists and changes players, bans, the whitelist and permission groups, and sends broadcasts.
A `{player}` in a path takes a name or a UUID. Online players match by name without regard to case. Anyone else
matches by UUID, or by the last name RestAPI saw them use, which it keeps in `names.json` because an offline mode
server has no profile lookup. A name never seen on this server answers 404 `player_not_found`.

Reads of the entity (game mode, health, stats, inventory) and every change to it run on the player's world thread,
the way the engine's own `/give`, `/kill` and `/tp` do. A request waits up to `players.worldTimeoutMs` for that
thread. A world that does not answer in time gets 504 `world_timeout`, and the change may still land a moment
later. `GET /api/players` asks each world once and lists a world that was late under `worldsLate` instead of
failing the whole answer.

| Method and path | Scope | What it does |
|---|---|---|
| `GET /api/players` | `read` | Online players with world, position, ping, game mode and health. `?world=` filters |
| `GET /api/players/offline` | `read` | Every player the server stores, with the last name seen. `?limit&offset` |
| `GET /api/players/{player}` | `read` | One player. Online adds head rotation, language and stats. Everyone gets groups, op, ban and join grant |
| `GET /api/players/{player}/inventory` | `players` | Hotbar, storage, armor, utility, tool and backpack with item id, quantity and durability |
| `POST /api/players/{player}/kick` | `players` | `{reason?}`. Disconnects the player |
| `POST /api/players/{player}/message` | `players` | `{text, color?}`, color as `#rrggbb` |
| `POST /api/players/{player}/title` | `players` | `{title, subtitle?, fadeIn?, stay?, fadeOut?, major?}`, times in seconds |
| `POST /api/players/{player}/teleport` | `players` | `{x, y, z, world?, yaw?, pitch?}` with yaw and pitch in degrees, or `{to: player}` |
| `POST /api/players/{player}/give` | `players` | `{item, amount}`. Answers what was given and what did not fit |
| `POST /api/players/{player}/clear` | `players` | `{sections?}`. Empties those sections, all of them when none are named |
| `PUT /api/players/{player}/gamemode` | `players` | `{mode}`, `Adventure` or `Creative` |
| `POST /api/players/{player}/heal` | `players` | Fills health |
| `POST /api/players/{player}/kill` | `players` | Kills the player as `/kill` does |
| `POST /api/players/{player}/refer` | `players` | `{host, port}`. Sends the player to another server |
| `GET /api/bans` | `read` | Bans with target, API key, reason, start and end. `?limit&offset` |
| `POST /api/bans` | `players` | `{player, reason?, minutes?}`. 201 for a new ban, 200 with `created: false` when one was in place. Kicks the player when online |
| `DELETE /api/bans/{player}` | `players` | Lifts the ban, 404 `not_banned` when there was none |
| `GET /api/whitelist` | `read` | Whether joining needs the join permission, and who holds it |
| `PUT /api/whitelist` | `players` | `{enabled}`. Switches `RequireJoinPermission` and saves `config.json` |
| `POST /api/whitelist` | `players` | `{player}`. Gives the join permission |
| `DELETE /api/whitelist/{player}` | `players` | Takes it away |
| `GET /api/permissions/groups` | `read` | Groups with parent, permissions and whether the API may change them |
| `GET /api/players/{player}/groups` | `read` | The player's groups |
| `PUT /api/players/{player}/groups` | `players` | `{groups}`. The player ends up in exactly these |
| `POST`, `DELETE /api/players/{player}/groups/{group}` | `players` | Adds or removes one group |
| `POST /api/broadcast` | `players` | `{text, color?, world?, prefix?}`. The `players.broadcast` line in `messages.json` wraps the text unless `prefix` is false |
| `POST /api/broadcast/title` | `players` | `{title, subtitle?, world?, fadeIn?, stay?, fadeOut?, major?}` |

Ban, clear, kill and every group change are marked dangerous, the rest of the changes are writes, and the audit
log keeps a line for each with the key name. A ban made over the API stores the key name in the ban, so
`GET /api/bans` shows which key made it. `hytale:Admin`, the group `/op` adds, is in `players.protectedGroups` by
default, so a key with the `players` scope cannot make an operator. Take it out of that list if you want that.

For a read only API, set `players.readOnly` to true. The read routes stay, every change answers 404.

## Commands and the console

`POST /api/commands` runs a console command and answers with what the command sent back, as plain text lines. It
runs as a sender named `RestAPI:<key name>`, so the server's own `executed command` line in the log says which key
ran it. Every run gets a `runId` that the audit log also has. The audit log gets a `started` line before the
engine sees the command and a second line with the outcome, so a crash between the two still leaves a trace.

```
curl -s -X POST http://127.0.0.1:8125/api/commands \
  -H "Authorization: Bearer $KEY" -H "Idempotency-Key: $(uuidgen)" \
  -d '{"command": "say hello"}'
{"runId":"r8f3a01c2d4e5","outcome":"done","output":["[RestAPI:admin] hello"],"millis":312,"replayed":false}
```

Send an `Idempotency-Key` header with each command. When the answer gets lost and the script sends the same
request again, RestAPI hands back the stored answer with `"replayed": true` and runs nothing. A retry that comes
while the first run is still going waits for it. The same key on a different command answers 409. Results are kept
in memory for `commands.idempotencySeconds`, a restart forgets them.

`outcome` is `done`, `failed` (the engine reported an error or does not know the command), `timed_out` (HTTP 202,
the answer holds the lines that arrived before `timeoutMs` and the command may still be running) or `denied`
(HTTP 403). Some commands reply after they report that they finished, so RestAPI keeps listening for
`commands.graceMs` after the last line, at most `commands.graceMaxMs`. `say` writes its line to the server console
and not to the sender, RestAPI picks that up from the console because the engine runs the command on a thread
named after it.

The deny list is checked before the engine sees the line. It matches on the words after aliases are resolved
through the engine's command tree, so `/stop`, `STOP` and `shutdown` are the same command, and `pl r X` is
`plugin reload X`. These are refused whatever the settings say: `stop` and `shutdown`, any `plugin` command
that names `Ryvrook:RestAPI`, `restapi reload`, `restapi keys`, and `sudo` or `su` around any refused command.
Stopping the server over the API goes through `POST /api/server/stop` instead. Commands only answer from the
addresses in `commands.allowFrom`, loopback by default, on top of the key's own `allowFrom`. Behind a reverse
proxy that is the real client address, so remote command calls need it widened on purpose.

Macros set up with the engine's command macro feature are not looked into. A macro that wraps `stop` runs. Put
the macro's name on `commands.deny` when that matters.

The server log is kept in memory, the newest `console.ringLines` lines, with colour codes stripped and RestAPI key
tokens cut down to `rapi_<id>_...`. `GET /api/console` answers the newest lines, `GET /api/console/stream` sends
them as Server Sent Events and then every new line as it is logged. A `/restapi reload` ends open streams, a client
that sends `Last-Event-ID` gets every line it missed that is still kept.

```
curl -N -H "Authorization: Bearer $KEY" "http://127.0.0.1:8125/api/console/stream?level=INFO&lines=50"
```

| Method, path | Scope | What it does |
|---|---|---|
| `POST /api/commands` | `commands` | Runs `{command, timeoutMs?}` and answers `{runId, outcome, output, millis, replayed}` |
| `POST /api/commands/batch` | `commands` | Runs `{commands: [...], stopOnError?, timeoutMs?}` one after the other, one result each. An `Idempotency-Key` covers the batch, each line is stored as key `#` index |
| `GET /api/commands/runs/{runId}` | `commands` | A kept run of the same key, with `engineMillis`, `lateLines` and `lastLateMillis` |
| `GET /api/commands/deny` | `commands` | The fixed entries and `commands.deny` |
| `GET /api/commands/list` | `read` | Registered commands with aliases, description, permission and sub commands |
| `GET /api/console` | `console` | `?lines=200&level=INFO&since=<line id or ISO time>` |
| `GET /api/console/stream` | `console` | Server Sent Events, event `log` with the line id as event id, `?level=` and `?lines=` |

## Plugins, worlds and server control

The `control` module, which `control.enabled` switches off as a whole. `GET /api/health` and `GET /api/server`
stay either way.

### Routes

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

### Stopping and restarting

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

### Backups

`POST /api/server/backup` runs the engine's backup, the same as `/backup`. It needs a backup folder, either
`--backup-dir <path>` on the command line or `Backup.Directory` in `config.json`, and answers 409
`backup_not_configured` without one. The engine locks saving, saves every world and writes a zip into that
folder. The job id in the answer and in the `Location` header leads to the result.

For a server that scripts may read but never change, set `control.enabled` to false, or give the keys `read`
and nothing else.

## Events, webhooks and metrics

RestAPI turns what happens on the server into events and keeps the last `events.ring` of them in memory, 1000
by default. These are the types.

| Type | When | Fields besides id, type and time |
|---|---|---|
| `join` | A player connects | `player`, `uuid`, `world` |
| `ready` | A player is in a world, at the first join and after every world change | `player`, `uuid`, `world` |
| `leave` | A player disconnects | `player`, `uuid`, `reason` |
| `chat` | A chat message that no chat plugin cancelled | `player`, `uuid`, `length`, `text` unless `events.chatText` is off |
| `death` | A player dies, and every creature with `events.npcDeaths` | `player` or `npc`, `world`, `cause`, `position`, `killer` or `killerNpc` when there is one |
| `command` | Any command the engine runs, from a player, the console, RCON or the API | `by`, `command` |
| `world` | A world was added or removed, `action` says which | `world` |
| `plugin` | A plugin changed state, seen by a poll every `events.pluginPollSeconds`. `action` is the new state in lower case, or `unloaded` | `plugin`, `version`, `from`, `to`, where `NONE` means not loaded |
| `server` | The server finished booting or started to shut down, `action` `booted` or `stopping` | |

Commands have no engine event. The engine logs `<sender> executed command: <line>` for every command it runs,
and RestAPI reads that line, so a command typed in the console shows as `by` `Console`, one run through the
API as `RestAPI:<key name>`. Plugins have no enable event either, which is why their changes come from a poll.

`GET /api/events` is a Server Sent Events stream. The SSE event name is the type, the data is the event as
JSON and the id is the event id. Ids keep rising across restarts, so a client that reconnects with
`Last-Event-ID` gets what it missed while it is still in the ring. When some of it already fell out of the
ring, or the id is from before a restart, the stream sends a `gap` event first. A comment line goes out after
`events.keepAliveSeconds` of quiet so proxies keep the connection open. A client that reads too slowly is
sent an `overflow` event and cut off, and resumes with its last id. `http.maxStreams` caps the open streams.

```
curl -N -H "Authorization: Bearer $KEY" "http://127.0.0.1:8125/api/events?types=join,leave,death"
curl -H "Authorization: Bearer $KEY" "http://127.0.0.1:8125/api/events/recent?types=chat&limit=20"
```

### Webhooks

A webhook is a URL RestAPI POSTs events to. Make one with a key that holds `events`.

```
curl -X POST -H "Authorization: Bearer $KEY" -H "Content-Type: application/json" \
  -d '{"url": "https://example.com/hytale", "types": ["join", "leave", "death"]}' \
  http://127.0.0.1:8125/api/webhooks
```

The answer holds a `secret` the one time, 64 hex characters. Every delivery then carries
`X-RestAPI-Signature: sha256=<hex>`, the HMAC-SHA256 of the raw body with that secret, plus
`X-RestAPI-Event` (the type, and the action after a dot such as `world.added`) and `X-RestAPI-Delivery`, a
UUID per delivery. Check the signature on the raw body before you parse it. Send your own `"secret"` of 16
to 256 characters, or `"secret": null` for unsigned deliveries.

For a Discord channel webhook add `"format": "discord"`. RestAPI then posts a plain `content` line from
`messages.json`, such as "Steve joined the server.", with mentions switched off so a chat message holding
`@everyone` pings nobody. Discord ignores the signature.

Each webhook gets its own queue of at most `webhooks.queueSize` deliveries and sends them one at a time, in
order. A network error, a 429 or a 5xx is tried again `webhooks.retries` times, after `webhooks.backoffSeconds`
and twice that each time after, 2, 4 and 8 seconds by default. A 3xx or a 4xx fails at once. Redirects are
never followed. When a target stays down long enough to fill the queue the oldest delivery is dropped.
Queues live in memory, so a server stop or `/restapi reload` drops what is still waiting after two seconds.

A webhook made over the API may not point at a loopback, LAN, link local or carrier NAT address, checked when
it is made and again before every delivery, so a name that later resolves to your LAN is refused too. Set
`webhooks.allowPrivateTargets` to true for a bot on the same machine, or write the hook into
`webhooks.json` by hand, which skips the check. `GET /api/webhooks` shows each hook with what its last
delivery answered and never the secret. Path parts of 20 characters or more and the query are shown as
`***`, since Discord puts its token in the path.

### Metrics

`GET /metrics` answers in Prometheus text format. It needs a key with `metrics`, or no key at all with
`metrics.public` on, for a scraper on the same machine. Names follow the Apex exporter where they overlap,
so its dashboards carry over.

```
scrape_configs:
  - job_name: hytale
    metrics_path: /metrics
    authorization:
      credentials: rapi_xxxxxxxx_...
    static_configs:
      - targets: ["127.0.0.1:8125"]
```

| Metric | Labels | What it is |
|---|---|---|
| `hytale_players_online`, `hytale_players_max` | | Players connected and the MaxPlayers of config.json |
| `hytale_world_tps` | `world` | Ticks per second over 10 seconds, the `/world perf` formula |
| `hytale_world_tick_ms` | `world`, `stat` `avg` or `max` | Tick length over 10 seconds |
| `hytale_world_chunks_loaded`, `hytale_world_entities`, `hytale_world_players` | `world` | From the world's last sample |
| `hytale_world_stalled` | `world` | 1 when the world thread missed three samples |
| `hytale_server_uptime_seconds` | | Since the boot |
| `hytale_plugins` | `state` | Plugins by state, ENABLED, DISABLED, FAILED and the rest |
| `jvm_memory_heap_used_bytes`, `_committed_bytes`, `_max_bytes`, `jvm_threads` | | The JVM |
| `jvm_gc_collections_total`, `jvm_gc_collection_seconds_total` | `gc` | Per collector |
| `process_cpu_load`, `system_load_average_1m` | | CPU of the server process from 0 to 1, and the system load |
| `restapi_requests_total` | `method`, `route`, `status` | Requests by route pattern, so the label set stays as small as the route table |
| `restapi_auth_failures_total` | | Requests with a wrong key, or none where one is needed |
| `restapi_webhook_deliveries_total` | `result` `ok`, `failed`, `retried`, `dropped` | Webhook deliveries |
| `restapi_webhooks`, `restapi_event_streams`, `restapi_routes` | | Webhooks in the file, open event streams, mounted routes |

World figures come from the samples RestAPI already takes for `/api/health`, so a scrape never waits on a
world thread.

### Routes

| Method and path | Scope | What it answers |
|---|---|---|
| `GET /api/events` | `events` | Server Sent Events, `?types=` to filter, resumes with `Last-Event-ID` or `?since=` |
| `GET /api/events/recent` | `events` | The newest events as JSON, `?types`, `?limit` (100 by default) and `?since` |
| `GET /api/webhooks` | `events` | Every webhook with its types and last delivery |
| `POST /api/webhooks` | `events` | `{url, types?, secret?, format?}`, answers 201 with the hook and its secret once |
| `DELETE /api/webhooks/{id}` | `events` | Removes the hook, 204 |
| `POST /api/webhooks/{id}/test` | `events` | Sends one test event now and answers with the target's status |
| `GET /metrics` | `metrics`, or none with `metrics.public` | Prometheus text |

Error codes: `unknown_type`, `bad_limit`, `bad_since`, `invalid_url`, `private_target`, `unresolved`,
`invalid_format`, `invalid_secret`, `too_many_webhooks` (409), `webhook_not_found` (404), `store_failed` (500).

## OpenAPI document and docs page

`GET /openapi.json` answers an OpenAPI 3.1 document written from the routes mounted at that moment. Every route
is one operation, tagged with its group, with its scope in `x-scope`, `read`, `write` or `dangerous` in
`x-access`, and `x-open: true` when it also answers without a key. Query parameters, example bodies and the
error codes a route can answer come from the module that owns it, and the schemas are read off those examples.
A suite group such as `suite.dominion` is in the document only while its mod is bound, so a client generator or
a Postman import never sees routes that answer 404. The document is kept in memory and written again when a
group is mounted or unmounted, a suite mod binds or leaves, or a setting it reads changes.

`GET /docs` is a page of about 16 KB that renders the document. One HTML file, one script and one style sheet,
all served from the jar, no CDN, no fonts from elsewhere and no Swagger UI bundle. The page sends
`Content-Security-Policy: default-src 'self'`, so it can load nothing and send nothing outside this server. Each
route opens to its parameters, examples and a Send button. Paste a key in the box at the top first. The key
lives in the page's memory only, it is never written to local storage or a cookie and it is gone when the tab
closes. Routes marked dangerous ask before they send. A stream route is read for 10 seconds and then closed.

The page loads `openapi.json` with a relative link, so it works behind a reverse proxy that serves RestAPI
under a sub path. With `docs.public` off the document needs a key with `read`, and the page asks for that key
before it loads.

| Method and path | Scope | What it answers |
|---|---|---|
| `GET /openapi.json` | none while `docs.public` is on, else `read` | The OpenAPI 3.1 document of the routes mounted now |
| `GET /docs` | none | The docs page. Gone while `docs.page` is off |
| `GET /docs/{file}` | none | `docs.js` and `docs.css` of the page, anything else is 404 `file_not_found` |

## RCON

RestAPI can also answer Source RCON, the protocol Minecraft servers use, so tools such as mcrcon and rcon-cli
and the RCON panels of game hosts work against a Hytale server. It is off by default. Turn it on in
`RestAPI.json` and run `/restapi reload`:

```json
"rcon": {"enabled": true, "bind": "127.0.0.1", "port": 25575}
```

The password is any RestAPI key that holds the `commands` scope, so the audit log names the key behind every
login and the engine log names it on every command (`RestAPI:<key> executed command: ...`).

```
mcrcon -H 127.0.0.1 -P 25575 -p rapi_1a2b3c4d_... "say Restart in 5 minutes"
```

Commands go through the same deny list as `POST /api/commands`. `stop`, `shutdown` and anything that loads,
unloads or reloads RestAPI never run, the reply says the command is on the deny list. `commands.allowFrom`
applies too, so with the default only loopback addresses can log in, and a key's own `allowFrom` is checked
as well. A wrong password answers the way RCON clients expect (`Authentication failed`), and after
`rcon.authFailuresPerMinute` failed logins the address is cut off for the rest of the minute.

RCON has no encryption. The key and every command cross the network in clear text. Keep `rcon.bind` on
127.0.0.1 and reach it through SSH (`ssh -L 25575:127.0.0.1:25575 you@server`) or a VPN. Any other bind logs a
warning at every start.

Replies longer than 4086 bytes are sent as several packets. Clients that send an empty packet after the
command and wait for its echo join them, and so do clients that send an unknown packet type, the way the
Minecraft server works with both. mcrcon 0.7.2 reads one packet per command, so it prints only the first 4086
bytes of a long reply such as `help` on a server with many plugins. `/restapi reload` closes open RCON
connections and opens the port again with the new settings.

## Suite mods

RestAPI works on its own. When it finds one of my other mods running it mounts that mod's routes under
`/api/<id>`, each behind a scope of its own, and takes them away again when the mod stops or `siblings.<id>` is
`never`. The OpenAPI document and `GET /api/suite` only list the routes that are mounted right now, so a script
never sees a route that answers "not installed". A key needs the mod's scope, `all` covers every one of them.

| Mod | Routes while it runs | Scope | Setting |
|---|---|---|---|
| TrackerAPI 1.1.0 | `/api/tracker`, `kinds`, `counters/{player}`, `server`, `leaderboards`, `leaderboards/{key}`, `players`, `players/{player}`, `recent`, `journal` | `tracker` | `siblings.tracker` |
| Coinage 1.3.0 | `/api/coinage`, `currencies`, `balances/{player}`, `accounts`, `accounts/{account}`, `ledger`, `ledger/{id}`, `top`, `reports/today`, `reports/days`, and `POST transfer`, `give`, `take` | `coinage`, writes `coinage.write` | `siblings.coinage` |
| Dominion Factions 0.7.0 | `/api/dominion`, `factions`, `factions/{tag}`, `claims`, `sieges`, `sieges/history`, `points`, `relations`, `players/{player}`, `at`, and `POST factions/{tag}/disband`, `factions/{tag}/leader`, `factions/{tag}/unclaim-all`, `players/{player}/power`, `unclaim` | `dominion`, writes `dominion.write` | `siblings.dominion` |
| Feats 1.7.0 | `/api/feats`, `players/{player}`, `players/{player}/stats/{key}`, `records`, `top`, `recent` | `feats` | `siblings.feats` |
| Chronicle Stats 1.5.0 | `/api/chronicle`, `stats`, `players/{player}`, `players/{player}/{stat}`, `boards`, `top/{stat}`, `server`, `series`, `series/{id}` | `chronicle` | `siblings.chronicle` |
| Atlas 1.8.0 | `/api/atlas`, `render`, `worlds`, `layers`, `markers`, `hidden` | `atlas` | `siblings.atlas` |
| Lullaby 1.1.0 | `/api/lullaby`, `worlds/{world}` | `lullaby` | `siblings.lullaby` |
| SocialBridge 0.2.0 | `/api/socialbridge`, `links/{player}`, `users/{user}`, and `POST post` | `socialbridge`, writes `socialbridge.write` | `siblings.socialbridge` |

A `{player}` is a UUID or a name. Names are looked up among the online players first, then wherever that mod
keeps names (Coinage's name index, TrackerAPI's names index, the Dominion rosters, the Feats and Chronicle exports),
so an offline player is found by name when the mod has seen them, and by UUID always.

Every group binds through the mod's own `onEnable` and `onDisable` hooks. When that mod is reloaded it reloads
RestAPI two seconds later and the group comes back, in between its routes answer 503 `sibling_gone` or are gone.

Two things still come from a file. Feats `recent` and the `rarity` per feat read `export/feats.json`, and
`ignoredPlayers` in `/api/lullaby` counts Lullaby's `ignored.json`. Atlas' `atlas.json` gives the bind, port and
base path for the health probe in `/api/atlas`, never the admin token.

Under `always` an older copy binds with what its API had. Coinage before 1.3.0 gets moves under the plugin
initiator `RestAPI` and an `initiator` filter applied to the page after the search. Dominion before 0.7.0 lists
the ranked factions only, gives sieges from the siege banners (`"sidesKnown": false`) and answers 501
`not_supported` on the staff routes and the siege history. Feats before 1.7.0 and Chronicle before 1.5.0 give
their boards and server totals from `export/feats.json` and `export/stats.json` (the all time boards only).
Atlas before 1.8.0 answers 501 on `render` and `worlds`. Lullaby before 1.1.0 has no API and does not bind, not even under `always`.

### Dominion

`GET /api/dominion/factions` lists the factions in Dominion's ranking order, then the safe and war zones with a
`rank` of null. `zones=false` leaves the zones out. `GET /api/dominion/sieges` gives each running siege with the
attacker and defender (id and tag, a tag is null when that faction is gone), the chunk, the banner position, the
phase (`capturing`, `breached`, `failed`, `defended` or `expired`), `phaseEndsAt` and the block, container and
death counts. `sieges/history?faction=NRT&limit=20` gives the finished ones, newest first.

The staff routes need `dominion.write` and are on while `suite.dominion.writes` is true. Each answers
`{"ok": true, "message": "..."}` with Dominion's own sentence, or 409 with `"error": "refused"` and the reason.
Dominion writes `RestAPI:<key name>` to the faction log where the matching `/fa` command writes one, and one INFO
line to the server log. Every call lands in RestAPI's audit log as `dominion <action>`.

| Route | Body | Same as |
|---|---|---|
| `POST /api/dominion/factions/{tag}/disband` | none | `/fa force disband`, the bank paid out |
| `POST /api/dominion/factions/{tag}/leader` | `{"player": "Steve"}` | a leader change, the player must be a member |
| `POST /api/dominion/factions/{tag}/unclaim-all` | none | releases every claim in every world |
| `POST /api/dominion/players/{player}/power` | `{"power": 12.5}` | clamped to the player's maximum |
| `POST /api/dominion/unclaim` | `{"world": "default", "chunkX": 3, "chunkZ": -4}` | one chunk, in chunk coordinates |

### Chronicle Stats

`GET /api/chronicle/top/{stat}?window=week&world=default&limit=10` reads Chronicle's board for that window and
world, at most 100 rows, with `movement`, the places a player moved against yesterday's ranks on the `all` board
(positive is up, 0 on the other windows), and hidden players as Chronicle shows them to the
public. A world filtered board Chronicle has not built yet is waited on for `suite.waitMs`. `GET /api/chronicle/boards`
lists the stats that have a board. `GET /api/chronicle/series` lists the server series, `players.*` and
`server.*` as activity and the rest as performance, and `series/{id}?resolution=minute&from=...&to=...` gives the
points, a day back at hour resolution when nothing is given, empty buckets as null. The series are raw, so
`suite.chronicle.performanceSeries` false keeps the performance ones away from every key.

### Atlas and Lullaby

`GET /api/atlas` takes the address players open from Atlas itself, `publicUrlSet` says whether the admin set one.
`render` gives the tile queue, tiles running and done, `paused` and the budget left this minute (-1 when there is
no budget). `worlds` gives each world Atlas renders with players, explored chunks, queued tiles and whether the
public map shows it. `GET /api/lullaby` gives Lullaby's mode (`native` or `manual`) and per world `online`,
`eligible`, `inBed`, `ready`, `needed`, `missing`, `progress` from 0 to 1, the cooldown and the nights skipped.

### SocialBridge

`GET /api/socialbridge/links/{player}` gives the Discord user id linked to a player, `users/{user}` the player
linked to a Discord user id, both 404 `not_linked` when there is no link. A `{player}` here is a UUID or the name
of a player who is online, SocialBridge keeps no name index. `POST /api/socialbridge/post` needs
`socialbridge.write` and takes `text` (required, cut at 2000 characters), `channel` (`chat`, `events`, `status` or
a name from SocialBridge's `channels.extra`, `events` when left out), `kind` (`restapi` when left out, SocialBridge
files it as `api.<kind>`), and `title`, `footer` and `colour` (`#RRGGBB`) for an embed. It answers 409
`not_posted` when no service runs or the channel is turned off.

```
curl -X POST -H "Authorization: Bearer $KEY" \
  -d '{"channel": "status", "text": "Restart in 10 minutes", "title": "Server"}' \
  http://127.0.0.1:8125/api/socialbridge/post
```

### Moving Coinage money

`POST /api/coinage/transfer`, `give` and `take` move money through the Coinage ledger with the key as the
initiator (type `api_key`), the plugin `RestAPI` and the reason `restapi:<key name>`, or
`restapi:<key name>:<your reason>`. `/coinage find` shows "API key shopbot through RestAPI", history rows show the
key, and the daily reports count the move under the key's name. `GET /api/coinage/ledger?initiator=shopbot`
finds every move a key made. Only a key with `coinage.write`
reaches them. There is no overdraft and no fee, the amount is checked against `suite.coinage.maxAmount`, and every
move is written to RestAPI's audit log with the transaction id.

```
curl -X POST -H "Authorization: Bearer $KEY" -H "Idempotency-Key: prize-2026-10-16" \
  -d '{"from": "server", "to": "Steve", "amount": "250", "reason": "build contest"}' \
  http://127.0.0.1:8125/api/coinage/transfer
```

Send an `Idempotency-Key` header and a retry with the same key gets the first answer back with `"replayed": true`
and moves nothing. The answers are kept in memory for `suite.coinage.idempotencySeconds` and lost at a restart.
`amount` is text in the currency's own units such as `"12.50"`, `minor` is whole minor units. An account is a
player name, a UUID or a shared account handle such as `server`.

