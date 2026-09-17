package dev.zerosum.order.outbox;

import dev.zerosum.money.OrderCandidate;
import dev.zerosum.order.order.OrderStore;
import dev.zerosum.outbox.OutboxWriter;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Renders a newly created order as its published form and appends it to the outbox (D03-5, D01-8).
 *
 * <p>The published event <em>is</em> the stored order (§0.3 C1), including the server-assigned id and timestamps, so a
 * consumer sees exactly what was committed. The message key is the order group (ADR-0007), which keeps a trip's
 * records on one partition and therefore in order.
 */
class MoneyOrderPublisher implements OrderStore.CreatedOrderPublisher {

    private final OutboxWriter writer;
    private final String topic;

    MoneyOrderPublisher(OutboxWriter writer, String topic) {
        this.writer = writer;
        this.topic = topic;
    }

    @Override
    public void publish(OrderStore.StoredOrder order) {
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("schema", "zerosum.money_order.v1");
        headers.put("order_id", order.orderId().toString());
        writer.append(topic, order.orderGroupId(), payload(order), headers);
    }

    /** The D01-8 stored money order, built by hand so the wire form is visible and cannot drift with a mapper setting. */
    private static String payload(OrderStore.StoredOrder order) {
        String entries = order.entries().stream().map(MoneyOrderPublisher::entry).collect(Collectors.joining(","));
        return "{\"schema\":\"zerosum.money_order.v1\""
                + ",\"order_id\":\"" + order.orderId() + '"'
                + ",\"order_group_id\":" + quote(order.orderGroupId())
                + ",\"type\":" + quote(order.type())
                + ",\"reason\":" + quote(order.reason())
                + ",\"adjusts_order_id\":" + (order.adjustsOrderId() == null ? "null" : "\"" + order.adjustsOrderId() + "\"")
                + ",\"source\":{\"system\":" + quote(order.sourceSystem())
                + ",\"idempotency_key\":" + quote(order.idempotencyKey()) + "}"
                + ",\"entries\":[" + entries + "]"
                + ",\"metadata\":" + (order.metadataJson() == null || order.metadataJson().isBlank()
                        ? "{}" : order.metadataJson())
                + ",\"effective_at\":\"" + order.effectiveAt() + '"'
                + ",\"created_at\":\"" + order.createdAt() + '"'
                + "}";
    }

    private static String entry(OrderCandidate.Entry entry) {
        return "{\"entity_id\":" + quote(entry.entityId())
                + ",\"account\":" + quote(entry.account())
                + ",\"currency\":" + quote(entry.currency())
                + ",\"amount_minor\":" + entry.amountMinor() + "}";
    }

    private static String quote(String value) {
        return '"' + value.replace("\\", "\\\\").replace("\"", "\\\"") + '"';
    }
}
