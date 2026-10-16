package dev.ryvrook.restapi.rcon;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.ryvrook.restapi.audit.AuditEntry;
import dev.ryvrook.restapi.auth.KeyStore;
import dev.ryvrook.restapi.auth.Scope;
import dev.ryvrook.restapi.config.Settings;
import dev.ryvrook.restapi.exec.CommandService;
import dev.ryvrook.restapi.text.Messages;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.*;

/** The listener on a free loopback port with real sockets, a key store on disk and a stand in command service. */
class RconServerTest {
    @TempDir
    Path dir;
    private KeyStore keys;
    private RconServer server;
    private String token;
    private final StandInCommands commands = new StandInCommands();
    private volatile Optional<CommandService> service = Optional.of(commands);
    private final List<AuditEntry> audit = new CopyOnWriteArrayList<>();
    private final List<String> log = new CopyOnWriteArrayList<>();

    @BeforeEach
    void start() throws Exception {
        RconSettings.ENABLED.key();
        Settings.useDefaults();
        Messages.reset();
        keys = new KeyStore(dir.resolve("keys.json"));
        token = keys.create("ops", EnumSet.of(Scope.COMMANDS), "test").token();
        server = new RconServer(new RconServer.Context(keys, audit::add, () -> service, Clock.systemUTC(), log::add));
        server.start("127.0.0.1", 0);
    }

    @AfterEach
    void stop() {
        server.stop();
        Settings.useDefaults();
    }

    private RconClient client() throws Exception {
        return new RconClient(server.port());
    }

    @Test
    void aKeyWithTheCommandsScopeLogsIn() throws Exception {
        try (RconClient c = client()) {
            assertEquals(7, c.login(token));
            assertEquals("[Server] hello", c.command(10, "say hello"));
        }
        StandInCommands.Run run = commands.runs.getFirst();
        assertEquals("ops", run.keyName());
        assertEquals("rcon", run.source());
        assertEquals("127.0.0.1", run.client());
        assertEquals("say hello", run.line());
        assertNull(run.idempotencyKey());
        assertTrue(audit.stream().anyMatch(e -> e.source().equals("rcon") && e.key().equals("ops")
                && e.action().equals("login") && e.detail().equals("ok")));
    }

    @Test
    void aWrongPasswordAnswersMinusOne() throws Exception {
        try (RconClient c = client()) {
            assertEquals(-1, c.login("hunter2"));
            char last = token.charAt(token.length() - 1);
            assertEquals(-1, c.login(token.substring(0, token.length() - 1) + (last == 'A' ? 'B' : 'A')));
            assertEquals(7, c.login(token));
        }
        assertEquals(2, audit.stream().filter(e -> e.key().equals("unknown") && e.detail().equals("wrong key")).count());
        assertTrue(audit.stream().noneMatch(e -> e.detail().contains(token)));
    }

    @Test
    void aKeyWithoutTheCommandsScopeIsRefused() throws Exception {
        String reader = keys.create("reader", EnumSet.of(Scope.READ, Scope.CONSOLE), "test").token();
        try (RconClient c = client()) {
            assertEquals(-1, c.login(reader));
        }
        assertTrue(audit.stream().anyMatch(e -> e.key().equals("reader") && e.detail().equals("key lacks the commands scope")));
    }

    @Test
    void theKeysAllowFromApplies() throws Exception {
        Path file = dir.resolve("keys.json");
        JsonObject doc = JsonParser.parseString(Files.readString(file)).getAsJsonObject();
        doc.getAsJsonArray("keys").get(0).getAsJsonObject().add("allowFrom", JsonParser.parseString("[\"10.0.0.0/8\"]"));
        Files.writeString(file, doc.toString(), StandardCharsets.UTF_8);
        assertTrue(keys.load().isEmpty());
        try (RconClient c = client()) {
            assertEquals(-1, c.login(token));
        }
        assertTrue(audit.stream().anyMatch(e -> e.detail().equals("address not in allowFrom")));
    }

    @Test
    void aCommandBeforeLoginAnswersMinusOne() throws Exception {
        try (RconClient c = client()) {
            c.send(new Packet(3, Packet.COMMAND, "say hi"));
            Packet p = c.read();
            assertEquals(-1, p.id());
            assertEquals(Packet.LOGIN_ANSWER, p.type());
            c.send(new Packet(4, Packet.RESPONSE, ""));
            assertEquals(-1, c.read().id());
        }
        assertTrue(commands.runs.isEmpty());
    }

    @Test
    void helpOverOnePacketIsSplitAndJoinsBack() throws Exception {
        String expected = String.join("\n", StandInCommands.helpLines());
        assertTrue(expected.getBytes(StandardCharsets.UTF_8).length > 4096);
        try (RconClient c = client()) {
            c.login(token);
            c.send(new Packet(20, Packet.COMMAND, "/help"));
            c.send(new Packet(21, Packet.RESPONSE, ""));
            StringBuilder joined = new StringBuilder();
            int pieces = 0;
            Packet p;
            while ((p = c.read()).id() == 20) {
                assertEquals(Packet.RESPONSE, p.type());
                assertTrue(p.encode().length - 4 <= 4096);
                joined.append(p.body());
                pieces++;
            }
            assertEquals(21, p.id());
            assertEquals("", p.body());
            assertTrue(pieces >= 2, "pieces " + pieces);
            assertEquals(expected, joined.toString());
        }
        assertEquals("help", commands.runs.getFirst().line());
    }

    @Test
    void anUnknownTypeMarksTheEndTheWayMinecraftDoes() throws Exception {
        try (RconClient c = client()) {
            c.login(token);
            c.send(new Packet(30, Packet.COMMAND, "help"));
            c.send(new Packet(31, 200, ""));
            StringBuilder joined = new StringBuilder();
            Packet p;
            while ((p = c.read()).id() == 30) {
                joined.append(p.body());
            }
            assertEquals(31, p.id());
            assertEquals(Packet.RESPONSE, p.type());
            assertEquals("Unknown request c8", p.body());
            assertEquals(String.join("\n", StandInCommands.helpLines()), joined.toString());
        }
    }

    @Test
    void stopIsDeniedAndTheConnectionStaysUp() throws Exception {
        try (RconClient c = client()) {
            c.login(token);
            assertEquals("stop is on the deny list and was not run.", c.command(40, "stop"));
            assertEquals("stop now is on the deny list and was not run.", c.command(44, "/stop now"));
            assertEquals("[Server] still here", c.command(42, "say still here"));
        }
    }

    @Test
    void failuresAndTimeoutsSaySo() throws Exception {
        try (RconClient c = client()) {
            c.login(token);
            assertEquals("boom failed. The server log has the cause.", c.command(50, "boom"));
            assertEquals("started\nslow was still running after 5000 ms. The lines above are what it sent until then.",
                    c.command(52, "slow"));
            assertEquals("", c.command(54, "   "));
        }
    }

    @Test
    void withoutTheConsoleModuleCommandsAnswerWithTheReason() throws Exception {
        service = Optional.empty();
        try (RconClient c = client()) {
            assertEquals(7, c.login(token));
            assertEquals(Messages.get(RconText.NO_CONSOLE), c.command(60, "help"));
            service = Optional.of(commands);
            assertEquals("[Server] back", c.command(62, "say back"));
        }
    }

    @Test
    void aRevokedKeyEndsTheConnection() throws Exception {
        try (RconClient c = client()) {
            c.login(token);
            keys.revoke("ops");
            c.send(new Packet(70, Packet.COMMAND, "say hi"));
            assertEquals(Messages.get(RconText.KEY_GONE), c.read().body());
            assertTrue(c.closed());
        }
        assertTrue(commands.runs.isEmpty());
    }

    @Test
    void tooManyFailedLoginsCloseTheAddressOut() throws Exception {
        Settings.override(RconSettings.AUTH_FAILURES, 3);
        try (RconClient c = client()) {
            assertEquals(-1, c.login("a"));
            assertEquals(-1, c.login("b"));
            assertEquals(-1, c.login("c"));
            assertEquals(-1, c.login("d"));
            assertTrue(c.closed());
        }
        try (RconClient c = client()) {
            assertTrue(c.closed(), "a new connection from the same address is closed too");
        }
        assertTrue(log.stream().anyMatch(l -> l.contains("rcon.authFailuresPerMinute")));
    }

    @Test
    void connectionsPastTheCapAreClosed() throws Exception {
        Settings.override(RconSettings.MAX_CONNECTIONS, 2);
        try (RconClient a = client(); RconClient b = client()) {
            assertEquals(7, a.login(token));
            assertEquals(7, b.login(token));
            try (RconClient c = client()) {
                assertTrue(c.closed());
            }
            assertEquals(2, server.connections());
        }
    }

    @Test
    void anIdleConnectionIsClosed() throws Exception {
        Settings.override(RconSettings.IDLE_SECONDS, 1);
        try (RconClient c = client()) {
            c.login(token);
            c.socket.setSoTimeout(4_000);
            assertTrue(c.closed());
        }
    }

    @Test
    void anOversizedPacketEndsTheConnection() throws Exception {
        try (RconClient c = client()) {
            c.sendRaw(new byte[]{(byte) 0x0B, 0x10, 0, 0});
            assertTrue(c.closed());
        }
        assertTrue(log.stream().anyMatch(l -> l.contains("outside 10 to 4106")));
    }

    @Test
    void stopClosesTheOpenConnections() throws Exception {
        try (RconClient c = client()) {
            c.login(token);
            server.stop();
            assertTrue(c.closed());
            assertEquals(-1, server.port());
            assertNull(server.boundTo());
        }
    }

    @Test
    void aHostNameIsNotABindAddress() {
        RconServer other = new RconServer(new RconServer.Context(keys, e -> { }, Optional::empty, Clock.systemUTC(), l -> { }));
        assertThrows(java.io.IOException.class, () -> other.start("localhost", 0));
    }

    @Test
    void replyTextFollowsTheOutcome() {
        var done = new dev.ryvrook.restapi.exec.CommandResult("r", dev.ryvrook.restapi.exec.CommandResult.Outcome.DONE,
                List.of("a", "b"), 1, false);
        assertEquals("a\nb", RconSession.text(done, "list", 5000));
        assertEquals("give Steve Rock 3", RconSession.shown("  give Steve Rock 3 "));
        assertEquals(60, RconSession.shown("say " + "x".repeat(100)).length());
    }
}
