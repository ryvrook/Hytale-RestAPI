package dev.ryvrook.restapi.events;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpServer;
import dev.ryvrook.restapi.config.Settings;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class WebhooksTest {
    @TempDir
    Path dir;

    /** Every POST the fake target saw. */
    final List<Map<String, String>> sent = Collections.synchronizedList(new ArrayList<>());
    final List<String> bodies = Collections.synchronizedList(new ArrayList<>());
    final List<Long> sleeps = Collections.synchronizedList(new ArrayList<>());
    final BlockingQueue<Integer> answers = new ArrayBlockingQueue<>(100);

    @BeforeEach
    void defaults() {
        EventsSettings.ENABLED.key();
        Settings.useDefaults();
    }

    @AfterEach
    void reset() {
        Settings.useDefaults();
    }

    private Webhooks hooks(Webhooks.Resolver resolver) {
        return new Webhooks(dir.resolve("webhooks.json"), (url, headers, body, timeout) -> {
            sent.add(headers);
            bodies.add(new String(body, StandardCharsets.UTF_8));
            Integer status = answers.poll();
            return status == null || status < 0 ? new Webhooks.Result(0, "network", 1) : new Webhooks.Result(status, null, 1);
        }, resolver, sleeps::add, (what, e) -> { });
    }

    private static Webhooks.Resolver publicDns() {
        return host -> new InetAddress[]{InetAddress.getByAddress(host, new byte[]{93, (byte) 184, (byte) 215, 14})};
    }

    private static EventRing.Event event(String type) {
        JsonObject d = new JsonObject();
        d.addProperty("player", "Steve");
        d.addProperty("world", "default");
        return new EventRing.Event(42, type, null, 0, d);
    }

    @Test
    void signsWithHmacSha256() {
        // RFC 4231 test case 2.
        assertEquals("5bdcc146bf60754e6a042426089575c75a003f089d2739839dec58b964ec3843",
                Webhooks.sign("Jefe", "what do ya want for nothing?".getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    void privateAddressesAreRecognised() throws Exception {
        for (String a : List.of("127.0.0.1", "10.1.2.3", "172.16.0.1", "192.168.1.1", "169.254.1.1", "0.0.0.0",
                "100.64.0.1", "::1", "fe80::1", "fd00::1", "::ffff:192.168.0.1", "224.0.0.1", "198.18.0.1")) {
            assertTrue(Webhooks.isPrivate(InetAddress.getByName(a)), a);
        }
        for (String a : List.of("93.184.215.14", "1.1.1.1", "2606:4700:4700::1111", "100.128.0.1")) {
            assertFalse(Webhooks.isPrivate(InetAddress.getByName(a)), a);
        }
    }

    @Test
    void urlsMustBeHttpWithAHost() {
        assertEquals("example.com", Webhooks.checkUrl("https://example.com/hook").getHost());
        for (String bad : new String[]{null, "ftp://example.com/x", "https:///x", "example.com/x", "http://user:pw@example.com/",
                "javascript:alert(1)", "https://exa mple.com"}) {
            assertThrows(IllegalArgumentException.class, () -> Webhooks.checkUrl(bad), String.valueOf(bad));
        }
    }

    @Test
    void longUrlPartsAreHidden() {
        assertEquals("https://discord.com/api/webhooks/***/***",
                Webhooks.masked("https://discord.com/api/webhooks/123456789012345678901/abcdefghijklmnopqrstuvwxyz"));
        assertEquals("http://127.0.0.1:9000/hook?***", Webhooks.masked("http://127.0.0.1:9000/hook?token=abc"));
    }

    @Test
    void theTargetCheckLooksAtEveryAddress() {
        Webhooks w = hooks(host -> new InetAddress[]{InetAddress.getByAddress(host, new byte[]{1, 1, 1, 1}),
                InetAddress.getByAddress(host, new byte[]{10, 0, 0, 1})});
        assertEquals("private_target", w.targetProblem("mixed.example"));
        assertNull(hooks(publicDns()).targetProblem("example.com"));
        assertEquals("unresolved", hooks(h -> {
            throw new UnknownHostException(h);
        }).targetProblem("nowhere.invalid"));
    }

    @Test
    void hooksAreSavedOwnerOnlyAndLoadBack() throws Exception {
        Webhooks w = hooks(publicDns());
        Webhooks.Hook h = w.add("https://example.com/a", Set.of("join", "leave"), "0123456789abcdef", "json", "grafana");
        Path file = dir.resolve("webhooks.json");
        assertTrue(Files.readString(file).contains("\"secret\": \"0123456789abcdef\""));
        if (Files.getFileStore(file).supportsFileAttributeView("posix")) {
            assertEquals("rw-------", java.nio.file.attribute.PosixFilePermissions.toString(Files.getPosixFilePermissions(file)));
        }
        Webhooks again = hooks(publicDns());
        assertTrue(again.load().isEmpty());
        Webhooks.Hook back = again.get(h.id());
        assertEquals(h, back);
        assertTrue(back.viaApi());
        assertTrue(again.remove(h.id()));
        assertFalse(again.remove(h.id()));
        assertEquals(0, hooks(publicDns()).load().size() + hooks(publicDns()).size());
    }

    @Test
    void brokenEntriesAreSkippedWithAReason() throws Exception {
        Files.writeString(dir.resolve("webhooks.json"), """
                {"hooks": [
                  {"id": "good", "url": "http://10.0.0.5/hook", "types": ["death"]},
                  {"id": "bad", "url": "ftp://x/y"},
                  {"id": "typo", "url": "https://example.com/", "types": ["jion"]},
                  {"id": "good", "url": "https://example.com/2"}
                ]}""");
        Webhooks w = hooks(publicDns());
        List<String> problems = w.load();
        assertEquals(3, problems.size(), problems.toString());
        assertEquals(1, w.size());
        assertFalse(w.get("good").viaApi(), "a hand written hook may point at the LAN");
        assertEquals(Set.of("death"), w.get("good").types());
    }

    @Test
    void retriesNetworkErrorsAnd5xxWithDoublingWaits() throws Exception {
        Webhooks w = hooks(publicDns());
        Webhooks.Hook h = w.add("https://example.com/a", Set.of("join"), null, "json", "k");
        answers.addAll(List.of(-1, 503, 204));
        w.start();
        w.offer(event("join"));
        w.offer(event("chat"));
        waitFor(() -> w.state(h.id()).delivered.get() == 1);
        w.stop(0);
        assertEquals(3, sent.size());
        assertEquals(List.of(2000L, 4000L), sleeps);
        assertEquals(204, w.state(h.id()).lastStatus);
        assertEquals(2L, w.totals().get("retried"));
        assertEquals(1L, w.totals().get("ok"));
        assertFalse(sent.get(0).containsKey("X-RestAPI-Signature"), "no secret, no signature");
        assertEquals("join", sent.get(0).get("X-RestAPI-Event"));
    }

    @Test
    void a404FailsAtOnce() throws Exception {
        Webhooks w = hooks(publicDns());
        Webhooks.Hook h = w.add("https://example.com/a", Set.of("join"), "0123456789abcdef", "json", "k");
        answers.add(404);
        w.start();
        w.offer(event("join"));
        waitFor(() -> w.state(h.id()).failed.get() == 1);
        w.stop(0);
        assertEquals(1, sent.size());
        String body = bodies.get(0);
        assertEquals("sha256=" + Webhooks.sign("0123456789abcdef", body.getBytes(StandardCharsets.UTF_8)),
                sent.get(0).get("X-RestAPI-Signature"));
        assertEquals("Steve", JsonParser.parseString(body).getAsJsonObject().get("player").getAsString());
    }

    @Test
    void apiHooksNeverReachAPrivateTargetAtDelivery() throws Exception {
        Webhooks w = hooks(host -> new InetAddress[]{InetAddress.getByAddress(host, new byte[]{(byte) 192, (byte) 168, 0, 9})});
        Webhooks.Hook h = w.add("https://rebinding.example/a", Set.of("join"), null, "json", "k");
        Webhooks.Result r = w.test(h);
        assertEquals("private_target", r.error());
        assertFalse(r.retry());
        assertTrue(sent.isEmpty());
        Settings.override(EventsSettings.WEBHOOKS_PRIVATE, true);
        answers.add(200);
        assertTrue(w.test(h).ok());
    }

    @Test
    void aFullQueueDropsTheOldest() throws Exception {
        Settings.override(EventsSettings.WEBHOOKS_QUEUE, 10);
        java.util.concurrent.CountDownLatch release = new java.util.concurrent.CountDownLatch(1);
        List<Long> delivered = Collections.synchronizedList(new ArrayList<>());
        Webhooks w = new Webhooks(dir.resolve("webhooks.json"), (url, headers, body, timeout) -> {
            release.await();
            delivered.add(JsonParser.parseString(new String(body, StandardCharsets.UTF_8)).getAsJsonObject().get("id").getAsLong());
            return new Webhooks.Result(200, null, 1);
        }, publicDns(), sleeps::add, (what, e) -> { });
        Webhooks.Hook h = w.add("https://example.com/a", Set.of("chat"), null, "json", "k");
        w.start();
        w.offer(new EventRing.Event(0, "chat", null, 0, new JsonObject()));
        waitFor(() -> w.queued(h.id()) == 0);
        for (int i = 1; i <= 15; i++) {
            w.offer(new EventRing.Event(i, "chat", null, 0, new JsonObject()));
        }
        assertEquals(10, w.queued(h.id()));
        assertEquals(5L, w.totals().get("dropped"));
        release.countDown();
        waitFor(() -> delivered.size() == 11);
        w.stop(0);
        assertEquals(List.of(0L, 6L, 7L, 8L, 9L, 10L, 11L, 12L, 13L, 14L, 15L), delivered);
    }

    @Test
    void discordBodiesMentionNobodyAndFitTheLimit() {
        Webhooks.Hook h = new Webhooks.Hook("d", "https://discord.com/api/webhooks/1/x", Set.of("chat"), null,
                "discord", null, null, true);
        JsonObject d = new JsonObject();
        d.addProperty("player", "Steve");
        d.addProperty("text", "@everyone " + "x".repeat(3000));
        JsonObject body = JsonParser.parseString(new String(Webhooks.body(h, new EventRing.Event(1, "chat", null, 0, d)),
                StandardCharsets.UTF_8)).getAsJsonObject();
        assertEquals(Webhooks.DISCORD_MAX, body.get("content").getAsString().length());
        assertTrue(body.get("content").getAsString().startsWith("<Steve> @everyone"));
        assertEquals(0, body.getAsJsonObject("allowed_mentions").getAsJsonArray("parse").size());

        JsonObject k = new JsonObject();
        k.addProperty("player", "Steve");
        k.addProperty("world", "default");
        k.addProperty("killer", "Alex");
        assertEquals("Steve was killed by Alex in default.", Webhooks.discordText(new EventRing.Event(1, "death", "player", 0, k)));
        assertEquals("The server is shutting down.", Webhooks.discordText(new EventRing.Event(1, "server", "stopping", 0, new JsonObject())));
    }

    @Test
    void backoffDoubles() {
        assertEquals(2000, Webhooks.backoffMillis(1, 2));
        assertEquals(4000, Webhooks.backoffMillis(2, 2));
        assertEquals(8000, Webhooks.backoffMillis(3, 2));
    }

    @Test
    void theJdkClientDeliversASignedPostAndNeverFollowsRedirects() throws Exception {
        Settings.override(EventsSettings.WEBHOOKS_PRIVATE, true);
        BlockingQueue<String[]> got = new ArrayBlockingQueue<>(4);
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/hook", ex -> {
            byte[] b = ex.getRequestBody().readAllBytes();
            got.add(new String[]{ex.getRequestHeaders().getFirst("X-RestAPI-Signature"), new String(b, StandardCharsets.UTF_8),
                    ex.getRequestHeaders().getFirst("X-RestAPI-Event")});
            ex.sendResponseHeaders(204, -1);
            ex.close();
        });
        server.createContext("/moved", ex -> {
            ex.getResponseHeaders().set("Location", "/hook");
            ex.sendResponseHeaders(302, -1);
            ex.close();
        });
        server.start();
        try {
            int port = server.getAddress().getPort();
            Webhooks w = Webhooks.standard(dir.resolve("webhooks.json"), (a, b) -> { });
            Webhooks.Hook h = w.add("http://127.0.0.1:" + port + "/hook", Set.of("world"), "0123456789abcdef0123", "json", "k");
            w.start();
            w.offer(new EventRing.Event(7, "world", "added", 0, new JsonObject()));
            String[] r = got.poll(10, TimeUnit.SECONDS);
            assertNotNull(r);
            assertEquals("sha256=" + Webhooks.sign("0123456789abcdef0123", r[1].getBytes(StandardCharsets.UTF_8)), r[0]);
            assertEquals("world.added", r[2]);
            waitFor(() -> w.state(h.id()).delivered.get() == 1);
            w.stop(0);

            Webhooks.JdkTransport t = new Webhooks.JdkTransport();
            Webhooks.Result moved = t.post(URI.create("http://127.0.0.1:" + port + "/moved"), Map.of(),
                    new byte[0], Duration.ofSeconds(5));
            assertTrue(t.open());
            t.close();
            assertFalse(t.open(), "the client is closed and dropped");
            assertEquals(302, moved.status());
            assertFalse(moved.ok());
            assertFalse(moved.retry());
            assertTrue(got.isEmpty(), "the redirect was not followed");
        } finally {
            server.stop(0);
        }
    }

    static void waitFor(java.util.function.BooleanSupplier c) throws InterruptedException {
        long until = System.currentTimeMillis() + 10_000;
        while (!c.getAsBoolean()) {
            assertTrue(System.currentTimeMillis() < until, "timed out");
            Thread.sleep(10);
        }
    }
}
