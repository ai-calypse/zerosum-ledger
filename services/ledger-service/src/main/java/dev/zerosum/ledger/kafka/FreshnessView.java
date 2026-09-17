package dev.zerosum.ledger.kafka;

/**
 * Lets the API package read freshness without reaching into this package's internals (D04-5).
 *
 * <p>{@link PauseOnFailureErrorHandler} and {@link FreshnessCalculator} stay package-private to the Kafka plumbing;
 * the controller depends on this narrow view instead.
 */
public interface FreshnessView {

    FreshnessCalculator.Freshness current();
}
