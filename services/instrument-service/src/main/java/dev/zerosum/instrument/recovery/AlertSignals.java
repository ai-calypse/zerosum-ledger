// decision: D05-8, D05-14 — docs/step_05_instruments_fake_providers.md#decisions-and-outputs (§0.3 O9)
package dev.zerosum.instrument.recovery;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import org.springframework.stereotype.Component;

/**
 * The alert signals §0.3 O9 asks for (master §6.4 naming, §10.5 alert rows).
 *
 * <p>Four signals, and each exists because an alert in master §10.5 cannot be written without it:
 *
 * <ul>
 *   <li>{@code unknown_attempts_oldest_seconds} — "Oldest {@code UNKNOWN} > 5 min" (also master §6.6);</li>
 *   <li>{@code pending_payouts_oldest_seconds} — "Oldest {@code PENDING} payout older than the webhook redelivery
 *       window";</li>
 *   <li>{@code attempts_needs_review} — "or any {@code NEEDS_REVIEW}", which is the half of the unknown-attempts
 *       alert that a maximum age can never express, because an attempt that reached {@code NEEDS_REVIEW} has stopped
 *       being {@code UNKNOWN};</li>
 *   <li>{@code attempts_state_total{kind,status}} — the "attempts by state" panel of the providers dashboard.</li>
 * </ul>
 *
 * <p><strong>Names are provisional (D05-14).</strong> D07-1 owns the metric catalogue from S07 on; these follow the
 * names master §6.4 proposes and are recorded as provisional in H.2 and in {@code infra/otel/registry.yaml}, which
 * until now listed them as blocked on this step.
 *
 * <p><strong>Measured on the sweeper's tick, not on scrape.</strong> A gauge that queried the database from the
 * scrape thread would put an unbounded, externally triggered query on the money path — and would report nothing at
 * all in the window where it matters most, which is when the database is the thing that is unwell.
 *
 * <p><strong>Absence is not zero.</strong> Before the first successful measurement the ages report {@code NaN}: "we
 * have not looked" and "nothing is stuck" are different facts, and reporting the first as the second would turn a
 * sweeper that never ran into a dashboard showing everything healthy. Once measured, no stuck attempt really is an
 * age of zero, and that is reported as zero. This follows the rule the ledger's lag gauge already set.
 */
@Component
public class AlertSignals {

    /** Master §6.4 gauges; provisional under D05-14. */
    public static final String OLDEST_UNKNOWN = "unknown_attempts_oldest_seconds";
    public static final String OLDEST_PENDING_PAYOUT = "pending_payouts_oldest_seconds";
    public static final String ATTEMPTS_BY_STATE = "attempts_state_total";
    public static final String NEEDS_REVIEW = "attempts_needs_review";

    private final MeterRegistry meters;
    private final Clock clock;

    /** Strong references: Micrometer holds gauge state weakly, so a field here is what keeps the series alive. */
    private final AtomicReference<Double> oldestUnknownSeconds = new AtomicReference<>(Double.NaN);
    private final AtomicReference<Double> oldestPendingPayoutSeconds = new AtomicReference<>(Double.NaN);
    private final AtomicLong needsReview = new AtomicLong();
    private final Map<String, AtomicLong> byState = new ConcurrentHashMap<>();

    AlertSignals(MeterRegistry meters, Clock clock) {
        this.meters = meters;
        this.clock = clock;

        Gauge.builder(OLDEST_UNKNOWN, oldestUnknownSeconds, AtomicReference::get)
                .description("Age of the oldest attempt still in UNKNOWN (§0.3 O9; alert above 5 minutes)")
                .baseUnit("seconds")
                .register(meters);
        Gauge.builder(OLDEST_PENDING_PAYOUT, oldestPendingPayoutSeconds, AtomicReference::get)
                .description("Age of the oldest payout still PENDING, so a dropped webhook is visible (§0.3 O9)")
                .baseUnit("seconds")
                .register(meters);
        Gauge.builder(NEEDS_REVIEW, needsReview, AtomicLong::doubleValue)
                .description("Attempts that reached NEEDS_REVIEW and are waiting for a human (master §10.5)")
                .register(meters);
    }

    /** Called once per sweeper tick, after the work of that tick, so the figures describe the state it left behind. */
    void refresh(RecoveryQueries.OldestAges ages, Map<String, Long> stateCounts) {
        Instant now = clock.instant();
        oldestUnknownSeconds.set(ageSeconds(ages.oldestUnknown(), now));
        oldestPendingPayoutSeconds.set(ageSeconds(ages.oldestPendingPayout(), now));

        long review = 0;
        for (Map.Entry<String, Long> count : stateCounts.entrySet()) {
            state(count.getKey()).set(count.getValue());
            if (count.getKey().endsWith("|NEEDS_REVIEW")) {
                review += count.getValue();
            }
        }
        // A (kind, status) that has emptied out reports zero rather than vanishing: a series that disappears reads
        // as "no data" on a dashboard, which is indistinguishable from the exporter having stopped.
        byState.forEach((key, value) -> {
            if (!stateCounts.containsKey(key)) {
                value.set(0);
            }
        });
        needsReview.set(review);
    }

    /**
     * Registers a (kind, status) series the first time that pair is seen.
     *
     * <p>Lazily rather than pre-registering all 33 combinations, because most of them are impossible — a charge is
     * never {@code SETTLED} — and a permanently zero series for an unreachable state is noise that makes the real
     * ones harder to find.
     */
    private AtomicLong state(String kindAndStatus) {
        return byState.computeIfAbsent(kindAndStatus, key -> {
            String[] parts = key.split("\\|", 2);
            var value = new AtomicLong();
            Gauge.builder(ATTEMPTS_BY_STATE, value, AtomicLong::doubleValue)
                    .description("Attempts currently in each state, by kind (master §6.4)")
                    .tag("kind", parts[0])
                    .tag("status", parts[1])
                    .register(meters);
            return value;
        });
    }

    private static double ageSeconds(Instant oldest, Instant now) {
        if (oldest == null) {
            return 0d;   // measured, and there is nothing of this kind: a real answer, not a missing one
        }
        return Math.max(0d, (now.toEpochMilli() - oldest.toEpochMilli()) / 1000d);
    }
}
