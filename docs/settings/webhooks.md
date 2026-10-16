# webhooks settings

Keys under `webhooks` in `mods/Ryvrook_RestAPI/RestAPI.json`. Written by SettingsDocTest, do not edit by hand.

| Key | Default | Applies | Meaning |
|---|---|---|---|
| `webhooks.enabled` | `true` | reload | Deliver events to the URLs in webhooks.json. Off keeps the file and the routes but sends nothing. |
| `webhooks.timeoutSeconds` | `5` | reload | Seconds a webhook target gets to connect and answer before the delivery counts as failed. Range 1 to 60. |
| `webhooks.retries` | `3` | reload | Tries after the first one for a delivery that failed with a network error, a 429 or a 5xx answer. Range 0 to 10. |
| `webhooks.backoffSeconds` | `2` | reload | Seconds before the first retry. Each further retry waits twice as long as the one before. Range 1 to 300. |
| `webhooks.queueSize` | `1000` | reload | Deliveries waiting per webhook. When a target is down long enough to fill it, the oldest waiting delivery is dropped. Range 10 to 100000. |
| `webhooks.max` | `20` | reload | Webhooks POST /api/webhooks may create in total. Range 1 to 500. |
| `webhooks.allowPrivateTargets` | `false` | reload | Let webhooks made over the API point at loopback, LAN and link local addresses. Hooks written into webhooks.json by hand may always do so. |
