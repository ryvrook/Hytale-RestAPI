# audit settings

Keys under `audit` in `mods/Ryvrook_RestAPI/RestAPI.json`. Written by SettingsDocTest, do not edit by hand.

| Key | Default | Applies | Meaning |
|---|---|---|---|
| `audit.enabled` | `true` | reload | Write every change made through the API, RCON or /restapi keys to audit/audit-<date>.jsonl. |
| `audit.logReads` | `false` | reload | Also write read requests to the audit log. Busy dashboards make this file grow fast. |
| `audit.logAuthFailures` | `true` | reload | Write requests that carry a wrong key to the audit log, with the address. Requests with no key at all are not written. |
| `audit.keepDays` | `30` | reload | Days of audit files kept. Older files are deleted at start and at midnight. Range 1 to 3650. |
