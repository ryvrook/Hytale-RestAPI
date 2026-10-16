package dev.ryvrook.restapi.events;

import dev.ryvrook.restapi.config.Setting;
import dev.ryvrook.restapi.config.Settings;
import dev.ryvrook.restapi.text.Messages;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;

class EventsTextTest {
    @Test
    void sentencesFollowTheWritingRules() throws Exception {
        Pattern colonReveal = Pattern.compile("^[A-Z][a-z ]+: ");
        int n = 0;
        for (Field f : EventsText.class.getFields()) {
            if (!Modifier.isStatic(f.getModifiers())) {
                continue;
            }
            String id = (String) f.get(null);
            assertTrue(id.startsWith("events."), id);
            String t = Messages.defaultOf(id);
            assertNotNull(t, id);
            assertFalse(t.contains("—"), id);
            assertFalse(t.contains(";"), id);
            assertFalse(t.contains("!"), id);
            assertFalse(colonReveal.matcher(t).find(), id);
            n++;
        }
        assertTrue(n > 20);
    }

    @Test
    void settingsHaveDefaultsAndSentences() throws Exception {
        int n = 0;
        for (Field f : EventsSettings.class.getFields()) {
            Setting<?> s = (Setting<?>) f.get(null);
            assertTrue(s.key().startsWith("events.") || s.key().startsWith("webhooks.") || s.key().startsWith("metrics."), s.key());
            assertNotNull(s.fallback(), s.key());
            assertTrue(Settings.all().stream().anyMatch(x -> x.key().equals(s.key())));
            n++;
        }
        assertEquals(18, n);
    }
}
