package dev.zerosum.order.api;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

/**
 * The create-order request body (D03-2, §0.3 C1).
 *
 * <p>It carries only client-supplied fields. {@code order_id}, {@code source} and {@code created_at} are server
 * assigned, so a body containing them is rejected rather than ignored — silently dropping them would let a caller
 * believe it had set them. Unknown fields are rejected for the same reason (configured on the mapper, TB1).
 */
record MoneyOrderRequest(
        @JsonProperty("order_group_id") String orderGroupId,
        String type,
        String reason,
        @JsonProperty("adjusts_order_id") String adjustsOrderId,
        List<EntryRequest> entries,
        Object metadata,
        @JsonProperty("effective_at") String effectiveAt) {

    record EntryRequest(
            @JsonProperty("entity_id") String entityId,
            String account,
            String currency,
            @JsonProperty("amount_minor") Long amountMinor) {
    }
}
