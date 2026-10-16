package dev.ryvrook.restapi.command;

import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.command.system.CommandContext;
import com.hypixel.hytale.server.core.command.system.arguments.system.OptionalArg;
import com.hypixel.hytale.server.core.command.system.arguments.system.RequiredArg;
import com.hypixel.hytale.server.core.command.system.arguments.types.ArgTypes;
import com.hypixel.hytale.server.core.command.system.basecommands.AbstractCommandCollection;
import com.hypixel.hytale.server.core.command.system.basecommands.CommandBase;
import dev.ryvrook.restapi.RestApiPlugin;
import dev.ryvrook.restapi.text.Messages;

import java.util.List;
import java.util.concurrent.CompletableFuture;

import static dev.ryvrook.restapi.text.CommandText.*;

/**
 * {@code /restapi status|reload|keys list|keys create <name> <scopes>|keys revoke <name>|audit tail [--lines n]|rcon status|webhooks list}.
 * Every node needs restapi.admin, the key nodes restapi.admin.keys as well, and all of them work from the console.
 * Anything that touches a file runs on RestAPI's scheduler and the reply comes back on the sender's world thread.
 */
public final class RestApiCommand extends AbstractCommandCollection {

    public RestApiCommand(RestApiPlugin plugin) {
        super("restapi", Messages.get(ROOT));
        requirePermission(Perms.ADMIN);
        addSubCommand(new Leaf("status", STATUS, Perms.ADMIN, ctx -> plugin.statusLines()));
        addSubCommand(new Leaf("reload", RELOAD, Perms.ADMIN, ctx -> plugin.reload(who(ctx))));
        addSubCommand(new Keys(plugin));
        addSubCommand(new Audit(plugin));
        addSubCommand(new Group("rcon", RCON, new Leaf("status", RCON_STATUS, Perms.ADMIN,
                ctx -> plugin.moduleLines("rcon", "status"))));
        addSubCommand(new Group("webhooks", WEBHOOKS, new Leaf("list", WEBHOOKS_LIST, Perms.ADMIN,
                ctx -> plugin.moduleLines("events", "webhooks"))));
    }

    /** A sub command group holding one leaf, such as rcon status. */
    static final class Group extends AbstractCommandCollection {
        Group(String name, String description, CommandBase leaf) {
            super(name, Messages.get(description));
            requirePermission(Perms.ADMIN);
            addSubCommand(leaf);
        }
    }

    /** Who ran a command, for the audit log and the createdBy of a key. */
    static String who(CommandContext ctx) {
        try {
            return ctx.isPlayer() ? ctx.sender().getUsername() : "console";
        } catch (RuntimeException e) {
            return "unknown";
        }
    }

    interface Work {
        CompletableFuture<List<Message>> run(CommandContext ctx);
    }

    /** A sub command without arguments whose work returns the reply lines. */
    static final class Leaf extends CommandBase {
        private final Work work;

        Leaf(String name, String description, String permission, Work work) {
            super(name, Messages.get(description));
            requirePermission(permission);
            this.work = work;
        }

        @Override
        protected void executeSync(CommandContext ctx) {
            work.run(ctx).whenComplete((lines, err) -> Replies.send(ctx,
                    err == null ? lines : List.of(Replies.line(Messages.get(FAILED), Replies.ERROR))));
        }
    }

    static final class Keys extends AbstractCommandCollection {
        Keys(RestApiPlugin plugin) {
            super("keys", Messages.get(KEYS));
            requirePermission(Perms.KEYS);
            addSubCommand(new Leaf("list", KEYS_LIST, Perms.KEYS, ctx -> plugin.keyLines()));
            addSubCommand(new Create(plugin));
            addSubCommand(new Revoke(plugin));
        }
    }

    static final class Create extends CommandBase {
        private final RestApiPlugin plugin;
        private final RequiredArg<String> name;
        private final RequiredArg<String> scopes;

        Create(RestApiPlugin plugin) {
            super("create", Messages.get(KEYS_CREATE));
            requirePermission(Perms.KEYS);
            this.plugin = plugin;
            this.name = withRequiredArg("name", Messages.get(ARG_NAME), ArgTypes.STRING);
            // The rest of the line, since the engine reads a comma inside a plain string argument as a list.
            this.scopes = withRequiredArg("scopes", Messages.get(ARG_SCOPES), ArgTypes.GREEDY_STRING);
        }

        @Override
        protected void executeSync(CommandContext ctx) {
            plugin.createKey(ctx.get(name).trim(), ctx.get(scopes).trim(), who(ctx))
                    .whenComplete((lines, err) -> Replies.send(ctx,
                            err == null ? lines : List.of(Replies.line(Messages.get(FAILED), Replies.ERROR))));
        }
    }

    static final class Revoke extends CommandBase {
        private final RestApiPlugin plugin;
        private final RequiredArg<String> name;

        Revoke(RestApiPlugin plugin) {
            super("revoke", Messages.get(KEYS_REVOKE));
            requirePermission(Perms.KEYS);
            this.plugin = plugin;
            this.name = withRequiredArg("name", Messages.get(ARG_NAME), ArgTypes.STRING);
        }

        @Override
        protected void executeSync(CommandContext ctx) {
            plugin.revokeKey(ctx.get(name).trim(), who(ctx))
                    .whenComplete((lines, err) -> Replies.send(ctx,
                            err == null ? lines : List.of(Replies.line(Messages.get(FAILED), Replies.ERROR))));
        }
    }

    static final class Audit extends AbstractCommandCollection {
        Audit(RestApiPlugin plugin) {
            super("audit", Messages.get(AUDIT));
            requirePermission(Perms.ADMIN);
            addSubCommand(new Tail(plugin));
        }
    }

    static final class Tail extends CommandBase {
        private final RestApiPlugin plugin;
        private final OptionalArg<Integer> lines;

        Tail(RestApiPlugin plugin) {
            super("tail", Messages.get(AUDIT_TAIL));
            requirePermission(Perms.ADMIN);
            this.plugin = plugin;
            this.lines = withOptionalArg("lines", Messages.get(ARG_LINES), ArgTypes.INTEGER);
        }

        @Override
        protected void executeSync(CommandContext ctx) {
            int n = ctx.provided(lines) ? Math.max(1, Math.min(50, ctx.get(lines))) : 10;
            plugin.auditLines(n).whenComplete((out, err) -> Replies.send(ctx,
                    err == null ? out : List.of(Replies.line(Messages.get(FAILED), Replies.ERROR))));
        }
    }
}
