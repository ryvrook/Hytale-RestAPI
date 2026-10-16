package dev.ryvrook.restapi.suite;

import com.google.gson.JsonObject;
import dev.ryvrook.restapi.http.ApiException;
import dev.ryvrook.restapi.http.Request;
import dev.ryvrook.restapi.http.Response;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

class SharedTest {
    record Inner(String name, int n) {
    }

    record Outer(UUID id, Inner inner, List<String> tags, Thread.State state, double ratio) {
    }

    enum Colour { DEEP_RED }

    @Test
    void recordsBecomeJsonByComponent() {
        UUID id = UUID.randomUUID();
        JsonObject o = Json.of(new Outer(id, new Inner("a", 2), List.of("x"), Thread.State.NEW, Double.NaN)).getAsJsonObject();
        assertEquals(id.toString(), o.get("id").getAsString());
        assertEquals(2, o.getAsJsonObject("inner").get("n").getAsInt());
        assertEquals("x", o.getAsJsonArray("tags").get(0).getAsString());
        assertEquals("new", o.get("state").getAsString());
        assertTrue(o.get("ratio").isJsonNull());
        assertEquals("deep_red", Json.of(Colour.DEEP_RED).getAsString());
        assertEquals(3, Json.of(new long[]{1, 2, 3}).getAsJsonArray().size());
    }

    @Test
    void timesTakeMillisOrInstants() {
        assertEquals(42L, Json.time("42", "from", 0));
        assertEquals(1760616000000L, Json.time("2025-10-16T12:00:00Z", "from", 0));
        assertEquals(7L, Json.time(null, "from", 7));
        ApiException e = assertThrows(ApiException.class, () -> Json.time("yesterday", "from", 0));
        assertEquals("bad_time", e.code());
    }

    @Test
    void uuidsAreStrict() {
        assertNull(Json.uuid("Steve"));
        assertNull(Json.uuid("not-a-uuid-but-thirty-six-characters"));
        UUID u = UUID.randomUUID();
        assertEquals(u, Json.uuid(u.toString()));
    }

    @Test
    void limitsAreCutAndChecked() {
        Limits l = new Limits(50, 200, 1000);
        assertEquals(50, l.limit(call("/x")));
        assertEquals(200, l.limit(call("/x?limit=5000")));
        assertEquals(7, l.limit(call("/x?limit=7")));
        assertEquals("bad_limit", assertThrows(ApiException.class, () -> l.limit(call("/x?limit=0"))).code());
        assertEquals("bad_number", assertThrows(ApiException.class, () -> l.limit(call("/x?limit=ten"))).code());
        assertEquals(3, l.offset(call("/x?offset=3")));
        assertEquals("bad_offset", assertThrows(ApiException.class, () -> l.offset(call("/x?offset=-1"))).code());
    }

    private static dev.ryvrook.restapi.http.Call call(String path) {
        return new dev.ryvrook.restapi.http.Call(Request.of("GET", path, "127.0.0.1"), Calls.KEY, "127.0.0.1", Map.of());
    }

    @Test
    void namesTryUuidThenEachLookup() {
        UUID steve = UUID.randomUUID();
        Names names = new Names("Coinage", n -> Optional.empty(), n -> n.equals("Steve") ? Optional.of(steve) : Optional.empty());
        assertEquals(steve, names.resolve("Steve"));
        assertEquals(steve, names.resolve(steve.toString()));
        ApiException e = assertThrows(ApiException.class, () -> names.resolve("Alex"));
        assertEquals(404, e.status());
        assertEquals("player_not_found", e.code());
        assertTrue(e.getMessage().contains("Coinage"));
    }

    @Test
    void aLookupThatFailsCountsAsNotFound() {
        Names names = new Names("TrackerAPI", n -> {
            throw new IllegalStateException("index not built");
        });
        assertEquals("player_not_found", assertThrows(ApiException.class, () -> names.resolve("Steve")).code());
    }

    @Test
    void waitsGiveUpWith504() {
        ApiException e = assertThrows(ApiException.class, () -> Waits.await(new CompletableFuture<String>(), "TrackerAPI", 20));
        assertEquals(504, e.status());
        assertEquals("sibling_timeout", e.code());
        assertEquals("done", Waits.await(CompletableFuture.completedFuture("done"), "x", 20));
    }

    @Test
    void aReplayRunsNothingAndSaysSo() {
        AtomicLong now = new AtomicLong(1000);
        Replays r = new Replays(now::get);
        AtomicInteger runs = new AtomicInteger();
        Response first = r.run("grafana", "abc", 60_000, () -> {
            runs.incrementAndGet();
            JsonObject o = new JsonObject();
            o.addProperty("replayed", false);
            return Response.ok(o);
        });
        Response second = r.run("grafana", "abc", 60_000, () -> {
            runs.incrementAndGet();
            return Response.ok(new JsonObject());
        });
        assertEquals(1, runs.get());
        assertFalse(Calls.json(first).get("replayed").getAsBoolean());
        assertTrue(Calls.json(second).get("replayed").getAsBoolean());
        assertEquals("true", second.headers().get("Idempotency-Replayed"));
        // Another key name with the same idempotency key is another write.
        r.run("dash", "abc", 60_000, () -> {
            runs.incrementAndGet();
            return Response.ok(new JsonObject());
        });
        assertEquals(2, runs.get());
        // After the keep time the pair runs again.
        now.addAndGet(61_000);
        r.run("grafana", "abc", 60_000, () -> {
            runs.incrementAndGet();
            return Response.ok(new JsonObject());
        });
        assertEquals(3, runs.get());
    }

    @Test
    void withoutAnIdempotencyKeyEveryCallRuns() {
        Replays r = new Replays(() -> 0);
        AtomicInteger runs = new AtomicInteger();
        for (int i = 0; i < 3; i++) {
            r.run("grafana", null, 1000, () -> {
                runs.incrementAndGet();
                return Response.ok(new JsonObject());
            });
        }
        assertEquals(3, runs.get());
        assertEquals("bad_idempotency_key", assertThrows(ApiException.class,
                () -> r.run("grafana", "has space", 1000, () -> Response.noContent())).code());
    }

    @Test
    void aRefusalIsTheStoredAnswerAndACrashIsNot() {
        Replays r = new Replays(() -> 0);
        AtomicInteger runs = new AtomicInteger();
        assertThrows(ApiException.class, () -> r.run("k", "one", 1000, () -> {
            runs.incrementAndGet();
            throw ApiException.badRequest("bad_amount", "no");
        }));
        Response again = r.run("k", "one", 1000, () -> {
            runs.incrementAndGet();
            return Response.ok(new JsonObject());
        });
        assertEquals(1, runs.get());
        assertEquals(400, again.status());
        assertThrows(IllegalStateException.class, () -> r.run("k", "two", 1000, () -> {
            throw new IllegalStateException("crash");
        }));
        assertEquals(200, r.run("k", "two", 1000, () -> Response.ok(new JsonObject())).status());
    }

    @Test
    void aRetryWhileTheFirstRunsWaitsForIt() throws Exception {
        Replays r = new Replays(() -> 0);
        CountDownLatch inside = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger runs = new AtomicInteger();
        Thread first = Thread.ofVirtual().start(() -> r.run("k", "same", 1000, () -> {
            runs.incrementAndGet();
            inside.countDown();
            try {
                release.await(5, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return Response.ok(new JsonObject());
        }));
        assertTrue(inside.await(5, TimeUnit.SECONDS));
        CompletableFuture<Response> second = CompletableFuture.supplyAsync(() -> r.run("k", "same", 1000, () -> {
            runs.incrementAndGet();
            return Response.ok(new JsonObject());
        }));
        Thread.sleep(50);
        assertFalse(second.isDone());
        release.countDown();
        first.join();
        assertTrue(Calls.json(second.get(5, TimeUnit.SECONDS)).get("replayed").getAsBoolean());
        assertEquals(1, runs.get());
    }

    @Test
    void jsonFilesAreReadAgainOnlyWhenTheyChange(@TempDir Path dir) throws Exception {
        Path f = dir.resolve("stats.json");
        JsonFile file = new JsonFile(f, "Chronicle Stats");
        assertNull(file.readOrNull());
        ApiException missing = assertThrows(ApiException.class, file::read);
        assertEquals(503, missing.status());
        assertEquals("file_missing", missing.code());
        Files.writeString(f, "{\"online\": 3}");
        Files.setLastModifiedTime(f, FileTime.fromMillis(1_000_000));
        JsonObject first = file.read();
        assertEquals(3, first.get("online").getAsInt());
        assertSame(first, file.read());
        Files.writeString(f, "{\"online\": 4, \"x\": 1}");
        Files.setLastModifiedTime(f, FileTime.fromMillis(2_000_000));
        assertEquals(4, file.read().get("online").getAsInt());
        Files.writeString(f, "{broken");
        Files.setLastModifiedTime(f, FileTime.fromMillis(3_000_000));
        assertEquals("file_broken", assertThrows(ApiException.class, file::read).code());
    }
}
