package dev.ryvrook.restapi.util;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class VersionsAndRangesTest {
    @Test
    void versionsCompareByNumberNotText() {
        assertTrue(Versions.below("1.5.1", "1.6.0"));
        assertFalse(Versions.below("1.10.0", "1.9.0"));
        assertFalse(Versions.below("1.6", "1.6.0"));
        assertFalse(Versions.below("1.6.0-beta", "1.6.0"));
        assertTrue(Versions.below("junk", "0.1.0"));
    }

    @Test
    void rangesMatchAddressesAndCidrs() {
        AddressRanges r = AddressRanges.parse(List.of("127.0.0.1", "10.0.0.0/8", "fd00::/8", "example.com", "1.2.3.4/40"));
        assertTrue(r.contains("127.0.0.1"));
        assertTrue(r.contains("10.200.3.4"));
        assertFalse(r.contains("11.0.0.1"));
        assertTrue(r.contains("fd12::1"));
        assertTrue(r.contains("::ffff:10.1.1.1"), "an IPv4 mapped address matches its IPv4 range");
        assertEquals(List.of("example.com", "1.2.3.4/40"), r.rejected());
        assertFalse(r.contains("example.com"));
    }

    @Test
    void loopbackIsRecognised() {
        assertTrue(AddressRanges.isLoopback("127.0.0.1"));
        assertTrue(AddressRanges.isLoopback("127.5.5.5"));
        assertTrue(AddressRanges.isLoopback("::1"));
        assertFalse(AddressRanges.isLoopback("0.0.0.0"));
        assertFalse(AddressRanges.isLoopback("192.168.1.2"));
        assertFalse(AddressRanges.isLoopback("localhost"));
    }
}
