# README rows for the events module

The lead folds these into README.md at release. Sections in the order they would sit, after "Endpoints".

## Events, webhooks and metrics

RestAPI turns what happens on the server into events and keeps the last `events.ring` of them in memory, 1000
by default. These are the types.

| Type | When | Fields besides id, type and time |
|---|---|---|
| `join` | A player connects | `player`, `uuid`, `world` |
| `ready` | A player is in a world, at the first join and after every world change | `player`, `uuid`, `world` |
| `leave` | A player disconnects | `player`, `uuid`, `reason` |
| `chat` | A chat message that no chat plugin cancelled | `player`, `uuid`, `length`, `text` unless `events.chatText` is off |
| `death` | A player dies, and every creature with `events.npcDeaths` | `player` or `npc`, `world`, `cause`, `position`, `killer` or `killerNpc` when there is one |
| `command` | Any command the engine runs, from a player, the console, RCON or the API | `by`, `command` |
| `world` | A world was added or removed, `action` says which | `world` |
| `plugin` | A plugin changed state, seen by a poll every `events.pluginPollSeconds` | `plugin`, `version`, `from`, `to` |
| `server` | The server finished booting or started to shut down, `action` `booted` or `stopping` | |

Commands have no engine event. The engine logs `<sender> executed command: <line>` for every command it runs,
and RestAPI reads that line, so a command typed in the console shows as `by` `Console`, one run through the
API as `RestAPI:<key name>`. Plugins have no enable event either, which is why their changes come from a poll.

`GET /api/events` is a Server Sent Events stream. The SSE event name is the type, the data is the event as
JSON and the id is the event id. Ids keep rising across restarts, so a client that reconnects with
`Last-Event-ID` gets what it missed while it is still in the ring. When some of it already fell out of the
ring, or the id is from before a restart, the stream sends a `gap` event first. A comment line goes out after
`events.keepAliveSeconds` of quiet so proxies keep the connection open. A client that reads too slowly is
sent an `overflow` event and cut off, and resumes with its last id. `http.maxStreams` caps the open streams.

```
curl -N -H "Authorization: Bearer $KEY" "http://127.0.0.1:8125/api/events?types=join,leave,death"
curl -H "Authorization: Bearer $KEY" "http://127.0.0.1:8125/api/events/recent?types=chat&limit=20"
```

### Webhooks

A webhook is a URL RestAPI POSTs events to. Make one with a key that holds `events`.

```
curl -X POST -H "Authorization: Bearer $KEY" -H "Content-Type: application/json" \
  -d '{"url": "https://example.com/hytale", "types": ["join", "leave", "death"]}' \
  http://127.0.0.1:8125/api/webhooks
```

The answer holds a `secret` the one time, 64 hex characters. Every delivery then carries
`X-RestAPI-Signature: sha256=<hex>`, the HMAC-SHA256 of the raw body with that secret, plus
`X-RestAPI-Event` (the type, and the action after a dot such as `world.added`) and `X-RestAPI-Delivery`, a
UUID per delivery. Check the signature on the raw body before you parse it. Send your own `"secret"` of 16
to 256 characters, or `"secret": null` for unsigned deliveries.

For a Discord channel webhook add `"format": "discord"`. RestAPI then posts a plain `content` line from
`messages.json`, such as "Steve joined the server.", with mentions switched off so a chat message holding
`@everyone` pings nobody. Discord ignores the signature.

Each webhook gets its own queue of at most `webhooks.queueSize` deliveries and sends them one at a time, in
order. A network error, a 429 or a 5xx is tried again `webhooks.retries` times, after `webhooks.backoffSeconds`
and twice that each time after, 2, 4 and 8 seconds by default. A 3xx or a 4xx fails at once. Redirects are
never followed. When a target stays down long enough to fill the queue the oldest delivery is dropped.
Queues live in memory, so a server stop or `/restapi reload` drops what is still waiting after two seconds.

A webhook made over the API may not point at a loopback, LAN, link local or carrier NAT address, checked when
it is made and again before every delivery, so a name that later resolves to your LAN is refused too. Set
`webhooks.allowPrivateTargets` to true for a bot on the same machine, or write the hook into
`webhooks.json` by hand, which skips the check. `GET /api/webhooks` shows each hook with what its last
delivery answered and never the secret. Path parts of 20 characters or more and the query are shown as
`***`, since Discord puts its token in the path.

### Metrics

`GET /metrics` answers in Prometheus text format. It needs a key with `metrics`, or no key at all with
`metrics.public` on, for a scraper on the same machine. Names follow the Apex exporter where they overlap,
so its dashboards carry over.

```
scrape_configs:
  - job_name: hytale
    metrics_path: /metrics
    authorization:
      credentials: rapi_xxxxxxxx_...
    static_configs:
      - targets: ["127.0.0.1:8125"]
```

| Metric | Labels | What it is |
|---|---|---|
| `hytale_players_online`, `hytale_players_max` | | Players connected and the MaxPlayers of config.json |
| `hytale_world_tps` | `world` | Ticks per second over 10 seconds, the `/world perf` formula |
| `hytale_world_tick_ms` | `world`, `stat` `avg` or `max` | Tick length over 10 seconds |
| `hytale_world_chunks_loaded`, `hytale_world_entities`, `hytale_world_players` | `world` | From the world's last sample |
| `hytale_world_stalled` | `world` | 1 when the world thread missed three samples |
| `hytale_server_uptime_seconds` | | Since the boot |
| `hytale_plugins` | `state` | Plugins by state, ENABLED, DISABLED, FAILED and the rest |
| `jvm_memory_heap_used_bytes`, `_committed_bytes`, `_max_bytes`, `jvm_threads` | | The JVM |
| `jvm_gc_collections_total`, `jvm_gc_collection_seconds_total` | `gc` | Per collector |
| `process_cpu_load`, `system_load_average_1m` | | CPU of the server process from 0 to 1, and the system load |
| `restapi_requests_total` | `method`, `route`, `status` | Requests by route pattern, so the label set stays as small as the route table |
| `restapi_auth_failures_total` | | Requests with a wrong key, or none where one is needed |
| `restapi_webhook_deliveries_total` | `result` `ok`, `failed`, `retried`, `dropped` | Webhook deliveries |
| `restapi_webhooks`, `restapi_event_streams`, `restapi_routes` | | Webhooks in the file, open event streams, mounted routes |

World figures come from the samples RestAPI already takes for `/api/health`, so a scrape never waits on a
world thread.

## Endpoint rows

| Method and path | Scope | What it answers |
|---|---|---|
| `GET /api/events` | `events` | Server Sent Events, `?types=` to filter, resumes with `Last-Event-ID` or `?since=` |
| `GET /api/events/recent` | `events` | The newest events as JSON, `?types`, `?limit` (100 by default) and `?since` |
| `GET /api/webhooks` | `events` | Every webhook with its types and last delivery |
| `POST /api/webhooks` | `events` | `{url, types?, secret?, format?}`, answers 201 with the hook and its secret once |
| `DELETE /api/webhooks/{id}` | `events` | Removes the hook, 204 |
| `POST /api/webhooks/{id}/test` | `events` | Sends one test event now and answers with the target's status |
| `GET /metrics` | `metrics`, or none with `metrics.public` | Prometheus text |

Error codes: `unknown_type`, `bad_limit`, `bad_since`, `invalid_url`, `private_target`, `unresolved`,
`invalid_format`, `invalid_secret`, `too_many_webhooks` (409), `webhook_not_found` (404), `store_failed` (500).

## Settings rows

| Key | Default | What it does |
|---|---|---|
| `events.enabled` | `true` | Serve the event stream, recent events and webhooks. Off unmounts them |
| `events.ring` | `1000` | Events kept in memory for resumes and `/api/events/recent` |
| `events.chat` | `true` | Record chat messages |
| `events.chatText` | `true` | Put the message text into chat events |
| `events.deaths` | `true` | Record player deaths |
| `events.npcDeaths` | `false` | Also record every creature death |
| `events.commands` | `true` | Record every command the engine runs |
| `events.pluginPollSeconds` | `5` | Seconds between looks at the plugin list |
| `events.keepAliveSeconds` | `15` | Quiet seconds before a stream gets a comment line |
| `webhooks.enabled` | `true` | Deliver events to webhooks |
| `webhooks.timeoutSeconds` | `5` | Seconds a target gets to answer |
| `webhooks.retries` | `3` | Tries after the first for network errors, 429 and 5xx |
| `webhooks.backoffSeconds` | `2` | Wait before the first retry, doubled each time after |
| `webhooks.queueSize` | `1000` | Deliveries waiting per webhook before the oldest is dropped |
| `webhooks.max` | `20` | Webhooks the API may create |
| `webhooks.allowPrivateTargets` | `false` | Let API made webhooks reach loopback and LAN addresses |
| `metrics.enabled` | `true` | Serve `/metrics` |
| `metrics.public` | `false` | Answer `/metrics` without a key |

## Data folder row

| File | What it holds |
|---|---|
| `webhooks.json` | Webhook URLs, event types, formats and signing secrets in clear, written with owner only permissions where the system has them |
