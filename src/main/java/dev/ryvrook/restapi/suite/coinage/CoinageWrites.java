package dev.ryvrook.restapi.suite.coinage;

import dev.ryvrook.restapi.suite.SuiteSettings;

/**
 * How the Coinage write routes behave, read from the settings at each request.
 *
 * @param maxAmount      the most minor units one move takes, 0 for no cap
 * @param requireKey     refuse a write without an Idempotency-Key
 * @param keepMillis     how long an answer stays for a retry
 */
record CoinageWrites(long maxAmount, boolean requireKey, long keepMillis) {
    static final CoinageWrites DEFAULTS = new CoinageWrites(1_000_000L, false, 600_000L);

    static CoinageWrites live() {
        return new CoinageWrites(SuiteSettings.COINAGE_MAX_AMOUNT.get(), SuiteSettings.COINAGE_REQUIRE_IDEMPOTENCY.get(),
                SuiteSettings.COINAGE_IDEMPOTENCY_SECONDS.get() * 1000L);
    }
}
