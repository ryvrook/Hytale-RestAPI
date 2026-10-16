package dev.ryvrook.restapi;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * The modules RestAPI looks for, by class name. A class missing from this build is skipped, so each module lands
 * on its own and the list never needs a second edit. The suite route groups are not here, they come and go with
 * their sibling through {@link dev.ryvrook.restapi.siblings.Siblings}.
 */
public final class Modules {
    private Modules() {
    }

    public static final List<String> CLASSES = List.of(
            "dev.ryvrook.restapi.server.CoreModule",
            "dev.ryvrook.restapi.players.PlayersModule",
            "dev.ryvrook.restapi.console.ConsoleModule",
            "dev.ryvrook.restapi.control.ControlModule",
            "dev.ryvrook.restapi.events.EventsModule",
            "dev.ryvrook.restapi.docs.DocsModule",
            "dev.ryvrook.restapi.rcon.RconModule",
            "dev.ryvrook.restapi.suite.SuiteModule");

    /** Makes every module this build holds. Problems other than a missing class go to the consumer. */
    public static List<RestModule> load(ClassLoader loader, Consumer<String> problems) {
        List<RestModule> out = new ArrayList<>();
        for (String name : CLASSES) {
            try {
                Class<?> c = Class.forName(name, true, loader);
                out.add((RestModule) c.getDeclaredConstructor().newInstance());
            } catch (ClassNotFoundException e) {
                // Not in this build.
            } catch (ReflectiveOperationException | LinkageError | ClassCastException e) {
                problems.accept(name + " could not be made, " + e);
            }
        }
        return out;
    }
}
