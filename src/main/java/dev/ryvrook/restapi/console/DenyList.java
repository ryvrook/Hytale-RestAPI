package dev.ryvrook.restapi.console;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.Function;

/**
 * Decides whether a command line may reach the engine. The line is split into words and the leading words are
 * turned into the engine's own names through a resolver, so {@code /shutdown}, {@code SHUTDOWN} and {@code stop}
 * all read as {@code stop}, and {@code pl r X} reads as {@code plugin reload X}.
 *
 * <p>Fixed entries that no setting removes: {@code stop} with its alias {@code shutdown}, any {@code plugin}
 * command that names Ryvrook:RestAPI, {@code restapi reload}, {@code restapi keys} and {@code restapi dashboard},
 * and {@code sudo} or {@code su} around any refused command. RestAPI never reloads, unloads or stops anything of
 * its own through its own channels, and a key with the commands scope never makes itself a key or a dashboard
 * login with more scopes.
 */
public final class DenyList {
    /** Words of RestAPI's own plugin id as the plugin commands take it. */
    private static final Set<String> SELF = Set.of("ryvrook:restapi", "restapi");
    private static final int MAX_DEPTH = 4;

    private final Function<List<String>, List<String>> canonical;

    /**
     * @param canonical turns the words of a line into the engine's names for the command and its sub commands,
     *                  leaving the words it does not know as they are. The engine version walks the command tree.
     */
    public DenyList(Function<List<String>, List<String>> canonical) {
        this.canonical = canonical;
    }

    /** The words of a line, without a leading slash, split on white space. */
    public static List<String> words(String line) {
        String t = line == null ? "" : line.strip();
        while (t.startsWith("/")) {
            t = t.substring(1).strip();
        }
        List<String> out = new ArrayList<>();
        for (String w : t.split("\\s+")) {
            if (!w.isEmpty()) {
                out.add(w);
            }
        }
        return out;
    }

    /** The configured entry that refuses this line, a fixed rule name, or null when the line may run. */
    public String match(String line, List<String> configured) {
        return match(words(line), configured, 0);
    }

    public boolean denied(String line, List<String> configured) {
        return match(line, configured) != null;
    }

    private String match(List<String> raw, List<String> configured, int depth) {
        if (raw.isEmpty()) {
            return null;
        }
        List<String> w = lower(canon(raw));
        String first = w.get(0);
        if (first.equals("stop") || first.equals("shutdown")) {
            return "stop";
        }
        if (first.equals("plugin") || first.equals("plugins") || first.equals("pl")) {
            for (int i = 1; i < w.size(); i++) {
                if (SELF.contains(unquote(w.get(i)))) {
                    return "plugin Ryvrook:RestAPI";
                }
            }
        }
        if (first.equals("restapi") && w.size() > 1
                && (w.get(1).equals("reload") || w.get(1).equals("keys") || w.get(1).equals("dashboard"))) {
            return "restapi " + w.get(1);
        }
        if ((first.equals("sudo") || first.equals("su")) && w.size() > 2) {
            if (depth >= MAX_DEPTH) {
                return "sudo";
            }
            String inner = match(raw.subList(2, raw.size()), configured, depth + 1);
            if (inner != null) {
                return inner;
            }
        }
        for (String entry : configured) {
            List<String> e = words(entry);
            if (e.isEmpty()) {
                continue;
            }
            List<String> ce = lower(canon(e));
            if (startsWith(w, ce)) {
                return String.join(" ", ce);
            }
        }
        return null;
    }

    private List<String> canon(List<String> words) {
        List<String> c = canonical == null ? null : canonical.apply(List.copyOf(words));
        return c == null || c.isEmpty() ? words : c;
    }

    private static boolean startsWith(List<String> line, List<String> prefix) {
        if (prefix.size() > line.size()) {
            return false;
        }
        for (int i = 0; i < prefix.size(); i++) {
            if (!line.get(i).equals(prefix.get(i))) {
                return false;
            }
        }
        return true;
    }

    private static List<String> lower(List<String> words) {
        List<String> out = new ArrayList<>(words.size());
        for (String w : words) {
            String x = w;
            while (x.startsWith("/")) {
                x = x.substring(1);
            }
            out.add(x.toLowerCase(Locale.ROOT));
        }
        return out;
    }

    private static String unquote(String s) {
        String t = s;
        while (t.length() >= 1 && (t.startsWith("\"") || t.startsWith("'"))) {
            t = t.substring(1);
        }
        while (t.length() >= 1 && (t.endsWith("\"") || t.endsWith("'"))) {
            t = t.substring(0, t.length() - 1);
        }
        return t;
    }

    /** The fixed entries, for GET /api/commands/deny. */
    public static List<String> fixed() {
        return List.of("stop", "shutdown", "plugin <any> Ryvrook:RestAPI", "restapi reload", "restapi keys",
                "restapi dashboard", "sudo <player> <a refused command>");
    }
}
