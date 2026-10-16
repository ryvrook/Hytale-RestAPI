# RestAPI

An HTTP API for a Hytale server, for admins who run their own stack and want to script it, watch it from
Grafana or an uptime checker, or drive it from a panel or a Discord bot. Every request carries a named key,
every key has scopes, every change is written to an audit log, and the server listens on 127.0.0.1 until you
change that.

Hytale 0.7.1 has no remote console, no query port and no management port of its own. The server reads
commands from stdin and that is all. RestAPI adds a network API next to it, inside the server process. It has 70
routes of its own for players, moderation, commands with their output, the live console, plugins, worlds,
events, webhooks and Prometheus metrics, an OpenAPI document with a docs page, and an RCON listener for tools
that already speak RCON. With my other mods installed it mounts their routes too, 120 routes with all seven.

The long form of every route, with request bodies, the stop and restart setup, webhook signatures and the
metric names, is in [docs/API.md](docs/API.md). Every setting is in [docs/SETTINGS.md](docs/SETTINGS.md).

## Requirements

Hytale server 0.7.0 release or newer, built against 0.7.1 and booted on 0.7.2, and Java 25. RestAPI needs no
other mod.

## Install

1. Drop `RestAPI-<version>.jar` into the server's `mods/` folder and start the server.
2. On the first start RestAPI makes a key named `admin` with every scope and prints it to the server log once,
   in a line that starts with `RestAPI made the key admin`. Only its SHA-256 is kept in
   `mods/Ryvrook_RestAPI/keys.json`, so copy it then. If you miss it, revoke it and make a new one with
   `/restapi keys create`.
3. Look for `RestAPI ... ready, HTTP API on http://127.0.0.1:8125 with 70 routes` in the log.
4. Try it from the server machine, or open `http://127.0.0.1:8125/docs` in a browser.

```
curl http://127.0.0.1:8125/api/health
curl -H "Authorization: Bearer rapi_xxxxxxxx_..." http://127.0.0.1:8125/api/server
```

## Keys and scopes

A key looks like `rapi_1a2b3c4d_` followed by 43 characters. The eight hex digits are the key id, the rest is
32 random bytes. Send it as `Authorization: Bearer <key>`. `X-API-Key: <key>` works too, for tools written
against HyRESTAPI, unless `keys.acceptXApiKey` is false.

| Scope | What it opens |
|---|---|
| `read` | Server, player, world and plugin state |
| `players` | Kick, ban, teleport, give, message and the other player changes |
| `commands` | Console commands with their output, and RCON logins |
| `console` | The server log and its live stream |
| `plugins` | Load, unload and reload plugins |
| `server` | Server config, saves, backups, time, weather, stop |
| `metrics` | `/metrics` |
| `events` | The event stream and webhooks |
| `tracker`, `coinage`, `dominion`, `feats`, `chronicle`, `atlas`, `lullaby` | That suite mod's routes while it runs |
| `coinage.write`, `dominion.write` | Changes through Coinage and Dominion |
| `all` | Every scope |

A key in `keys.json` can carry its own `requestsPerMinute` and an `allowFrom` list of addresses or CIDR ranges.
Edit the file and run `/restapi reload`.

Errors are `{"error": "missing_scope", "message": "This key needs the players scope"}` with the HTTP status.
The codes are fixed for scripts. Most sentences come from `messages.json`, the key and limit errors stay English. A request without a valid key gets
401 on every path except the open ones, so a stranger learns nothing about which routes exist. An address that
sends wrong keys gets 429 after `limits.authFailuresPerMinute` of them.

## Exposure, TLS and reverse proxies

RestAPI speaks plain HTTP. Keep `http.bind` at `127.0.0.1` when the tools that call it run on the same machine.
To reach it from elsewhere, put a reverse proxy with TLS in front of it, keep the bind at 127.0.0.1 and add the
proxy to `http.trustedProxies` so the audit log and the limits see the real client from `X-Forwarded-For`.
A bind of `0.0.0.0` opens the API to every network the machine is on. RestAPI then logs a warning at every
start and `/restapi status` says so in red.

Caddy, with the API under `/restapi` on a domain that already has a certificate:

```
example.com {
    handle_path /restapi/* {
        reverse_proxy 127.0.0.1:8125
    }
}
```

`handle_path` strips the prefix, so leave `http.basePath` empty. A proxy that keeps it, such as nginx with
`location /restapi/ { proxy_pass http://127.0.0.1:8125; }`, needs `http.basePath` set to `/restapi`.

Commands answer only from `commands.allowFrom`, loopback by default, so remote command calls need it widened.

## Routes

A `{player}` takes a name or a UUID. Lists take `limit` and `offset`. Routes marked D are dangerous and take an
`Idempotency-Key` header, so a retry after a lost answer gets the first answer back with `"replayed": true` and
nothing runs twice.

### Core

| Route | Scope | What it does |
|---|---|---|
| `GET /api/health` | none, `read` for more | `ok` with 200, or `booting`, `stopping` or `stalled` with 503 |
| `GET /api/server` | `read` | Server, players, worlds with TPS and tick times, JVM, suite mods, route groups |
| `GET /api/suite` | `read` | Each suite mod, whether its routes are mounted, and what waits on that mod |

`stalled` means a world thread missed RestAPI's sample for at least 15 seconds.

### Players and moderation

| Route | Scope | What it does |
|---|---|---|
| `GET /api/players` | `read` | Online players with world, position, ping, game mode, health |
| `GET /api/players/offline` | `read` | Every stored player with the last name seen |
| `GET /api/players/{player}` | `read` | One player with groups, op, ban and join grant |
| `GET /api/players/{player}/inventory` | `players` | Every inventory section |
| `POST /api/players/{player}/kick`, `message`, `title`, `teleport`, `give`, `heal`, `refer` | `players` | What the name says |
| `POST /api/players/{player}/clear`, `kill` | `players` | D |
| `PUT /api/players/{player}/gamemode` | `players` | `Adventure` or `Creative` |
| `GET /api/bans`, `POST /api/bans` (D), `DELETE /api/bans/{player}` | `read`, `players` | The ban list, ban, unban |
| `GET`, `PUT`, `POST /api/whitelist`, `DELETE /api/whitelist/{player}` | `read`, `players` | The join permission switch and who holds it |
| `GET /api/permissions/groups`, `GET /api/players/{player}/groups` | `read` | Groups |
| `PUT /api/players/{player}/groups`, `POST` and `DELETE .../groups/{group}` | `players` | D. Never the groups in `players.protectedGroups` |
| `POST /api/broadcast`, `POST /api/broadcast/title` | `players` | Chat line or title to everyone or one world |

`hytale:Admin`, the group `/op` adds, is protected by default, so a key with `players` cannot make an operator.
A ban made over the API stores the key name in the ban. Routes that touch the entity run on the player's world
thread and answer 504 `world_timeout` after `players.worldTimeoutMs`.

### Commands and the console

| Route | Scope | What it does |
|---|---|---|
| `POST /api/commands` | `commands` | D. Runs `{command}` and answers its output lines |
| `POST /api/commands/batch` | `commands` | D. Several commands in order |
| `GET /api/commands/runs/{runId}` | `commands` | A kept run |
| `GET /api/commands/deny` | `commands` | The deny list in force |
| `GET /api/commands/list` | `read` | Registered commands with aliases |
| `GET /api/console` | `console` | The newest log lines |
| `GET /api/console/stream` | `console` | Server Sent Events, the log as it is written, resumes with `Last-Event-ID` |

```
curl -s -X POST http://127.0.0.1:8125/api/commands \
  -H "Authorization: Bearer $KEY" -H "Idempotency-Key: $(uuidgen)" \
  -d '{"command": "say hello"}'
{"runId":"r8f3a01c2d4e5","outcome":"done","output":["RestAPI:admin: hello"],"millis":312,"replayed":false}
```

The command runs as `RestAPI:<key name>`, so the engine's own log line names the key. The audit log gets a
`started` line before the engine sees the command and the outcome after it. The deny list is checked after
aliases are resolved, so `/stop`, `STOP` and `shutdown` are the same. No setting removes `stop`, `shutdown`, any
`plugin` command that names `Ryvrook:RestAPI`, `restapi reload` or `restapi keys`. Command macros are not looked
into, put a macro's name on `commands.deny` when that matters.

### Plugins, worlds and server control

| Route | Scope | What it does |
|---|---|---|
| `GET /api/plugins`, `GET /api/plugins/{id}` | `read` | Loaded plugins with version, state and dependencies |
| `GET /api/plugins/available` | `plugins` | Jars found but not loaded |
| `POST /api/plugins/{id}/load`, `unload`, `reload` | `plugins` | D. Never RestAPI itself, 403 `self_refused` |
| `GET /api/worlds`, `GET /api/worlds/{world}` | `read` | Worlds with TPS, chunks, entities, time and weather |
| `PUT /api/worlds/{world}/time`, `weather` | `server` | Set the hour or force a weather |
| `POST /api/worlds/{world}/save` | `server` | Save one world |
| `PUT /api/worlds/{world}/ticking` | `server` | D. Stop the tick or pause |
| `POST /api/worlds/{world}/load`, `unload` | `server` | D. Unload only with `control.allowWorldUnload` |
| `GET`, `PATCH /api/server/config` | `read`, `server` | Name, MOTD, max players, view radius, join permission |
| `POST /api/server/save` | `server` | Save every world |
| `POST /api/server/backup`, `GET /api/server/backup/{job}` | `server` | Backup zip as a job |
| `GET`, `POST /api/server/stop`, `POST /api/server/stop/cancel` | `server` | D. Stop or restart with a countdown |

A plugin reload through the API takes the suite lock TrackerAPI and Coinage hold while they reload their
dependents. Hytale has no restart of its own, so `"restart": true` on a stop exits with code 8 for systemd or a
wrapper script to start the server again. docs/API.md has both setups.

### Events, webhooks and metrics

| Route | Scope | What it does |
|---|---|---|
| `GET /api/events` | `events` | Server Sent Events for join, ready, leave, chat, death, command, world, plugin and server, resumes with `Last-Event-ID` |
| `GET /api/events/recent` | `events` | The newest events as JSON |
| `GET`, `POST /api/webhooks`, `DELETE /api/webhooks/{id}` | `events` | Webhooks, stored in `webhooks.json` |
| `POST /api/webhooks/{id}/test` | `events` | Send one test event now |
| `GET /metrics` | `metrics`, or none with `metrics.public` | Prometheus text |

Webhook deliveries are signed with HMAC-SHA256 and retried on errors. `"format": "discord"` posts a plain line to a Discord channel webhook with mentions switched off. A
hook made over the API may not point at a private address unless `webhooks.allowPrivateTargets` is on. Metric
names follow the Apex exporter where they overlap, so its dashboards carry over.

### OpenAPI and docs

| Route | Scope | What it does |
|---|---|---|
| `GET /openapi.json` | none while `docs.public` is on | OpenAPI 3.1 of the routes mounted right now |
| `GET /docs`, `GET /docs/{file}` | none | A 16 KB page that renders it, with a Send box |

The page loads nothing from elsewhere and keeps the key you paste in memory only.

## RCON

RestAPI also answers Source RCON, so mcrcon, rcon-cli and the RCON panels of game hosts work against a Hytale
server. It is off by default. Set `"rcon": {"enabled": true}` and run `/restapi reload`. The password is any key
with the `commands` scope, and commands go through the same deny list and audit log as `POST /api/commands`.

```
mcrcon -H 127.0.0.1 -P 25575 -p rapi_1a2b3c4d_... "say Restart in 5 minutes"
```

RCON has no encryption. Keep `rcon.bind` on 127.0.0.1 and reach it through SSH
(`ssh -L 25575:127.0.0.1:25575 you@server`) or a VPN. mcrcon reads one packet per reply, so it prints only the
first 4086 bytes of a long reply such as `help`.

## Suite mods

RestAPI checks every `siblings.checkSeconds` for my other mods. While one runs its routes are mounted under
`/api/<id>` behind its own scope, and when it stops they are taken away, so a key never sees a route that would
answer "not installed". `GET /api/suite` and the OpenAPI document list only what is mounted. Each setting takes
`auto` (bind that version or newer), `always` (also an older one) or `never`.

| Mod | Routes under `/api/<id>` | Scope | Setting |
|---|---|---|---|
| TrackerAPI 1.1.0 | `tracker`, `kinds`, `counters/{player}`, `server`, `leaderboards`, `leaderboards/{key}`, `players`, `players/{player}`, `recent`, `journal` | `tracker` | `siblings.tracker` |
| Coinage 1.2.0 | `coinage`, `currencies`, `balances/{player}`, `accounts`, `accounts/{account}`, `ledger`, `ledger/{id}`, `top`, `reports/today`, `reports/days`, POST `transfer`, `give`, `take` | `coinage`, writes `coinage.write` | `siblings.coinage` |
| Dominion Factions 0.6.2 | `dominion`, `factions`, `factions/{tag}`, `claims`, `sieges`, `points`, `relations`, `players/{player}`, `at` | `dominion` | `siblings.dominion` |
| Feats 1.6.0 | `feats`, `players/{player}`, `players/{player}/stats/{key}`, `records`, `top`, `recent` | `feats` | `siblings.feats` |
| Chronicle Stats 1.4.0 | `chronicle`, `stats`, `players/{player}`, `players/{player}/{stat}`, `top/{stat}`, `server` | `chronicle` | `siblings.chronicle` |
| Atlas 1.7.0 | `atlas`, `layers`, `markers`, `hidden` | `atlas` | `siblings.atlas` |
| Lullaby 1.0.1 | `lullaby`, `worlds/{world}` | `lullaby` | `siblings.lullaby` |

The Coinage writes go through the Coinage ledger with the reason `restapi:<key name>`, so `/coinage` history
shows which key moved the money, and they take an `Idempotency-Key`. A `/plugin reload` of a bound mod unmounts its group.
TrackerAPI and Coinage reload RestAPI with them, for the others run `/plugin reload Ryvrook:RestAPI`.

Some of it waits on a change in the other mod. Until then RestAPI reads that mod's export files, or leaves the
route out.

- Coinage has no initiator type for an API key, so a move names RestAPI as the plugin and the key in the reason.
- Dominion is read only. Staff actions under `dominion.write`, a list that holds safe and war zones, and sieges
  with both sides and the phase need a Dominion API for them.
- The Feats points board and recent unlocks come from `export/feats.json`.
- Chronicle boards per window and the server series need an API call, the all time boards come from
  `export/stats.json`.
- Atlas has no call for its render status and the worlds rendered.
- Lullaby has no API. `/api/lullaby` reads its files and gives players per world and how many the rule needs,
  not who is asleep.

## Commands

| Command | What it does |
|---|---|
| `/restapi status` | Where the HTTP API listens, routes, keys, the audit log, RCON and a line per suite mod |
| `/restapi reload` | Reads `RestAPI.json`, `messages.json` and `keys.json` again, remounts the routes, moves the HTTP API or RCON when the bind or port changed |
| `/restapi keys list` | Every key with its id, scopes, when and by whom it was made |
| `/restapi keys create <name> <scopes>` | Makes a key and shows it once, such as `/restapi keys create grafana read metrics` |
| `/restapi keys revoke <name>` | Removes a key at once |
| `/restapi audit tail [--lines <n>]` | The newest audit lines, 10 by default, at most 50 |
| `/restapi rcon status` | The RCON listener and its connections |
| `/restapi webhooks list` | Webhooks with their delivery counts, the URL by host only |

Every command needs `restapi.admin`. The `keys` commands also need `restapi.admin.keys`, so staff who may read
the status cannot make themselves a key. A key made in game is shown in that player's chat. `/restapi reload`
never reloads RestAPI through the plugin manager.

## Audit log

Every change through the API or RCON, every command with its `started` line and outcome, every
`/restapi keys` change and reload, and every wrong key goes to `audit/audit-<UTC date>.jsonl` as one JSON line.
Tokens and request bodies are never written. Reads are left out unless `audit.logReads` is on.

## Settings

Edit `mods/Ryvrook_RestAPI/RestAPI.json` and run `/restapi reload`. A file with a wrong value is refused whole
and the values in use stay. There are 120 settings, each listed with its default and range in `docs/settings/`.

| Section | Keys | What it covers |
|---|---|---|
| `http` | 11 | Bind, port 8125, base path, body and stream limits, trusted proxies, CORS |
| `keys`, `limits`, `audit` | 10 | The first key, `X-API-Key`, requests per minute, the audit log |
| `server`, `siblings` | 9 | World sample interval, the suite check and `auto`, `always` or `never` per mod |
| `players` | 15 | Read only mode, world timeout, give and ban caps, protected groups, which routes mount |
| `commands`, `console` | 20 | Deny list, `allowFrom`, permissions of the command sender, timeouts, idempotency, the log ring |
| `control` | 15 | Stop, plugin load, world unload and config edit switches, timeouts, the restart exit code |
| `events`, `webhooks`, `metrics` | 18 | Event types, the ring, retries, queue size, private targets, `metrics.public` |
| `docs` | 5 | Open document, page, Send box, title, server URL |
| `rcon` | 6 | Off by default, bind, port 25575, connections, idle time, failed logins |
| `suite` | 11 | Page sizes, sibling waits, the TrackerAPI recent ring, Coinage write caps |

For an API that reads and never changes anything, give keys `read` only, or set `players.readOnly` true and
`commands.enabled` and `control.enabled` false. Every sentence RestAPI says in game or in the console is in
`messages.json`, keyed by id, and so are the error sentences of the modules.

## Data folder

| File | What it holds |
|---|---|
| `RestAPI.json` | Settings |
| `messages.json` | Every sentence, keyed by id |
| `keys.json` | Key names, ids, the SHA-256 of each token, scopes, limits and `allowFrom`. Owner only permissions |
| `audit/audit-<date>.jsonl` | The audit log, `audit.keepDays` of it |
| `names.json` | The last name seen per UUID, for `{player}` lookups of offline players. Written every 30 s when it changed |
| `webhooks.json` | Webhook URLs, types, formats and signing secrets in clear. Owner only permissions |

Idempotency answers, the log and event rings and webhook queues live in memory only.

## Roadmap

The player routes that need someone online were never run with a client connected, and need that before
1.0.0. A p95 tick metric needs the world sampler to keep samples.

## Build

```
export JAVA_HOME=/opt/homebrew/opt/openjdk@25/libexec/openjdk.jdk/Contents/Home
./gradlew build
```

The jar is in `build/libs/`. The tests need no server. `RESTAPI_WRITE_DOCS=1 ./gradlew test` writes
`docs/settings/` again after a setting changed. docs/DEVELOPMENT.md has the engine notes.

## License

MIT, see `LICENSE`.
