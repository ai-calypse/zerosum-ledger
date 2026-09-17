// decision: D05-6 — docs/step_05_instruments_fake_providers.md#decisions-and-outputs
package dev.zerosum.instrument.policy;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * Every statement the collection policy runs (D05-6).
 *
 * <p><strong>Nothing here changes an attempt's status.</strong> That belongs to {@code AttemptTransitions} alone,
 * which guards it with the optimistic version and writes the history row and the payment event in the same
 * transaction. The one {@code UPDATE} below touches {@code blocked_on_capture} and {@code amount_minor} and is
 * conditioned on the attempt still being {@code CREATED}, so it cannot race a submission into changing what is
 * already on the wire.
 *
 * <p>Insertion is {@code ON CONFLICT DO NOTHING} against {@code payment_attempts_one_per_order}. That is the whole
 * idempotency story for redelivered orders: a check-then-insert would let two deliveries both pass the check and
 * charge the rider twice, which is the failure this key exists to make impossible.
 */
@Component
class AttemptStore {

    private final JdbcClient db;

    AttemptStore(JdbcClient db) {
        this.db = db;
    }

    /** An attempt as the policy needs to see it. The token is included because a submission has to send it. */
    record AttemptRow(UUID attemptId, String kind, String status, String orderGroupId, String entityId,
            String provider, String instrumentToken, String currency, long amountMinor, String providerRef,
            boolean blockedOnCapture, long version) {

        boolean isCharge() {
            return "CHARGE".equals(kind);
        }

        boolean isRefund() {
            return "REFUND".equals(kind);
        }
    }

    /**
     * Inserts the attempt, or returns the one already there.
     *
     * <p>A conflict is redelivery, not an error: the same order arriving three times must produce one attempt. The
     * existing row is loaded and returned so the caller continues with it rather than branching on "was this new".
     */
    AttemptRow insertOrLoad(String kind, UUID sourceOrderId, String orderGroupId, String entityId, String provider,
            String instrumentToken, String currency, long amountMinor, boolean blockedOnCapture) {
        Optional<UUID> inserted = db.sql("""
                INSERT INTO payment_attempts (kind, order_group_id, source_order_id, entity_id, provider,
                                              instrument_token, currency, amount_minor, status, blocked_on_capture)
                VALUES (:kind, :group, :order, :entity, :provider, :token, :currency, :amount, 'CREATED', :blocked)
                ON CONFLICT (kind, source_order_id, entity_id, currency) DO NOTHING
                RETURNING attempt_id
                """)
                .param("kind", kind)
                .param("group", orderGroupId)
                .param("order", sourceOrderId)
                .param("entity", entityId)
                .param("provider", provider)
                .param("token", instrumentToken)
                .param("currency", currency)
                .param("amount", amountMinor)
                .param("blocked", blockedOnCapture)
                .query((rs, rowNum) -> rs.getObject(1, UUID.class))
                .optional();

        return inserted.flatMap(this::find)
                .or(() -> existing(kind, sourceOrderId, entityId, currency))
                .orElseThrow(() -> new IllegalStateException(
                        "the attempt for order " + sourceOrderId + " neither inserted nor exists"));
    }

    Optional<AttemptRow> find(UUID attemptId) {
        return db.sql(SELECT + " WHERE attempt_id = :id")
                .param("id", attemptId)
                .query(AttemptStore::row)
                .optional();
    }

    private Optional<AttemptRow> existing(String kind, UUID sourceOrderId, String entityId, String currency) {
        return db.sql(SELECT + """
                 WHERE kind = :kind AND source_order_id = :order AND entity_id = :entity AND currency = :currency
                """)
                .param("kind", kind)
                .param("order", sourceOrderId)
                .param("entity", entityId)
                .param("currency", currency)
                .query(AttemptStore::row)
                .optional();
    }

    /**
     * Every attempt in a group for one (entity, currency), oldest first.
     *
     * <p>Scoped to the currency because the refundable amount is a per-currency quantity: summing a group's USD and
     * EUR attempts would produce a number that is not money.
     */
    List<AttemptRow> group(String orderGroupId, String entityId, String currency) {
        return db.sql(SELECT + """
                 WHERE order_group_id = :group AND entity_id = :entity AND currency = :currency
                 ORDER BY created_at, attempt_id
                """)
                .param("group", orderGroupId)
                .param("entity", entityId)
                .param("currency", currency)
                .query(AttemptStore::row)
                .list();
    }

    /**
     * Unblocks a refund and sizes it to what is actually refundable.
     *
     * <p>Guarded on {@code CREATED} and on the flag still being set, so a concurrent release cannot unblock the same
     * refund twice and hand it to two submissions. The status column is deliberately untouched: moving an attempt
     * between statuses is {@code AttemptTransitions}' job and nobody else's.
     */
    int release(UUID attemptId, long amountMinor) {
        return db.sql("""
                UPDATE payment_attempts SET blocked_on_capture = false, amount_minor = :amount, updated_at = now()
                 WHERE attempt_id = :id AND status = 'CREATED' AND blocked_on_capture = true
                """)
                .param("amount", amountMinor)
                .param("id", attemptId)
                .update();
    }

    private static final String SELECT = """
            SELECT attempt_id, kind, status, order_group_id, entity_id, provider, instrument_token, currency,
                   amount_minor, provider_ref, blocked_on_capture, version
              FROM payment_attempts
            """;

    private static AttemptRow row(ResultSet rs, int rowNum) throws SQLException {
        return new AttemptRow(rs.getObject(1, UUID.class), rs.getString(2), rs.getString(3), rs.getString(4),
                rs.getString(5), rs.getString(6), rs.getString(7), rs.getString(8), rs.getLong(9), rs.getString(10),
                rs.getBoolean(11), rs.getLong(12));
    }
}
