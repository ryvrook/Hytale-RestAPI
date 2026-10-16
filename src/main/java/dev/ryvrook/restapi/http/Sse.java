package dev.ryvrook.restapi.http;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

/**
 * Server Sent Events. A route answers {@link Response#stream(Stream)} and the stream writes events until the client
 * goes away or the stream returns. Streams do not count against http.maxInFlight, http.maxStreams caps them.
 */
public final class Sse {
    private Sse() {
    }

    /** The code that feeds one open stream. Runs on the request's virtual thread and may block on a queue. */
    @FunctionalInterface
    public interface Stream {
        void run(Sink sink) throws Exception;
    }

    /** Where a stream writes. Every call throws IOException once the client is gone. */
    public interface Sink {
        void event(String name, String id, String data) throws IOException;

        /** A comment line, used as a keep alive every 15 seconds or so. */
        void comment(String text) throws IOException;
    }

    /**
     * One event in the wire format. Data with line breaks becomes several data lines. Name and id lose any line
     * break, so a value can never start a new field.
     */
    public static String format(String name, String id, String data) {
        StringBuilder b = new StringBuilder();
        if (name != null && !name.isEmpty()) {
            b.append("event: ").append(oneLine(name)).append('\n');
        }
        if (id != null && !id.isEmpty()) {
            b.append("id: ").append(oneLine(id)).append('\n');
        }
        String d = data == null ? "" : data;
        for (String line : d.split("\r\n|\r|\n", -1)) {
            b.append("data: ").append(line).append('\n');
        }
        return b.append('\n').toString();
    }

    static String oneLine(String s) {
        return s.replace('\r', ' ').replace('\n', ' ');
    }

    /** A sink over an output stream, flushed after every write. */
    static Sink sink(OutputStream out) {
        return new Sink() {
            @Override
            public void event(String name, String id, String data) throws IOException {
                out.write(format(name, id, data).getBytes(StandardCharsets.UTF_8));
                out.flush();
            }

            @Override
            public void comment(String text) throws IOException {
                out.write((": " + oneLine(text == null ? "" : text) + "\n\n").getBytes(StandardCharsets.UTF_8));
                out.flush();
            }
        };
    }
}
