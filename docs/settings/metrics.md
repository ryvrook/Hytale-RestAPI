# metrics settings

Keys under `metrics` in `mods/Ryvrook_RestAPI/RestAPI.json`. Written by SettingsDocTest, do not edit by hand.

| Key | Default | Applies | Meaning |
|---|---|---|---|
| `metrics.enabled` | `true` | reload | Serve GET /metrics in Prometheus text format. |
| `metrics.public` | `false` | reload | Answer GET /metrics without a key, for a Prometheus scraper on the same machine. Leave it off when http.bind is not a loopback address. |
