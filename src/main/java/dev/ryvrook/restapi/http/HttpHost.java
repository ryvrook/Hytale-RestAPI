package dev.ryvrook.restapi.http;

import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import dev.ryvrook.restapi.config.CoreSettings;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;

/**
 * The JDK HTTP server from the jdk.httpserver module, with one virtual thread per request. It turns an exchange
 * into a {@link Request}, hands it to the {@link Gate} and writes the {@link Response}. Nothing here knows about
 * keys or routes.
 *
 * <p>At most http.maxInFlight requests run at once, the rest get 503 with Retry-After 1. Bodies over
 * http.maxBodyBytes get 413 without being read to the end. The JDK property sun.net.httpserver.maxReqTime
 * closes a client that takes longer than http.requestSeconds to send its request, it is read once per JVM.
 */
public final class HttpHost {
    private final Gate gate;
    private HttpServer server;
    private ExecutorService executor;
    private volatile Semaphore inFlight;
    private volatile int inFlightSize;
    private String boundTo;

    public HttpHost(Gate gate) {
        this.gate = gate;
    }

    /** Binds and starts. Throws when the address cannot be bound, such as a port in use. */
    public synchronized void start(String bind, int port) throws IOException {
        stop();
        if (System.getProperty("sun.net.httpserver.maxReqTime") == null) {
            System.setProperty("sun.net.httpserver.maxReqTime", Integer.toString(CoreSettings.HTTP_REQUEST_SECONDS.get()));
        }
        HttpServer s = HttpServer.create(new InetSocketAddress(bind, port), 64);
        ExecutorService ex = Executors.newVirtualThreadPerTaskExecutor();
        s.setExecutor(ex);
        s.createContext("/", this::exchange);
        s.start();
        server = s;
        executor = ex;
        boundTo = bind + ":" + port;
    }

    public synchronized void stop() {
        if (server != null) {
            server.stop(0);
            executor.shutdownNow();
            server = null;
            executor = null;
            boundTo = null;
        }
    }

    /** host:port while serving, else null. */
    public synchronized String boundTo() {
        return boundTo;
    }

    private Semaphore slots() {
        int want = CoreSettings.HTTP_MAX_IN_FLIGHT.get();
        Semaphore s = inFlight;
        if (s == null || inFlightSize != want) {
            synchronized (this) {
                if (inFlight == null || inFlightSize != want) {
                    inFlight = new Semaphore(want);
                    inFlightSize = want;
                }
                s = inFlight;
            }
        }
        return s;
    }

    private void exchange(HttpExchange ex) throws IOException {
        Semaphore slots = slots();
        if (!slots.tryAcquire()) {
            write(ex, Response.error(503, "busy", "The API is busy, try again in a second").with("Retry-After", "1"));
            return;
        }
        try {
            int max = CoreSettings.HTTP_MAX_BODY_BYTES.get();
            String length = ex.getRequestHeaders().getFirst("Content-Length");
            if (length != null) {
                try {
                    if (Long.parseLong(length.trim()) > max) {
                        write(ex, Response.error(413, "body_too_large", "The body is over " + max + " bytes"));
                        return;
                    }
                } catch (NumberFormatException e) {
                    write(ex, Response.error(400, "bad_length", "Content-Length is not a number"));
                    return;
                }
            }
            byte[] body = readBody(ex.getRequestBody(), max);
            if (body == null) {
                write(ex, Response.error(413, "body_too_large", "The body is over " + max + " bytes"));
                return;
            }
            Map<String, List<String>> headers = new LinkedHashMap<>();
            for (Map.Entry<String, List<String>> h : ex.getRequestHeaders().entrySet()) {
                headers.put(h.getKey(), h.getValue());
            }
            String peer = ex.getRemoteAddress() == null || ex.getRemoteAddress().getAddress() == null
                    ? null : ex.getRemoteAddress().getAddress().getHostAddress();
            Request req = new Request(ex.getRequestMethod(), ex.getRequestURI().getRawPath(),
                    ex.getRequestURI().getRawQuery(), headers, body, peer);
            write(ex, gate.handle(req));
        } finally {
            slots.release();
        }
    }

    /** Reads at most max bytes. Null when the body is longer. */
    static byte[] readBody(InputStream in, int max) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int total = 0;
        int n;
        while ((n = in.read(buf)) > 0) {
            total += n;
            if (total > max) {
                return null;
            }
            out.write(buf, 0, n);
        }
        return out.toByteArray();
    }

    private static void write(HttpExchange ex, Response r) throws IOException {
        try (ex) {
            Headers h = ex.getResponseHeaders();
            if (r.contentType() != null) {
                h.set("Content-Type", r.contentType());
            }
            r.headers().forEach(h::set);
            boolean head = "HEAD".equalsIgnoreCase(ex.getRequestMethod());
            if (r.status() == 204 || r.body().length == 0) {
                ex.sendResponseHeaders(r.status(), -1);
                return;
            }
            if (head) {
                h.set("Content-Length", Integer.toString(r.body().length));
                ex.sendResponseHeaders(r.status(), -1);
                return;
            }
            ex.sendResponseHeaders(r.status(), r.body().length);
            try (OutputStream os = ex.getResponseBody()) {
                os.write(r.body());
            }
        }
    }
}
