package dev.zerosum.order.api;

import com.fasterxml.jackson.annotation.JsonProperty;
import dev.zerosum.order.order.OrderStore;
import java.util.List;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** A stored money order as the API returns it (D03-2). The shape mirrors the D01-8 stored-order contract. */
record MoneyOrderResponse(
        @JsonProperty("order_id") String orderId,
        @JsonProperty("order_group_id") String orderGroupId,
        String type,
        String reason,
        @JsonProperty("adjusts_order_id") String adjustsOrderId,
        Source source,
        List<Entry> entries,
        // Required by the D01-8 stored-order contract, which also sets additionalProperties: false, so it must be
        // present and must be an object rather than the canonical string the store holds.
        JsonNode metadata,
        @JsonProperty("effective_at") String effectiveAt,
        @JsonProperty("created_at") String createdAt) {

    record Source(String system, @JsonProperty("idempotency_key") String idempotencyKey) {
    }

    record Entry(@JsonProperty("entity_id") String entityId, String account, String currency,
            @JsonProperty("amount_minor") long amountMinor) {
    }

    static MoneyOrderResponse of(OrderStore.StoredOrder order) {
        return new MoneyOrderResponse(order.orderId().toString(), order.orderGroupId(), order.type(), order.reason(),
                order.adjustsOrderId() == null ? null : order.adjustsOrderId().toString(),
                new Source(order.sourceSystem(), order.idempotencyKey()),
                order.entries().stream()
                        .map(e -> new Entry(e.entityId(), e.account(), e.currency(), e.amountMinor()))
                        .toList(),
                metadataOf(order),
                order.effectiveAt().toString(), order.createdAt().toString());
    }

    private static final JsonMapper JSON = JsonMapper.builder().build();

    /** The stored metadata as an object. The store keeps the canonical JSON text; the contract wants the object. */
    private static JsonNode metadataOf(OrderStore.StoredOrder order) {
        String json = order.metadataJson();
        return JSON.readTree(json == null || json.isBlank() ? "{}" : json);
    }
}
