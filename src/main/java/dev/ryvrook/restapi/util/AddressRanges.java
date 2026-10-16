package dev.ryvrook.restapi.util;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * A set of IP addresses and CIDR ranges, such as 127.0.0.1, 10.0.0.0/8 or fd00::/8. Used for the trusted
 * reverse proxies and for the addresses a key is accepted from. Only IP literals are parsed, a host name is
 * refused, so nothing here ever asks DNS.
 */
public final class AddressRanges {
    public static final AddressRanges NONE = new AddressRanges(List.of(), List.of());

    private record Range(byte[] network, int bits) {
        boolean contains(byte[] addr) {
            if (addr.length != network.length) {
                return false;
            }
            int full = bits / 8;
            for (int i = 0; i < full; i++) {
                if (addr[i] != network[i]) {
                    return false;
                }
            }
            int rest = bits % 8;
            if (rest == 0) {
                return true;
            }
            int mask = (0xFF << (8 - rest)) & 0xFF;
            return (addr[full] & mask) == (network[full] & mask);
        }
    }

    private final List<Range> ranges;
    private final List<String> rejected;

    private AddressRanges(List<Range> ranges, List<String> rejected) {
        this.ranges = ranges;
        this.rejected = rejected;
    }

    /** Parses the entries. Those that are not an address or a range end up in {@link #rejected()}. */
    public static AddressRanges parse(List<String> entries) {
        if (entries == null || entries.isEmpty()) {
            return NONE;
        }
        List<Range> ranges = new ArrayList<>();
        List<String> rejected = new ArrayList<>();
        for (String raw : entries) {
            if (raw == null || raw.isBlank()) {
                continue;
            }
            String entry = raw.trim();
            int slash = entry.indexOf('/');
            byte[] addr = literal(slash < 0 ? entry : entry.substring(0, slash));
            if (addr == null) {
                rejected.add(entry);
                continue;
            }
            int bits = addr.length * 8;
            if (slash >= 0) {
                try {
                    bits = Integer.parseInt(entry.substring(slash + 1).trim());
                } catch (NumberFormatException e) {
                    bits = -1;
                }
                if (bits < 0 || bits > addr.length * 8) {
                    rejected.add(entry);
                    continue;
                }
            }
            ranges.add(new Range(addr, bits));
        }
        return new AddressRanges(List.copyOf(ranges), List.copyOf(rejected));
    }

    public boolean isEmpty() {
        return ranges.isEmpty();
    }

    public List<String> rejected() {
        return rejected;
    }

    public boolean contains(String address) {
        byte[] addr = literal(address);
        if (addr == null) {
            return false;
        }
        for (Range r : ranges) {
            if (r.contains(addr)) {
                return true;
            }
        }
        return false;
    }

    /** True for 127.0.0.0/8, ::1 and the IPv4 mapped loopback. */
    public static boolean isLoopback(String address) {
        byte[] a = literal(address);
        if (a == null) {
            return false;
        }
        if (a.length == 4) {
            return a[0] == 127;
        }
        for (int i = 0; i < 15; i++) {
            if (a[i] != 0) {
                return false;
            }
        }
        return a[15] == 1;
    }

    /**
     * The bytes of an IPv4 or IPv6 literal, or null for anything else. Brackets and a zone id are removed. An IPv4
     * mapped IPv6 address comes back as its four IPv4 bytes.
     */
    public static byte[] literal(String raw) {
        if (raw == null) {
            return null;
        }
        String s = raw.trim().toLowerCase(Locale.ROOT);
        if (s.startsWith("[") && s.endsWith("]")) {
            s = s.substring(1, s.length() - 1);
        }
        int zone = s.indexOf('%');
        if (zone >= 0) {
            s = s.substring(0, zone);
        }
        if (s.isEmpty() || s.length() > 45) {
            return null;
        }
        boolean v6 = s.indexOf(':') >= 0;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            boolean ok = (c >= '0' && c <= '9') || c == '.' || (v6 && (c == ':' || (c >= 'a' && c <= 'f')));
            if (!ok) {
                return null;
            }
        }
        if (!v6) {
            String[] parts = s.split("\\.", -1);
            if (parts.length != 4) {
                return null;
            }
            byte[] out = new byte[4];
            for (int i = 0; i < 4; i++) {
                if (parts[i].isEmpty() || parts[i].length() > 3) {
                    return null;
                }
                int v = Integer.parseInt(parts[i]);
                if (v > 255) {
                    return null;
                }
                out[i] = (byte) v;
            }
            return out;
        }
        try {
            // Only hex digits, dots and colons get here, so this parses and never resolves a name.
            return InetAddress.getByName(s).getAddress();
        } catch (UnknownHostException | SecurityException e) {
            return null;
        }
    }
}
