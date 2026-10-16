## Commands and the console

`POST /api/commands` runs a console command and answers with what the command sent back, as plain text lines. It
runs as a sender named `RestAPI:<key name>`, so the server's own `executed command` line in the log says which key
ran it. Every run gets a `runId` that the audit log also has. The audit log gets a `started` line before the
engine sees the command and a second line with the outcome, so a crash between the two still leaves a trace.

```
curl -s -X POST http://127.0.0.1:8125/api/commands \
  -H "Authorization: Bearer $KEY" -H "Idempotency-Key: $(uuidgen)" \
  -d '{"command": "say hello"}'
{"runId":"r8f3a01c2d4e5","outcome":"done","output":["[RestAPI:admin] hello"],"millis":312,"replayed":false}
```

Send an `Idempotency-Key` header with each command. When the answer gets lost and the script sends the same
request again, RestAPI hands back the stored answer with `"replayed": true` and runs nothing. A retry that comes
while the first run is still going waits for it. The same key on a different command answers 409. Results are kept
in memory for `commands.idempotencySeconds`, a restart forgets them.

`outcome` is `done`, `failed` (the engine reported an error or does not know the command), `timed_out` (HTTP 202,
the answer holds the lines that arrived before `timeoutMs` and the command may still be running) or `denied`
(HTTP 403). Some commands reply after they report that they finished, so RestAPI keeps listening for
`commands.graceMs` after the last line, at most `commands.graceMaxMs`. `say` writes its line to the server console
and not to the sender, RestAPI picks that up from the console because the engine runs the command on a thread
named after it.

The deny list is checked before the engine sees the line. It matches on the words after aliases are resolved
through the engine's command tree, so `/stop`, `STOP` and `shutdown` are the same command, and `pl r X` is
`plugin reload X`. These are refused whatever the settings say: `stop` and `shutdown`, any `plugin` command
that names `Ryvrook:RestAPI`, `restapi reload`, `restapi keys`, and `sudo` or `su` around any refused command.
Stopping the server over the API goes through `POST /api/server/stop` instead. Commands only answer from the
addresses in `commands.allowFrom`, loopback by default, on top of the key's own `allowFrom`. Behind a reverse
proxy that is the real client address, so remote command calls need it widened on purpose.

Macros set up with the engine's command macro feature are not looked into. A macro that wraps `stop` runs. Put
the macro's name on `commands.deny` when that matters.

The server log is kept in memory, the newest `console.ringLines` lines, with colour codes stripped and RestAPI key
tokens cut down to `rapi_<id>_...`. `GET /api/console` answers the newest lines, `GET /api/console/stream` sends
them as Server Sent Events and then every new line as it is logged. A `/restapi reload` ends open streams, a client
that sends `Last-Event-ID` gets every line it missed that is still kept.

```
curl -N -H "Authorization: Bearer $KEY" "http://127.0.0.1:8125/api/console/stream?level=INFO&lines=50"
```

| Method, path | Scope | What it does |
|---|---|---|
| `POST /api/commands` | `commands` | Runs `{command, timeoutMs?}` and answers `{runId, outcome, output, millis, replayed}` |
| `POST /api/commands/batch` | `commands` | Runs `{commands: [...], stopOnError?, timeoutMs?}` one after the other, one result each. An `Idempotency-Key` covers the batch, each line is stored as key `#` index |
| `GET /api/commands/runs/{runId}` | `commands` | A kept run of the same key, with `engineMillis`, `lateLines` and `lastLateMillis` |
| `GET /api/commands/deny` | `commands` | The fixed entries and `commands.deny` |
| `GET /api/commands/list` | `read` | Registered commands with aliases, description, permission and sub commands |
| `GET /api/console` | `console` | `?lines=200&level=INFO&since=<line id or ISO time>` |
| `GET /api/console/stream` | `console` | Server Sent Events, event `log` with the line id as event id, `?level=` and `?lines=` |

| Setting | Default | Meaning |
|---|---|---|
| `commands.enabled` | `true` | Serve the command routes and offer the command service to RCON |
| `commands.deny` | `["op", "deop", "perm", "whitelist clear", "plugin unload"]` | Refused commands on top of the fixed ones |
| `commands.allowFrom` | `["127.0.0.0/8", "::1"]` | Addresses that may run commands over HTTP or RCON, empty for any |
| `commands.permissions` | `["*"]` | Nodes the command sender holds, `a.b.*` covers a branch, `-a.b` takes one away |
| `commands.timeoutMs` | `5000` | Time a command gets before the answer goes out |
| `commands.maxTimeoutMs` | `60000` | Largest `timeoutMs` a request may ask for |
| `commands.graceMs` | `300` | Quiet time after the command finished before answering |
| `commands.graceMaxMs` | `2000` | Most time spent waiting for late replies |
| `commands.maxOutputLines` | `500` | Lines kept per command, the rest are counted |
| `commands.maxLineChars` | `2000` | Longest command line accepted |
| `commands.captureConsoleEcho` | `true` | Take console lines written by the command's own thread, such as the line of `say` |
| `commands.idempotencySeconds` | `600` | How long results stay under their `Idempotency-Key` |
| `commands.requireIdempotencyKey` | `false` | Refuse commands without the header |
| `commands.batchMax` | `20` | Most commands in one batch |
| `commands.runsKept` | `1000` | Finished runs kept for `GET /api/commands/runs/{runId}` |
| `console.enabled` | `true` | Keep the log in memory and serve it |
| `console.ringLines` | `2000` | Log lines kept |
| `console.minLevel` | `"INFO"` | Lowest level kept |
| `console.defaultLines` | `200` | Lines answered when the request names no count |
| `console.keepAliveSeconds` | `15` | Seconds between keep alive comments on a quiet stream |
