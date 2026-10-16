package dev.ryvrook.restapi.rcon;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.EOFException;
import java.net.ProtocolException;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** The codec against byte arrays written out by hand from the protocol description. */
class PacketTest {
    /** A login with id 1 and password "pw". Length 12 = id 4 + type 4 + "pw" 2 + two nulls. */
    private static final byte[] LOGIN = {
            12, 0, 0, 0,
            1, 0, 0, 0,
            3, 0, 0, 0,
            'p', 'w', 0, 0};

    @Test
    void aLoginEncodesToTheBytesOfTheProtocol() {
        assertArrayEquals(LOGIN, new Packet(1, Packet.LOGIN, "pw").encode());
    }

    @Test
    void aLoginDecodesFromThoseBytes() throws Exception {
        Packet p = Packet.read(new ByteArrayInputStream(LOGIN));
        assertEquals(new Packet(1, 3, "pw"), p);
    }

    @Test
    void theFailedLoginAnswerHasIdMinusOne() {
        byte[] expected = {10, 0, 0, 0, -1, -1, -1, -1, 2, 0, 0, 0, 0, 0};
        assertArrayEquals(expected, new Packet(-1, Packet.LOGIN_ANSWER, "").encode());
    }

    @Test
    void mcrconsRequestIdIsLittleEndian() throws Exception {
        byte[] bytes = new Packet(0xBADC0DE, Packet.COMMAND, "help").encode();
        assertEquals((byte) 0xDE, bytes[4]);
        assertEquals((byte) 0xC0, bytes[5]);
        assertEquals((byte) 0xAD, bytes[6]);
        assertEquals((byte) 0x0B, bytes[7]);
        assertEquals(new Packet(0xBADC0DE, 2, "help"), Packet.read(new ByteArrayInputStream(bytes)));
    }

    @Test
    void twoPacketsInARowAreReadOneAfterTheOther() throws Exception {
        byte[] a = new Packet(5, Packet.COMMAND, "list").encode();
        byte[] b = new Packet(6, Packet.RESPONSE, "").encode();
        byte[] both = new byte[a.length + b.length];
        System.arraycopy(a, 0, both, 0, a.length);
        System.arraycopy(b, 0, both, a.length, b.length);
        ByteArrayInputStream in = new ByteArrayInputStream(both);
        assertEquals(new Packet(5, 2, "list"), Packet.read(in));
        assertEquals(new Packet(6, 0, ""), Packet.read(in));
        assertNull(Packet.read(in));
    }

    @Test
    void theBodyEndsAtTheFirstNull() throws Exception {
        byte[] bytes = {14, 0, 0, 0, 2, 0, 0, 0, 2, 0, 0, 0, 's', 'a', 'y', 0, 'x', 0};
        assertEquals("say", Packet.read(new ByteArrayInputStream(bytes)).body());
    }

    @Test
    void lengthsOutOfRangeAreRefused() {
        byte[] small = {9, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0};
        assertThrows(ProtocolException.class, () -> Packet.read(new ByteArrayInputStream(small)));
        byte[] large = {(byte) 0x0B, 0x10, 0, 0};
        assertEquals(4096 + 11, 0x100B);
        assertThrows(ProtocolException.class, () -> Packet.read(new ByteArrayInputStream(large)));
        byte[] negative = {-1, -1, -1, -1};
        assertThrows(ProtocolException.class, () -> Packet.read(new ByteArrayInputStream(negative)));
    }

    @Test
    void theLargestRequestIsAccepted() throws Exception {
        String body = "a".repeat(Packet.MAX_BODY_IN);
        byte[] bytes = new byte[4 + 4096 + 10];
        bytes[0] = (byte) 0x0A;
        bytes[1] = 0x10;
        bytes[8] = 2;
        System.arraycopy(body.getBytes(StandardCharsets.US_ASCII), 0, bytes, 12, 4096);
        assertEquals(body, Packet.read(new ByteArrayInputStream(bytes)).body());
    }

    @Test
    void aPacketCutShortIsAnError() {
        byte[] cut = {12, 0, 0, 0, 1, 0, 0, 0, 3, 0};
        assertThrows(EOFException.class, () -> Packet.read(new ByteArrayInputStream(cut)));
        byte[] head = {12, 0};
        assertThrows(EOFException.class, () -> Packet.read(new ByteArrayInputStream(head)));
    }

    @Test
    void anEmptyStreamIsTheEnd() throws Exception {
        assertNull(Packet.read(new ByteArrayInputStream(new byte[0])));
    }

    @Test
    void repliesNeverPassTheLimit() {
        assertEquals(4096, new Packet(1, 0, "a".repeat(Packet.MAX_BODY_OUT)).encode().length - 4);
        assertThrows(IllegalArgumentException.class, () -> new Packet(1, 0, "a".repeat(Packet.MAX_BODY_OUT + 1)).encode());
    }

    @Test
    void splitPiecesJoinBackExactly() {
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < 400; i++) {
            b.append("line ").append(i).append(" of the help output, with some width to it\n");
        }
        String text = b.toString();
        List<String> pieces = Packet.split(text, Packet.MAX_BODY_OUT);
        assertTrue(pieces.size() > 1);
        assertEquals(text, String.join("", pieces));
        for (String p : pieces) {
            assertTrue(p.getBytes(StandardCharsets.UTF_8).length <= Packet.MAX_BODY_OUT);
        }
        for (int i = 0; i < pieces.size() - 1; i++) {
            assertTrue(pieces.get(i).endsWith("\n"), "piece " + i + " ends on a line break");
        }
    }

    @Test
    void splitNeverCutsACharacter() {
        String text = "é€😀".repeat(500);
        List<String> pieces = Packet.split(text, 10);
        assertEquals(text, String.join("", pieces));
        for (String p : pieces) {
            byte[] bytes = p.getBytes(StandardCharsets.UTF_8);
            assertTrue(bytes.length <= 10);
            assertEquals(p, new String(bytes, StandardCharsets.UTF_8));
        }
    }

    @Test
    void aLongLineWithoutBreaksIsCutAtTheLimit() {
        List<String> pieces = Packet.split("x".repeat(10_000), Packet.MAX_BODY_OUT);
        assertEquals(List.of(4086, 4086, 1828), pieces.stream().map(String::length).toList());
    }

    @Test
    void anEmptyReplyIsOneEmptyPiece() {
        assertEquals(List.of(""), Packet.split("", Packet.MAX_BODY_OUT));
        assertEquals(List.of("short"), Packet.split("short", Packet.MAX_BODY_OUT));
    }
}
