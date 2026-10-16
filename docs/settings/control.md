# control settings

Keys under `control` in `mods/Ryvrook_RestAPI/RestAPI.json`. Written by SettingsDocTest, do not edit by hand.

| Key | Default | Applies | Meaning |
|---|---|---|---|
| `control.enabled` | `true` | reload | Serve the plugin, world and server control routes. Off removes all of them, GET /api/server and /api/health stay. |
| `control.allowStop` | `true` | reload | Let POST /api/server/stop stop the server. Off answers 403 there. |
| `control.allowPluginLoad` | `true` | reload | Let the plugin routes load, unload and reload plugins. RestAPI itself is always refused. Off answers 403 there. |
| `control.allowWorldUnload` | `false` | reload | Let POST /api/worlds/<world>/unload remove a loaded world. The default world and the last world are always refused. |
| `control.allowConfigEdit` | `true` | reload | Let PATCH /api/server/config change config.json. Off answers 403 there, the GET still works. |
| `control.worldTimeoutMs` | `5000` | reload | Milliseconds a request waits for a world thread before it answers 504 world_timeout. Range 100 to 120000. |
| `control.saveTimeoutSeconds` | `60` | reload | Seconds a world save through the API may take before the answer reports it as still running. Range 1 to 3600. |
| `control.reloadWaitSeconds` | `15` | reload | Seconds a plugin reload waits for the new copy to reach ENABLED before it answers 202 with the state so far. Range 1 to 120. |
| `control.lockWaitSeconds` | `30` | reload | Seconds a plugin load, unload or reload waits for the suite lock that TrackerAPI and Coinage hold while they reload their dependents. After that it goes ahead without it. Range 0 to 300. |
| `control.stopDelaySeconds` | `0` | reload | Countdown in seconds when a stop request gives no delaySeconds. Range 0 to 86400. |
| `control.stopMaxDelaySeconds` | `3600` | reload | Longest countdown a stop request may ask for. Range 0 to 86400. |
| `control.stopAnnounce` | `true` | reload | Tell the players in chat while a stop counts down, when the request gives no announce field. |
| `control.restartExitCode` | `8` | reload | Exit code a stop with restart true uses. 8 is the engine's update code. Your supervisor must start the server again on it, see the README. Range 1 to 255. |
| `control.backupJobsKept` | `20` | reload | Finished backup jobs kept in memory for GET /api/server/backup/<job>. Range 1 to 1000. |
| `control.idempotencySeconds` | `600` | reload | Seconds the answer of a dangerous control route is kept under its key name and Idempotency-Key header, so a retry gets it again without running twice. 0 keeps nothing. Range 0 to 86400. |
