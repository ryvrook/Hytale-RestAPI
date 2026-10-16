package dev.ryvrook.restapi.config;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * One key of RestAPI.json with its type, default, limits and the sentence the README and docs/SETTINGS.md print
 * for it. Declared once as a static constant through {@link Settings#bool} and its siblings, read with
 * {@link #get()} from any thread. The value is swapped whole on every load, so a reader never sees half a reload.
 */
public final class Setting<T> {
    /** The kinds of value a setting can hold, which decide how the file entry is read. */
    public enum Kind {
        BOOL, INT, TEXT, LIST
    }

    private final String key;
    private final Kind kind;
    private final T fallback;
    private final long min;
    private final long max;
    private final boolean restart;
    private final String description;

    Setting(String key, Kind kind, T fallback, long min, long max, boolean restart, String description) {
        this.key = key;
        this.kind = kind;
        this.fallback = fallback;
        this.min = min;
        this.max = max;
        this.restart = restart;
        this.description = description;
    }

    public String key() {
        return key;
    }

    public Kind kind() {
        return kind;
    }

    public T fallback() {
        return fallback;
    }

    public long min() {
        return min;
    }

    public long max() {
        return max;
    }

    /** True when a change only applies at the next server start, not on /restapi reload. */
    public boolean restart() {
        return restart;
    }

    public String description() {
        return description;
    }

    /** The value in use, the default until a file was read. */
    public T get() {
        return Settings.value(this);
    }

    /**
     * Turns a raw value from the file into the setting's type, or throws with a sentence naming the key when it
     * does not fit. Numbers outside min and max are refused, not clamped, so a typo is seen instead of guessed.
     */
    @SuppressWarnings("unchecked")
    T coerce(Object raw) {
        switch (kind) {
            case BOOL -> {
                if (raw instanceof Boolean b) {
                    return (T) b;
                }
                throw new IllegalArgumentException(key + " must be true or false");
            }
            case INT -> {
                if (raw instanceof Number n && n.doubleValue() == Math.rint(n.doubleValue())) {
                    long v = n.longValue();
                    if (v < min || v > max) {
                        throw new IllegalArgumentException(key + " must be between " + min + " and " + max + ", it is " + v);
                    }
                    return (T) Integer.valueOf((int) v);
                }
                throw new IllegalArgumentException(key + " must be a whole number");
            }
            case TEXT -> {
                if (raw instanceof String s) {
                    return (T) s.trim();
                }
                throw new IllegalArgumentException(key + " must be text in quotes");
            }
            case LIST -> {
                if (raw instanceof List<?> list) {
                    List<String> out = new ArrayList<>(list.size());
                    for (Object o : list) {
                        if (!(o instanceof String s)) {
                            throw new IllegalArgumentException(key + " must be a list of text entries");
                        }
                        if (!s.isBlank()) {
                            out.add(s.trim());
                        }
                    }
                    return (T) List.copyOf(out);
                }
                throw new IllegalArgumentException(key + " must be a list such as [\"a\", \"b\"]");
            }
            default -> throw new IllegalStateException(kind.name().toLowerCase(Locale.ROOT));
        }
    }
}
