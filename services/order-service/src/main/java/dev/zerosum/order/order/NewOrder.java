package dev.zerosum.order.order;

import dev.zerosum.money.OrderCandidate;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * One money order as a caller supplies it (D03-1): the client-supplied fields plus the source system derived from the
 * authenticated principal and the idempotency key taken from the {@code Idempotency-Key} header (§0.3 C1).
 *
 * <p>Server-assigned values are deliberately absent. {@code order_id} and {@code created_at} are assigned by the
 * database, so a replay returns the originally stored ones; neither is ever taken from a request body.
 *
 * @param sourceSystem   derived from the principal, never from the body (D01-5 rule 8)
 * @param idempotencyKey from the request header, at most 255 characters (D03-2)
 * @param metadataJson   the raw metadata object, or null, which normalizes to {@code {}}
 */
public record NewOrder(String sourceSystem, String idempotencyKey, String type, String reason, String orderGroupId,
        UUID adjustsOrderId, List<OrderCandidate.Entry> entries, String metadataJson, Instant effectiveAt) {

    /** The validator's view of this order (D01-5), so the rules live in libs/money rather than here. */
    public OrderCandidate asCandidate() {
        return new OrderCandidate(type, reason, entries);
    }
}
