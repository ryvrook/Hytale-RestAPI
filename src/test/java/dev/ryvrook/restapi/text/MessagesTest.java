package dev.ryvrook.restapi.text;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;

class MessagesTest {
    @TempDir
    Path dir;

    @AfterEach
    void reset() {
        Messages.reset();
    }

    @Test
    void placeholdersAreFilledOnce() {
        CommandText.ROOT.length();
        assertEquals("Revoked the key {name}x. Requests that use it get 401 from now on.",
                Messages.get(CommandText.KEY_REVOKED, "name", "{name}x"));
        assertEquals("unknown.id", Messages.get("unknown.id"));
    }

    @Test
    void theFileIsWrittenAndRewordingApplies() throws Exception {
        CommandText.ROOT.length();
        Path file = dir.resolve("messages.json");
        assertTrue(Messages.load(file).isEmpty());
        String json = Files.readString(file);
        assertTrue(json.contains("\"keys.none\""));
        Files.writeString(file, json.replace("The audit log is empty.", "Nothing in the audit log."));
        assertTrue(Messages.load(file).isEmpty());
        assertEquals("Nothing in the audit log.", Messages.get(CommandText.AUDIT_EMPTY));
    }

    @Test
    void defaultsFollowTheWritingRules() {
        CommandText.ROOT.length();
        Pattern colonReveal = Pattern.compile("^[A-Z][a-z ]+: ");
        for (String id : Messages.ids()) {
            String t = Messages.defaultOf(id);
            assertFalse(t.contains("—"), id);
            assertFalse(t.contains(";"), id);
            assertFalse(t.contains("!"), id);
            assertFalse(colonReveal.matcher(t).find(), id + " starts with a label and a colon");
        }
    }
}
