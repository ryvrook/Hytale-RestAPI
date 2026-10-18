# RestAPI settings

Every key of `mods/Ryvrook_RestAPI/RestAPI.json` with its default, its range and whether `/restapi reload`
applies it, one file per section in `docs/settings/`. Keys are dotted in these files and nested in
`RestAPI.json`, `http.port` is `{"http": {"port": 8125}}`. A missing key is added with its default at the next
start or reload. A file with a wrong value is refused whole and the values in use stay. At the first start a
refused file keeps the HTTP API down.

The files are written by SettingsDocTest from the code and the build fails when they disagree.

Two keys are also changed by a command. `/restapi dashboard on` and `off` set `dashboard.enabled`, and
`/restapi dashboard writes on` and `off` set `dashboard.writes`. Each writes that one key into `RestAPI.json`
through a temporary file and a move and leaves the rest of the file as it is, so the change lasts past a restart.
