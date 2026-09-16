package dev.zerosum.ledger.apply;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Apply-engine settings. Values live in {@code application.yml} with trace comments; this record only declares them.
 *
 * @param lockTimeout       transaction-local {@code lock_timeout} (D02-4)
 * @param statementTimeout  transaction-local {@code statement_timeout} for apply (D02-4)
 * @param maxAttempts       attempts including the first (D02-4)
 * @param backoffMin        first backoff, jittered (D02-4)
 * @param backoffMax        backoff ceiling (D02-4)
 * @param defaultBatchSize  the mode callers get by default; 1 means per-order (D02-3, revisited by D02-10)
 */
@ConfigurationProperties("ledger.apply")
public record LedgerApplyProperties(Duration lockTimeout, Duration statementTimeout, int maxAttempts,
        Duration backoffMin, Duration backoffMax, int defaultBatchSize) {

    public LedgerApplyProperties {
        if (maxAttempts < 1) {
            throw new IllegalArgumentException("ledger.apply.max-attempts must be >= 1: " + maxAttempts);
        }
        if (defaultBatchSize < 1) {
            throw new IllegalArgumentException("ledger.apply.default-batch-size must be >= 1: " + defaultBatchSize);
        }
    }
}
