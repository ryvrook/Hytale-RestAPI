package dev.ryvrook.restapi.http;

import dev.ryvrook.restapi.audit.AuditSink;
import dev.ryvrook.restapi.auth.KeyStore;
import dev.ryvrook.restapi.auth.Scope;
import dev.ryvrook.restapi.config.CoreSettings;
import dev.ryvrook.restapi.config.Settings;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.time.Clock;
import java.util.EnumSet;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** The JDK server on a free loopback port, with real sockets and no engine. */
class HttpHostTest {
    @TempDir
    Path dir;
    private HttpHost host;
    private String token;
    private final HttpClient client = HttpClient.newHttpClient();

    @BeforeEach
    void start() throws Exception {
        CoreSettings.HTTP_PORT.key();
        Settings.useDefaults();
        KeyStore keys = new KeyStore(dir.resolve("keys.json"));
        token = keys.create("test", EnumSet.allOf(Scope.class), "test").token();
        Router router = new Router();
        router.mount("t", List.of(
                Route.of("t", "GET", "/api/ping", Scope.READ, Access.READ, "ping", c -> Response.text(200, "pong")),
                Route.of("t", "POST", "/api/echo", Scope.READ, Access.WRITE, "echo", c -> Response.text(200, c.request().bodyText())),
                Route.of("t", "GET", "/api/stream", Scope.EVENTS, Access.READ, "stream", c -> Response.stream(sink -> {
                    sink.comment("hello");
                    for (int i = 1; i <= 3; i++) {
                        sink.event("tick", Integer.toString(i), "n=" + i);
                    }
                }))));
        Gate gate = new Gate(router, keys, AuditSink.NONE, Clock.systemUTC(), new RateLimiter(), new RateLimiter(), (w, e) -> { });
        host = new HttpHost(gate);
        host.start("127.0.0.1", 0);
    }

    @AfterEach
    void stop() {
        host.stop();
    }

    private HttpRequest.Builder req(String path) {
        return HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + host.port() + path))
                .header("Authorization", "Bearer " + token);
    }

    @Test
    void answersOverARealSocket() throws Exception {
        HttpResponse<String> r = client.send(req("/api/ping").build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(200, r.statusCode());
        assertEquals("pong", r.body());
        assertEquals("no-store", r.headers().firstValue("Cache-Control").orElse(null));
    }

    @Test
    void headHasNoBody() throws Exception {
        HttpResponse<String> r = client.send(req("/api/ping").method("HEAD", HttpRequest.BodyPublishers.noBody()).build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(200, r.statusCode());
        assertEquals("", r.body());
    }

    @Test
    void bodiesOverTheLimitGet413() throws Exception {
        Settings.override(CoreSettings.HTTP_MAX_BODY_BYTES, 1024);
        HttpResponse<String> ok = client.send(req("/api/echo").POST(HttpRequest.BodyPublishers.ofString("x".repeat(1000))).build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(200, ok.statusCode());
        HttpResponse<String> big = client.send(req("/api/echo").POST(HttpRequest.BodyPublishers.ofString("x".repeat(5000))).build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(413, big.statusCode());
    }

    @Test
    void streamsSendServerSentEvents() throws Exception {
        HttpResponse<String> r = client.send(req("/api/stream").build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(200, r.statusCode());
        assertTrue(r.headers().firstValue("Content-Type").orElse("").startsWith("text/event-stream"));
        assertEquals(": hello\n\nevent: tick\nid: 1\ndata: n=1\n\nevent: tick\nid: 2\ndata: n=2\n\nevent: tick\nid: 3\ndata: n=3\n\n",
                r.body());
    }

    @Test
    void streamsCanBeTurnedOff() throws Exception {
        Settings.override(CoreSettings.HTTP_MAX_STREAMS, 0);
        HttpResponse<String> r = client.send(req("/api/stream").build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(503, r.statusCode());
    }

    @Test
    void stopFreesThePort() throws Exception {
        int port = host.port();
        host.stop();
        assertEquals(-1, host.port());
        host.start("127.0.0.1", port);
        assertEquals(port, host.port());
    }
}
