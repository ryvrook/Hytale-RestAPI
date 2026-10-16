package dev.ryvrook.restapi.rcon;

import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.net.ProtocolException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * One Source RCON packet. On the wire it is an int32 length of the rest, an int32 request id, an int32 type, the
 * body and two null bytes, little endian. The length counts the id, the type, the body and both nulls, so the
 * smallest packet says 10.
 *
 * <p>Requests may carry up to {@link #MAX_BODY_IN} body bytes, more than the 1446 Minecraft takes. A length above
 * that plus 10, or under 10, ends the connection. Replies carry at most {@link #MAX_BODY_OUT} bytes, so the length
 * field never passes 4096, the limit in Valve's description that mcrcon checks. Longer output goes out as several
 * type 0 packets with the same id, see {@link #split}.
 *
 * @param id   the request id, echoed in every reply, or -1 in the answer to a failed login
 * @param type {@link #RESPONSE}, {@link #COMMAND} (also the login answer) or {@link #LOGIN}
 * @param body the text, decoded as UTF-8 up to the first null byte
 */
public record Packet(int id, int type, String body) {
    public static final int RESPONSE = 0;
    public static final int COMMAND = 2;
    public static final int LOGIN_ANSWER = 2;
    public static final int LOGIN = 3;

    public static final int MAX_BODY_IN = 4096;
    public static final int MAX_BODY_OUT = 4096 - 10;
    static final int MIN_LENGTH = 10;
    static final int MAX_LENGTH = MAX_BODY_IN + 10;

    public Packet {
        body = body == null ? "" : body;
    }

    /** The bytes to send. Throws when the body is longer than a reply may be. */
    public byte[] encode() {
        byte[] text = body.getBytes(StandardCharsets.UTF_8);
        if (text.length > MAX_BODY_OUT) {
            throw new IllegalArgumentException("An RCON reply body is at most " + MAX_BODY_OUT + " bytes, this one has " + text.length);
        }
        ByteBuffer b = ByteBuffer.allocate(4 + 8 + text.length + 2).order(ByteOrder.LITTLE_ENDIAN);
        b.putInt(8 + text.length + 2).putInt(id).putInt(type).put(text).put((byte) 0).put((byte) 0);
        return b.array();
    }

    /**
     * Reads one packet. Returns null when the stream ends cleanly before a new packet starts. Throws
     * {@link ProtocolException} for a length out of range and {@link EOFException} for a packet cut short.
     */
    public static Packet read(InputStream in) throws IOException {
        byte[] head = new byte[4];
        int first = in.read();
        if (first < 0) {
            return null;
        }
        head[0] = (byte) first;
        fill(in, head, 1, 3);
        int length = ByteBuffer.wrap(head).order(ByteOrder.LITTLE_ENDIAN).getInt();
        if (length < MIN_LENGTH || length > MAX_LENGTH) {
            throw new ProtocolException("RCON packet length " + length + " is outside " + MIN_LENGTH + " to " + MAX_LENGTH);
        }
        byte[] rest = new byte[length];
        fill(in, rest, 0, length);
        ByteBuffer b = ByteBuffer.wrap(rest).order(ByteOrder.LITTLE_ENDIAN);
        int id = b.getInt();
        int type = b.getInt();
        int end = 8;
        int limit = length - 1;
        while (end < limit && rest[end] != 0) {
            end++;
        }
        return new Packet(id, type, new String(rest, 8, end - 8, StandardCharsets.UTF_8));
    }

    private static void fill(InputStream in, byte[] into, int off, int len) throws IOException {
        int at = off;
        while (at < off + len) {
            int n = in.read(into, at, off + len - at);
            if (n < 0) {
                throw new EOFException("RCON packet cut short");
            }
            at += n;
        }
    }

    /**
     * Cuts a reply into bodies of at most maxBytes UTF-8 bytes. A cut never splits a character and falls after a
     * line break when one sits in the second half of the piece, so the pieces joined in order give the text back
     * exactly. An empty text gives one empty body.
     */
    public static List<String> split(String text, int maxBytes) {
        if (maxBytes < 4) {
            throw new IllegalArgumentException("maxBytes must be at least 4");
        }
        List<String> out = new ArrayList<>();
        if (text == null || text.isEmpty()) {
            out.add("");
            return out;
        }
        int start = 0;
        while (start < text.length()) {
            int at = start;
            int bytes = 0;
            int lastBreak = -1;
            while (at < text.length()) {
                int cp = text.codePointAt(at);
                int size = utf8Size(cp);
                if (bytes + size > maxBytes) {
                    break;
                }
                bytes += size;
                at += Character.charCount(cp);
                if (cp == '\n') {
                    lastBreak = at;
                }
            }
            if (at < text.length() && lastBreak > start && lastBreak - start >= (at - start) / 2) {
                at = lastBreak;
            }
            out.add(text.substring(start, at));
            start = at;
        }
        return out;
    }

    private static int utf8Size(int cp) {
        if (cp < 0x80) {
            return 1;
        }
        if (cp < 0x800) {
            return 2;
        }
        return cp < 0x10000 ? 3 : 4;
    }
}
