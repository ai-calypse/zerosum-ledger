package dev.zerosum.ledger.api;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Read-API settings. Values live in {@code application.yml} with trace comments; this record only declares them.
 *
 * @param defaultPageSize             changelog rows returned when the caller gives no limit (D02-7)
 * @param maxPageSize                 the ceiling from the master's REST proposal, 500 rows (D02-7)
 * @param operationalStatementTimeout per-transaction limit for invariants and verify, longer than the global apply
 *                                    timeout because these reads scan (§0.3 C19, D02-8)
 * @param verifyFetchSize             JDBC fetch size verify streams the changelog with, so a large entity is never
 *                                    materialized in full (D02-8)
 */
@ConfigurationProperties("ledger.api")
public record LedgerApiProperties(int defaultPageSize, int maxPageSize, java.time.Duration operationalStatementTimeout,
        int verifyFetchSize) {

    public LedgerApiProperties {
        if (defaultPageSize < 1 || maxPageSize < 1 || defaultPageSize > maxPageSize) {
            throw new IllegalArgumentException(
                    "ledger.api page sizes must satisfy 1 <= default-page-size <= max-page-size, was "
                            + defaultPageSize + " and " + maxPageSize);
        }
        if (operationalStatementTimeout == null || operationalStatementTimeout.isNegative()
                || operationalStatementTimeout.isZero()) {
            throw new IllegalArgumentException(
                    "ledger.api.operational-statement-timeout must be positive: " + operationalStatementTimeout);
        }
        if (verifyFetchSize < 1) {
            throw new IllegalArgumentException("ledger.api.verify-fetch-size must be >= 1: " + verifyFetchSize);
        }
    }
}
