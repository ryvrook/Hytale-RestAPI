package dev.ryvrook.restapi.console;

import dev.ryvrook.restapi.RestApi;
import dev.ryvrook.restapi.RestApiPlugin;
import dev.ryvrook.restapi.RestModule;
import dev.ryvrook.restapi.exec.CommandService;
import dev.ryvrook.restapi.http.Route;

import java.util.List;
import java.util.logging.Level;

/**
 * Commands with their output, the deny list and idempotency, and the server log as a ring and a stream. Provides
 * the {@link CommandService} that RCON runs commands through, from start until stop.
 *
 * <p>The log subscription starts in setup so the ring holds the boot lines that come after RestAPI's setup. A
 * stop, which a /restapi reload also does, ends open log streams, a client resumes with Last-Event-ID because
 * the ring and its ids live as long as the module.
 */
public final class ConsoleModule implements RestModule {
    private final LogRing ring;
    private final LogTap tap;
    private volatile CommandRunner runner;
    private RestApi api;

    public ConsoleModule() {
        ConsoleSettings.COMMANDS_ENABLED.key();
        ConsoleText.NO_COMMAND.length();
        ring = new LogRing(ConsoleSettings.CONSOLE_RING_LINES.fallback());
        tap = new LogTap(ring, ConsoleModule::minLevel, (thread, text) -> {
            CommandRunner r = runner;
            if (r != null) {
                r.echo(thread, text);
            }
        });
    }

    /** The lowest level kept, or OFF so nothing is kept while console.enabled is off. */
    static Level minLevel() {
        if (!ConsoleSettings.CONSOLE_ENABLED.get()) {
            return Level.OFF;
        }
        Level l = LogRing.parseLevel(ConsoleSettings.CONSOLE_MIN_LEVEL.get());
        return l == null ? Level.INFO : l;
    }

    @Override
    public String group() {
        return ConsoleRoutes.GROUP;
    }

    @Override
    public void setup(RestApiPlugin plugin) {
        tap.subscribe();
    }

    @Override
    public List<Route> routes(RestApi api) {
        this.api = api;
        if (LogRing.parseLevel(ConsoleSettings.CONSOLE_MIN_LEVEL.get()) == null) {
            api.logger().at(Level.WARNING).log("RestAPI reads console.minLevel = %s as INFO, use SEVERE, WARNING, INFO, CONFIG, FINE, FINER or FINEST",
                    ConsoleSettings.CONSOLE_MIN_LEVEL.get());
        }
        ring.resize(ConsoleSettings.CONSOLE_RING_LINES.get());
        CommandRunner r = null;
        if (ConsoleSettings.COMMANDS_ENABLED.get()) {
            r = runner;
            if (r == null) {
                r = new CommandRunner(new EngineCommands(), api.audit(), System::currentTimeMillis, CapturingSender::engineText);
                runner = r;
            }
        }
        return new ConsoleRoutes(r, ConsoleSettings.CONSOLE_ENABLED.get() ? ring : null, EngineCommands::list).routes();
    }

    @Override
    public void start(RestApi api) {
        this.api = api;
        boolean commands = ConsoleSettings.COMMANDS_ENABLED.get();
        boolean console = ConsoleSettings.CONSOLE_ENABLED.get();
        if (console || (commands && ConsoleSettings.COMMANDS_ECHO.get())) {
            ring.open();
            tap.subscribe();
        }
        CommandRunner r = runner;
        if (commands && r != null) {
            r.open();
            api.provide(CommandService.class, r);
        }
    }

    @Override
    public void stop() {
        CommandRunner r = runner;
        if (r != null) {
            if (api != null) {
                api.withdraw(CommandService.class, r);
            }
            r.stop();
        }
        tap.unsubscribe();
        ring.close();
    }
}
