package dev.zerosum.ledger.kafka;

import io.micrometer.core.instrument.MeterRegistry;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Consumer lag as scheduled gauges (D07-1, master §6.4 and §0.3 O9).
 *
 * <p>S04-T04 computes lag inside the freshness endpoint, which means it only exists when someone makes an HTTP
 * request. An alert cannot poll an endpoint, so "consumer lag or paused" could not have fired without traffic — the
 * one situation where lag matters most is a pipeline nobody is currently calling. This evaluates the same computation
 * on a schedule and publishes it.
 *
 * <p>It reuses {@link FreshnessCalculator} rather than reimplementing the offset arithmetic. Two implementations of
 * "how far behind are we" would eventually disagree, and the endpoint and the alert would tell different stories
 * about the same pipeline.
 *
 * <p><strong>Failure reports absence, not zero.</strong> Micrometer gauges publish NaN for a null reading, so a
 * failed computation leaves no sample rather than a reassuring 0. A lag gauge stuck at zero because its query broke
 * is worse than no gauge at all.
 */
@Component
class ConsumerLagGauges {

    private static final Logger log = LoggerFactory.getLogger(ConsumerLagGauges.class);

    /** Sentinel for "not measured". Translated to a null gauge reading, which Micrometer exports as NaN. */
    private static final long UNMEASURED = Long.MIN_VALUE;

    private final FreshnessCalculator freshness;
    private final AtomicLong lagRecords = new AtomicLong(UNMEASURED);
    private final AtomicLong lagMillis = new AtomicLong(UNMEASURED);

    ConsumerLagGauges(FreshnessCalculator freshness, MeterRegistry meters) {
        this.freshness = freshness;
        meters.gauge("kafka_consumer_lag_records", lagRecords, holder -> reading(holder.get()));
        // Seconds, from the Kafka record timestamp of the oldest unapplied record (§0.3 C12) — the same basis the
        // freshness endpoint reports, so the alert and the API cannot disagree.
        meters.gauge("kafka_consumer_lag_seconds", lagMillis, holder -> reading(holder.get()) / 1000.0);
    }

    private static double reading(long value) {
        return value == UNMEASURED ? Double.NaN : value;
    }

    @Scheduled(fixedDelayString = "${ledger.consumer.lag-gauge-interval:15s}")
    void sample() {
        try {
            FreshnessCalculator.Freshness current = freshness.compute();
            if (current.status() == FreshnessCalculator.Status.OK) {
                lagRecords.set(current.totalLagRecords());
                lagMillis.set(Math.round(current.oldestUnappliedAgeSeconds() * 1000));
            } else {
                // The broker is unreachable or the offsets are untrustworthy. Publishing the last good value would
                // claim the pipeline is healthy at exactly the moment it cannot be measured.
                lagRecords.set(UNMEASURED);
                lagMillis.set(UNMEASURED);
                log.debug("consumer lag unmeasurable: {}", current.error());
            }
        } catch (RuntimeException failure) {
            lagRecords.set(UNMEASURED);
            lagMillis.set(UNMEASURED);
            log.warn("consumer lag sampling failed; gauges report absence", failure);
        }
    }
}
