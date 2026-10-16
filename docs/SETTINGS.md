# RestAPI settings

Every key of `mods/Ryvrook_RestAPI/RestAPI.json` with its default. Keys are dotted here and nested in the file, `http.port` is `{"http": {"port": 8125}}`. A missing key is added with its default at the next start or `/restapi reload`. A file with a wrong value is refused whole and the values in use stay. At the first start a refused file keeps the HTTP API down. This file is written by SettingsDocTest, do not edit it by hand.

## http

| Key | Default | Applies | Meaning |
|---|---|---|---|
| `http.enabled` | `true` | reload | Serve the HTTP API. Off leaves only the in game command and RCON, when RCON is on. |
| `http.bind` | `"127.0.0.1"` | reload | Address the HTTP API listens on. 127.0.0.1 keeps it on this machine. 0.0.0.0 opens it to every network the machine is on, read the README section on exposure first. |
| `http.port` | `8125` | reload | TCP port of the HTTP API. Atlas uses 8123 and Chronicle Stats 8124 by default. Range 1 to 65535. |
| `http.basePath` | `""` | reload | Path prefix when a reverse proxy serves the API under a sub path, such as /restapi. Empty serves at the root. |
| `http.maxBodyBytes` | `65536` | reload | Largest request body accepted, in bytes. Bigger requests get 413 before the body is read. Range 1024 to 16777216. |
| `http.maxInFlight` | `32` | reload | Requests handled at the same time. The rest get 503 with Retry-After 1. Event streams do not count. Range 1 to 1024. |
| `http.requestSeconds` | `30` | next start | Seconds a client gets to send a whole request before the connection is closed. Shared with Atlas and Chronicle Stats in the same JVM, the first plugin to start sets it. Range 5 to 600. |
| `http.healthPublic` | `true` | reload | Answer GET /api/health without a key, with the status and version only. Off asks for a key there too. |
| `http.trustedProxies` | `[]` | reload | Addresses or CIDR ranges of reverse proxies whose X-Forwarded-For header is believed, such as 127.0.0.1 or 10.0.0.0/8. Empty believes nobody. |
| `http.corsOrigins` | `[]` | reload | Browser origins allowed to call the API, such as https://panel.example.com. Empty sends no CORS headers, which is what scripts and panels on a server need. |

## keys

| Key | Default | Applies | Meaning |
|---|---|---|---|
| `keys.acceptXApiKey` | `true` | reload | Accept the key in an X-API-Key header as well as in Authorization Bearer, for tools written against HyRESTAPI. |
| `keys.createFirstKey` | `true` | reload | Create a key on the first start when keys.json holds none, and print it to the server log once. |
| `keys.firstKeyName` | `"admin"` | reload | Name of the key made on the first start. |
| `keys.firstKeyScopes` | `["all"]` | reload | Scopes of the key made on the first start. all means every scope, the command deny list still applies. |

## limits

| Key | Default | Applies | Meaning |
|---|---|---|---|
| `limits.requestsPerMinute` | `120` | reload | Requests a key may make per minute, as a token bucket that holds one minute. A key in keys.json can set its own. 0 turns the limit off. Range 0 to 100000. |
| `limits.authFailuresPerMinute` | `10` | reload | Requests with a missing or wrong key one address may make per minute before it gets 429. Range 1 to 10000. |

## audit

| Key | Default | Applies | Meaning |
|---|---|---|---|
| `audit.enabled` | `true` | reload | Write every change made through the API, RCON or /restapi keys to audit/audit-<date>.jsonl. |
| `audit.logReads` | `false` | reload | Also write read requests to the audit log. Busy dashboards make this file grow fast. |
| `audit.logAuthFailures` | `true` | reload | Write requests that carry a wrong key to the audit log, with the address. Requests with no key at all are not written. |
| `audit.keepDays` | `30` | reload | Days of audit files kept. Older files are deleted at start and at midnight. Range 1 to 3650. |

## server

| Key | Default | Applies | Meaning |
|---|---|---|---|
| `server.sampleSeconds` | `5` | reload | Seconds between the samples of each world's tick time, chunk and entity counts that /api/server reports. Range 1 to 300. |

## siblings

| Key | Default | Applies | Meaning |
|---|---|---|---|
| `siblings.checkSeconds` | `5` | reload | Seconds between checks of the plugin manager for suite mods that started, stopped or were reloaded. Range 1 to 300. |
| `siblings.tracker` | `"auto"` | reload | auto, always or never. Counters, leaderboards, recent events and the journal under /api/tracker while TrackerAPI 1.1.0 or newer runs. always binds an older version too, never keeps the routes away. |
| `siblings.coinage` | `"auto"` | reload | auto, always or never. Balances, accounts, ledger moves and the rich list under /api/coinage while Coinage 1.2.0 or newer runs. always binds an older version too, never keeps the routes away. |
| `siblings.dominion` | `"auto"` | reload | auto, always or never. Factions, claims, sieges and relations under /api/dominion while Dominion 0.6.2 or newer runs. always binds an older version too, never keeps the routes away. |
| `siblings.feats` | `"auto"` | reload | auto, always or never. Feat progress, unlocks and world records under /api/feats while Feats 1.6.0 or newer runs. always binds an older version too, never keeps the routes away. |
| `siblings.chronicle` | `"auto"` | reload | auto, always or never. Player and server stats under /api/chronicle while ChronicleStats 1.4.0 or newer runs. always binds an older version too, never keeps the routes away. |
| `siblings.atlas` | `"auto"` | reload | auto, always or never. The map link, map status and markers under /api/atlas while Atlas 1.7.0 or newer runs. always binds an older version too, never keeps the routes away. |
| `siblings.lullaby` | `"auto"` | reload | auto, always or never. Sleep state per world under /api/lullaby while Lullaby 1.0.1 or newer runs. always binds an older version too, never keeps the routes away. |
