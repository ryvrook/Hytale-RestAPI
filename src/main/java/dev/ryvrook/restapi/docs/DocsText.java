package dev.ryvrook.restapi.docs;

import dev.ryvrook.restapi.text.Messages;

/**
 * The sentences of the OpenAPI document and the docs routes. Admins reword them in messages.json like every other
 * sentence. Error codes stay fixed for scripts.
 */
public final class DocsText {
    private DocsText() {
    }

    public static final String INFO = Messages.define("docs.info",
            "The HTTP API of this Hytale server. Send a key in Authorization: Bearer <key>. Each route names the scope "
                    + "its key needs in x-scope. Routes of suite mods are listed only while that mod is installed and bound.");
    public static final String BEARER = Messages.define("docs.scheme.bearer",
            "A RestAPI key, made on the first start or with /restapi keys create.");
    public static final String X_API_KEY = Messages.define("docs.scheme.xApiKey",
            "The same key in an X-API-Key header, accepted while keys.acceptXApiKey is on.");
    public static final String PATH_PARAM = Messages.define("docs.param.path", "The {name} from the path.");
    public static final String PARAM_PLAYER = Messages.define("docs.param.player", "A player name or UUID.");
    public static final String PARAM_WORLD = Messages.define("docs.param.world", "A world name.");
    public static final String RESPONSE_OK = Messages.define("docs.response.ok", "The answer.");
    public static final String RESPONSE_NO_CONTENT = Messages.define("docs.response.noContent", "Done, nothing to return.");
    public static final String RESPONSE_401 = Messages.define("docs.response.401", "No key, or a key that is not valid.");
    public static final String RESPONSE_403 = Messages.define("docs.response.403",
            "The key lacks the scope, or its allowFrom does not hold the client address.");
    public static final String RESPONSE_429 = Messages.define("docs.response.429",
            "Over the key's requests per minute. Retry-After says when to try again.");
    public static final String RESPONSE_500 = Messages.define("docs.response.500",
            "The request failed on the server. The server log has the cause, the answer never does.");
    public static final String RESPONSE_ERRORS = Messages.define("docs.response.errors",
            "Error codes this route answers besides the common ones, {codes}.");
    public static final String OPEN = Messages.define("docs.open", "Answers without a key as well.");
    public static final String ACCESS_WRITE = Messages.define("docs.access.write", "Changes state. Every call is written to the audit log.");
    public static final String ACCESS_DANGEROUS = Messages.define("docs.access.dangerous",
            "Hard to undo. Every call is written to the audit log.");
    public static final String TAG_CORE = Messages.define("docs.tag.core", "Health and the server overview.");
    public static final String TAG_PLAYERS = Messages.define("docs.tag.players", "Players, moderation, bans, the whitelist and groups.");
    public static final String TAG_CONSOLE = Messages.define("docs.tag.console", "Console commands with their output and the server log.");
    public static final String TAG_CONTROL = Messages.define("docs.tag.control", "Plugins, worlds, saves, backups and stopping the server.");
    public static final String TAG_EVENTS = Messages.define("docs.tag.events", "The event stream, webhooks and Prometheus metrics.");
    public static final String TAG_DOCS = Messages.define("docs.tag.docs", "This document and the docs page.");
    public static final String TAG_SUITE = Messages.define("docs.tag.suite", "{adds}, while {plugin} is bound.");

    public static final String ROUTE_SPEC = Messages.define("docs.route.spec",
            "The OpenAPI 3.1 document of every route mounted right now.");
    public static final String ROUTE_PAGE = Messages.define("docs.route.page",
            "A page that renders the document, with no outside scripts.");
    public static final String ROUTE_ASSET = Messages.define("docs.route.asset", "The docs page's script and style sheet.");
    public static final String NO_FILE = Messages.define("docs.error.noFile", "The docs page has no file named {file}.");
}
