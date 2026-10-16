# Changelog

Every notable change to RestAPI is recorded here. The format follows Keep a Changelog and the project uses
semantic versioning.

## Unreleased

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
