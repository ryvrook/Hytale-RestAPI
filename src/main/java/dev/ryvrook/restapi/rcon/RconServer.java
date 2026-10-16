package dev.ryvrook.restapi.rcon;

import dev.ryvrook.restapi.audit.AuditSink;
import dev.ryvrook.restapi.auth.KeyStore;
import dev.ryvrook.restapi.exec.CommandService;
import dev.ryvrook.restapi.http.RateLimiter;
import dev.ryvrook.restapi.util.AddressRanges;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.time.Clock;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * The RCON listener. One virtual thread accepts, each connection gets a virtual thread of its own running a
 * {@link RconSession}. Connections past rcon.maxConnections and addresses over rcon.authFailuresPerMinute are
 * closed right after they connect, without a word, as a Minecraft server does with a full slot.
 */
public final class RconServer {
    /**
     * What the sessions need from the plugin.
     *
     * @param keys     the key store, a key with the commands scope is a password
     * @param audit    where logins and refused logins go, command runs are audited by the command service
     * @param commands the console module's command service, looked up again for every command
     * @param clock    for audit times
     * @param log      one line for the server log
     */
    public record Context(KeyStore keys, AuditSink audit, Supplier<Optional<CommandService>> commands, Clock clock,
                          Consumer<String> log) {
    }

    private final Context context;
    private final RateLimiter failures;
    private final Clock clock;
    private final Map<String, Long> blockedUntil = new ConcurrentHashMap<>();
    private final Set<RconSession> open = ConcurrentHashMap.newKeySet();
    private final AtomicInteger counter = new AtomicInteger();
    private volatile ServerSocket socket;
    private volatile Thread acceptor;

    public RconServer(Context context) {
        this.context = context;
        this.clock = context.clock();
        this.failures = new RateLimiter(clock::millis);
    }

    /** Opens the port. The bind must be an IP literal, a host name is refused so nothing here asks DNS. */
    public synchronized void start(String bind, int port) throws IOException {
        stop();
        byte[] addr = AddressRanges.literal(bind);
        if (addr == null) {
            throw new IOException("rcon.bind " + bind + " is not an IP address");
        }
        ServerSocket ss = new ServerSocket();
        try {
            ss.setReuseAddress(true);
            ss.bind(new InetSocketAddress(InetAddress.getByAddress(addr), port), 16);
        } catch (IOException e) {
            ss.close();
            throw e;
        }
        socket = ss;
        acceptor = Thread.ofVirtual().name("RestAPI-rcon-accept").start(() -> acceptLoop(ss));
    }

    private void acceptLoop(ServerSocket ss) {
        while (!ss.isClosed()) {
            Socket s;
            try {
                s = ss.accept();
            } catch (IOException e) {
                if (!ss.isClosed()) {
                    context.log().accept("RestAPI RCON could not accept a connection, " + e.getMessage());
                }
                continue;
            }
            String client = s.getInetAddress().getHostAddress();
            if (blocked(client)) {
                quietClose(s);
                continue;
            }
            if (open.size() >= RconSettings.MAX_CONNECTIONS.get()) {
                context.log().accept("RestAPI RCON closed a connection from " + client + ", all "
                        + RconSettings.MAX_CONNECTIONS.get() + " connections are in use");
                quietClose(s);
                continue;
            }
            RconSession session = new RconSession(this, context, s, client, counter.incrementAndGet());
            open.add(session);
            try {
                Thread.ofVirtual().name("RestAPI-rcon-" + session.number()).start(() -> {
                    try {
                        session.run();
                    } finally {
                        open.remove(session);
                    }
                });
            } catch (RuntimeException e) {
                open.remove(session);
                quietClose(s);
            }
        }
    }

    /**
     * Counts a failed login for an address. True when the address went over rcon.authFailuresPerMinute, then
     * its connections are refused until its bucket has a token again.
     */
    boolean failedLogin(String client) {
        long wait = failures.acquire(client, RconSettings.AUTH_FAILURES.get());
        if (wait > 0) {
            blockedUntil.put(client, clock.millis() + wait * 1000);
            return true;
        }
        return false;
    }

    boolean blocked(String client) {
        Long until = blockedUntil.get(client);
        if (until == null) {
            return false;
        }
        if (clock.millis() >= until) {
            blockedUntil.remove(client, until);
            return false;
        }
        return true;
    }

    /** Closes the port and every connection. Safe to call twice. */
    public synchronized void stop() {
        ServerSocket ss = socket;
        socket = null;
        if (ss != null) {
            quietClose(ss);
        }
        for (RconSession s : open) {
            s.close();
        }
        Thread t = acceptor;
        acceptor = null;
        if (t != null) {
            try {
                t.join(2_000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    /** The port in use, or -1 while closed. */
    public int port() {
        ServerSocket ss = socket;
        return ss == null || ss.isClosed() ? -1 : ss.getLocalPort();
    }

    /** host:port while listening, else null. */
    public String boundTo() {
        ServerSocket ss = socket;
        if (ss == null || ss.isClosed()) {
            return null;
        }
        String host = ss.getInetAddress().getHostAddress();
        return (host.indexOf(':') >= 0 ? "[" + host + "]" : host) + ":" + ss.getLocalPort();
    }

    public int connections() {
        return open.size();
    }

    static void quietClose(java.io.Closeable c) {
        try {
            c.close();
        } catch (IOException ignored) {
            // Closing anyway.
        }
    }
}
