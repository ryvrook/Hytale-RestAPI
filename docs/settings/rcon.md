# rcon settings

Keys under `rcon` in `mods/Ryvrook_RestAPI/RestAPI.json`. Written by SettingsDocTest, do not edit by hand.

| Key | Default | Applies | Meaning |
|---|---|---|---|
| `rcon.enabled` | `false` | reload | Listen for Source RCON clients such as mcrcon. The password is any RestAPI key that holds the commands scope. |
| `rcon.bind` | `"127.0.0.1"` | reload | Address RCON listens on, an IP literal. RCON has no encryption, so keep 127.0.0.1 and reach it through SSH or a VPN. |
| `rcon.port` | `25575` | reload | TCP port of RCON. 25575 is the port Minecraft servers use, so most RCON tools try it first. Range 1 to 65535. |
| `rcon.maxConnections` | `8` | reload | RCON connections open at the same time. One more is closed right after it connects. Range 1 to 256. |
| `rcon.idleSeconds` | `300` | reload | Seconds a connection may stay silent before it is closed. Range 10 to 86400. |
| `rcon.authFailuresPerMinute` | `5` | reload | Failed logins one address may make per minute. After that its connections are closed until the minute has passed. Range 1 to 1000. |
