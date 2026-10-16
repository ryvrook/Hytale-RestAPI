## OpenAPI document and docs page

`GET /openapi.json` answers an OpenAPI 3.1 document written from the routes mounted at that moment. Every route
is one operation, tagged with its group, with its scope in `x-scope`, `read`, `write` or `dangerous` in
`x-access`, and `x-open: true` when it also answers without a key. Query parameters, example bodies and the
error codes a route can answer come from the module that owns it, and the schemas are read off those examples.
A suite group such as `suite.dominion` is in the document only while its mod is bound, so a client generator or
a Postman import never sees routes that answer 404. The document is kept in memory and written again when a
group is mounted or unmounted, a suite mod binds or leaves, or a setting it reads changes.

`GET /docs` is a page of about 16 KB that renders the document. One HTML file, one script and one style sheet,
all served from the jar, no CDN, no fonts from elsewhere and no Swagger UI bundle. The page sends
`Content-Security-Policy: default-src 'self'`, so it can load nothing and send nothing outside this server. Each
route opens to its parameters, examples and a Send button. Paste a key in the box at the top first. The key
lives in the page's memory only, it is never written to local storage or a cookie and it is gone when the tab
closes. Routes marked dangerous ask before they send. A stream route is read for 10 seconds and then closed.

The page loads `openapi.json` with a relative link, so it works behind a reverse proxy that serves RestAPI
under a sub path. With `docs.public` off the document needs a key with `read`, and the page asks for that key
before it loads.

| Method and path | Scope | What it answers |
|---|---|---|
| `GET /openapi.json` | none while `docs.public` is on, else `read` | The OpenAPI 3.1 document of the routes mounted now |
| `GET /docs` | none | The docs page. Gone while `docs.page` is off |
| `GET /docs/{file}` | none | `docs.js` and `docs.css` of the page, anything else is 404 `file_not_found` |

| Key | Default | What it does |
|---|---|---|
| `docs.public` | `true` | Answer `/openapi.json` without a key. Off asks for a `read` key |
| `docs.page` | `true` | Serve the page at `/docs` |
| `docs.tryIt` | `true` | Show the Send button under each route on the page |
| `docs.title` | `"RestAPI"` | Title of the document and heading of the page, such as the server's name |
| `docs.serverUrl` | `""` | The URL under `servers` in the document, for a proxy with its own host or path. Empty uses `http.basePath` |
