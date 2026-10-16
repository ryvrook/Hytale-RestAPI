# keys settings

Keys under `keys` in `mods/Ryvrook_RestAPI/RestAPI.json`. Written by SettingsDocTest, do not edit by hand.

| Key | Default | Applies | Meaning |
|---|---|---|---|
| `keys.acceptXApiKey` | `true` | reload | Accept the key in an X-API-Key header as well as in Authorization Bearer, for tools written against HyRESTAPI. |
| `keys.createFirstKey` | `true` | reload | Create a key on the first start when keys.json holds none, and print it to the server log once. |
| `keys.firstKeyName` | `"admin"` | reload | Name of the key made on the first start. |
| `keys.firstKeyScopes` | `["all"]` | reload | Scopes of the key made on the first start. all means every scope, the command deny list still applies. |
