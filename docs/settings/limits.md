# limits settings

Keys under `limits` in `mods/Ryvrook_RestAPI/RestAPI.json`. Written by SettingsDocTest, do not edit by hand.

| Key | Default | Applies | Meaning |
|---|---|---|---|
| `limits.requestsPerMinute` | `120` | reload | Requests a key may make per minute, as a token bucket that holds one minute. A key in keys.json can set its own. 0 turns the limit off. Range 0 to 100000. |
| `limits.authFailuresPerMinute` | `10` | reload | Requests with a missing or wrong key one address may make per minute before it gets 429. Range 1 to 10000. |
