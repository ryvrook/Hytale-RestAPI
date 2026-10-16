## Suite mods

RestAPI works on its own. When it finds one of my other mods running it mounts that mod's routes under
`/api/<id>`, each behind a scope of its own, and takes them away again when the mod stops or `siblings.<id>` is
`never`. The OpenAPI document and `GET /api/suite` only list the routes that are mounted right now, so a script
never sees a route that answers "not installed". A key needs the mod's scope, `all` covers every one of them.

| Mod | Routes while it runs | Scope | Setting |
|---|---|---|---|
| TrackerAPI 1.1.0 | `/api/tracker`, `kinds`, `counters/{player}`, `server`, `leaderboards`, `leaderboards/{key}`, `players`, `players/{player}`, `recent`, `journal` | `tracker` | `siblings.tracker` |
| Coinage 1.2.0 | `/api/coinage`, `currencies`, `balances/{player}`, `accounts`, `accounts/{account}`, `ledger`, `ledger/{id}`, `top`, `reports/today`, `reports/days`, and `POST transfer`, `give`, `take` | `coinage`, writes `coinage.write` | `siblings.coinage` |
| Dominion Factions 0.6.2 | `/api/dominion`, `factions`, `factions/{tag}`, `claims`, `sieges`, `points`, `relations`, `players/{player}`, `at` | `dominion` | `siblings.dominion` |
| Feats 1.6.0 | `/api/feats`, `players/{player}`, `players/{player}/stats/{key}`, `records`, `top`, `recent` | `feats` | `siblings.feats` |
| Chronicle Stats 1.4.0 | `/api/chronicle`, `stats`, `players/{player}`, `players/{player}/{stat}`, `top/{stat}`, `server` | `chronicle` | `siblings.chronicle` |
| Atlas 1.7.0 | `/api/atlas`, `layers`, `markers`, `hidden` | `atlas` | `siblings.atlas` |
| Lullaby 1.0.1 | `/api/lullaby`, `worlds/{world}` | `lullaby` | `siblings.lullaby` |

A `{player}` is a UUID or a name. Names are looked up among the online players first, then wherever that mod
keeps names (Coinage's name index, TrackerAPI's names index, the Dominion rosters, the Feats and Chronicle exports),
so an offline player is found by name when the mod has seen them, and by UUID always.

Some routes read a file the mod writes for other tools instead of its API, because the API has no call for it
yet. Feats `top` and `recent` read `export/feats.json`, Chronicle `top/{stat}` and `server` read
`export/stats.json` (the all time boards only), Atlas reads `atlas.json` for its address (never the admin token),
and all of Lullaby comes from `Lullaby.json`, `stats.json` and `ignored.json`. Such a route answers 503
`file_missing` until the mod wrote the file once. Who is asleep right now is not in any Lullaby file, so
`/api/lullaby` gives the players online per world and how many of them the rule needs, not the sleepers.

### Moving Coinage money

`POST /api/coinage/transfer`, `give` and `take` move money through the Coinage ledger with the plugin and
initiator `RestAPI` and the reason `restapi:<key name>`, or `restapi:<key name>:<your reason>`, so `/coinage`
history, the ledger search and the daily reports all show which key did it. Only a key with `coinage.write`
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

Dominion is read only for now. Staff actions over the API wait until Dominion has an API for them.

| Key | Default | What it does |
|---|---|---|
| `suite.defaultLimit` | `50` | Rows a list route answers when the request names no limit |
| `suite.maxLimit` | `500` | Most rows a list route answers |
| `suite.waitMs` | `5000` | How long a route waits on a mod that reads files, such as the TrackerAPI journal, before 504 |
| `suite.tracker.recentPerKind` | `100` | Events kept per kind for `/api/tracker/recent`, 0 for none |
| `suite.tracker.recentHighVolume` | `false` | Keep high volume kinds such as `player_moved` too |
| `suite.coinage.writes` | `true` | Mount the three Coinage write routes |
| `suite.coinage.maxAmount` | `1000000` | Most minor units one move takes, 0 for no cap |
| `suite.coinage.requireIdempotencyKey` | `false` | Refuse a write without the header with 428 |
| `suite.coinage.idempotencySeconds` | `600` | How long a write's answer is kept for a retry |
| `suite.dominion.homesAndWarps` | `true` | Show faction homes and warps in `points` and the faction detail |
| `suite.atlas.probe` | `true` | `/api/atlas` asks the map's own `/api/health` on the loopback address |
