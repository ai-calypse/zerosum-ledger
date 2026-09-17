package dev.zerosum.fakeproviders.admin;

import dev.zerosum.fakeproviders.admin.AdminApi.Delivery;
import dev.zerosum.fakeproviders.admin.AdminApi.Truth;
import dev.zerosum.fakeproviders.admin.AdminApi.WebhookQueue;
import dev.zerosum.fakeproviders.bank.FakeBankApi.PayoutResponse;
import dev.zerosum.fakeproviders.card.FakeCardApi.ChargeResponse;
import dev.zerosum.fakeproviders.card.FakeCardApi.RefundResponse;
import dev.zerosum.fakeproviders.faults.FaultLog;
import java.sql.Timestamp;
import java.util.List;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

/**
 * Reads provider-side ground truth straight from the simulator's own tables (D05-2, I7).
 *
 * <p>Deliberately not routed through the provider services: those apply fault knobs, and truth that could be
 * withheld by a fault would be useless as the thing a test measures the system against.
 */
@Service
class TruthService {

    private final JdbcClient db;
    private final FaultLog faults;

    TruthService(JdbcClient db, FaultLog faults) {
        this.db = db;
        this.faults = faults;
    }

    /**
     * decision: CR-S05-02 — ground truth is queried by the reference the caller actually gave this provider.
     *
     * @param clientReference the client reference (the attempt id) or a provider-side id; null returns everything,
     *                        which is what the verifier and the fault tests use
     */
    Truth truth(String clientReference) {
        return new Truth(clientReference, charges(clientReference), refunds(clientReference), payouts(clientReference),
                faults.counts(), faults.entries(), queue());
    }

    private List<ChargeResponse> charges(String reference) {
        return db.sql("SELECT charge_id, client_reference, status, decline_code, amount_minor, currency, fee_minor, "
                        + "refunded_minor FROM card_charges"
                        + filter(reference, "charge_id") + " ORDER BY created_at, charge_id")
                .params(params(reference))
                .query((rs, rowNum) -> new ChargeResponse(rs.getString(1), rs.getString(2), rs.getString(3),
                        rs.getString(4), rs.getLong(5), rs.getString(6), rs.getLong(7), rs.getLong(8)))
                .list();
    }

    private List<RefundResponse> refunds(String reference) {
        return db.sql("SELECT refund_id, charge_id, client_reference, status, failure_code, amount_minor "
                        + "FROM card_refunds" + filter(reference, "refund_id") + " ORDER BY created_at, refund_id")
                .params(params(reference))
                .query((rs, rowNum) -> new RefundResponse(rs.getString(1), rs.getString(2), rs.getString(3),
                        rs.getString(4), rs.getString(5), rs.getLong(6)))
                .list();
    }

    private List<PayoutResponse> payouts(String reference) {
        return db.sql("SELECT payout_id, client_reference, status, return_code, amount_minor, currency, accepted_at, "
                        + "settled_at, returned_at FROM bank_payouts"
                        + filter(reference, "payout_id") + " ORDER BY accepted_at, payout_id")
                .params(params(reference))
                .query((rs, rowNum) -> new PayoutResponse(rs.getString(1), rs.getString(2), rs.getString(3),
                        rs.getString(4), rs.getLong(5), rs.getString(6), rs.getTimestamp(7).toInstant(),
                        instant(rs.getTimestamp(8)), instant(rs.getTimestamp(9))))
                .list();
    }

    /** Everything the sender still owes a receiver (§0.3 E3): empty is the only honest definition of drained. */
    private WebhookQueue queue() {
        List<Delivery> deliveries = db.sql("""
                SELECT event_id, provider, event_type, provider_ref, delivery_attempts, last_delivery_status,
                       reorder_held, next_attempt_at
                FROM provider_events
                WHERE delivered_at IS NULL
                ORDER BY next_attempt_at, event_id
                """)
                .query((rs, rowNum) -> new Delivery(rs.getString(1), rs.getString(2), rs.getString(3),
                        rs.getString(4), rs.getInt(5), (Integer) rs.getObject(6), rs.getBoolean(7),
                        rs.getTimestamp(8).toInstant()))
                .list();
        return new WebhookQueue(deliveries.size(), deliveries);
    }

    /** The filter matches either side of the relationship: the caller's reference, or the provider's own id. */
    private static String filter(String reference, String idColumn) {
        return reference == null ? "" : " WHERE client_reference = ? OR " + idColumn + " = ?";
    }

    private static Object[] params(String reference) {
        return reference == null ? new Object[0] : new Object[] {reference, reference};
    }

    private static java.time.Instant instant(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toInstant();
    }
}
