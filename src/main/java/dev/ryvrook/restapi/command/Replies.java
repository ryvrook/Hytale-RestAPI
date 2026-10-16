package dev.ryvrook.restapi.command;

import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.command.system.CommandContext;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.Universe;
import com.hypixel.hytale.server.core.universe.world.World;

import java.util.List;
import java.util.UUID;

/** Reply helpers. Replies from RestAPI's own threads go back on the sender's world thread, as in the siblings. */
public final class Replies {
    public static final String MUTED = "#a0a8b0";
    public static final String OK = "#7cc88a";
    public static final String ERROR = "#e07a6a";
    public static final String PREFIX = "#6fb3e0";

    private Replies() {
    }

    public static Message line(String text, String color) {
        return Message.join(Message.raw("[RestAPI] ").color(PREFIX), Message.raw(text).color(color));
    }

    static void send(CommandContext context, List<Message> lines) {
        World world = senderWorld(context);
        Runnable r = () -> lines.forEach(context::sendMessage);
        if (world == null) {
            r.run();
            return;
        }
        try {
            world.execute(r);
        } catch (RuntimeException e) {
            r.run();
        }
    }

    private static World senderWorld(CommandContext context) {
        if (!context.isPlayer()) {
            return null;
        }
        try {
            PlayerRef sender = context.senderAs(PlayerRef.class);
            UUID worldId = sender == null ? null : sender.getWorldUuid();
            return worldId == null ? null : Universe.get().getWorld(worldId);
        } catch (RuntimeException e) {
            return null;
        }
    }
}
