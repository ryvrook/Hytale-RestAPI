package dev.ryvrook.restapi.console;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class DenyListTest {
    private static final Map<String, String> ROOTS = Map.of("shutdown", "stop", "pl", "plugin", "plugins", "plugin",
            "su", "sudo", "stop", "stop", "plugin", "plugin", "sudo", "sudo", "whitelist", "whitelist", "say", "say",
            "restapi", "restapi");
    private static final Map<String, String> PLUGIN_SUBS = Map.of("r", "reload", "reload", "reload", "u", "unload",
            "unload", "unload", "l", "load", "load", "load", "list", "list");

    /** Stands in for the engine's command tree. */
    static List<String> canonical(List<String> words) {
        List<String> out = new ArrayList<>(words);
        String root = ROOTS.get(words.get(0).toLowerCase());
        if (root == null) {
            return out;
        }
        out.set(0, root);
        if (root.equals("plugin") && words.size() > 1) {
            String sub = PLUGIN_SUBS.get(words.get(1).toLowerCase());
            if (sub != null) {
                out.set(1, sub);
            }
        }
        return out;
    }

    private final DenyList deny = new DenyList(DenyListTest::canonical);
    private final List<String> defaults = List.of("op", "deop", "perm", "whitelist clear", "plugin unload");

    @Test
    void stopIsRefusedInEverySpelling() {
        for (String line : List.of("stop", "/stop", "STOP", "  /stop now", "shutdown", "/Shutdown", "stop --crash")) {
            assertEquals("stop", deny.match(line, List.of()), line);
        }
    }

    @Test
    void restApiNeverReloadsOrUnloadsItself() {
        for (String line : List.of("plugin reload Ryvrook:RestAPI", "pl r ryvrook:restapi", "plugin unload \"Ryvrook:RestAPI\"",
                "plugins load Ryvrook:RestAPI --boot", "pl u RestAPI")) {
            assertEquals("plugin Ryvrook:RestAPI", deny.match(line, List.of()), line);
        }
        assertNull(deny.match("plugin reload Ryvrook:Lullaby", List.of()));
        assertNull(deny.match("plugin list", List.of()));
    }

    @Test
    void restApiReloadAndKeysAreRefused() {
        assertEquals("restapi reload", deny.match("restapi reload", List.of()));
        assertEquals("restapi keys", deny.match("/restapi keys create root all", List.of()));
        assertNull(deny.match("restapi status", List.of()));
    }

    @Test
    void sudoAroundARefusedCommandIsRefused() {
        assertEquals("stop", deny.match("sudo * stop", List.of()));
        assertEquals("stop", deny.match("su Steve /shutdown", List.of()));
        assertEquals("plugin Ryvrook:RestAPI", deny.match("sudo Steve pl r Ryvrook:RestAPI", List.of()));
        assertEquals("op", deny.match("sudo Steve op add Alex", defaults));
        assertNull(deny.match("sudo Steve say hi", defaults));
        assertEquals("stop", deny.match("sudo a sudo b sudo c stop", List.of()));
    }

    @Test
    void configuredEntriesMatchOnTheLeadingWords() {
        assertEquals("whitelist clear", deny.match("whitelist clear", defaults));
        assertNull(deny.match("whitelist add Steve", defaults));
        assertEquals("plugin unload", deny.match("pl u Ryvrook:Lullaby", defaults));
        assertEquals("op", deny.match("OP add Steve", defaults));
        assertNull(deny.match("opinion", defaults));
        assertNull(deny.match("say op", defaults));
    }

    @Test
    void configuredEntriesGoThroughAliasesToo() {
        assertEquals("plugin reload", deny.match("plugin reload Ryvrook:Feats", List.of("pl r")));
    }

    @Test
    void anEmptyLineIsNotTheDenyListsBusiness() {
        assertNull(deny.match("", defaults));
        assertNull(deny.match("   /  ", defaults));
        assertEquals(List.of("say", "hello", "world"), DenyList.words("/say  hello\tworld"));
    }

    @Test
    void withoutAResolverTheRawWordsCount() {
        DenyList bare = new DenyList(w -> null);
        assertEquals("stop", bare.match("shutdown", List.of()));
        assertEquals("plugin Ryvrook:RestAPI", bare.match("pl reload Ryvrook:RestAPI", List.of()));
    }
}
