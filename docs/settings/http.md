# http settings

Keys under `http` in `mods/Ryvrook_RestAPI/RestAPI.json`. Written by SettingsDocTest, do not edit by hand.

| Key | Default | Applies | Meaning |
|---|---|---|---|
| `http.enabled` | `true` | reload | Serve the HTTP API. Off leaves only the in game command and RCON, when RCON is on. |
| `http.bind` | `"127.0.0.1"` | reload | Address the HTTP API listens on. 127.0.0.1 keeps it on this machine. 0.0.0.0 opens it to every network the machine is on, read the README section on exposure first. |
| `http.port` | `8125` | reload | TCP port of the HTTP API. Atlas uses 8123 and Chronicle Stats 8124 by default. Range 1 to 65535. |
| `http.basePath` | `""` | reload | Path prefix when a reverse proxy serves the API under a sub path, such as /restapi. Empty serves at the root. |
| `http.maxBodyBytes` | `65536` | reload | Largest request body accepted, in bytes. Bigger requests get 413 before the body is read. Range 1024 to 16777216. |
| `http.maxInFlight` | `32` | reload | Requests handled at the same time. The rest get 503 with Retry-After 1. Event streams do not count. Range 1 to 1024. |
| `http.maxStreams` | `16` | reload | Server Sent Event streams open at the same time, for the console and event streams. 0 refuses every stream. Range 0 to 1024. |
| `http.requestSeconds` | `30` | next start | Seconds a client gets to send a whole request before the connection is closed. Shared with Atlas and Chronicle Stats in the same JVM, the first plugin to start sets it. Range 5 to 600. |
| `http.healthPublic` | `true` | reload | Answer GET /api/health without a key, with the status and version only. Off asks for a key there too. |
| `http.trustedProxies` | `[]` | reload | Addresses or CIDR ranges of reverse proxies whose X-Forwarded-For header is believed, such as 127.0.0.1 or 10.0.0.0/8. Empty believes nobody. |
| `http.corsOrigins` | `[]` | reload | Browser origins allowed to call the API, such as https://panel.example.com. Empty sends no CORS headers, which is what scripts and panels on a server need. |
