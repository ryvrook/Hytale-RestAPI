## RCON

RestAPI can also answer Source RCON, the protocol Minecraft servers use, so tools such as mcrcon and rcon-cli
and the RCON panels of game hosts work against a Hytale server. It is off by default. Turn it on in
`RestAPI.json` and run `/restapi reload`:

```json
"rcon": {"enabled": true, "bind": "127.0.0.1", "port": 25575}
```

The password is any RestAPI key that holds the `commands` scope, so the audit log names the key behind every
login and the engine log names it on every command (`RestAPI:<key> executed command: ...`).

```
mcrcon -H 127.0.0.1 -P 25575 -p rapi_1a2b3c4d_... "say Restart in 5 minutes"
```

Commands go through the same deny list as `POST /api/commands`. `stop`, `shutdown` and anything that loads,
unloads or reloads RestAPI never run, the reply says the command is on the deny list. `commands.allowFrom`
applies too, so with the default only loopback addresses can log in, and a key's own `allowFrom` is checked
as well. A wrong password answers the way RCON clients expect (`Authentication failed`), and after
`rcon.authFailuresPerMinute` failed logins the address is cut off for the rest of the minute.

RCON has no encryption. The key and every command cross the network in clear text. Keep `rcon.bind` on
127.0.0.1 and reach it through SSH (`ssh -L 25575:127.0.0.1:25575 you@server`) or a VPN. Any other bind logs a
warning at every start.

Replies longer than 4086 bytes are sent as several packets. Clients that send an empty packet after the
command and wait for its echo join them, and so do clients that send an unknown packet type, the way the
Minecraft server works with both. mcrcon 0.7.2 reads one packet per command, so it prints only the first 4086
bytes of a long reply such as `help` on a server with many plugins. `/restapi reload` closes open RCON
connections and opens the port again with the new settings.

| Key | Default | What it does |
|---|---|---|
| `rcon.enabled` | `false` | Listen for RCON clients |
| `rcon.bind` | `"127.0.0.1"` | Listen address, an IP. Keep loopback, RCON has no encryption |
| `rcon.port` | `25575` | TCP port, the one Minecraft uses |
| `rcon.maxConnections` | `8` | Connections open at once, the next one is closed |
| `rcon.idleSeconds` | `300` | Seconds of silence before a connection is closed |
| `rcon.authFailuresPerMinute` | `5` | Failed logins per address and minute before it is cut off |
