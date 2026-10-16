# commands settings

Keys under `commands` in `mods/Ryvrook_RestAPI/RestAPI.json`. Written by SettingsDocTest, do not edit by hand.

| Key | Default | Applies | Meaning |
|---|---|---|---|
| `commands.enabled` | `true` | reload | Serve the command routes under /api/commands and offer the command service to RCON. Off leaves both without commands. |
| `commands.deny` | `["op","deop","perm","whitelist clear","plugin unload"]` | reload | Commands refused before the engine sees them, matched on the first words after aliases are resolved. stop, shutdown, sudo around a refused command, any plugin command naming Ryvrook:RestAPI and restapi reload or keys are refused on top of this list and cannot be taken off. |
| `commands.allowFrom` | `["127.0.0.0/8","::1"]` | reload | Client addresses or CIDR ranges that may run commands over HTTP or RCON, on top of the key's own allowFrom. Behind a reverse proxy this is the real client address. Empty allows every address. |
| `commands.permissions` | `["*"]` | reload | Permission nodes the command sender holds. * is every node, a.b.* every node under a.b, and -a.b takes a node away. The default answers like the console. |
| `commands.timeoutMs` | `5000` | reload | Milliseconds a command gets to finish before the answer goes out with the lines that arrived. The command may keep running. Range 100 to 120000. |
| `commands.maxTimeoutMs` | `60000` | reload | Largest timeoutMs a request may ask for. Range 100 to 600000. |
| `commands.graceMs` | `300` | reload | Milliseconds to keep listening after the command finished, for commands that reply late. Every new line starts the wait again. Range 0 to 10000. |
| `commands.graceMaxMs` | `2000` | reload | Most milliseconds spent listening for late replies after the command finished, however often new lines arrive. Range 0 to 60000. |
| `commands.maxOutputLines` | `500` | reload | Lines of output kept per command. The rest are counted and left out. Range 1 to 100000. |
| `commands.maxLineChars` | `2000` | reload | Longest command line accepted, in characters. Range 16 to 65536. |
| `commands.captureConsoleEcho` | `true` | reload | Also return what a command sends to the server console instead of its sender, such as the line of say. Only console lines written by that command's own thread are taken. |
| `commands.idempotencySeconds` | `600` | reload | Seconds a result is kept under its Idempotency-Key, so a retry gets the stored answer and runs nothing. Kept in memory only, a restart forgets them. Range 1 to 86400. |
| `commands.requireIdempotencyKey` | `false` | reload | Refuse command requests that carry no Idempotency-Key header with 400. |
| `commands.batchMax` | `20` | reload | Most commands in one POST /api/commands/batch. Range 1 to 500. |
| `commands.runsKept` | `1000` | reload | Finished runs kept in memory for GET /api/commands/runs/{runId}, newest first, each for commands.idempotencySeconds. Range 10 to 100000. |
