# dashboard settings

Keys under `dashboard` in `mods/Ryvrook_RestAPI/RestAPI.json`. Written by SettingsDocTest, do not edit by hand.

| Key | Default | Applies | Meaning |
|---|---|---|---|
| `dashboard.enabled` | `false` | reload | Serve the admin dashboard at /dashboard on the HTTP API's own address. Off mounts none of its routes. /restapi dashboard on and off change it and write it here. |
| `dashboard.writes` | `false` | reload | Let a dashboard session change the server. Off it may only read, and the page draws no controls. /restapi dashboard writes on and off change it and write it here. |
| `dashboard.sessionHours` | `12` | reload | Hours a dashboard session lasts without use. A session ends after 7 days whatever this says. Range 1 to 168. |
| `dashboard.linkMinutes` | `10` | reload | Minutes a login link from /restapi dashboard login works. Each link works once. Range 1 to 60. |
| `dashboard.requestsPerMinute` | `600` | reload | Requests a dashboard session may make per minute, the same token bucket as a key. The page asks for many small things at once, so it gets more than limits.requestsPerMinute. 0 turns the limit off. Range 0 to 100000. |
| `dashboard.assets` | `""` | reload | Path of the game's Assets.zip for the item, creature and map marker icons of the page. Empty looks for the archive the server loaded, then Assets.zip in the server folder and the folder above it. Without one the page shows names only. |
| `dashboard.chronicle` | `true` | reload | Pass the character pictures of Chronicle Stats through to the page while Chronicle Stats is bound. Off keeps the letter busts. |
