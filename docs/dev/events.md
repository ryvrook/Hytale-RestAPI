# Events module, maintainer notes

Package `dev.ryvrook.restapi.events`, group `events`. The lead folds this into docs/DEVELOPMENT.md at release.

## Classes

| Class | What it does |
|---|---|
| `EventsModule` | Registers the engine hooks in `setup`, mounts the routes, starts the webhook workers, the log subscription and the plugin poll in `start`, stops them in `stop` |
| `EventsSettings`, `EventsText` | Sections `events`, `webhooks`, `metrics` and the `events.*` ids of messages.json |
| `EventRing` | The last `events.ring` events, rising ids, resume with `since`, one bounded queue per open stream |
| `EngineEvents` | Bus listeners, the death system, the command log subscription and the plugin poll |
| `EventRoutes` | `GET /api/events` (SSE) and `GET /api/events/recent` |
| `Webhooks`, `WebhookRoutes` | webhooks.json, one worker per hook, HMAC, retries, the private target check |
| `Metrics` | Prometheus text writer with no engine in it |
| `MetricsRoute` | `GET /metrics` |
| `RequestCounters` | `restapi_requests_total` and `restapi_auth_failures_total`, fed by the gate once wired |

## Engine facts

Read with javap on Server-0.7.1.jar and seen on the headless smoke on 2026-10-16 (client's bundled 0.7.2).

- `PlayerConnectEvent` is `IAsyncEvent<Void>`, taken with `registerAsyncGlobal`. `getPlayerRef()` and `getWorld()`.
- `PlayerReadyEvent` extends `PlayerEvent<String>` with `getPlayerRef()` as an entity `Ref`, world thread. The
  name and uuid come from `store.getComponent(ref, PlayerRef.getComponentType())`. It fires on every world entry,
  so it is its own type `ready` next to `join`.
- `PlayerDisconnectEvent` extends `PlayerRefEvent` with `getPlayerRef()` and `getDisconnectReason()`.
- `PlayerChatEvent` is async and cancellable, taken at `EventPriority.LAST`, cancelled ones skipped.
- `AddWorldEvent` (cancellable) and `RemoveWorldEvent` (cancellable, `getRemovalReason()`) both extend
  `WorldEvent` with `getWorld()`. Taken at LAST. A cancelled removal is skipped unless the reason is
  EXCEPTIONAL, the TrackerAPI rule. Seen on the smoke: `world add smokeworld` and `world remove smokeworld`
  each gave one event, the default world gave `added` at boot and `removed` at stop.
- `BootEvent` and `ShutdownEvent` are `IEvent<Void>`, taken with `register(Class, Consumer)`. Seen: `booted`
  about a second after the default world was added, `stopping` right after the `stop` command, before the
  plugins shut down, so a webhook queue gets the two second drain in `stop`.
- Deaths: `DeathSystems.OnDeathSystem` with `Archetype.empty()`, registered through
  `getEntityStoreRegistry().registerSystem` in setup. World thread. Cause from `DeathComponent.getDeathCause()`,
  else the `DamageCause` asset map by index. The killer from `Damage.EntitySource.getRef()` when it is a valid
  ref of the same store and not the victim. Registration was accepted on 0.7.2. A death could not be caused
  headless, no player can join, so the death path is checked by reading only.
- Commands: the `CommandManager` logs `<sender> executed command: <line>` at INFO for every run. A list passed
  to `HytaleLoggerBackend.subscribe` gets `add(LogRecord)` for every root record. `CommandTap.add` keeps
  nothing and returns true. `commandOf` reads the record both as the format `%s executed command: %s` with two
  parameters and as formatted text. Seen: `Console executed command: say hello from the smoke` became
  `{"by":"Console","command":"say hello from the smoke"}`. Unsubscribed in `stop`.
- Plugins: no enable event in 0.7.1. `PluginManager.get().getPlugins()` with `getIdentifier()`, `getState()`
  and `getManifest().getVersion()` every `events.pluginPollSeconds`. An unloaded plugin leaves the list, so its
  `to` is `NONE`. Seen: `plugin unload Ryvrook:Lullaby` gave ENABLED to NONE within two seconds, `plugin load`
  NONE to ENABLED. The first poll is the baseline. RestAPI's own START to ENABLED shows because the first poll
  runs inside `start`. 107 core plugins are ENABLED on a bare 0.7.2 server.

## Ring and ids

Ids start at the module's construction time in milliseconds times 1000 and count up, about 1.79e15 in 2026,
under 2^53 so JavaScript keeps them exact. An id below the base is from an earlier run, an id at or above the
next one is unknown. Both resume as a gap with the whole ring. `subscribe` takes the replay and registers the
stream queue under one lock, so nothing is lost or doubled between the two. A stream queue holds 1024 events,
a reader that falls that far behind gets an `overflow` event and is closed. The ring and open streams survive
`/restapi reload`. At a server stop `HttpHost.stop` interrupts the stream threads.

## Webhooks

One virtual thread per hook named `RestAPI-webhook-<id>`, not one per delivery, so a target sees events in
order. The JDK `HttpClient` is pinned to HTTP/1.1. With the default version it sent `Upgrade: h2c` on plain
http, and against `nc -l` the body never arrived. Redirects are never followed. A hook made over the API is
marked `viaApi` in the file and checked for private targets at creation and before every delivery, which
catches a name that resolves to the LAN later. The check and the client resolve separately, so a name that
changes between the two within milliseconds is not caught. Hand written hooks skip the check.

## Verification

Tests in `src/test/java/dev/ryvrook/restapi/events/`: the ring, the stream through a fake sink, recent with its
checks, webhooks through a fake transport (retries and their waits, no retry on 404, queue overflow drops the
oldest, private targets at creation and delivery), the JDK client against a local `HttpServer` (signature,
no redirect), the HMAC against RFC 4231 case 2, the Prometheus format with a line validator, the command line
parser and the plugin diff. promtool is not installed here, the validator checks the line shapes promtool
checks on one exposition.

Headless smoke with a copy of the smoke script, HTTP on 8134, game on 5534, with Lullaby 1.0.1.

- `/metrics` without a key 401, with one 200 `text/plain; version=0.0.4`.
- A webhook to `http://127.0.0.1:9134` answered 400 `private_target`, accepted after
  `webhooks.allowPrivateTargets` true and `/restapi reload`.
- `nc -d -l 127.0.0.1 9134` received a POST with `X-RestAPI-Signature: sha256=1187...13fb5`, and the HMAC of
  the received body with the secret from the create answer matched.
- A Discord format hook got `{"content":"Console ran /say hello from the smoke","allowed_mentions":{"parse":[]}}`
  and eight more, the test route answered `{"ok":true,"status":204}`.
- `curl -N /api/events` stayed open across `/restapi reload` and printed command, world, plugin and server
  events. `Last-Event-ID` resumed after the given id. `Last-Event-ID: 5` gave a `gap` event and the ring.

## Lead merges

The gate does not report requests yet. Until it does, `/metrics` leaves out `restapi_requests_total` and
`restapi_auth_failures_total`. The report to the lead holds the exact edit.
