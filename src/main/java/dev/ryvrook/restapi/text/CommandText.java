package dev.ryvrook.restapi.text;

/** The sentences of /restapi. */
public final class CommandText {
    private CommandText() {
    }

    public static final String ROOT = Messages.define("command.root", "RestAPI tools for server staff.");
    public static final String STATUS = Messages.define("command.status", "Show the HTTP API, keys, audit log and suite mods.");
    public static final String RELOAD = Messages.define("command.reload", "Read RestAPI.json, messages.json and keys.json again.");
    public static final String KEYS = Messages.define("command.keys", "List, make and revoke API keys.");
    public static final String KEYS_LIST = Messages.define("command.keys.list", "List the API keys.");
    public static final String KEYS_CREATE = Messages.define("command.keys.create", "Make an API key and show it once.");
    public static final String KEYS_REVOKE = Messages.define("command.keys.revoke", "Revoke an API key.");
    public static final String AUDIT = Messages.define("command.audit", "Read the audit log.");
    public static final String AUDIT_TAIL = Messages.define("command.audit.tail", "Show the newest audit log lines.");
    public static final String ARG_NAME = Messages.define("command.arg.name", "Key name, lower case letters, digits, dash or underscore");
    public static final String ARG_SCOPES = Messages.define("command.arg.scopes", "Scopes separated by spaces or commas, such as read players, or all");
    public static final String ARG_LINES = Messages.define("command.arg.lines", "How many lines, 1 to 50");

    public static final String HTTP_ON = Messages.define("status.http.on",
            "HTTP API on {address}{base} with {routes} in {groups}.");
    public static final String HTTP_OFF = Messages.define("status.http.off", "The HTTP API is off because http.enabled is false.");
    public static final String HTTP_DOWN = Messages.define("status.http.down", "The HTTP API is down, {reason}.");
    public static final String HTTP_EXPOSED = Messages.define("status.http.exposed",
            "It listens beyond this machine. Keep a reverse proxy with TLS in front of it.");
    public static final String KEYS_COUNT = Messages.define("status.keys",
            "{keys} in keys.json, {limit} requests a minute per key unless a key sets its own.");
    public static final String AUDIT_STATE = Messages.define("status.audit", "The audit log is {state}, {days} days kept.");
    public static final String SIBLINGS_HEAD = Messages.define("status.siblings", "Suite mods, checked every {seconds} seconds");
    public static final String SIBLING_BOUND = Messages.define("status.sibling.bound",
            "{plugin} {version} is bound with {routes} under /api/{id}, scope {scope}.");
    public static final String SIBLING_UNBOUND = Messages.define("status.sibling.unbound", "{plugin} is not bound, {reason}.");

    public static final String RELOADED = Messages.define("reload.done", "Reloaded. {routes} in {groups}, {keys}.");
    public static final String RELOAD_PROBLEM = Messages.define("reload.problem", "{problem}.");
    public static final String RELOAD_REFUSED = Messages.define("reload.refused",
            "RestAPI.json has problems, nothing changed. Fix them and run /restapi reload again.");
    public static final String RELOAD_RESTART = Messages.define("reload.restart",
            "http.requestSeconds only changes at the next server start.");

    public static final String KEYS_NONE = Messages.define("keys.none",
            "No keys yet. Make one with /restapi keys create <name> <scopes>.");
    public static final String KEYS_LINE = Messages.define("keys.line", "{name} ({id}) with {scopes}, made {created} by {by}.");
    public static final String KEY_MADE = Messages.define("keys.made",
            "Made the key {name} with {scopes}. This is the only time it is shown, copy it now.");
    public static final String KEY_REVOKED = Messages.define("keys.revoked",
            "Revoked the key {name}. Requests that use it get 401 from now on.");
    public static final String KEY_UNKNOWN = Messages.define("keys.unknown", "No key is named {name}.");
    public static final String KEY_FAILED = Messages.define("keys.failed", "{reason}.");

    public static final String AUDIT_EMPTY = Messages.define("audit.empty", "The audit log is empty.");
    public static final String AUDIT_LINE = Messages.define("audit.line", "{time} {key} from {client} {action} answered {status} ({detail})");
    public static final String AUDIT_LINE_COMMAND = Messages.define("audit.line.command", "{time} {key} ran {action} ({detail})");
    public static final String FAILED = Messages.define("command.failed", "That did not work, the server log has the cause.");
}
