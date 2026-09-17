// decision: D05-4, D05-5 — docs/step_05_instruments_fake_providers.md#decisions-and-outputs
package dev.zerosum.instrument.store;

import dev.zerosum.contracts.kafka.TopicDefinitions;
import dev.zerosum.outbox.OutboxWriter;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The only code that changes an attempt's status (D05-4).
 *
 * <p>One transaction does three things together: the guarded update, the history row, and — when D05-5 says the
 * transition emits one — the outbox row. They commit together or not at all, so there is never a status change
 * without its event, and never an event describing a status the attempt did not reach.
 *
 * <p>The guard is {@code WHERE attempt_id = ? AND version = ? AND status = ?}. Two actors racing the same attempt —
 * a webhook and a sweeper, typically — cannot both win: the loser updates zero rows, learns nothing was applied, and
 * re-reads. A read-then-write would let both believe they moved the attempt and both emit an event, which downstream
 * becomes two money orders for one movement of money.
 */
@Service
public class AttemptTransitions {

    private final JdbcClient db;
    private final OutboxWriter outbox;
    private final Clock clock;

    AttemptTransitions(JdbcClient db, OutboxWriter outbox, Clock clock) {
        this.db = db;
        this.outbox = outbox;
        this.clock = clock;
    }

    /**
     * A requested status change.
     *
     * @param expectedVersion the version the caller read; the update applies only if it is still current
     * @param nextCheckAt     when a sweeper should look again, or null for a terminal status
     */
    public record Transition(UUID attemptId, long expectedVersion, String from, String to, String cause,
            String providerRef, String failureCode, Instant nextCheckAt) {

        public Transition {
            if (attemptId == null || from == null || to == null || cause == null) {
                throw new IllegalArgumentException("attempt, from, to and cause are required");
            }
            if (from.equals(to)) {
                throw new IllegalArgumentException("a transition must change the status: " + from);
            }
        }
    }

    /** What happened. Sealed so a caller cannot forget that losing the race is a normal outcome, not an error. */
    public sealed interface Result {

        /** Applied. {@code eventType} is present when the transition emitted a payment event. */
        record Applied(long version, Optional<String> eventType) implements Result {
        }

        /**
         * Nothing was written. Either another actor moved the attempt first, or the caller's {@code from} was never
         * the current status. The caller re-reads to tell those apart — the database cannot, and guessing here would
         * turn a legal concurrent update into a spurious error.
         */
        record LostRace() implements Result {
        }
    }

    @Transactional
    public Result apply(Transition transition) {
        int updated = db.sql("""
                UPDATE payment_attempts
                   SET status = :to,
                       version = version + 1,
                       updated_at = :now,
                       provider_ref = coalesce(:providerRef, provider_ref),
                       failure_code = coalesce(:failureCode, failure_code),
                       next_check_at = :nextCheckAt
                 WHERE attempt_id = :id AND version = :version AND status = :from
                """)
                .param("to", transition.to())
                .param("now", Timestamp.from(clock.instant()))
                .param("providerRef", transition.providerRef())
                .param("failureCode", transition.failureCode())
                .param("nextCheckAt", transition.nextCheckAt() == null ? null
                        : Timestamp.from(transition.nextCheckAt()))
                .param("id", transition.attemptId())
                .param("version", transition.expectedVersion())
                .param("from", transition.from())
                .update();

        if (updated == 0) {
            // Nothing else has been written yet, so there is nothing to undo; returning here keeps the caller's
            // transaction clean rather than marking it rollback-only.
            return new Result.LostRace();
        }

        PaymentEvents.Attempt attempt = read(transition.attemptId());
        appendHistory(transition);

        Optional<String> eventType = PaymentEvents.eventTypeFor(attempt.kind(), transition.from(), transition.to());
        eventType.ifPresent(type -> appendEvent(attempt, type));

        return new Result.Applied(attempt.version(), eventType);
    }

    /**
     * History is numbered per attempt. The guarded update above has already serialized writers for this attempt, so
     * only one transaction can be here at a time and the next sequence cannot be handed out twice.
     */
    private void appendHistory(Transition transition) {
        db.sql("""
                INSERT INTO attempt_transitions (attempt_id, seq, from_status, to_status, cause)
                SELECT :id, coalesce(max(seq), 0) + 1, :from, :to, :cause
                  FROM attempt_transitions WHERE attempt_id = :id
                """)
                .param("id", transition.attemptId())
                .param("from", transition.from())
                .param("to", transition.to())
                .param("cause", transition.cause())
                .update();
    }

    private void appendEvent(PaymentEvents.Attempt attempt, String eventType) {
        String payload = PaymentEvents.toJson(attempt, eventType, clock.instant());
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("schema", PaymentEvents.SCHEMA);
        headers.put("event_id", PaymentEvents.eventId(attempt.attemptId(), eventType));
        // No traceparent here: OutboxWriter captures the active span itself (§0.3 C11), so the trace continues
        // across this hop without every caller remembering to pass it.
        outbox.append(TopicDefinitions.PAYMENT_EVENTS.name(), attempt.orderGroupId(), payload, headers);
    }

    /** Reads the attempt inside the transaction, after the update, so the event carries the post-transition state. */
    public PaymentEvents.Attempt read(UUID attemptId) {
        return db.sql("""
                SELECT attempt_id, kind, order_group_id, source_order_id, entity_id, provider, instrument_token,
                       currency, amount_minor, status, provider_ref, failure_code, version
                  FROM payment_attempts WHERE attempt_id = :id
                """)
                .param("id", attemptId)
                .query((rs, rowNum) -> new PaymentEvents.Attempt(rs.getString(1), rs.getString(2), rs.getString(3),
                        rs.getString(4), rs.getString(5), rs.getString(6), rs.getString(7), rs.getString(8),
                        rs.getLong(9), rs.getString(10), rs.getString(11), rs.getString(12), rs.getLong(13)))
                .single();
    }
}
