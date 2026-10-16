package dev.ryvrook.restapi.suite;

import dev.ryvrook.restapi.http.ApiException;
import dev.ryvrook.restapi.http.Call;

import java.util.function.Supplier;

/**
 * Row limits and the wait for file reads, shared by every suite route.
 *
 * @param defaultLimit rows when the request names none
 * @param maxLimit     the most rows ever answered
 * @param waitMs       how long a route waits on a sibling future
 */
public record Limits(int defaultLimit, int maxLimit, int waitMs) {
    public static final Limits DEFAULTS = new Limits(50, 500, 5000);

    /** The settings in force at each call, so a /restapi reload applies at once. */
    public static Supplier<Limits> live() {
        return SuiteSettings::limits;
    }

    /** {@code ?limit=}, cut to the maximum, 400 bad_limit when it is not a whole number above 0. */
    public int limit(Call call) {
        String raw = call.query("limit", null);
        if (raw == null || raw.isBlank()) {
            return Math.min(defaultLimit, maxLimit);
        }
        int n = Json.integer(raw, "limit");
        if (n < 1) {
            throw ApiException.badRequest("bad_limit", SuiteText.get(SuiteText.BAD_LIMIT));
        }
        return Math.min(n, maxLimit);
    }

    /** {@code ?offset=}, 0 when missing. */
    public int offset(Call call) {
        String raw = call.query("offset", null);
        if (raw == null || raw.isBlank()) {
            return 0;
        }
        int n = Json.integer(raw, "offset");
        if (n < 0) {
            throw ApiException.badRequest("bad_offset", SuiteText.get(SuiteText.BAD_OFFSET));
        }
        return n;
    }
}
