// decision: D05-6, D04-3, D04-4 — docs/step_05_instruments_fake_providers.md#decisions-and-outputs
package dev.zerosum.instrument.policy;

import dev.zerosum.contracts.ContractSchemas;
import java.util.List;
import java.util.UUID;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The {@code instrument-policy} consumer (D04-1, D04-3): money orders in, at most one attempt out.
 *
 * <p><strong>Acknowledge only after the policy transaction commits.</strong> A crash before the acknowledgement
 * redelivers the order, and the attempt uniqueness key makes the repeat a no-op. The reverse ordering would move the
 * offset past an order whose charge was never created, and a rider would simply never be billed.
 *
 * <p><strong>Submission happens after the acknowledgement, not before.</strong> The attempt is durable by then, so
 * the listener thread is free to poll again while providers are slow; an attempt whose submission never started is
 * left in {@code CREATED} for the S05-T12 sweeper.
 *
 * <p>Poison — an undecodable record or one that fails the D01-8 schema — is quarantined in this service's own
 * database and acknowledged, so one bad record cannot stop every order behind it on the partition. Anything else,
 * a database outage above all, propagates: nothing is acknowledged and the container pauses (D04-4).
 */
@Component
class MoneyOrderPolicyListener {

    private static final Logger log = LoggerFactory.getLogger(MoneyOrderPolicyListener.class);
    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final CollectionPolicyService policy;
    private final AttemptSubmitter submitter;
    private final PolicyQuarantine quarantine;
    private final PolicyMetrics metrics;

    MoneyOrderPolicyListener(CollectionPolicyService policy, AttemptSubmitter submitter, PolicyQuarantine quarantine,
            PolicyMetrics metrics) {
        this.policy = policy;
        this.submitter = submitter;
        this.quarantine = quarantine;
        this.metrics = metrics;
    }

    @KafkaListener(
            topics = "#{T(dev.zerosum.contracts.kafka.TopicDefinitions).MONEY_ORDERS.name()}",
            groupId = "#{T(dev.zerosum.contracts.kafka.TopicDefinitions).INSTRUMENT_POLICY_GROUP}",
            autoStartup = "${zs.policy.consumer.enabled:true}")
    void onRecord(ConsumerRecord<String, String> record, Acknowledgment acknowledgment) {
        JsonNode order = decode(record);
        if (order == null) {
            acknowledgment.acknowledge();
            return;
        }

        // Reached only when plan() returned, which means its transaction committed.
        List<UUID> toSubmit = policy.plan(order);
        acknowledgment.acknowledge();
        submitter.submitLater(toSubmit);
    }

    /**
     * The decoded order, or null when it was quarantined. Schema first: a bad order is never guessed at.
     *
     * <p>Decoding is classified before anything is written, rather than quarantining from inside the {@code catch}.
     * The validator <em>throws</em> on bytes that are not JSON at all rather than returning errors, so a record of
     * random bytes would otherwise escape as an exception, pause the partition, and be redelivered for ever — a
     * single malformed record stopping every rider's collection behind it.
     */
    private JsonNode decode(ConsumerRecord<String, String> record) {
        String payload = record.value();
        String code = null;
        String detail = null;
        JsonNode order = null;
        try {
            List<ContractSchemas.SchemaError> errors =
                    ContractSchemas.validate(ContractSchemas.MONEY_ORDER_V1, payload == null ? "{}" : payload);
            if (errors.isEmpty()) {
                order = JSON.readTree(payload);
            } else {
                code = "schema_invalid";
                detail = errors.toString();
            }
        } catch (RuntimeException undecodable) {
            code = "undecodable";
            detail = undecodable.toString();
        }
        if (code == null) {
            return order;
        }
        // Outside the try: a database failure here is transient, and it must propagate and pause rather than be
        // mistaken for another decoding problem.
        poison(record, code, detail);
        return null;
    }

    private void poison(ConsumerRecord<String, String> record, String code, String detail) {
        quarantine.quarantine(record.value(), code, detail, record.topic(), record.partition(), record.offset());
        metrics.quarantined(code);
        log.error("quarantined {}-{}@{} as {}: {}", record.topic(), record.partition(), record.offset(), code, detail);
    }
}
