# suite settings

Keys under `suite` in `mods/Ryvrook_RestAPI/RestAPI.json`. Written by SettingsDocTest, do not edit by hand.

| Key | Default | Applies | Meaning |
|---|---|---|---|
| `suite.defaultLimit` | `50` | reload | Rows a suite list route answers when the request names no limit. Range 1 to 1000. |
| `suite.maxLimit` | `500` | reload | Most rows a suite list route answers, a bigger limit is cut to this. Range 1 to 10000. |
| `suite.waitMs` | `5000` | reload | Milliseconds a suite route waits for a sibling that reads files, such as the TrackerAPI journal or the Coinage ledger search, before it answers 504. Range 100 to 60000. |
| `suite.tracker.recentPerKind` | `100` | reload | TrackerAPI events kept in memory per kind for GET /api/tracker/recent. 0 opens no subscription. Range 0 to 1000. |
| `suite.tracker.recentHighVolume` | `false` | reload | Also keep the high volume kinds such as player_moved and block_damaged for /api/tracker/recent. Applies on the next bind. |
| `suite.coinage.writes` | `true` | reload | Mount POST /api/coinage/transfer, give and take for keys with the coinage.write scope. Applies on the next bind. |
| `suite.coinage.maxAmount` | `1000000` | reload | Largest amount one Coinage write moves, in minor units of the currency (1000000 is 10000.00 Coins). 0 means no cap. Range 0 to 2147483647. |
| `suite.coinage.requireIdempotencyKey` | `false` | reload | Refuse a Coinage write that has no Idempotency-Key header with 428. |
| `suite.coinage.idempotencySeconds` | `600` | reload | Seconds the answer to a Coinage write is kept under its key name and Idempotency-Key, so a retry gets the same answer and moves nothing. Range 10 to 86400. |
| `suite.dominion.homesAndWarps` | `true` | reload | Include faction homes and warps in GET /api/dominion/points and in the faction detail. |
| `suite.dominion.writes` | `true` | reload | Mount the Dominion staff routes (disband, leader, power, unclaim, unclaim all) for keys with the dominion.write scope. Applies on the next bind. |
| `suite.atlas.probe` | `true` | reload | GET /api/atlas asks the Atlas web server for its own /api/health on the loopback address. |
