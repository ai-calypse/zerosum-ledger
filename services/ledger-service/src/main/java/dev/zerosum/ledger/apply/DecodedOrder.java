package dev.zerosum.ledger.apply;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** A money order the ledger accepted for applying: identity, source link (M6 (a)) and its entries in payload order. */
public record DecodedOrder(UUID orderId, String orderGroupId, String sourceSystem, String idempotencyKey,
        Instant createdAt, List<Entry> entries) {

    /** One signed line; debit positive, credit negative (ADR-0003). */
    public record Entry(String entityId, String accountCode, String currency, long amountMinor) {
    }
}
