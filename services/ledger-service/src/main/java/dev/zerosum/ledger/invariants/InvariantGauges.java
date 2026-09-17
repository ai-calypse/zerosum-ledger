package dev.zerosum.ledger.invariants;

import io.micrometer.core.instrument.MeterRegistry;
import java.util.concurrent.atomic.AtomicInteger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Exports the D02-8 invariant checks as gauges (D07-1), so a violation raises an alert instead of waiting for someone
 * to open the invariants endpoint.
 *
 * <p>One series per invariant ID rather than a single "consistent" flag: an alert that fires without naming which
 * invariant broke sends an operator to read code at 3am.
 *
 * <p>Evaluated on a schedule, cheaply enough to leave running during measured runs. It is never switched off to
 * improve a performance result — a number obtained by disabling the thing that checks correctness is not a result.
 */
@Component
class InvariantGauges {

    private static final Logger log = LoggerFactory.getLogger(InvariantGauges.class);

    private final InvariantQueries queries;
    private final AtomicInteger i2 = new AtomicInteger();
    private final AtomicInteger i3 = new AtomicInteger();
    private final AtomicInteger i4 = new AtomicInteger();
    private final AtomicInteger clearing = new AtomicInteger();
    /** -1 until the first successful evaluation: absence is not the same as zero violations. */
    private final AtomicInteger lastEvaluationFailed = new AtomicInteger();

    InvariantGauges(InvariantQueries queries, MeterRegistry meters) {
        this.queries = queries;
        gauge(meters, "i2", i2, "Currencies whose balances do not sum to zero");
        gauge(meters, "i3", i3, "Accounts whose balance disagrees with its summed changelog deltas");
        gauge(meters, "i4", i4, "Changelog rows whose running balance is inconsistent");
        gauge(meters, "clearing_non_zero", clearing, "Clearing accounts that have not returned to zero");
        meters.gauge("ledger_invariant_evaluation_failed", lastEvaluationFailed, AtomicInteger::doubleValue);
    }

    private void gauge(MeterRegistry meters, String invariant, AtomicInteger value, String description) {
        io.micrometer.core.instrument.Gauge
                .builder("invariant_violations", value, AtomicInteger::doubleValue)
                .description(description)
                .tag("invariant", invariant)
                .register(meters);
    }

    @Scheduled(fixedDelayString = "${ledger.invariants.gauge-interval:30s}")
    void evaluate() {
        try {
            i2.set(queries.i2Violations().size());
            i3.set(queries.i3Violations().size());
            i4.set(queries.i4Violations().size());
            clearing.set(queries.nonZeroClearingBalances().size());
            lastEvaluationFailed.set(0);
        } catch (RuntimeException failure) {
            // Report the failure rather than leaving stale zeros standing: a gauge that keeps reading 0 because the
            // query stopped running is the most dangerous shape this signal can take.
            lastEvaluationFailed.set(1);
            log.warn("invariant gauge evaluation failed; the violation counts below are stale", failure);
        }
    }
}
