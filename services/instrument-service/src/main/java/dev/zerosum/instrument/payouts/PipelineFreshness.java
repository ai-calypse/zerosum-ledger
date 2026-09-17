// decision: D05-7 — docs/step_05_instruments_fake_providers.md#decisions-and-outputs
package dev.zerosum.instrument.payouts;

import java.time.Duration;

/**
 * How far behind the order → ledger → instrument pipeline is, as one number (M10(c), master §5.11).
 *
 * <p>The three stages are summed rather than maxed because they are sequential: an order still in order-service's
 * outbox has not reached the ledger, and the ledger's own lag starts only once it has. A maximum would report the
 * worst single stage and call a pipeline fresh that is in fact the sum of all three behind.
 *
 * <p><strong>No stage is counted twice</strong> (§0.3 C12). The two outbox ages are measured from each row's
 * {@code created_at} with the database's own clock, and the ledger age from the Kafka record timestamp of the oldest
 * unapplied record (D04-5) — so the ledger's figure begins where the order outbox's ends rather than overlapping it.
 *
 * <p>A pure record with no I/O, so the arithmetic that decides whether money leaves the building is testable without
 * a network.
 */
record PipelineFreshness(double orderOutboxAgeSeconds, double ledgerUnappliedAgeSeconds,
        double instrumentOutboxAgeSeconds) {

    /** The pipeline staleness the M10(c) threshold is compared against. */
    double totalSeconds() {
        return orderOutboxAgeSeconds + ledgerUnappliedAgeSeconds + instrumentOutboxAgeSeconds;
    }

    /** Strictly greater: M10(c) refuses when freshness <em>exceeds</em> the threshold, so exactly 5 s still runs. */
    boolean exceeds(Duration threshold) {
        return totalSeconds() > threshold.toNanos() / 1_000_000_000.0;
    }

    @Override
    public String toString() {
        return "order outbox " + orderOutboxAgeSeconds + "s + ledger unapplied " + ledgerUnappliedAgeSeconds
                + "s + instrument outbox " + instrumentOutboxAgeSeconds + "s = " + totalSeconds() + "s";
    }
}
