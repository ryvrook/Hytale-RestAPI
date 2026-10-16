# docs settings

Keys under `docs` in `mods/Ryvrook_RestAPI/RestAPI.json`. Written by SettingsDocTest, do not edit by hand.

| Key | Default | Applies | Meaning |
|---|---|---|---|
| `docs.public` | `true` | reload | Answer GET /openapi.json without a key. Off asks for a key with the read scope, and the docs page asks for that key before it loads the document. |
| `docs.page` | `true` | reload | Serve the docs page at /docs. Off leaves /openapi.json for tools and removes the page and its two files. |
| `docs.tryIt` | `true` | reload | Show a send button under each route on the docs page. The key you paste stays in the page's memory and is gone when the tab closes. |
| `docs.title` | `"RestAPI"` | reload | Title of the OpenAPI document and heading of the docs page, such as the server's name. |
| `docs.serverUrl` | `""` | reload | URL the document lists under servers, such as https://play.example.com/restapi when a reverse proxy serves the API there. Empty uses http.basePath. |
