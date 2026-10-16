## Suite route groups

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

### Binding, per sibling, read from the jars on 2026-10-16

| Sibling | Bound from | Read at each request |
|---|---|---|
| TrackerAPI 1.1.0 | `TrackerApi.onEnable` and `onDisable`, taken back with `removeHooks` in `unbind` | the API object the hook handed out last |
| Coinage 1.2.0 (API 4) | `CoinageApi.onEnable`, `onDisable`, `removeHooks` | the same |
| Atlas 1.7.0 | `AtlasApi.onEnable`, `onDisable`, `removeHooks` (public in 1.7.0) | the same, `SessionApi.get()` for hidden players |
| Dominion 0.6.2 | `DominionApi.get()` non null | `DominionApi.get()` |
| Feats 1.6.0 | `FeatsApi.get()` non null | `FeatsApi.get()` |
| Chronicle Stats 1.4.0 | `ChronicleApi.get()` non null | `ChronicleApi.get()` |
| Lullaby 1.0.1 | nothing, files only | `Lullaby.json`, `stats.json`, `ignored.json` |

`onEnable` runs at once when the API is up, so `bind` registers the hooks, checks that one fired, and removes
them again before it throws when none did. The registry reports the reason and tries again on the next check. A
route whose API went away answers 503 `sibling_gone` until the registry unbinds the group.

### Threads

Every route runs on the HTTP virtual thread. All seven APIs say they are safe from any thread and read memory,
except these, which complete a future on the sibling's own IO thread and are waited on for `suite.waitMs`:
`Counters.load`, `nameOf` and `uuidOf` (TrackerAPI names index), `Journal.find` (newest first, at most four at
once inside TrackerAPI), `Ledger.search` (Coinage journal files) and `ChronicleApi.totalsAsync`. A leaderboard asks
`nameOf` for every row at once and leaves a name null when its lookup did not finish in time. `Reports.days` reads
a closed day's small file on the calling thread the first time.

The recent events ring is one `subscribeKinds("RestAPI", kinds, ASYNC, ...)` on TrackerAPI's dispatch thread. The
listener only appends to a deque per kind under one lock, `suite.tracker.recentPerKind` long, read on every add.
The high volume kinds (`EventKind.highVolume()`) are left out unless `suite.tracker.recentHighVolume` is on, and
nothing is subscribed with 0. The subscription is closed in `unbind` and in the disable hook.

### What each group reads

- TrackerAPI. Counters through `Counters.all` for a loaded player, `load` for an offline one. Leaderboards through
  `boardKeys` and `top`, capped at 1000 by TrackerAPI. Snapshots, `Stats`, `isTracked`, `Journal.kinds`. The
  journal route builds `Journal.Query` from the query string, the box needs all six bounds, `next` is the oldest
  `at` minus one when the page was full. Events become JSON through their record components, so a field TrackerAPI
  adds shows up without a change here.
- Coinage. `Currencies.resolve` (null is the primary), `Accounts.get`, `byHandle`, `uuidOf`, `shared`, `players`,
  `all`, `top`, `rank`, `Ledger.history`, `search`, `find`, `stats`, `Reports.netWorth`, `topNetWorth`, `today`,
  `days`. `Accounts.player(uuid)` is never called, it makes an account. The writes call `Ledger.apply` with
  `Initiator.plugin("RestAPI")`, `plugin("RestAPI")`, `overdraft(false)` and the reason `restapi:<key>[:<reason>]`,
  whose part before the first colon is the source the daily reports count. Amounts go through `Currency.parse`.
  A `Result` that failed answers with the failure in lower case and Coinage's own sentence, 409 for funds, limits
  and locks, 404, 400, 403 or 503 for the others.
- Dominion. `top(Integer.MAX_VALUE)` is the list of factions in ranking order, the normal factions only (safe and
  war zones are not in it). Claims come from `territoryProvider().territories(world)` per loaded world, chunk
  coordinates through `ChunkUtil.xOfChunkIndex` and `zOfChunkIndex` (high and low 32 bits). Sieges are the points
  of kind `siege`, owner the attacker, `expiresAt` the end of the phase. Warp passwords are never read.
- Feats. `feats`, `points`, `unlocked`, `progress`, `title`, `rank`, `milestones`, `firsts`, `stats`. The points
  board and recent unlocks come from `export/feats.json` (top 100, newest 50, `rarity` per feat).
- Chronicle Stats. `stats`, `totalsAsync`, `window`, `perId`, `worldKey`, `isTracked`. A counter key is grouped
  under its own definition or the longest prefix definition it starts with. `StatDef` refuses a windowed stat
  that is not board eligible and SUM. Boards and server totals come from `export/stats.json`, the `ALL` window
  only, with hidden players as Chronicle exported them.
- Atlas. `layers`, `MarkerLayer.markers(world)`, point, circle, polygon and polyline fields. `atlas.json` gives
  `bind`, `port`, `basePath` and `publicUrl`. With `suite.atlas.probe` the summary asks
  `http://<bind or 127.0.0.1>:<port><basePath>/api/health` with a 1.5 second limit and keeps `ok`, `version` and
  `status` of the public answer.
- Lullaby. `Lullaby.json` keys `Mode`, `Requirement`, `Percent`, `Count`, `MinimumSleepers` and `Worlds`, the
  per world entry over the top level. `stats.json` holds `NightsSkipped` per world, written by bson, so a number
  may come as `{"$numberLong": "3"}`. `SleepRule.needed` copies Lullaby's `Rule.needed`. Players per world come from
  RestAPI's own world samples.
