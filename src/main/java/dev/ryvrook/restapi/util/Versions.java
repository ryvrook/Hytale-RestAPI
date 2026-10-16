package dev.ryvrook.restapi.util;

/** Compares dotted versions such as 1.6.0 and 0.6.2. Text after a dash or plus is ignored. */
public final class Versions {
    private Versions() {
    }

    /** True when version is older than minimum. A version that does not parse counts as older. */
    public static boolean below(String version, String minimum) {
        int[] a = parse(version);
        int[] b = parse(minimum);
        if (a == null) {
            return true;
        }
        if (b == null) {
            return false;
        }
        for (int i = 0; i < Math.max(a.length, b.length); i++) {
            int x = i < a.length ? a[i] : 0;
            int y = i < b.length ? b[i] : 0;
            if (x != y) {
                return x < y;
            }
        }
        return false;
    }

    static int[] parse(String v) {
        if (v == null || v.isBlank()) {
            return null;
        }
        String core = v.trim().split("[-+]", 2)[0];
        String[] parts = core.split("\\.");
        int[] out = new int[parts.length];
        try {
            for (int i = 0; i < parts.length; i++) {
                out[i] = Integer.parseInt(parts[i]);
            }
        } catch (NumberFormatException e) {
            return null;
        }
        return out;
    }
}
