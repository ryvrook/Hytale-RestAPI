## Players module

Package `dev.ryvrook.restapi.players`, group `players`, 28 routes with every switch on. Read with javap on
`Server-0.7.1.jar` and seen on a headless smoke run on the client's bundled 0.7.2 server on 2026-10-16, unless a
line says otherwise.

### Classes

| Class | What it does |
|---|---|
| `PlayersModule` | Routes, paging, the 30 second name sweep on the scheduler, the disconnect listener registered in setup |
| `PlayersSettings`, `PlayersText` | `players.*` settings and `players.*` messages |
| `Lookup` | `{player}` to a UUID, a name and the `PlayerRef` when online |
| `NameBook` | `names.json`, the last name seen per UUID |
| `WorldCall` | Runs a task on a world thread, waits `players.worldTimeoutMs`, 504 `world_timeout` after |
| `PlayerRead` | List, detail and inventory |
| `PlayerActions` | Kick, message, title, teleport, give, clear, game mode, heal, kill, refer, broadcasts |
| `Moderation` | Bans, whitelist, groups |
| `Fields` | Typed body fields with 400 `missing_field` or `invalid_field` |

### Threads

On the request thread: `PlayerRef.getUuid`, `getUsername`, `getWorldUuid`, `getTransform`, `getHeadRotation`,
`getLanguage`, `getPacketHandler`, `sendMessage`, `referToServer` (it only writes a packet), the title packets
through `EventTitleUtil` (`PacketHandler.writeNoCache`), `ConnectionRegistry.disconnect`, every
`AccessControlModule` and `PermissionsModule` call, `PlayerStorage.getPlayers()` and `Item.getAssetMap()`.

On the world thread through `WorldCall`: `Store.getComponent` for `Player`, `EntityStatMap` and the inventory
components, `Player.giveItem`, `Player.setGameMode`, `EntityStatMap.maximizeStatValue`, the `Teleport` component
and `DeathComponent.tryAddComponent`. The entity ref is `PlayerRef.getReference()`, checked with `isValid()` on
the world thread. A player whose ref is not valid is between worlds and gets 409 `not_in_world`.

A world task that passes the timeout is not taken back. The 504 answer says a change may still land.

### Engine facts

- Rotations are radians inside the engine. `/tp` takes degrees and multiplies by 0.017453292, so the API takes
  yaw and pitch in degrees and converts, and the detail answers head rotation in degrees.
  `Rotation3f(x, y, z)` is pitch, yaw, roll.
- A teleport is `store.addComponent(ref, Teleport.getComponentType(), Teleport.createForPlayer(world, transform))`
  on the player's current world thread, as `TeleportOtherToPlayerCommand` does. The target world goes in the
  component, so one call covers both cases.
- Inventory sections come from `InventoryComponent.getComponentTypeById(id)` with the section ids hotbar -1,
  storage -2, armor -3, utility -5, tool -8, backpack -9, and `ItemContainer.forEach((slot, stack) -> ...)`.
  `ItemContainer.clear()` answers a `ClearTransaction` whose `getItems()` are the stacks it removed.
- `Player.giveItem(stack, ref, store)` answers an `ItemStackTransaction`, its `getRemainder()` is what did not fit.
- Kill is `new Damage(new Damage.CommandSource(ConsoleSender.INSTANCE, "kill"), DamageCause.COMMAND, Float.MAX_VALUE)`
  as `/kill` does. `DamageCause.COMMAND` is deprecated in 0.7.1 but it is what the engine still uses.
- `EventTitleUtil.showEventTitleToPlayer(ref, title, subtitle, boolean, ...)` is deprecated for removal. The
  overload with `EventTitleStyle` (`Default`, `Major`) is used. The floats are duration, fade in, fade out, and
  the defaults are 4.0, 1.5 and 1.5.
- Ping is `getPingInfo(PongType.Direct).getPingMetricSet().getAverage(ONE_MINUTE_INDEX)` in `PingInfo.TIME_UNIT`.
- `AccessControlModule.ban(Ban)` answers false for a ban that is not in effect or when the provider already holds
  one, and kicks the target through `ConnectionRegistry` when it adds one. A `Ban` with a null `expiresOn` is
  permanent. `Ban.withMetadata("RestApiKey", Codec.STRING, key)` is stored in `bans.json` under `Metadata`, seen
  on the smoke run, so the ban list names the key that made it.
- `setJoinPermissionRequired(boolean)` sets `RequireJoinPermission` and saves `config.json` through
  `HytaleServerConfig.save`, its future completes after the save. The smoke run read `true` and then `false` back
  from `config.json`. `allowJoin(UUID)` writes the permission `hytale.server.join` into `permissions.json`.
- Groups registered on a fresh 0.7.2 server: `hytale:Admin` (parent `hytale:ServerEditor`, permission `*`),
  `hytale:ServerEditor`, `hytale:WorldEditor`, `hytale:Builder`, `hytale:Adventurer`, `hytale:None`. A user with
  no stored group reads as `hytale:Adventurer`. `/op` maps to `hytale:Admin`.
  `addUserToGroup` and `removeUserFromGroup` write `permissions.json` at once.
- `PlayerStorage.getPlayers()` lists UUIDs only. Offline mode has no profile lookup by name, which is why
  `names.json` exists.

### Not verified

Every route that needs an online player (list with a player in it, detail with stats, inventory, message, title,
teleport, give, clear, game mode, heal, kill, refer, broadcast with recipients) was not run, a headless server
cannot take a client join. Their engine calls copy what the engine's own commands do, read with javap. The first
run with a client should cover them before 1.0.0.
