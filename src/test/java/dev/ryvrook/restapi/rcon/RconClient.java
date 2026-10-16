package dev.ryvrook.restapi.rcon;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;

/** A small RCON client for the tests, the way mcrcon and rcon-cli talk. */
final class RconClient implements AutoCloseable {
    final Socket socket;
    private final InputStream in;
    private final OutputStream out;

    RconClient(int port) throws IOException {
        socket = new Socket("127.0.0.1", port);
        socket.setSoTimeout(5_000);
        in = new BufferedInputStream(socket.getInputStream());
        out = socket.getOutputStream();
    }

    void send(Packet p) throws IOException {
        out.write(p.encode());
        out.flush();
    }

    void sendRaw(byte[] bytes) throws IOException {
        out.write(bytes);
        out.flush();
    }

    Packet read() throws IOException {
        return Packet.read(in);
    }

    /** Logs in and returns the id of the answer, -1 on a refused login. */
    int login(String password) throws IOException {
        send(new Packet(7, Packet.LOGIN, password));
        Packet p = read();
        if (p == null || p.type() != Packet.LOGIN_ANSWER) {
            throw new IOException("no login answer, got " + p);
        }
        return p.id();
    }

    /**
     * Runs a command and joins every piece of the reply. Sends an empty type 0 right after the command and reads
     * until its echo, the trick that tells where a reply split over several packets ends.
     */
    String command(int id, String line) throws IOException {
        send(new Packet(id, Packet.COMMAND, line));
        send(new Packet(id + 1, Packet.RESPONSE, ""));
        StringBuilder b = new StringBuilder();
        while (true) {
            Packet p = read();
            if (p == null) {
                throw new IOException("connection closed");
            }
            if (p.id() == id + 1) {
                return b.toString();
            }
            if (p.id() != id) {
                throw new IOException("unexpected id " + p.id());
            }
            b.append(p.body());
        }
    }

    /** True when the server closed the connection. */
    boolean closed() {
        try {
            return in.read() < 0;
        } catch (IOException e) {
            return true;
        }
    }

    @Override
    public void close() throws IOException {
        socket.close();
    }
}
