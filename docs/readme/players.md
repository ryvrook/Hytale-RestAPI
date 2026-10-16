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

| Key | Default | What it does |
|---|---|---|
| `players.enabled` | `true` | Mount the players module at all |
| `players.readOnly` | `false` | Keep only the read routes |
| `players.worldTimeoutMs` | `2000` | How long a request waits for a world thread before 504 |
| `players.maxGiveAmount` | `1000` | Largest amount one give accepts |
| `players.teleportAcrossWorlds` | `true` | Allow teleports into another world, off answers 409 `other_world` |
| `players.kickNamesKey` | `false` | Add the API key name to the reason a kicked or banned player sees |
| `players.allowKill` | `true` | Mount the kill route |
| `players.allowClear` | `true` | Mount the clear route |
| `players.allowRefer` | `true` | Mount the refer route |
| `players.protectedGroups` | `["hytale:Admin"]` | Groups the API never adds or removes |
| `players.maxBanMinutes` | `0` | Longest ban over the API. 0 allows permanent bans |
| `players.maxTextLength` | `500` | Longest message, title, broadcast or reason |
| `players.pageSize` | `100` | Default page size of the paged lists |
| `players.maxPageSize` | `1000` | Largest `limit` a request may ask for |
| `players.rememberNames` | `true` | Keep adding to `names.json` |
