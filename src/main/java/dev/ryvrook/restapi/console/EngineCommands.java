package dev.ryvrook.restapi.console;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.command.system.AbstractCommand;
import com.hypixel.hytale.server.core.command.system.CommandManager;
import com.hypixel.hytale.server.core.command.system.CommandSender;

import java.util.ArrayList;
import java.util.ConcurrentModificationException;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.CompletableFuture;

/**
 * The engine side of {@link CommandRunner}, the only class here that calls {@code CommandManager}.
 * {@code handleCommand} can be called from any thread and runs the command on the common pool. The engine takes
 * the first word up to a space, lower cases it and looks it up through {@code resolveCommand}, which tries the
 * name and then the aliases.
 */
public final class EngineCommands implements CommandRunner.Engine {
    @Override
    public CompletableFuture<Void> run(CommandSender sender, String line) {
        CommandManager m = CommandManager.get();
        if (m == null) {
            return CompletableFuture.failedFuture(new IllegalStateException("The command manager is not up"));
        }
        return m.handleCommand(sender, line);
    }

    @Override
    public boolean known(String firstWord) {
        CommandManager m = CommandManager.get();
        return m == null || m.resolveCommand(firstWord.toLowerCase(Locale.ROOT)) != null;
    }

    /**
     * Walks the command tree as far as the words name commands. {@code pl r X} becomes {@code plugin reload X}.
     * Words after the last known sub command stay as they are.
     */
    @Override
    public List<String> canonical(List<String> words) {
        CommandManager m = CommandManager.get();
        if (m == null || words.isEmpty()) {
            return words;
        }
        List<String> out = new ArrayList<>(words);
        AbstractCommand at = m.resolveCommand(words.get(0).toLowerCase(Locale.ROOT));
        if (at == null) {
            return out;
        }
        out.set(0, at.getName());
        for (int i = 1; i < words.size(); i++) {
            AbstractCommand sub = sub(at, words.get(i).toLowerCase(Locale.ROOT));
            if (sub == null) {
                break;
            }
            out.set(i, sub.getName());
            at = sub;
        }
        return out;
    }

    private static AbstractCommand sub(AbstractCommand parent, String word) {
        Map<String, AbstractCommand> subs = parent.getSubCommands();
        if (subs == null || subs.isEmpty()) {
            return null;
        }
        for (AbstractCommand c : List.copyOf(subs.values())) {
            if (word.equalsIgnoreCase(c.getName())) {
                return c;
            }
            for (String a : c.getAliases()) {
                if (word.equalsIgnoreCase(a)) {
                    return c;
                }
            }
        }
        return null;
    }

    /** Every registered root command with its aliases, description and permission, sorted by name. */
    public static JsonArray list() {
        CommandManager m = CommandManager.get();
        JsonArray out = new JsonArray();
        if (m == null) {
            return out;
        }
        Map<String, AbstractCommand> copy = new TreeMap<>();
        for (int attempt = 0; attempt < 3; attempt++) {
            try {
                copy.clear();
                copy.putAll(m.getCommandRegistration());
                break;
            } catch (ConcurrentModificationException e) {
                // A plugin registered a command at that moment. Read again.
            }
        }
        for (AbstractCommand c : copy.values()) {
            JsonObject o = new JsonObject();
            o.addProperty("name", c.getName());
            JsonArray aliases = new JsonArray();
            c.getAliases().stream().sorted().forEach(aliases::add);
            o.add("aliases", aliases);
            o.addProperty("description", describe(c.getDescription()));
            o.addProperty("permission", c.getPermission());
            JsonArray subs = new JsonArray();
            if (c.getSubCommands() != null) {
                c.getSubCommands().keySet().stream().sorted().forEach(subs::add);
            }
            o.add("subcommands", subs);
            out.add(o);
        }
        return out;
    }

    /** Descriptions are often translation keys such as server.commands.say.desc. Those are looked up. */
    static String describe(String d) {
        if (d == null || d.isEmpty() || d.contains(" ") || !d.contains(".")) {
            return d;
        }
        try {
            String t = CapturingSender.engineText(Message.translation(d));
            return t == null || t.isBlank() ? d : t;
        } catch (RuntimeException | LinkageError e) {
            return d;
        }
    }
}
