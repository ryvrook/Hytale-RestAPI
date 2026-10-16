package dev.ryvrook.restapi.events;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * A Prometheus text format writer, version 0.0.4, with no engine in it. Families are written in the order they
 * were made, each with HELP and TYPE, a family with no samples is left out. Label values are escaped the way the
 * format asks, backslash, double quote and line feed.
 */
public final class Metrics {
    public static final String CONTENT_TYPE = "text/plain; version=0.0.4; charset=utf-8";

    private static final Pattern NAME = Pattern.compile("[a-zA-Z_:][a-zA-Z0-9_:]*");
    private static final Pattern LABEL = Pattern.compile("[a-zA-Z_][a-zA-Z0-9_]*");

    public enum Type {
        GAUGE, COUNTER
    }

    private final Map<String, Family> families = new LinkedHashMap<>();

    public Family gauge(String name, String help) {
        return family(name, Type.GAUGE, help);
    }

    /** A counter. Its name must end in _total, as the format's conventions ask. */
    public Family counter(String name, String help) {
        if (!name.endsWith("_total")) {
            throw new IllegalArgumentException("Counter " + name + " must end in _total");
        }
        return family(name, Type.COUNTER, help);
    }

    private Family family(String name, Type type, String help) {
        if (!NAME.matcher(name).matches()) {
            throw new IllegalArgumentException("Bad metric name " + name);
        }
        Family f = families.get(name);
        if (f != null) {
            if (f.type != type) {
                throw new IllegalArgumentException(name + " is already a " + f.type);
            }
            return f;
        }
        f = new Family(name, type, help);
        families.put(name, f);
        return f;
    }

    /** One metric with its samples. */
    public static final class Family {
        private final String name;
        private final Type type;
        private final String help;
        private final List<String> lines = new ArrayList<>();
        private final java.util.Set<String> seen = new java.util.HashSet<>();

        Family(String name, Type type, String help) {
            this.name = name;
            this.type = type;
            this.help = help;
        }

        /**
         * Adds a sample with labels as name and value pairs. A second sample with the same labels is refused, the
         * format forbids it.
         */
        public Family add(double value, String... labels) {
            if (labels.length % 2 != 0) {
                throw new IllegalArgumentException("Labels come in name and value pairs");
            }
            StringBuilder b = new StringBuilder(name);
            if (labels.length > 0) {
                b.append('{');
                for (int i = 0; i < labels.length; i += 2) {
                    if (!LABEL.matcher(labels[i]).matches() || labels[i].startsWith("__")) {
                        throw new IllegalArgumentException("Bad label name " + labels[i]);
                    }
                    if (i > 0) {
                        b.append(',');
                    }
                    b.append(labels[i]).append("=\"").append(escapeLabel(labels[i + 1])).append('"');
                }
                b.append('}');
            }
            String key = b.toString();
            if (!seen.add(key)) {
                throw new IllegalArgumentException("Two samples of " + key);
            }
            lines.add(key + ' ' + number(value));
            return this;
        }

        public int size() {
            return lines.size();
        }
    }

    /** The whole exposition, ending with a line feed. */
    public String render() {
        StringBuilder b = new StringBuilder();
        for (Family f : families.values()) {
            if (f.lines.isEmpty()) {
                continue;
            }
            b.append("# HELP ").append(f.name).append(' ').append(escapeHelp(f.help)).append('\n');
            b.append("# TYPE ").append(f.name).append(' ').append(f.type.name().toLowerCase(Locale.ROOT)).append('\n');
            for (String l : f.lines) {
                b.append(l).append('\n');
            }
        }
        return b.toString();
    }

    static String escapeLabel(String v) {
        if (v == null) {
            return "";
        }
        return v.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n");
    }

    static String escapeHelp(String v) {
        return v == null ? "" : v.replace("\\", "\\\\").replace("\n", "\\n");
    }

    /** Whole numbers without a fraction, NaN and infinities in the format's spelling. */
    static String number(double v) {
        if (Double.isNaN(v)) {
            return "NaN";
        }
        if (Double.isInfinite(v)) {
            return v > 0 ? "+Inf" : "-Inf";
        }
        if (v == Math.rint(v) && Math.abs(v) < 1e15) {
            return Long.toString((long) v);
        }
        return Double.toString(v);
    }
}
