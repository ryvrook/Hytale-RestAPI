# siblings settings

Keys under `siblings` in `mods/Ryvrook_RestAPI/RestAPI.json`. Written by SettingsDocTest, do not edit by hand.

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
