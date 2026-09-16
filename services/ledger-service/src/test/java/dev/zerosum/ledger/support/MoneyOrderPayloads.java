package dev.zerosum.ledger.support;

import dev.zerosum.money.OrderCandidate;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

/** Renders generated orders as stored money-order JSON (D01-8) for the apply engine. Golden payloads come from libs/contracts. */
public final class MoneyOrderPayloads {

    private MoneyOrderPayloads() {
    }

    public static String render(UUID orderId, String groupId, OrderCandidate order) {
        return render(orderId, groupId, order, null);
    }

    /** {@code adjustsOrderId} is the adjusted order for a {@code fare.adjusted} order, or null (D01-8). */
    public static String render(UUID orderId, String groupId, OrderCandidate order, UUID adjustsOrderId) {
        String entries = order.entries().stream()
                .map(e -> "{\"entity_id\":\"" + e.entityId() + "\",\"account\":\"" + e.account() + "\",\"currency\":\""
                        + e.currency() + "\",\"amount_minor\":" + e.amountMinor() + "}")
                .collect(Collectors.joining(","));
        return "{\"schema\":\"zerosum.money_order.v1\",\"order_id\":\"" + orderId + "\",\"order_group_id\":\"" + groupId
                + "\",\"type\":\"" + order.type() + "\",\"reason\":\"" + order.reason() + "\",\"adjusts_order_id\":"
                + (adjustsOrderId == null ? "null" : "\"" + adjustsOrderId + "\"") + ","
                + "\"source\":{\"system\":\"trip-simulator\",\"idempotency_key\":\"" + orderId + "\"},"
                + "\"entries\":[" + entries + "],\"metadata\":{},"
                + "\"effective_at\":\"2026-09-15T10:00:00.000Z\",\"created_at\":\"2026-09-15T10:00:00.001Z\"}";
    }

    /** A minimal valid order between two entities, for tests that need one specific movement. */
    public static String transfer(UUID orderId, String groupId, String debitEntity, String debitAccount,
            String creditEntity, String creditAccount, String currency, long amountMinor) {
        return render(orderId, groupId, new OrderCandidate("COMMERCE", "trip.completed", List.of(
                OrderCandidate.Entry.of(debitEntity, debitAccount, currency, amountMinor),
                OrderCandidate.Entry.of(creditEntity, creditAccount, currency, -amountMinor))));
    }
}
