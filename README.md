# RestAPI

An HTTP API for a Hytale server, for admins who run their own stack and want to script it, watch it from
Grafana or an uptime checker, or drive it from a panel. Every request carries a named key, every key has
scopes, every change is written to an audit log, and the server listens on 127.0.0.1 until you change that.

This is 0.1.0, the first build. It serves `GET /api/health` and `GET /api/server` with the key store, scopes,
rate limits, the audit log and the suite detection in place. Players, moderation, commands with their output,
the console stream, plugins, worlds, events, webhooks, Prometheus metrics, the OpenAPI document and an RCON
listener come in the next versions, see the roadmap at the end.

Hytale 0.7.1 has no remote console, no query port and no management port of its own. The server reads
commands from stdin and that is all. Panels such as Pterodactyl write to that stdin. RestAPI adds a network
API next to it, inside the server process.

## Requirements

Hytale server 0.7.0 release or newer, built against 0.7.1 and booted on 0.7.2, and Java 25. RestAPI needs no
other mod. When mods of my suite are installed it finds them, see "Suite mods" below.

## Install

1. Drop `RestAPI-0.1.0.jar` into the server's `mods/` folder and start the server.
2. On the first start RestAPI makes a key named `admin` with every scope and prints it to the server log
   once, in a line that starts with `RestAPI made the key admin`. Only its SHA-256 is kept in
   `mods/Ryvrook_RestAPI/keys.json`, so copy it then. If you miss it, revoke it and make a new one with
   `/restapi keys create`.
3. Look for `RestAPI 0.1.0 ready, HTTP API on http://127.0.0.1:8125 with 2 routes` in the log.
4. Try it from the server machine.

```
curl http://127.0.0.1:8125/api/health
curl -H "Authorization: Bearer rapi_xxxxxxxx_..." http://127.0.0.1:8125/api/server
```

## Keys and scopes

A key looks like `rapi_1a2b3c4d_` followed by 43 characters. The eight hex digits are the key id, the rest
is 32 random bytes. Send it as `Authorization: Bearer <key>`. `X-API-Key: <key>` works too, for tools written
against HyRESTAPI, unless `keys.acceptXApiKey` is false.

Make one key per tool, with only the scopes it needs. A Grafana scraper gets `read metrics`, an uptime
checker needs no key at all for `/api/health`, a moderation bot gets `read players`.

| Scope | What it opens |
|---|---|
| `read` | Server, player, world and plugin state |
| `players` | Kick, ban, teleport, give, message and the other player changes |
| `commands` | Console commands with their output |
| `console` | The server log and its live stream |
| `plugins` | Load, unload and reload plugins |
| `server` | Save worlds, time and weather, stop |
| `metrics` | `/metrics` in Prometheus text format |
| `events` | The event stream and webhooks |
| `tracker`, `coinage`, `dominion`, `feats`, `chronicle`, `atlas`, `lullaby` | The routes of that suite mod while it is installed |
| `coinage.write`, `dominion.write` | Changes through Coinage and Dominion |
| `all` | Every scope |

A key in `keys.json` can carry its own `requestsPerMinute` and an `allowFrom` list of addresses or CIDR
ranges. Edit the file and run `/restapi reload`.

Answers to scripts are JSON. Errors are `{"error": "missing_scope", "message": "This key needs the players scope"}`
with the HTTP status. A request without a valid key gets 401 on every path except `/api/health`, so a
stranger learns nothing about which routes exist. An address that sends wrong keys gets 429 after
`limits.authFailuresPerMinute` of them.

## Exposure, TLS and reverse proxies

RestAPI speaks plain HTTP. A key sent over plain HTTP across a network can be read by anyone on the path.

- Keep `http.bind` at `127.0.0.1` when the tools that call it run on the same machine.
- To reach it from elsewhere, put a reverse proxy with TLS in front of it on the same machine, keep the bind
  at 127.0.0.1, and add the proxy's address to `http.trustedProxies` so the audit log and the limits see the
  real client address from `X-Forwarded-For`.
- Setting `http.bind` to `0.0.0.0` opens the API to every network the machine is on. RestAPI logs a warning at
  every start when the bind is not a loopback address, and `/restapi status` says so in red. Do this only on a
  private network you control, and give keys an `allowFrom` list.

Caddy, with the API under `/restapi` on a domain that already has a certificate:

```
example.com {
    handle_path /restapi/* {
        reverse_proxy 127.0.0.1:8125
    }
}
```

With `handle_path` Caddy strips the prefix, so leave `http.basePath` empty. A proxy that keeps the prefix,
such as nginx with `location /restapi/ { proxy_pass http://127.0.0.1:8125; }`, needs `http.basePath` set to
`/restapi`. Browsers need `http.corsOrigins` to call the API from another origin. Scripts and panels on a
server do not.

## Endpoints

| Method and path | Scope | What it answers |
|---|---|---|
| `GET /api/health` | none, or `read` for more | `{"status": "ok", "restapi": "0.1.0"}` with 200, or `booting`, `stopping` or `stalled` with 503. With a `read` key it adds the stalled worlds, the world count, the uptime and how many suite mods are bound |
| `GET /api/server` | `read` | Server name, MOTD, version and revision, max players, uptime, the players online with their world, every world with its TPS, tick times, loaded chunks and entities, JVM heap, threads and CPU, the suite mods RestAPI found, and the route groups mounted |

`stalled` means a world thread has not answered RestAPI's sample for three sample intervals, at least 15
seconds. RestAPI asks each world for its numbers every `server.sampleSeconds` on that world's own thread and
the API answers from the last sample, so a request never waits on a world. A hung world thread shows up in
`/api/health` as a 503, which an uptime checker can alert on.

## Suite mods

RestAPI checks the plugin manager every `siblings.checkSeconds` for my other mods. When one runs, its route
group is mounted under its own path and scope. When it stops, the group is unmounted. A key never sees a route
that would answer "not installed". `GET /api/server` lists every suite mod in its `siblings` section with the
version found, whether it is bound, and why not.

| Mod | What appears while it runs | Setting |
|---|---|---|
| TrackerAPI 1.1.0 | Counters, leaderboards, recent events and the journal under `/api/tracker`, scope `tracker` | `siblings.tracker` |
| Coinage 1.2.0 | Balances, accounts, ledger moves and the rich list under `/api/coinage`, scopes `coinage` and `coinage.write` | `siblings.coinage` |
| Dominion Factions 0.6.2 | Factions, claims, sieges and relations under `/api/dominion`, scopes `dominion` and `dominion.write` | `siblings.dominion` |
| Feats 1.6.0 | Feat progress, unlocks and world records under `/api/feats`, scope `feats` | `siblings.feats` |
| Chronicle Stats 1.4.0 | Player and server stats under `/api/chronicle`, scope `chronicle` | `siblings.chronicle` |
| Atlas 1.7.0 | The map link, map status and markers under `/api/atlas`, scope `atlas` | `siblings.atlas` |
| Lullaby 1.0.1 | Sleep state per world under `/api/lullaby`, scope `lullaby` | `siblings.lullaby` |

The version is the oldest one the routes were written against. Each setting takes `auto` (bind when that
version or newer runs), `always` (also bind an older version, and log when the mod is missing) or `never`.
In 0.1.0 the detection works and `/restapi status` lists every mod, but no route group ships yet, so each
one reads "this build of RestAPI has no routes for it yet".

When a bound mod is reloaded with `/plugin reload` while RestAPI runs, its group is unmounted, because the
classes RestAPI linked belong to the old copy. TrackerAPI and Coinage reload their dependents, RestAPI among
them, so their groups come back by themselves. For the others run `/plugin reload Ryvrook:RestAPI`.

## Commands

| Command | What it does |
|---|---|
| `/restapi status` | Where the HTTP API listens, the route count, keys, the audit log and a line per suite mod |
| `/restapi reload` | Reads `RestAPI.json`, `messages.json` and `keys.json` again, moves the HTTP API when the bind or port changed, and checks the suite mods |
| `/restapi keys list` | Every key with its id, scopes, when and by whom it was made |
| `/restapi keys create <name> <scopes>` | Makes a key and shows it once. Scopes separated by spaces or commas, such as `/restapi keys create grafana read metrics` |
| `/restapi keys revoke <name>` | Removes a key. Requests with it get 401 at once |
| `/restapi audit tail [--lines <n>]` | The newest audit lines, 10 by default, at most 50 |

Every command needs `restapi.admin`. The three `keys` commands also need `restapi.admin.keys`, so staff who
may read the status cannot make themselves a key. The console has both. A key made from in game is shown in
that player's chat.

`/restapi reload` never reloads RestAPI through the plugin manager. It reads the files again in place.

## Audit log

Every change made through the API, every `/restapi keys` change, every reload and every request with a wrong
key is written to `mods/Ryvrook_RestAPI/audit/audit-<UTC date>.jsonl`, one JSON object a line with the time,
the source, the key name, the client address, the method and path, the status and a short note. Tokens and
request bodies are never written. Reads are left out unless `audit.logReads` is on. Files older than
`audit.keepDays` are deleted.

## Settings

Edit `mods/Ryvrook_RestAPI/RestAPI.json` and run `/restapi reload`. A file with a wrong value, such as a port
over 65535, is refused whole and the values in use stay, and at the first start a refused file keeps the HTTP
API down. `docs/SETTINGS.md` lists every key with its range and is checked against the code by the tests.

| Key | Default | What it does |
|---|---|---|
| `http.enabled` | `true` | Serve the HTTP API |
| `http.bind` | `"127.0.0.1"` | Listen address. Read "Exposure" above before changing it |
| `http.port` | `8125` | TCP port. Atlas uses 8123 and Chronicle Stats 8124 |
| `http.basePath` | `""` | Path prefix behind a reverse proxy that keeps it, such as `/restapi` |
| `http.maxBodyBytes` | `65536` | Largest request body, bigger ones get 413 |
| `http.maxInFlight` | `32` | Requests handled at once, the rest get 503 with Retry-After 1 |
| `http.requestSeconds` | `30` | Seconds a client gets to send its request. Read once per JVM, needs a restart |
| `http.healthPublic` | `true` | Answer `/api/health` without a key |
| `http.trustedProxies` | `[]` | Proxies whose `X-Forwarded-For` is believed, addresses or CIDR ranges |
| `http.corsOrigins` | `[]` | Browser origins allowed to call the API. Empty sends no CORS headers |
| `keys.acceptXApiKey` | `true` | Accept `X-API-Key` as well as `Authorization: Bearer` |
| `keys.createFirstKey` | `true` | Make a key on the first start when there is none |
| `keys.firstKeyName` | `"admin"` | Name of that key |
| `keys.firstKeyScopes` | `["all"]` | Scopes of that key |
| `limits.requestsPerMinute` | `120` | Requests per key and minute, 0 for no limit. A key can set its own |
| `limits.authFailuresPerMinute` | `10` | Wrong or missing keys per address and minute before 429 |
| `audit.enabled` | `true` | Write the audit log |
| `audit.logReads` | `false` | Also write read requests |
| `audit.logAuthFailures` | `true` | Write requests that carry a wrong key |
| `audit.keepDays` | `30` | Days of audit files kept |
| `server.sampleSeconds` | `5` | Seconds between world samples |
| `siblings.checkSeconds` | `5` | Seconds between checks for suite mods |
| `siblings.<id>` | `"auto"` | `auto`, `always` or `never` for `tracker`, `coinage`, `dominion`, `feats`, `chronicle`, `atlas` and `lullaby` |

Every sentence RestAPI says in game or in the console is in `messages.json` next to the settings, keyed by id,
so you can reword any of them. API answers stay in English with fixed error codes, since scripts test them.

## Data folder

| File | What it holds |
|---|---|
| `RestAPI.json` | Settings |
| `messages.json` | Sentences of `/restapi` |
| `keys.json` | Key names, ids, SHA-256 of each token, scopes, limits and `allowFrom`. Written with owner only permissions where the system has them |
| `audit/audit-<date>.jsonl` | The audit log |

## Roadmap

These are planned and not in 0.1.0. Players with position, world, health, game mode and ping, kick, ban,
unban and the ban list, the join whitelist, permission groups, teleport, give, clear, heal, kill, messages,
broadcasts and titles. Console commands with their output, run once even when a client retries, with a deny
list that always holds `stop` and anything that reloads RestAPI. The server log as a tail and as a Server Sent
Events stream. Plugins, worlds, time, weather, saves and backups. An event stream and outgoing webhooks for
joins, leaves, chat, deaths and commands. `/metrics` for Prometheus. `/openapi.json` and a docs page. An RCON
listener on 127.0.0.1:25575, off by default, for mcrcon, rcon-cli and panels that speak RCON, with the same
keys, deny list and audit log.

## Build

```
export JAVA_HOME=/opt/homebrew/opt/openjdk@25/libexec/openjdk.jdk/Contents/Home
./gradlew build
```

The jar is `build/libs/RestAPI-0.1.0.jar`. The tests need no server. `RESTAPI_WRITE_DOCS=1 ./gradlew test`
writes `docs/SETTINGS.md` again after a setting changed.

## License

MIT, see `LICENSE`.
