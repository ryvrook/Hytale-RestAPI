package dev.ryvrook.restapi.http;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

class SseTest {
    @Test
    void multiLineDataBecomesSeveralDataLines() {
        assertEquals("event: log\nid: 7\ndata: one\ndata: two\n\n", Sse.format("log", "7", "one\ntwo"));
    }

    @Test
    void aNameOrIdCannotStartANewField() {
        assertEquals("event: a data: x\ndata: \n\n", Sse.format("a\ndata: x", null, ""));
    }

    @Test
    void theSinkWritesEventsAndComments() throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        Sse.Sink sink = Sse.sink(out);
        sink.comment("keep alive");
        sink.event("join", null, "{\"name\":\"Steve\"}");
        assertEquals(": keep alive\n\nevent: join\ndata: {\"name\":\"Steve\"}\n\n", out.toString(StandardCharsets.UTF_8));
    }

    @Test
    void aStreamResponseCarriesTheContentType() {
        Response r = Response.stream(sink -> { });
        assertEquals(Response.EVENT_STREAM, r.contentType());
        assertNotNull(r.with("X-Test", "1").stream(), "with keeps the stream");
    }
}
