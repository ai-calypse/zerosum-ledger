package dev.zerosum.contracts.kafka;

import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * The one definition of every topic the pipeline uses (D04-1, master §5.4).
 *
 * <p>Plain Java with no Spring dependency, so both services and any test can read the same values. Each service
 * declares the topics it produces to, consumes from or dead-letters to; identical declarations from several services
 * are intended, because topic creation is idempotent and no service should have to start after another.
 *
 * <p><strong>Partition count is part of the contract, not a tuning knob.</strong> The key is the order group
 * (ADR-0007), so changing the count remaps groups to partitions and breaks the per-group ordering the ledger relies
 * on. Nothing here ever adds partitions to an existing topic.
 */
public final class TopicDefinitions {

    /** Local cluster has one broker, so nothing can replicate (master §5.4). */
    public static final short LOCAL_REPLICATION_FACTOR = 1;

    private TopicDefinitions() {
    }

    /**
     * @param name         the topic name
     * @param partitions   fixed by the contract; see the class note on why this is not tunable
     * @param retention    how long records are kept
     * @param keySemantics what the record key means, so a producer cannot invent its own convention
     */
    public record TopicDefinition(String name, int partitions, Duration retention, String keySemantics) {

        /** Broker-side configuration for this topic, ready to hand to an admin client or a {@code NewTopic}. */
        public Map<String, String> configs() {
            return Map.of("retention.ms", Long.toString(retention.toMillis()));
        }
    }

    /** Money orders published by order-service's outbox relay and applied by ledger-service. */
    public static final TopicDefinition MONEY_ORDERS =
            new TopicDefinition("payments.money-orders.v1", 12, Duration.ofDays(7), "order_group_id");

    /** Payment facts published by instrument-service and mapped into orders by order-service. */
    public static final TopicDefinition PAYMENT_EVENTS =
            new TopicDefinition("payments.payment-events.v1", 12, Duration.ofDays(7), "order_group_id");

    /**
     * Dead-letter topics are smaller and kept far longer than their sources: they hold the records an operator has to
     * look at, and 7 days is not long enough for a poison record discovered on a Monday.
     */
    private static final int DLQ_PARTITIONS = 3;
    private static final Duration DLQ_RETENTION = Duration.ofDays(30);

    /**
     * The dead-letter topic for a source topic. One function, so a consumer cannot spell the destination differently
     * from the service that provisions it — the failure mode would be a DLQ publish into an auto-created topic nobody
     * is watching.
     */
    public static TopicDefinition dlqFor(TopicDefinition source) {
        return new TopicDefinition(source.name() + ".dlq", DLQ_PARTITIONS, DLQ_RETENTION,
                "the original record's key, preserved so a re-publish keeps its ordering");
    }

    /** Every topic in the pipeline, including dead-letter topics. */
    public static List<TopicDefinition> all() {
        return List.of(MONEY_ORDERS, PAYMENT_EVENTS, dlqFor(MONEY_ORDERS), dlqFor(PAYMENT_EVENTS));
    }

    /** Consumer group ids, named here so a service and its freshness query cannot disagree about the group. */
    public static final String LEDGER_APPLY_GROUP = "ledger-apply";
    public static final String ORDER_MAPPER_GROUP = "order-mapper";
    public static final String INSTRUMENT_POLICY_GROUP = "instrument-policy";
}
