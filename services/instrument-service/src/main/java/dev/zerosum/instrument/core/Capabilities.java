package dev.zerosum.instrument.core;

import java.time.Duration;

/**
 * What an adapter can do (D05-1). Core code checks these before calling an operation.
 *
 * <p>The pairing that matters is {@code idempotencyKeys} and {@code safeResubmitQuietPeriod}. After an uncertain
 * outcome, a provider with idempotency keys can simply be retried — the key makes the retry harmless. A provider
 * without them cannot: resubmitting might charge a payer twice, so the only safe recovery is lookup, then waiting a
 * quiet period before resubmitting. A provider that offers neither is one where an uncertain outcome can never be
 * resolved safely, which is why construction rejects it.
 *
 * @param safeResubmitQuietPeriod how long to wait after a lookup before resubmitting; required when there are no
 *                                idempotency keys, ignored when there are
 */
public record Capabilities(boolean charge, boolean disburse, boolean refund,
        boolean idempotencyKeys, boolean settlementReports, Duration safeResubmitQuietPeriod) {

    public Capabilities {
        if (!idempotencyKeys && safeResubmitQuietPeriod == null) {
            throw new IllegalArgumentException(
                    "a provider without idempotency keys needs a safe resubmit quiet period: without one there is no "
                            + "safe way to recover an uncertain outcome, and a blind retry can move money twice");
        }
        if (safeResubmitQuietPeriod != null && safeResubmitQuietPeriod.isNegative()) {
            throw new IllegalArgumentException("quiet period must not be negative: " + safeResubmitQuietPeriod);
        }
        if (!charge && !disburse) {
            throw new IllegalArgumentException("a provider that can neither charge nor disburse moves no money");
        }
    }

    /** Set when the provider replays a stored result for a repeated key, so a retry cannot double-charge. */
    public boolean supportsIdempotentRetry() {
        return idempotencyKeys;
    }
}
