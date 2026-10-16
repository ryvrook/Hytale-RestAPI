# console settings

Keys under `console` in `mods/Ryvrook_RestAPI/RestAPI.json`. Written by SettingsDocTest, do not edit by hand.

| Key | Default | Applies | Meaning |
|---|---|---|---|
| `console.enabled` | `true` | reload | Keep a copy of the server log in memory and serve it under /api/console and as a stream. |
| `console.ringLines` | `2000` | reload | Log lines kept in memory. The oldest go first. Range 50 to 100000. |
| `console.minLevel` | `"INFO"` | reload | Lowest log level kept, one of SEVERE, WARNING, INFO, CONFIG, FINE, FINER, FINEST. Console replies always count as INFO. |
| `console.defaultLines` | `200` | reload | Lines GET /api/console answers when the request names no count, and lines a new stream starts with. Range 1 to 100000. |
| `console.keepAliveSeconds` | `15` | reload | Seconds between keep alive comments on a quiet log stream, so proxies do not close it. Range 1 to 300. |
