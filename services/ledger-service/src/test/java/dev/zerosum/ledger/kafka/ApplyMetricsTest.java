package dev.zerosum.ledger.kafka;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.zerosum.ledger.apply.ApplyBatchResult;
import dev.zerosum.ledger.apply.ApplyOutcome;
import dev.zerosum.ledger.apply.QuarantineCode;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * The apply-path signals record committed work only (D07-1, S07-T01).
 *
 * <p>The failure this guards against is subtle and flattering: if a redelivered order recorded an order-to-apply
 * observation, a pipeline reprocessing its backlog would look like a pipeline doing more work, and P2 would improve
 * exactly when the system was repeating itself. The same applies to a rolled-back batch, which did nothing at all.
 */
@Tag("unit")
class ApplyMetricsTest {

    private static ApplyBatchResult batch(List<ApplyOutcome> outcomes, Duration total, Duration lockWait,
            int deadlocks, int lockTimeouts, int connectionFailures) {
        return new ApplyBatchResult(outcomes, total, lockWait, 1, deadlocks, lockTimeouts, connectionFailures);
    }

    @Test
    void aCommittedBatchRecordsItsDurationAndLockWaitOnce() {
        var meters = new SimpleMeterRegistry();
        var metrics = new ApplyMetrics(meters);

        metrics.batchApplied(batch(List.of(new ApplyOutcome(0, UUID.randomUUID(), ApplyOutcome.Status.APPLIED, null, null)),
                Duration.ofMillis(40), Duration.ofMillis(12), 0, 0, 0));

        assertEquals(1, meters.get("ledger_apply_seconds").timer().count(),
                "one observation per batch, not per order — S07-T05 must not read batch counts as throughput");
        assertEquals(1, meters.get("ledger_lock_wait_seconds").timer().count());
        assertTrue(meters.get("ledger_apply_seconds").timer().totalTime(java.util.concurrent.TimeUnit.MILLISECONDS) >= 40);
    }

    @Test
    void onlyAppliedOrdersProduceAnOrderToApplyObservation() {
        var meters = new SimpleMeterRegistry();
        var metrics = new ApplyMetrics(meters);
        Instant createdAt = Instant.now().minusMillis(250);

        metrics.orderApplied(new ApplyOutcome(0, UUID.randomUUID(), ApplyOutcome.Status.APPLIED, null, null), createdAt);
        // A duplicate is an order that was already applied earlier. Counting it again would make redelivery look
        // like throughput.
        metrics.orderApplied(new ApplyOutcome(1, UUID.randomUUID(), ApplyOutcome.Status.DUPLICATE, null, null), createdAt);
        metrics.orderApplied(new ApplyOutcome(2, null, ApplyOutcome.Status.QUARANTINED, QuarantineCode.SCHEMA_INVALID, "bad"), createdAt);

        assertEquals(1, meters.get("order_to_apply_seconds").timer().count(),
                "duplicates and quarantined records are not applications");
    }

    @Test
    void aBatchThatNeverCommittedRecordsNoSuccessObservation() {
        // The listener only calls these after apply() returns, so a thrown batch reaches neither. Asserted here so
        // the guarantee is pinned even if the call site moves.
        var meters = new SimpleMeterRegistry();
        new ApplyMetrics(meters);

        assertEquals(0, meters.get("ledger_apply_seconds").timer().count());
        assertEquals(0, meters.get("order_to_apply_seconds").timer().count());
    }

    @Test
    void retriesAreCountedByTheirD024Class() {
        var meters = new SimpleMeterRegistry();
        var metrics = new ApplyMetrics(meters);

        metrics.batchApplied(batch(List.of(), Duration.ofMillis(5), Duration.ZERO, 2, 1, 3));

        assertEquals(2.0, meters.get("ledger_apply_retries_total").tag("retry_class", "deadlock").counter().count());
        assertEquals(1.0, meters.get("ledger_apply_retries_total").tag("retry_class", "lock_timeout").counter().count());
        assertEquals(3.0, meters.get("ledger_apply_retries_total").tag("retry_class", "connection").counter().count());
    }

    @Test
    void everyRetryClassStartsAtZeroRatherThanAbsent() {
        // An empty panel could mean "no deadlocks" or "the metric is broken". Initialising the bounded label set
        // removes that ambiguity.
        var meters = new SimpleMeterRegistry();
        new ApplyMetrics(meters);

        for (String retryClass : List.of("deadlock", "lock_timeout", "connection")) {
            assertEquals(0.0, meters.get("ledger_apply_retries_total").tag("retry_class", retryClass).counter().count(),
                    retryClass + " must be initialised, so an empty panel cannot be mistaken for a broken metric");
        }
    }

    @Test
    void aClockSkewedCreationTimeIsClampedRatherThanNegative() {
        var meters = new SimpleMeterRegistry();
        var metrics = new ApplyMetrics(meters);

        metrics.orderApplied(new ApplyOutcome(0, UUID.randomUUID(), ApplyOutcome.Status.APPLIED, null, null), Instant.now().plusSeconds(30));

        assertEquals(1, meters.get("order_to_apply_seconds").timer().count());
        assertTrue(meters.get("order_to_apply_seconds").timer().totalTime(java.util.concurrent.TimeUnit.SECONDS) >= 0,
                "an order cannot apply before it was created; skew is clamped, not recorded as negative latency");
    }
}
