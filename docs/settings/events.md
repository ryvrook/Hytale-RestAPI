# events settings

Keys under `events` in `mods/Ryvrook_RestAPI/RestAPI.json`. Written by SettingsDocTest, do not edit by hand.

| Key | Default | Applies | Meaning |
|---|---|---|---|
| `events.enabled` | `true` | reload | Serve the event stream, the recent events list and the webhook routes. Off unmounts them and stops every webhook delivery. |
| `events.ring` | `1000` | reload | Events kept in memory for /api/events/recent and for a stream that resumes with Last-Event-ID. Lost at a server stop. Range 50 to 100000. |
| `events.chat` | `true` | reload | Record chat messages as chat events. Messages a chat plugin cancelled are never recorded. |
| `events.chatText` | `true` | reload | Put the chat message itself into chat events. Off keeps only the sender, the world and the length. |
| `events.deaths` | `true` | reload | Record player deaths with the cause, the place and the killer when there is one. |
| `events.npcDeaths` | `false` | reload | Also record the death of every creature. A busy server makes hundreds a minute. |
| `events.commands` | `true` | reload | Record every command the engine runs, from players, the console, RCON and the API, read from the engine's own log line. |
| `events.pluginPollSeconds` | `5` | reload | Seconds between two looks at the plugin list. The engine has no plugin enable event, so plugin events come from this poll. Range 1 to 300. |
| `events.keepAliveSeconds` | `15` | reload | Seconds of quiet before an open event stream gets a comment line, so proxies do not close it. Range 5 to 300. |
