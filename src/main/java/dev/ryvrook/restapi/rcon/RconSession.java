package dev.ryvrook.restapi.rcon;

import dev.ryvrook.restapi.audit.AuditEntry;
import dev.ryvrook.restapi.auth.ApiKey;
import dev.ryvrook.restapi.auth.Scope;
import dev.ryvrook.restapi.config.CoreSettings;
import dev.ryvrook.restapi.exec.CommandResult;
import dev.ryvrook.restapi.exec.CommandService;
import dev.ryvrook.restapi.text.Messages;
import dev.ryvrook.restapi.util.AddressRanges;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * One RCON connection, read and answered on its own virtual thread, one packet at a time as the protocol
 * expects.
 *
 * <p>Login (type 3) takes a RestAPI key with the commands scope as the password. The key's own allowFrom and
 * commands.allowFrom both have to accept the address. A refused login answers id -1 and counts against the
 * address. Before a login every other packet answers id -1.
 *
 * <p>A command (type 2) runs through {@code RestApi.service(CommandService.class)}, so it meets the same deny list
 * and audit trail as POST /api/commands. No idempotency key is passed. mcrcon sends every packet with the id
 * 0xBADC0DE, so a key built from the connection and the request id would replay the first answer for every later
 * command, and inside one TCP connection a packet never arrives twice anyway. The run id the command service
 * gives each run is in the audit log.
 *
 * <p>Clients find the end of a reply split into several packets in one of two ways. An empty type 0 sent after
 * the command is echoed back after the last piece, and any other type answers {@code Unknown request <hex>}, the
 * way Minecraft does. Both work here because packets are handled strictly in order.
 */
final class RconSession {
    private final RconServer server;
    private final RconServer.Context context;
    private final Socket socket;
    private final String client;
    private final int number;
    private ApiKey key;

    RconSession(RconServer server, RconServer.Context context, Socket socket, String client, int number) {
        this.server = server;
        this.context = context;
        this.socket = socket;
        this.client = client;
        this.number = number;
    }

    int number() {
        return number;
    }

    void run() {
        try {
            socket.setSoTimeout(RconSettings.IDLE_SECONDS.get() * 1000);
            socket.setTcpNoDelay(true);
            InputStream in = new BufferedInputStream(socket.getInputStream());
            OutputStream out = new BufferedOutputStream(socket.getOutputStream());
            while (!socket.isClosed()) {
                Packet p = Packet.read(in);
                if (p == null) {
                    return;
                }
                boolean more = answer(p, out);
                out.flush();
                if (!more) {
                    return;
                }
            }
        } catch (SocketTimeoutException e) {
            // Idle past rcon.idleSeconds.
        } catch (java.net.ProtocolException e) {
            context.log().accept("RestAPI RCON closed the connection from " + client + ", " + e.getMessage());
            if (key == null) {
                server.failedLogin(client);
            }
        } catch (IOException e) {
            // The client left or stop() closed the socket.
        } finally {
            close();
        }
    }

    /** Handles one packet. False ends the connection. */
    private boolean answer(Packet p, OutputStream out) throws IOException {
        if (p.type() == Packet.LOGIN) {
            return login(p, out);
        }
        if (key == null) {
            send(out, new Packet(-1, Packet.LOGIN_ANSWER, ""));
            return true;
        }
        if (p.type() == Packet.COMMAND) {
            if (!stillValid()) {
                reply(out, p.id(), Messages.get(RconText.KEY_GONE));
                return false;
            }
            reply(out, p.id(), run(p.body()));
            return true;
        }
        if (p.type() == Packet.RESPONSE) {
            send(out, new Packet(p.id(), Packet.RESPONSE, ""));
            return true;
        }
        reply(out, p.id(), Messages.get(RconText.UNKNOWN_TYPE, "type", Integer.toHexString(p.type())));
        return true;
    }

    private boolean login(Packet p, OutputStream out) throws IOException {
        ApiKey k = context.keys().authenticate(p.body());
        String refused = refusal(k);
        if (refused == null) {
            key = k;
            audit(k.name(), "login", "ok");
            send(out, new Packet(p.id(), Packet.LOGIN_ANSWER, ""));
            return true;
        }
        key = null;
        if (CoreSettings.AUDIT_AUTH_FAILURES.get()) {
            audit(k == null ? "unknown" : k.name(), "login", refused);
        }
        send(out, new Packet(-1, Packet.LOGIN_ANSWER, ""));
        if (server.failedLogin(client)) {
            context.log().accept("RestAPI RCON refuses " + client + " for now, it passed rcon.authFailuresPerMinute");
            return false;
        }
        return true;
    }

    /** Why a key may not log in from this address, or null when it may. */
    private String refusal(ApiKey k) {
        if (k == null) {
            return "wrong key";
        }
        if (!k.has(Scope.COMMANDS)) {
            return "key lacks the commands scope";
        }
        if (!k.allowFrom().isEmpty() && !AddressRanges.parse(k.allowFrom()).contains(client)) {
            return "address not in allowFrom";
        }
        if (!AddressRanges.parse(RconSettings.commandsAllowFrom()).contains(client)) {
            return "address not in commands.allowFrom";
        }
        return null;
    }

    /** The key may have been revoked or changed since the login. */
    private boolean stillValid() {
        ApiKey now = context.keys().byName(key.name());
        return now != null && now.id().equals(key.id()) && refusal(now) == null;
    }

    private String run(String body) {
        String line = body.strip();
        if (line.startsWith("/")) {
            line = line.substring(1).strip();
        }
        if (line.isEmpty()) {
            return "";
        }
        Optional<CommandService> service = context.commands().get();
        if (service.isEmpty()) {
            return Messages.get(RconText.NO_CONSOLE);
        }
        int ms = RconSettings.commandTimeoutMs();
        CommandResult r;
        try {
            r = service.get().run(key.name(), "rcon", client, line, null, Duration.ofMillis(ms))
                    .get(ms + 10_000L, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Messages.get(RconText.FAILED, "command", shown(line));
        } catch (ExecutionException | TimeoutException | RuntimeException e) {
            context.log().accept("RestAPI RCON could not run a command for " + key.name() + ", " + e);
            return Messages.get(RconText.FAILED, "command", shown(line));
        }
        return text(r, line, ms);
    }

    /** The reply body for a result. */
    static String text(CommandResult r, String line, int timeoutMs) {
        List<String> lines = new ArrayList<>(r.output());
        switch (r.outcome()) {
            case DENIED -> {
                return Messages.get(RconText.DENIED, "command", shown(line));
            }
            case FAILED -> {
                if (lines.isEmpty()) {
                    lines.add(Messages.get(RconText.FAILED, "command", shown(line)));
                }
            }
            case TIMED_OUT -> lines.add(Messages.get(RconText.TIMED_OUT, "command", shown(line), "ms", timeoutMs));
            default -> {
            }
        }
        return String.join("\n", lines);
    }

    /** The command as replies quote it, cut to 60 characters. */
    static String shown(String line) {
        String s = line.strip();
        return s.length() <= 60 ? s : s.substring(0, 57) + "...";
    }

    private void reply(OutputStream out, int id, String text) throws IOException {
        for (String piece : Packet.split(text, Packet.MAX_BODY_OUT)) {
            send(out, new Packet(id, Packet.RESPONSE, piece));
        }
    }

    private static void send(OutputStream out, Packet p) throws IOException {
        out.write(p.encode());
    }

    private void audit(String keyName, String action, String detail) {
        context.audit().record(new AuditEntry(context.clock().instant().toString(), "rcon", keyName, client, action, 0, detail));
    }

    void close() {
        RconServer.quietClose(socket);
    }
}
