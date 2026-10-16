package dev.ryvrook.restapi.events;

import dev.ryvrook.restapi.text.Messages;

/**
 * The sentences of the events module, editable in messages.json. The Discord lines are what a webhook with
 * format discord posts. The error sentences go into the message field of an API error, the code stays fixed.
 */
public final class EventsText {
    private EventsText() {
    }

    public static final String DISCORD_JOIN = Messages.define("events.discord.join", "{player} joined the server.");
    public static final String DISCORD_READY = Messages.define("events.discord.ready", "{player} is in {world}.");
    public static final String DISCORD_LEAVE = Messages.define("events.discord.leave", "{player} left the server.");
    public static final String DISCORD_CHAT = Messages.define("events.discord.chat", "<{player}> {text}");
    public static final String DISCORD_DEATH = Messages.define("events.discord.death", "{player} died in {world}, cause {cause}.");
    public static final String DISCORD_KILLED = Messages.define("events.discord.killed", "{player} was killed by {killer} in {world}.");
    public static final String DISCORD_COMMAND = Messages.define("events.discord.command", "{by} ran /{command}");
    public static final String DISCORD_WORLD_ADDED = Messages.define("events.discord.world.added", "The world {world} was added.");
    public static final String DISCORD_WORLD_REMOVED = Messages.define("events.discord.world.removed", "The world {world} was removed.");
    public static final String DISCORD_PLUGIN = Messages.define("events.discord.plugin", "{plugin} went from {from} to {to}.");
    public static final String DISCORD_BOOT = Messages.define("events.discord.boot", "The server finished booting.");
    public static final String DISCORD_SHUTDOWN = Messages.define("events.discord.shutdown", "The server is shutting down.");
    public static final String DISCORD_TEST = Messages.define("events.discord.test", "Test delivery from RestAPI for webhook {id}.");
    public static final String DISCORD_OTHER = Messages.define("events.discord.other", "{type} event.");

    public static final String STREAM_OVERFLOW = Messages.define("events.stream.overflow",
            "The client read too slowly and the stream was closed. Reconnect with Last-Event-ID to resume.");

    public static final String ERR_TYPES = Messages.define("events.error.types",
            "Unknown event type {types}. Known types are {known}.");
    public static final String ERR_LIMIT = Messages.define("events.error.limit", "limit must be a whole number from 1 to {max}.");
    public static final String ERR_SINCE = Messages.define("events.error.since", "since must be an event id, a whole number.");
    public static final String ERR_URL = Messages.define("events.error.url", "url must be an absolute http or https address with a host.");
    public static final String ERR_PRIVATE = Messages.define("events.error.private",
            "{host} resolves to a loopback, LAN or link local address. Set webhooks.allowPrivateTargets to true to allow it.");
    public static final String ERR_RESOLVE = Messages.define("events.error.resolve", "{host} does not resolve to an address.");
    public static final String ERR_FORMAT = Messages.define("events.error.format", "format must be json or discord.");
    public static final String ERR_SECRET = Messages.define("events.error.secret", "secret must be text of 16 to 256 characters, or null for no signature.");
    public static final String ERR_TOO_MANY = Messages.define("events.error.tooMany",
            "There are already {max} webhooks, the most webhooks.max allows.");
    public static final String ERR_HOOK_UNKNOWN = Messages.define("events.error.hookUnknown", "No webhook has the id {id}.");
    public static final String ERR_STORE = Messages.define("events.error.store", "webhooks.json could not be written, the server log has the cause.");
}
