package dev.ryvrook.restapi.control;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ControlTextTest {
    @Test
    void durationsNameTwoUnitsAtMost() {
        assertEquals("1 second", ControlText.duration(1));
        assertEquals("5 seconds", ControlText.duration(5));
        assertEquals("1 minute", ControlText.duration(60));
        assertEquals("1 minute and 30 seconds", ControlText.duration(90));
        assertEquals("10 minutes", ControlText.duration(600));
        assertEquals("1 hour", ControlText.duration(3600));
        assertEquals("2 hours and 5 minutes", ControlText.duration(7_500));
        assertEquals("0 seconds", ControlText.duration(-3));
    }
}
