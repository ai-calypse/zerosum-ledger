// decision: D05-5 — docs/step_05_instruments_fake_providers.md#decisions-and-outputs
package dev.zerosum.instrument.store;

import dev.zerosum.instrument.store.AttemptStateMachines.Event;
import dev.zerosum.instrument.store.AttemptStateMachines.Kind;
import dev.zerosum.instrument.store.AttemptStateMachines.State;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Records a transition the table forbids: one log line and one counter increment (S05-T08).
 *
 * <p>Both, not either. The log line names the attempt so the case can be investigated; the counter is what an alert
 * can watch, because a defect that refuses one transition per thousand is invisible in logs and obvious in a rate.
 *
 * <p>Separate from {@link AttemptTransitions} so the webhook receiver and the resolver record illegal events the same
 * way, and so this is testable without a database.
 */
@Component
public class IllegalTransitions {

    private static final Logger log = LoggerFactory.getLogger(IllegalTransitions.class);

    /** Master §6.4 naming. D07-1 owns metric names from S07 on; this one is provisional under D05-14. */
    public static final String COUNTER = "instrument_illegal_transitions_total";

    private final MeterRegistry meters;

    IllegalTransitions(MeterRegistry meters) {
        this.meters = meters;
    }

    /** An event the table has no row for, in this state. */
    public void record(UUID attemptId, Kind kind, State state, Event event) {
        record(attemptId, kind, state, event.name());
    }

    /**
     * A status change refused by {@link AttemptTransitions}, where the caller supplied a target rather than an event.
     *
     * @param event what was attempted, as {@code ->TARGET}. Tag cardinality stays bounded by kind × state × state.
     */
    void record(UUID attemptId, Kind kind, State state, String event) {
        log.warn("illegal transition refused: attempt_id={} kind={} state={} event={}", attemptId, kind, state, event);
        Counter.builder(COUNTER)
                .description("Transitions refused because the D05-5 table has no row for the state and event")
                .tag("kind", kind.name())
                .tag("state", state.name())
                .tag("event", event)
                .register(meters)
                .increment();
    }
}
