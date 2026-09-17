package dev.zerosum.order.consumer;

import dev.zerosum.contracts.ContractSchemas;
import dev.zerosum.money.OrderCandidate;
import dev.zerosum.money.Violation;
import dev.zerosum.money.ZeroSumValidator;
import dev.zerosum.order.mapping.PaymentEventMapper;
import dev.zerosum.order.order.NewOrder;
import dev.zerosum.order.order.OrderStore;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Turns payment events into money orders (D03-6, ADR-0006).
 *
 * <p>One record at a time, acknowledged manually after the transaction commits. A crash between commit and
 * acknowledgement redelivers the event, which replays and is acknowledged — the duplicate costs nothing, because
 * {@code event_id} is the idempotency key (I8).
 *
 * <p><strong>Nothing here publishes to Kafka.</strong> The order and its outbox row commit together in the store, and
 * the relay publishes later (M4(b)).
 */
public class PaymentEventListener {

    private static final Logger log = LoggerFactory.getLogger(PaymentEventListener.class);
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final ZeroSumValidator VALIDATOR = ZeroSumValidator.defaults();

    /** The internal mapper principal (§0.3 C7, D03-4): mapped orders are attributed to instrument-service. */
    private static final String MAPPER_SOURCE_SYSTEM = "instrument-service";

    private final OrderStore store;
    private final QuarantineStore quarantine;
    private final MeterRegistry meters;
    private final Map<String, Counter> counters = new ConcurrentHashMap<>();

    public PaymentEventListener(OrderStore store, QuarantineStore quarantine, MeterRegistry meters) {
        this.store = store;
        this.quarantine = quarantine;
        this.meters = meters;
    }

    @KafkaListener(
            topics = "${zs.consumer.payment-events-topic}",
            groupId = "${zs.consumer.group-id}",
            autoStartup = "${zs.consumer.enabled:true}")
    public void onEvent(ConsumerRecord<String, String> record, Acknowledgment acknowledgment) {
        String payload = record.value();

        // 1. Schema first. An event that does not match the contract is never guessed at.
        List<ContractSchemas.SchemaError> schemaErrors =
                ContractSchemas.validate(ContractSchemas.PAYMENT_EVENT_V1, payload == null ? "{}" : payload);
        if (!schemaErrors.isEmpty()) {
            poison(record, null, "schema_invalid", schemaErrors.toString());
            acknowledgment.acknowledge();
            return;
        }

        JsonNode event = JSON.readTree(payload);
        String eventId = event.get("event_id").asString();

        switch (PaymentEventMapper.map(event)) {
            case PaymentEventMapper.NoOrder noOrder -> {
                // A normal outcome, not a failure: the books are already correct without an order.
                count(noOrder.metricName());
                acknowledgment.acknowledge();
            }
            case PaymentEventMapper.Poison badEvent -> {
                poison(record, eventId, badEvent.code(), badEvent.detail());
                acknowledgment.acknowledge();
            }
            case PaymentEventMapper.OrderDraft draft -> {
                handle(record, eventId, draft);
                acknowledgment.acknowledge();
            }
        }
    }

    private void handle(ConsumerRecord<String, String> record, String eventId, PaymentEventMapper.OrderDraft draft) {
        // The same validation an API order goes through (D01-5, D01-6). A failure here is a mapper bug or a bad event,
        // never something to write anyway.
        List<Violation> violations =
                VALIDATOR.validate(new OrderCandidate(draft.type(), draft.reason(), draft.entries()));
        if (!violations.isEmpty()) {
            poison(record, eventId, "validation_failed", violations.toString());
            return;
        }

        NewOrder order = new NewOrder(MAPPER_SOURCE_SYSTEM, draft.idempotencyKey(), draft.type(), draft.reason(),
                draft.orderGroupId(), null, draft.entries(), draft.metadataJson(), draft.effectiveAt());

        OrderStore.Result result = store.create(order);
        switch (result.status()) {
            case CREATED -> count("orders_mapped_total");
            // Duplicate delivery, which is expected rather than exceptional (I8).
            case REPLAYED -> count("payment_events_replayed_total");
            // The same event id carrying different content: the producer changed history. That is an integrity breach,
            // not a retryable error, so it is quarantined and logged loudly rather than written under a new key.
            case KEY_REUSED -> {
                log.error("event {} was already applied with different content; quarantining", eventId);
                poison(record, eventId, "idempotency_key_reused",
                        "the same event id previously produced a different order");
            }
            case NOT_ZERO_SUM -> poison(record, eventId, "not_zero_sum", "the mapped order was rejected at COMMIT");
            // Another delivery of the same event is mid-flight. Leaving it unacknowledged would redeliver it forever;
            // the in-flight one will finish, so this delivery is simply dropped.
            case IN_PROGRESS -> count("payment_events_in_progress_total");
        }
    }

    private void poison(ConsumerRecord<String, String> record, String eventId, String code, String detail) {
        quarantine.quarantine(eventId, record.value(), code, detail,
                record.topic(), record.partition(), record.offset());
        count("payment_events_quarantined_total");
        log.error("quarantined {}-{}@{} as {}: {}", record.topic(), record.partition(), record.offset(), code, detail);
    }

    private void count(String name) {
        counters.computeIfAbsent(name, meters::counter).increment();
    }
}
