// decision: D05-8 — docs/step_05_instruments_fake_providers.md#decisions-and-outputs
package dev.zerosum.instrument.recovery;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * Every statement the sweeper and the resolver run (D05-8).
 *
 * <p><strong>Nothing here changes an attempt's status.</strong> That belongs to {@code AttemptTransitions} alone,
 * which guards it with the optimistic version and writes the history row and the payment event in one transaction.
 * The single {@code UPDATE} below moves {@code next_check_at} and nothing else — it is bookkeeping about when to look
 * again, not a claim about what happened to the money, and it is conditioned on the attempt still being
 * {@code UNKNOWN} so it cannot re-arm an attempt that has already resolved.
 *
 * <p>The due-work queries all sit inside the {@code attempts_due} partial index from {@code V2}, which covers exactly
 * {@code CREATED}, {@code SUBMITTING} and {@code UNKNOWN}. That is why the sweeper stays cheap as the table grows:
 * it never scans attempts that have finished.
 *
 * <p><strong>Times come from the injected clock, not from SQL {@code now()}.</strong> Mixing the two would make the
 * thresholds untestable without sleeping, and worse, would compare a database clock against a service clock on a
 * multi-host deployment — exactly the skew master §6.4 warns about.
 */
@Component
class RecoveryQueries {

    private final JdbcClient db;

    RecoveryQueries(JdbcClient db) {
        this.db = db;
    }

    /** An attempt the sweeper has picked up; the kind decides which submitter owns it. */
    record DueAttempt(UUID attemptId, String kind) {
    }

    /**
     * When the attempt last entered each of the two statuses the resolver reasons about.
     *
     * @param enteredUnknownAt  drives the resolution schedule and the review cut-off
     * @param lastSubmittedAt   drives the quiet period — "the most recent submission", per ADR-0010, which is the
     *                          only reading that stays correct when a resubmission is itself interrupted
     */
    record AttemptTimes(Instant enteredUnknownAt, Instant lastSubmittedAt) {
    }

    /**
     * {@code UNKNOWN} attempts whose next check is due.
     *
     * <p>A null {@code next_check_at} counts as due. An {@code UNKNOWN} attempt with no scheduled check is a defect
     * somewhere upstream, and the safe reading of it is "look at this now" — the alternative is an attempt sitting
     * unresolved and invisible for ever, which is the precise failure M8(c) exists to forbid.
     */
    List<UUID> dueUnknown(Instant now, int limit) {
        return db.sql("""
                SELECT attempt_id FROM payment_attempts
                 WHERE status = 'UNKNOWN' AND (next_check_at IS NULL OR next_check_at <= :now)
                 ORDER BY next_check_at NULLS FIRST
                 LIMIT :limit
                """)
                .param("now", Timestamp.from(now))
                .param("limit", limit)
                .query(UUID.class)
                .list();
    }

    /** Attempts still in {@code SUBMITTING} past the master §5.11 threshold: the call is over, the outcome is not. */
    List<UUID> timedOutSubmitting(Instant before, int limit) {
        return db.sql("""
                SELECT attempt_id FROM payment_attempts
                 WHERE status = 'SUBMITTING' AND updated_at < :before
                 ORDER BY updated_at
                 LIMIT :limit
                """)
                .param("before", Timestamp.from(before))
                .param("limit", limit)
                .query(UUID.class)
                .list();
    }

    /**
     * Attempts left in {@code CREATED} past the threshold (§0.3 C24): a crash before submission, or a kill switch
     * that was off when they were created.
     *
     * <p>{@code blocked_on_capture} attempts are excluded. A refund waiting for its capture is not stuck — it is
     * waiting for exactly the thing that will size it — and submitting it here would send a refund with no charge to
     * reverse.
     */
    List<DueAttempt> oldCreated(Instant before, int limit) {
        return db.sql("""
                SELECT attempt_id, kind FROM payment_attempts
                 WHERE status = 'CREATED' AND blocked_on_capture = false AND updated_at < :before
                 ORDER BY updated_at
                 LIMIT :limit
                """)
                .param("before", Timestamp.from(before))
                .param("limit", limit)
                .query((rs, rowNum) -> new DueAttempt(rs.getObject(1, UUID.class), rs.getString(2)))
                .list();
    }

    /**
     * {@code PENDING} payouts older than the polling threshold, so a dropped webhook still converges (master §6.6).
     *
     * <p>Deliberately not restricted to the {@code attempts_due} index: {@code PENDING} is not in it, because a
     * pending payout is not stuck — it is waiting on a banking day. This is a slower, rarer query by design.
     */
    List<UUID> stalePendingPayouts(Instant before, int limit) {
        return db.sql("""
                SELECT attempt_id FROM payment_attempts
                 WHERE kind = 'PAYOUT' AND status = 'PENDING' AND updated_at < :before
                 ORDER BY updated_at
                 LIMIT :limit
                """)
                .param("before", Timestamp.from(before))
                .param("limit", limit)
                .query(UUID.class)
                .list();
    }

    /**
     * Reads both timestamps the resolver needs in one round trip, from the append-only history.
     *
     * <p>The history is used rather than {@code updated_at} because {@code updated_at} answers "when did anything
     * last change", and the quiet period needs "when did we last send this to a bank" — two different questions that
     * happen to have the same answer right up until the moment they do not.
     */
    Optional<AttemptTimes> timesOf(UUID attemptId) {
        return db.sql("""
                SELECT max(at) FILTER (WHERE to_status = 'UNKNOWN'),
                       max(at) FILTER (WHERE to_status = 'SUBMITTING')
                  FROM attempt_transitions WHERE attempt_id = :id
                """)
                .param("id", attemptId)
                .query((rs, rowNum) -> new AttemptTimes(instant(rs.getTimestamp(1)), instant(rs.getTimestamp(2))))
                .optional();
    }

    /**
     * Re-arms the next check. Status, version and {@code updated_at} are untouched on purpose.
     *
     * <p>Leaving {@code updated_at} alone is what keeps the resolution schedule and the review cut-off measured from
     * when the attempt became {@code UNKNOWN}: if every check bumped it, an attempt checked often would look
     * perpetually fresh and would never reach {@code NEEDS_REVIEW}.
     *
     * @return false when the attempt has left {@code UNKNOWN} meanwhile, which means somebody resolved it
     */
    boolean reschedule(UUID attemptId, Instant nextCheckAt) {
        return db.sql("""
                UPDATE payment_attempts SET next_check_at = :at
                 WHERE attempt_id = :id AND status = 'UNKNOWN'
                """)
                .param("at", Timestamp.from(nextCheckAt))
                .param("id", attemptId)
                .update() == 1;
    }

    /**
     * The provider's reference for the captured charge a refund reverses, needed to re-issue that refund.
     *
     * <p>The policy's own {@code GroupLedger} answers the same question for the submission path, but it and the row
     * type it reads are package-private to {@code ..instrument.policy}. Restating the one column here beats widening
     * that package's surface for a single read; the judgement it encodes — which charge counts as captured — is the
     * {@code SUCCEEDED} status in the query and nothing more.
     */
    Optional<String> capturedChargeRef(String orderGroupId, String entityId, String currency) {
        return db.sql("""
                SELECT provider_ref FROM payment_attempts
                 WHERE kind = 'CHARGE' AND order_group_id = :group AND entity_id = :entity AND currency = :currency
                   AND status = 'SUCCEEDED' AND provider_ref IS NOT NULL
                 ORDER BY created_at
                 LIMIT 1
                """)
                .param("group", orderGroupId)
                .param("entity", entityId)
                .param("currency", currency)
                .query(String.class)
                .optional();
    }

    /** How many attempts sit in each (kind, status), for the §0.3 O9 state gauge. */
    Map<String, Long> stateCounts() {
        return db.sql("SELECT kind, status, count(*) FROM payment_attempts GROUP BY kind, status")
                .query((rs, rowNum) -> Map.entry(rs.getString(1) + '|' + rs.getString(2), rs.getLong(3)))
                .list().stream()
                .collect(java.util.stream.Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
    }

    /**
     * The oldest {@code UNKNOWN} attempt and the oldest {@code PENDING} payout, as instants (§0.3 O9).
     *
     * <p>Null means "none of those exist", which the caller reports as an age of zero — a real answer. It is
     * different from a measurement that could not be taken, which is reported as absent.
     */
    record OldestAges(Instant oldestUnknown, Instant oldestPendingPayout) {
    }

    OldestAges oldestAges() {
        return db.sql("""
                SELECT min(updated_at) FILTER (WHERE status = 'UNKNOWN'),
                       min(updated_at) FILTER (WHERE status = 'PENDING' AND kind = 'PAYOUT')
                  FROM payment_attempts
                """)
                .query((rs, rowNum) -> new OldestAges(instant(rs.getTimestamp(1)), instant(rs.getTimestamp(2))))
                .single();
    }

    private static Instant instant(Timestamp value) {
        return value == null ? null : value.toInstant();
    }
}
