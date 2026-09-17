package dev.zerosum.ledger.kafka;

import dev.zerosum.ledger.apply.ApplyBatchResult;
import dev.zerosum.ledger.apply.ApplyOutcome;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;

/**
 * The apply-path latency and retry signals (D07-1, master §6.4).
 *
 * <p>Everything here is read from what the engine already measured — {@link ApplyBatchResult} carries the batch
 * duration, the summed lock wait and the retry counts by SQLSTATE class. Re-timing any of it in the listener would
 * measure a different thing and then disagree with the engine.
 *
 * <p><strong>Only committed work is observed.</strong> A batch that threw never reaches this class, so a rolled-back
 * or exhausted attempt records no success observation — it is counted as a retry or a pause instead. Duplicates that
 * the dedupe drops record no order-to-apply observation either: they were applied once, earlier, and counting them
 * again would make redelivery look like throughput.
 */
@Component
class ApplyMetrics {

    /**
     * SLO buckets from master §6.4: 5, 10, 25, 50, 100, 250, 500, 1000, 2500 ms. Bucket histograms, not client-side
     * percentiles, because percentiles computed per instance cannot be aggregated across instances or re-windowed.
     */
    private static final Duration[] SLO_BUCKETS = {
        Duration.ofMillis(5), Duration.ofMillis(10), Duration.ofMillis(25), Duration.ofMillis(50),
        Duration.ofMillis(100), Duration.ofMillis(250), Duration.ofMillis(500), Duration.ofSeconds(1),
        Duration.ofMillis(2500)
    };

    private final Timer applyDuration;
    private final Timer lockWait;
    private final Timer orderToApply;
    private final MeterRegistry meters;
    private final Map<String, Counter> retriesByClass = new ConcurrentHashMap<>();

    ApplyMetrics(MeterRegistry meters) {
        this.meters = meters;
        this.applyDuration = histogram("ledger_apply_seconds", "Wall time of one apply batch, including retries");
        this.lockWait = histogram("ledger_lock_wait_seconds", "Time spent acquiring sorted entity locks, per batch");
        this.orderToApply = histogram("order_to_apply_seconds",
                "From the order's creation in order-service to its ledger apply commit (P2)");
        // Counters start at zero for every known retry class, so a dashboard shows "no deadlocks" rather than an
        // empty panel that could equally mean the metric is broken.
        for (String retryClass : List.of("deadlock", "lock_timeout", "connection")) {
            retriesByClass.put(retryClass, retryCounter(retryClass));
        }
    }

    private Timer histogram(String name, String description) {
        return Timer.builder(name).description(description).serviceLevelObjectives(SLO_BUCKETS).register(meters);
    }

    private Counter retryCounter(String retryClass) {
        return Counter.builder("ledger_apply_retries_total")
                .description("Apply transaction retries, by the D02-4 classification of the failure")
                .tag("retry_class", retryClass)
                .register(meters);
    }

    /**
     * Records one committed batch.
     *
     * @param result what the engine returned; its timings are authoritative
     */
    void batchApplied(ApplyBatchResult result) {
        applyDuration.record(result.total());
        lockWait.record(result.lockWait());
        count("deadlock", result.deadlockRetries());
        count("lock_timeout", result.lockTimeoutRetries());
        count("connection", result.connectionRetries());
    }

    private void count(String retryClass, int amount) {
        if (amount > 0) {
            retriesByClass.computeIfAbsent(retryClass, this::retryCounter).increment(amount);
        }
    }

    /**
     * One observation per order actually applied in this batch (P2, master stage budgets).
     *
     * @param orderCreatedAt when order-service created the order; the two services share a clock in measured runs
     */
    void orderApplied(ApplyOutcome outcome, Instant orderCreatedAt) {
        if (outcome.status() != ApplyOutcome.Status.APPLIED || orderCreatedAt == null) {
            return;   // duplicates and quarantined records are not applications
        }
        Duration elapsed = Duration.between(orderCreatedAt, Instant.now());
        // A negative reading means the clocks disagree, not that the order applied before it existed.
        orderToApply.record(elapsed.isNegative() ? Duration.ZERO : elapsed);
    }
}
