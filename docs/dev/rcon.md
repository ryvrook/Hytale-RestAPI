## RCON

Package `dev.ryvrook.restapi.rcon`. `RconModule` (group `rcon`, no routes) opens the port in `start` when
`rcon.enabled` is on and closes it with every connection in `stop`, so `/restapi reload` applies a new bind or
port and drops the open connections. `RconServer` accepts on one virtual thread named `RestAPI-rcon-accept` and
runs each connection as an `RconSession` on a virtual thread `RestAPI-rcon-<n>`. `Packet` is the codec, pure and
tested against byte arrays.

The session looks up `RestApi.service(CommandService.class)` for every command, not once at start, so it works
whichever order the console and RCON modules start in and after the console module restarts on a reload. When
the service is missing the reply says the console module is not running and nothing runs.

### Protocol as built

Read from the Minecraft wiki and the Valve talk page, then checked against mcrcon 0.7.2 (`mcrcon.c`, built from
github.com/Tiiffi/mcrcon at b5951e9) and a headless smoke run.

- A packet is int32 length, int32 id, int32 type, the body, two null bytes, little endian. The length counts
  id, type, body and both nulls, so it is at least 10. A request may carry 4096 body bytes. A length under 10 or
  over 4106 closes the connection, and on a connection that has not logged in it counts as a failed login.
- Login is type 3 with the key as the body. The answer is type 2 with the request id, or id -1 when refused.
  Before a login every packet answers type 2 id -1, as Minecraft does for a command.
- A command is type 2. The reply is type 0 with the same id. A body over 4086 bytes goes out as several type 0
  packets with the same id, cut after a line break when one sits in the second half of the piece and never inside
  a UTF-8 character. 4086 keeps the length field at 4096 or less. Valve's description gives 4096 as the largest
  packet and mcrcon refuses a length over 4096 with "invalid packet size", so the 4096 body bytes Minecraft sends
  in one piece would break it.
- An empty type 0 is echoed back with its id and an empty body. Any other type answers type 0
  `Unknown request <hex type>`. Packets are handled strictly in order, so either one sent after a command marks
  the end of a split reply.
- mcrcon sends every packet with the id `0xBADC0DE` and reads exactly one packet per command, both seen in its
  source. A long reply therefore shows only its first piece in mcrcon.

### No idempotency key on RCON

The evaluation planned an idempotency key built from the connection and the request id. mcrcon uses the same
id for every packet, so that key would replay the first answer for every later command on the connection.
Inside one TCP connection a packet never arrives twice, and a client that reconnects cannot be told apart from
one that repeats a command on purpose. RCON passes no idempotency key. The command service's run id and audit
lines are the trace for a lost answer.

### Login checks

A key logs in when it holds the `commands` scope, its own `allowFrom` is empty or holds the address, and
`commands.allowFrom` holds the address. `commands.allowFrom` and `commands.timeoutMs` belong to the console
module and are read by key name through `Settings.all()`, so this package builds without it. Without the console
module the defaults are loopback and 5000 ms. Before each command the key is looked up again by name. A revoked
key or one that lost the scope gets one reply saying so and the connection closes.

Logins are audited with source `rcon`, the key name, the address, action `login` and detail `ok`. Refused
logins are audited under `audit.logAuthFailures` with the reason (`wrong key`, `key lacks the commands scope`,
`address not in allowFrom`, `address not in commands.allowFrom`) and the key name when the token matched one,
else `unknown`. Command runs are audited by the command service, which gets source `rcon`.

Failed logins go into a `RateLimiter` per address with `rcon.authFailuresPerMinute` per minute. The failure that
empties the bucket closes the connection and new connections from that address are closed on accept until the
bucket has a token again. Connections over `rcon.maxConnections` are closed on accept with one log line.
`rcon.idleSeconds` is the socket read timeout.

### Smoke on 2026-10-16

Client's bundled 0.7.2 server, RestAPI with a stand in for the console module that runs lines through
`CommandManager.get().handleCommand` with a capturing sender named `RestAPI:<key>`, HTTP on 8136 and RCON on
25586. Alone and with the seven sibling jars.

- `RestAPI RCON on 127.0.0.1:25586, the password is any key with the commands scope`, then the ready line, again
  after `/restapi reload`.
- `mcrcon -p <admin key> "say hello from rcon"` exit 0, engine log
  `RestAPI:admin executed command: say hello from rcon`. Two commands on one mcrcon connection both ran.
- `help` with the seven siblings is 4812 bytes. The Java client got it in 2 packets, the largest length field
  4066, and joined them into 113 lines. Alone it is 3947 bytes in one packet.
- A wrong key and a key with only `read` both gave `Authentication failed!` and exit 1, audited as `wrong key` and
  `key lacks the commands scope`.
- `stop` answered `stop is on the deny list and did not run.`, `/api/health` still answered 200 after it, and
  `plugin reload Ryvrook:RestAPI` was refused the same way. Neither reached the engine log.
- `stop` from the console ended the server with exit code 0, no exception in the log.
