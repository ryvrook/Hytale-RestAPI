# RestAPI settings

Every key of `mods/Ryvrook_RestAPI/RestAPI.json` with its default, its range and whether `/restapi reload`
applies it, one file per section in `docs/settings/`. Keys are dotted in these files and nested in
`RestAPI.json`, `http.port` is `{"http": {"port": 8125}}`. A missing key is added with its default at the next
start or reload. A file with a wrong value is refused whole and the values in use stay. At the first start a
refused file keeps the HTTP API down.

The files are written by SettingsDocTest from the code and the build fails when they disagree.
