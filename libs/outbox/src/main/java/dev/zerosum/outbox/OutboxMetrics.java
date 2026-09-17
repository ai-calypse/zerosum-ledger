package dev.zerosum.outbox;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

/**
 * Outbox meters (D03-5). Micrometer is the single metrics path (D00-6); nothing here exports directly.
 *
 * <p>Names follow the master's instrumentation proposal. S07-T01 owns the permanent metric catalogue and may rename
 * these through a change request, so they are recorded in D03-5 rather than treated as settled.
 *
 * <p>The freshness gauge is registered from a supplier rather than set by the relay, so it reports the true oldest
 * age even when the relay is blocked or idle — a gauge the relay updated would freeze at its last successful pass,
 * which is exactly when the number matters most.
 */
public class OutboxMetrics {

    public static final String PUBLISH_LAG = "outbox_publish_lag_seconds";
    public static final String OLDEST_UNPUBLISHED = "outbox_oldest_unpublished_seconds";
    public static final String BATCH_SIZE = "outbox_batch_size";
    public static final String SEND_FAILURES = "outbox_send_failures_total";
    public static final String GUARD_SKIPS = "outbox_guard_skips_total";

    private final Timer publishLag;
    private final Counter sendFailures;
    private final Counter guardSkips;
    private final AtomicInteger lastBatchSize = new AtomicInteger();

    /**
     * @param oldestUnpublishedAgeSeconds read at scrape time, so the gauge stays truthful while the relay is stuck
     */
    public OutboxMetrics(MeterRegistry registry, Supplier<Number> oldestUnpublishedAgeSeconds) {
        this.publishLag = Timer.builder(PUBLISH_LAG)
                .description("Time from an outbox row being appended to it being acknowledged by the broker")
                .publishPercentileHistogram()
                .register(registry);
        this.sendFailures = Counter.builder(SEND_FAILURES)
                .description("Batches the broker did not acknowledge; the batch is rolled back and re-sent")
                .register(registry);
        this.guardSkips = Counter.builder(GUARD_SKIPS)
                .description("Batches skipped because another relay instance held the single-instance guard")
                .register(registry);
        registry.gauge(OLDEST_UNPUBLISHED, this, self -> oldestUnpublishedAgeSeconds.get().doubleValue());
        registry.gauge(BATCH_SIZE, lastBatchSize, AtomicInteger::doubleValue);
    }

    /** Records publish lag for one row, measured from when it was appended (master §0.3 C12). */
    void published(Instant createdAt) {
        publishLag.record(Duration.between(createdAt, Instant.now()));
    }

    void batchSize(int size) {
        lastBatchSize.set(size);
    }

    void sendFailed() {
        sendFailures.increment();
    }

    void guardSkipped() {
        guardSkips.increment();
    }
}
