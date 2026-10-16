# Changelog

Every notable change to RestAPI is recorded here. The format follows Keep a Changelog and the project uses
semantic versioning.

## Unreleased

### Added

- Players module. List, detail and inventory, kick, message, title, teleport, give, clear, game mode, heal, kill
  and refer, bans with the API key stored on each ban, whitelist, permission groups with protected groups, and
  broadcasts. 28 routes, 15 `players.*` settings, and `names.json` for offline name lookups.
- Commands over HTTP with their output, a deny list that resolves aliases, `Idempotency-Key` replays and audit
  lines before and after each run, plus the server log as JSON and as a Server Sent Events stream with resume.
- Plugins, worlds and server control. List, load, unload and reload plugins under the suite lock (never RestAPI
  itself), set world time, weather, ticking and pause, save, load and unload worlds, edit `config.json`, run
  backups as jobs, and stop or restart the server with a countdown, cancel and exit code 8 for a supervisor.
- Events as Server Sent Events with `Last-Event-ID` resume (join, ready, leave, chat, death, command, world,
  plugin, server), signed webhooks with retries, a Discord format and a private target check, and `/metrics` in
  Prometheus format with request counts per route pattern and failed key counts.
- `GET /openapi.json` with an OpenAPI 3.1 document of the routes mounted now, suite groups included while bound,
  and a 16 KB docs page at `/docs` with no outside scripts, a strict Content-Security-Policy and a Send box that
  keeps the key in memory only.
- RCON (off by default, port 25575, loopback). Any key with the `commands` scope is the password, commands go
  through the same deny list as `POST /api/commands`, long replies are split into packets, and failed logins and
  connections are capped per address.
- Route groups for every suite mod under `/api/<mod>` with their own scopes, mounted while the mod runs. Coinage
  moves through `coinage.write` name the key in the ledger and take `Idempotency-Key` replays. `GET /api/suite`
  shows what is mounted.
- `/restapi rcon status` and `/restapi webhooks list`, and `/restapi status` prints the RCON line.
- Dominion staff routes under `dominion.write`: disband, leader, power, unclaim and unclaim all, acting as
  `RestAPI:<key name>`, each with an audit line. `suite.dominion.writes` turns them off.
- `GET /api/dominion/sieges/history`, `/api/chronicle/boards`, `/api/chronicle/series` and `series/{id}`,
  `/api/atlas/render` and `/api/atlas/worlds`. 131 routes with all seven suite mods.
- `?initiator=<name>` on `GET /api/coinage/ledger`, and `suite.chronicle.performanceSeries`.

### Changed

- Every suite group binds through its mod's `onEnable` and `onDisable` hooks and needs TrackerAPI 1.1.0, Coinage
  1.3.0, Dominion 0.7.0, Feats 1.7.0, Chronicle Stats 1.5.0, Atlas 1.8.0 and Lullaby 1.1.0. A reload of any of
  them brings RestAPI back through that mod's dependents pass, and the unbind on the way logs at FINE only.
  Under `always` an older copy binds with what its API had.
- Coinage moves name the API key as the initiator, so `/coinage find` and the daily reports show the key.
- `/api/dominion/factions` holds the safe and war zones after the ranked factions, and `/api/dominion/sieges`
  gives both sides, the phase and the counts.
- `/api/feats/top` reads `FeatsApi.top`, Chronicle boards come per window and world with server totals and
  records live, and `/api/atlas` takes its address from Atlas.
- `/api/lullaby` reads Lullaby's API and gives who is in bed, who is ready and how many are needed. The copy of
  Lullaby's sleep rule is gone.

## 0.1.0 - 2026-10-16

First build, not released yet. Needs the Hytale 0.7.0 release server or newer.

### Added

- `GET /api/health`, open by default, 503 while the server boots or stops or a world thread stops answering.
- `GET /api/server` with the server, players, worlds and their tick times, JVM figures, the suite mods found and
  the mounted route groups.
- Named keys with scopes in `keys.json`, stored as SHA-256 only. The first start makes an `admin` key and prints
  it once.
- Server Sent Events support in the HTTP layer for the streams that come next, capped by `http.maxStreams`.
- A token bucket rate limit per key, a limit on wrong keys per address, trusted proxies for `X-Forwarded-For`,
  CORS off unless origins are listed, a request body limit and a cap on requests in flight.
- The audit log in `audit/audit-<date>.jsonl`.
- Detection of TrackerAPI, Coinage, Dominion, Feats, Chronicle Stats, Atlas and Lullaby, with `siblings.<id>`
  set to auto, always or never, and their route groups mounted and unmounted with them.
- `/restapi status`, `reload`, `keys list`, `keys create`, `keys revoke` and `audit tail`.
- 30 settings in `RestAPI.json`, every sentence of `/restapi` in `messages.json`.
