package dev.ryvrook.restapi.console;

import com.hypixel.hytale.server.core.Message;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class CapturingSenderTest {
    @Test
    void theNameCarriesTheKeyAndLinesAreSplit() {
        List<String> got = new ArrayList<>();
        CapturingSender s = new CapturingSender("panel", List.of("*"), Message::getRawText, got::add);
        assertEquals("RestAPI:panel", s.getUsername());
        assertEquals(s.getUuid(), new CapturingSender("panel", List.of(), m -> "", x -> { }).getUuid());
        s.sendMessage(Message.raw("one\ntwo\r\nthree"));
        s.sendMessage(null);
        assertEquals(List.of("one", "two", "three"), got);
    }

    @Test
    void aFormatterThatThrowsFallsBackToTheRawText() {
        List<String> got = new ArrayList<>();
        CapturingSender s = new CapturingSender("panel", List.of("*"), m -> {
            throw new IllegalStateException("no i18n");
        }, got::add);
        s.sendMessage(Message.raw("plain"));
        assertEquals(List.of("plain"), got);
    }

    @Test
    void permissionRules() {
        List<String> rules = List.of("hytale.command.*", "-hytale.command.stop", "feats.admin");
        assertTrue(CapturingSender.allowed(rules, "hytale.command.say"));
        assertTrue(CapturingSender.allowed(rules, "hytale.command"));
        assertFalse(CapturingSender.allowed(rules, "hytale.command.stop"));
        assertFalse(CapturingSender.allowed(rules, "hytale.commandx"));
        assertTrue(CapturingSender.allowed(rules, "Feats.Admin"));
        assertFalse(CapturingSender.allowed(rules, "feats.admin.reset"));
        assertTrue(CapturingSender.allowed(List.of("*"), "anything.at.all"));
        assertFalse(CapturingSender.allowed(List.of("*", "-restapi.*"), "restapi.admin.keys"));
        assertFalse(CapturingSender.allowed(List.of(), "x"));
        CapturingSender s = new CapturingSender("k", List.of(), m -> "", x -> { });
        assertTrue(s.hasPermission("x", true), "with no rules the engine's default answers");
        assertFalse(s.hasPermission("x"));
    }
}
