package dev.ryvrook.restapi.rcon;

import dev.ryvrook.restapi.RestApi;
import dev.ryvrook.restapi.RestModule;
import dev.ryvrook.restapi.exec.CommandService;
import dev.ryvrook.restapi.http.Route;
import dev.ryvrook.restapi.text.Messages;
import dev.ryvrook.restapi.util.AddressRanges;

import java.io.IOException;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Level;

/**
 * Source RCON next to the HTTP API, off by default. It has no routes. Start opens the port when rcon.enabled is
 * on, stop closes it with every connection, so /restapi reload applies a new bind or port and drops the open
 * connections. Commands go through the console module's {@link CommandService}, which this module looks up for
 * every command, so it keeps working when the console module starts after it or comes back after a reload.
 */
public final class RconModule implements RestModule {
    public static final String GROUP = "rcon";

    private volatile RconServer server;
    private volatile String error;

    public RconModule() {
        RconSettings.ENABLED.key();
        RconText.NO_CONSOLE.length();
    }

    @Override
    public String group() {
        return GROUP;
    }

    @Override
    public List<Route> routes(RestApi api) {
        return List.of();
    }

    @Override
    public void start(RestApi api) {
        error = null;
        if (!RconSettings.ENABLED.get()) {
            return;
        }
        RconServer s = new RconServer(new RconServer.Context(api.keys(), api.audit(),
                () -> api.service(CommandService.class), Clock.systemUTC(),
                line -> api.logger().at(Level.INFO).log("%s", line)));
        String bind = RconSettings.BIND.get();
        int port = RconSettings.PORT.get();
        try {
            s.start(bind, port);
        } catch (IOException e) {
            error = bind + ":" + port + " could not be opened, " + e.getMessage();
            api.logger().at(Level.SEVERE).log("RestAPI RCON is down, %s", error);
            return;
        }
        server = s;
        api.logger().at(Level.INFO).log("RestAPI RCON on %s, the password is any key with the commands scope", s.boundTo());
        if (!AddressRanges.isLoopback(bind)) {
            api.logger().at(Level.WARNING).log("RestAPI RCON listens on %s beyond this machine. RCON has no encryption, "
                    + "keys and commands cross the network in clear. Reach it through SSH or a VPN", s.boundTo());
        }
        if (api.service(CommandService.class).isEmpty()) {
            api.logger().at(Level.WARNING).log("RestAPI RCON is on but the console module is not running, so commands answer with an error");
        }
    }

    @Override
    public void stop() {
        RconServer s = server;
        server = null;
        if (s != null) {
            s.stop();
        }
    }

    /** Lines for /restapi status. */
    @Override
    public List<String> status() {
        List<String> out = new ArrayList<>();
        RconServer s = server;
        if (s != null && s.boundTo() != null) {
            out.add(Messages.get(RconText.STATUS_ON, "address", s.boundTo(), "connections", s.connections(),
                    "max", RconSettings.MAX_CONNECTIONS.get()));
            if (!AddressRanges.isLoopback(RconSettings.BIND.get())) {
                out.add(Messages.get(RconText.STATUS_EXPOSED));
            }
        } else if (error != null) {
            out.add(Messages.get(RconText.STATUS_DOWN, "reason", error));
        } else {
            out.add(Messages.get(RconText.STATUS_OFF));
        }
        return out;
    }

    /** /restapi rcon status prints the status lines. */
    @Override
    public List<String> lines(String topic) {
        return "status".equals(topic) ? status() : List.of();
    }
}
