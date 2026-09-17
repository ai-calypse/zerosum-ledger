package dev.zerosum.fakeproviders.faults;

import java.util.function.ToDoubleFunction;

/**
 * One kind of random choice the simulator makes (D05-2).
 *
 * <p>Each value owns its own seeded stream, so adding a knob does not shift the outcomes of the existing ones: a
 * shared stream would make every previously recorded chaos run irreproducible the day a knob was added.
 */
public enum Decision {

    /** Not a rate: draws the normal deviate the lognormal latency sample is built from. */
    LATENCY(null),
    HTTP_500(FaultKnobs::http500Rate),
    RESET_BEFORE_COMMIT(FaultKnobs::resetBeforeCommitRate),
    TIMEOUT_AFTER_COMMIT(FaultKnobs::timeoutAfterCommitRate),
    WEBHOOK_DROP(FaultKnobs::webhookDropRate),
    WEBHOOK_DUPLICATE(FaultKnobs::webhookDuplicateRate),
    WEBHOOK_REORDER(FaultKnobs::webhookReorderRate),
    RETURN(FaultKnobs::returnRate),
    /** Not a rate: draws the fraction of the maximum processing delay a payout waits. */
    PROCESSING_DELAY(null),
    /** decision: D06-1 (§0.3 C23) — settlement-report discrepancies, drawn once per line at report generation. */
    REPORT_MISSING_LINE(FaultKnobs::reportMissingLineRate),
    REPORT_OFF_BY_ONE(FaultKnobs::reportOffByOneRate),
    REPORT_DUPLICATE_LINE(FaultKnobs::reportDuplicateLineRate);

    private final ToDoubleFunction<FaultKnobs> rate;

    Decision(ToDoubleFunction<FaultKnobs> rate) {
        this.rate = rate;
    }

    double rate(FaultKnobs knobs) {
        return rate == null ? 0 : rate.applyAsDouble(knobs);
    }

    /** The value recorded in the fault log (§0.3 E2), and the key in the ground-truth fault counters. */
    public String faultType() {
        return name().toLowerCase();
    }
}
