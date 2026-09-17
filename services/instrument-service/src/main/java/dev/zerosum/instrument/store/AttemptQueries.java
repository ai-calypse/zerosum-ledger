// decision: D05-13 — docs/step_05_instruments_fake_providers.md#decisions-and-outputs
package dev.zerosum.instrument.store;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

/**
 * Read-only projections of an attempt and its history, for the operator endpoints (D05-13).
 *
 * <p>The instrument token is deliberately not selected. It is the one field on the row that identifies a payment
 * instrument, and an endpoint that returns it would put it in every client log (D00-8 redaction).
 */
@Service
public class AttemptQueries {

    private final JdbcClient db;

    AttemptQueries(JdbcClient db) {
        this.db = db;
    }

    /** The attempt as the API presents it; field names are serialized in snake_case by this service's mapper. */
    public record Attempt(String attemptId, String kind, String status, String orderGroupId, String sourceOrderId,
            String payoutRunId, String entityId, String provider, String currency, long amountMinor,
            String providerRef, String failureCode, boolean blockedOnCapture, long version, Instant createdAt,
            Instant updatedAt) {
    }

    /** One row of the append-only history, numbered per attempt. */
    public record Transition(int seq, String fromStatus, String toStatus, String cause, Instant at) {
    }

    public Optional<Attempt> find(UUID attemptId) {
        return db.sql("""
                SELECT attempt_id, kind, status, order_group_id, source_order_id, payout_run_id, entity_id, provider,
                       currency, amount_minor, provider_ref, failure_code, blocked_on_capture, version, created_at,
                       updated_at
                  FROM payment_attempts WHERE attempt_id = :id
                """)
                .param("id", attemptId)
                .query((rs, rowNum) -> new Attempt(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4),
                        rs.getString(5), rs.getString(6), rs.getString(7), rs.getString(8), rs.getString(9),
                        rs.getLong(10), rs.getString(11), rs.getString(12), rs.getBoolean(13), rs.getLong(14),
                        instant(rs, 15), instant(rs, 16)))
                .optional();
    }

    /** History in sequence order, which is the order the statuses actually happened in. */
    public List<Transition> history(UUID attemptId) {
        return db.sql("""
                SELECT seq, from_status, to_status, cause, at
                  FROM attempt_transitions WHERE attempt_id = :id ORDER BY seq
                """)
                .param("id", attemptId)
                .query((rs, rowNum) -> new Transition(rs.getInt(1), rs.getString(2), rs.getString(3), rs.getString(4),
                        instant(rs, 5)))
                .list();
    }

    private static Instant instant(ResultSet rs, int column) throws SQLException {
        Timestamp value = rs.getTimestamp(column);
        return value == null ? null : value.toInstant();
    }
}
