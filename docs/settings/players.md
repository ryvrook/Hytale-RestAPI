# players settings

Keys under `players` in `mods/Ryvrook_RestAPI/RestAPI.json`. Written by SettingsDocTest, do not edit by hand.

| Key | Default | Applies | Meaning |
|---|---|---|---|
| `players.enabled` | `true` | reload | Mount the player, ban, whitelist, group and broadcast routes. Off removes every one of them on the next /restapi reload. |
| `players.readOnly` | `false` | reload | Mount only the read routes of the players module. Kicks, bans, teleports, gives, group changes and broadcasts answer 404. |
| `players.worldTimeoutMs` | `2000` | reload | Milliseconds a request waits for a world thread to read or change a player. Past it the answer is 504 world_timeout and a change may still land a moment later. Range 100 to 30000. |
| `players.maxGiveAmount` | `1000` | reload | Largest amount POST /api/players/{player}/give accepts in one call. Range 1 to 1000000. |
| `players.teleportAcrossWorlds` | `true` | reload | Let a teleport move a player into another world. Off answers 409 other_world when the target is in a different world. |
| `players.kickNamesKey` | `false` | reload | Add the name of the API key to the reason a kicked or banned player sees. |
| `players.allowKill` | `true` | reload | Mount POST /api/players/{player}/kill. |
| `players.allowClear` | `true` | reload | Mount POST /api/players/{player}/clear, which empties inventory sections. |
| `players.allowRefer` | `true` | reload | Mount POST /api/players/{player}/refer, which sends a player to another server. |
| `players.protectedGroups` | `["hytale:Admin"]` | reload | Permission groups the API never adds a player to or takes a player out of, so a key with the players scope cannot make an operator. hytale:Admin is the group /op adds. Empty allows every group. |
| `players.maxBanMinutes` | `0` | reload | Longest ban in minutes the API may give. 0 allows permanent bans, any other value refuses bans without minutes or longer than it. Range 0 to 52560000. |
| `players.maxTextLength` | `500` | reload | Longest text in characters for messages, broadcasts, titles and reasons. Range 10 to 4000. |
| `players.pageSize` | `100` | reload | Entries per page of GET /api/players/offline and GET /api/bans when the request sets no limit. Range 1 to 10000. |
| `players.maxPageSize` | `1000` | reload | Largest limit a request may ask for on the paged player lists. Range 1 to 100000. |
| `players.rememberNames` | `true` | reload | Keep names.json, the last name seen for each UUID, so offline players and bans resolve by name. Off forgets nothing already written but stops adding to it. |
