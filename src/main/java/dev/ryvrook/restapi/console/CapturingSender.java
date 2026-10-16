package dev.ryvrook.restapi.console;

import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.command.system.CommandSender;
import com.hypixel.hytale.server.core.util.MessageUtil;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * The sender a command runs as when it comes over HTTP or RCON. Its name is {@code RestAPI:<key>}, so the engine's
 * own "executed command" log line names the key. Every message the command sends it is turned into plain text,
 * split on line breaks and handed to the sink. Permission answers come from commands.permissions.
 *
 * <p>The engine may call {@link #sendMessage} from the common pool, a world thread or a plugin's own thread, the
 * sink must take lines from any thread.
 */
public final class CapturingSender implements CommandSender {
    public static final String PREFIX = "RestAPI:";

    private final String name;
    private final UUID uuid;
    private final List<String> permissions;
    private final Function<Message, String> plain;
    private final Consumer<String> sink;

    /**
     * @param keyName     the key or login, shown after RestAPI: in the engine log
     * @param permissions rules as commands.permissions holds them
     * @param plain       turns a message into text, {@link #engineText} on a server
     * @param sink        takes each line
     */
    public CapturingSender(String keyName, List<String> permissions, Function<Message, String> plain,
                           Consumer<String> sink) {
        this.name = PREFIX + keyName;
        this.uuid = UUID.nameUUIDFromBytes(name.getBytes(StandardCharsets.UTF_8));
        this.permissions = List.copyOf(permissions);
        this.plain = plain;
        this.sink = sink;
    }

    /** The engine's plain text of a message, translation keys resolved and parameters filled. */
    public static String engineText(Message message) {
        return MessageUtil.formatMessageToPlainString(message.getFormattedMessage());
    }

    @Override
    public String getUsername() {
        return name;
    }

    @Override
    public UUID getUuid() {
        return uuid;
    }

    @Override
    public void sendMessage(Message message) {
        if (message == null) {
            return;
        }
        String text;
        try {
            text = plain.apply(message);
        } catch (RuntimeException e) {
            text = message.getRawText() != null ? message.getRawText() : String.valueOf(message.getMessageId());
        }
        if (text == null) {
            return;
        }
        for (String line : text.split("\r\n|\r|\n", -1)) {
            sink.accept(LogRing.clean(line));
        }
    }

    @Override
    public boolean hasPermission(String node) {
        return allowed(permissions, node);
    }

    @Override
    public boolean hasPermission(String node, boolean fallback) {
        return permissions.isEmpty() ? fallback : allowed(permissions, node);
    }

    /**
     * Whether the rules grant a node. {@code -node} rules win over grants, {@code *} grants everything, a rule
     * ending in {@code .*} covers every node under it. Nodes compare without regard to case.
     */
    public static boolean allowed(List<String> rules, String node) {
        if (node == null) {
            return false;
        }
        String n = node.trim().toLowerCase(Locale.ROOT);
        for (String r : rules) {
            String rule = r.trim().toLowerCase(Locale.ROOT);
            if (rule.startsWith("-") && covers(rule.substring(1).trim(), n)) {
                return false;
            }
        }
        for (String r : rules) {
            String rule = r.trim().toLowerCase(Locale.ROOT);
            if (!rule.startsWith("-") && covers(rule, n)) {
                return true;
            }
        }
        return false;
    }

    private static boolean covers(String rule, String node) {
        if (rule.equals("*")) {
            return true;
        }
        if (rule.endsWith(".*")) {
            String base = rule.substring(0, rule.length() - 2);
            return node.equals(base) || node.startsWith(base + ".");
        }
        return rule.equals(node);
    }
}
