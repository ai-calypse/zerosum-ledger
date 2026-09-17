package dev.zerosum.ledger.kafka;

import dev.zerosum.contracts.kafka.TopicDefinitions;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.admin.ListOffsetsResult;
import org.apache.kafka.clients.admin.OffsetSpec;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.springframework.boot.kafka.autoconfigure.KafkaProperties;
import org.springframework.stereotype.Component;

/**
 * How far behind the ledger is (D04-5): consumer lag in records, and the age of the oldest record not yet applied.
 *
 * <p><strong>Fails closed.</strong> Every path that cannot compute a number returns {@link Status#ERROR} with the
 * reason, never a zero. A freshness endpoint that reported "fresh" when it could not tell would be worse than no
 * endpoint at all: S05's payout run refuses above 5 s, so a false zero would authorise paying out against balances
 * nobody had checked.
 */
@Component
public class FreshnessCalculator implements FreshnessView {

    /** Bounded so a slow or unreachable broker becomes an error rather than a hung request. */
    private static final Duration CALL_TIMEOUT = Duration.ofSeconds(5);

    private final Admin admin;
    private final KafkaProperties kafkaProperties;
    private final PauseOnFailureErrorHandler errorHandler;

    FreshnessCalculator(Admin admin, KafkaProperties kafkaProperties, PauseOnFailureErrorHandler errorHandler) {
        this.admin = admin;
        this.kafkaProperties = kafkaProperties;
        this.errorHandler = errorHandler;
    }

    public enum Status {
        /** Lag and age were computed. */
        OK,
        /** They could not be computed. The values are absent, not zero. */
        ERROR
    }

    /**
     * @param status                 OK only when every value below was actually measured
     * @param totalLagRecords        records committed-to-latest across all partitions
     * @param partitionLag           per-partition lag, so a single stuck partition is visible
     * @param oldestUnappliedAgeSeconds age of the oldest record not yet applied, from its Kafka record timestamp
     *                                  (§0.3 C12), so it composes with outbox ages without counting a stage twice
     * @param listenerPaused         whether the apply listener is paused (§0.3 O9)
     * @param computedAt             when this was measured
     * @param error                  why, when the status is ERROR
     */
    public record Freshness(Status status, long totalLagRecords, Map<Integer, Long> partitionLag,
            double oldestUnappliedAgeSeconds, boolean listenerPaused, Instant computedAt, String error) {

        static Freshness error(String reason, boolean paused) {
            return new Freshness(Status.ERROR, 0, Map.of(), 0, paused, Instant.now(), reason);
        }
    }

    @Override
    public Freshness current() {
        return compute();
    }

    public Freshness compute() {
        boolean paused = errorHandler.isPaused();
        String topic = TopicDefinitions.MONEY_ORDERS.name();
        try {
            Map<TopicPartition, org.apache.kafka.clients.consumer.OffsetAndMetadata> committed =
                    admin.listConsumerGroupOffsets(TopicDefinitions.LEDGER_APPLY_GROUP)
                            .partitionsToOffsetAndMetadata()
                            .get(CALL_TIMEOUT.toSeconds(), TimeUnit.SECONDS);

            var partitions = admin.describeTopics(List.of(topic)).allTopicNames()
                    .get(CALL_TIMEOUT.toSeconds(), TimeUnit.SECONDS)
                    .get(topic).partitions();
            if (partitions.size() != TopicDefinitions.MONEY_ORDERS.partitions()) {
                // The key-to-partition mapping is not what the contract says, so any lag figure describes a topic
                // this service was not built against (ADR-0007).
                return Freshness.error("topic has " + partitions.size() + " partitions, expected "
                        + TopicDefinitions.MONEY_ORDERS.partitions(), paused);
            }

            Map<TopicPartition, OffsetSpec> latestSpecs = new HashMap<>();
            Map<TopicPartition, OffsetSpec> earliestSpecs = new HashMap<>();
            for (var partition : partitions) {
                var tp = new TopicPartition(topic, partition.partition());
                latestSpecs.put(tp, OffsetSpec.latest());
                earliestSpecs.put(tp, OffsetSpec.earliest());
            }
            Map<TopicPartition, ListOffsetsResult.ListOffsetsResultInfo> latest =
                    admin.listOffsets(latestSpecs).all().get(CALL_TIMEOUT.toSeconds(), TimeUnit.SECONDS);
            Map<TopicPartition, ListOffsetsResult.ListOffsetsResultInfo> earliest =
                    admin.listOffsets(earliestSpecs).all().get(CALL_TIMEOUT.toSeconds(), TimeUnit.SECONDS);

            var positions = new ArrayList<PartitionPosition>();
            for (var entry : latest.entrySet()) {
                TopicPartition tp = entry.getKey();
                var offsetAndMetadata = committed.get(tp);
                positions.add(new PartitionPosition(tp.partition(), entry.getValue().offset(),
                        earliest.get(tp).offset(),
                        offsetAndMetadata == null ? null : offsetAndMetadata.offset()));
            }

            Lag lag = lagOf(positions);
            if (lag.error() != null) {
                return Freshness.error(lag.error(), paused);
            }
            var unapplied = lag.laggingPartitions().stream().map(p -> new TopicPartition(topic, p)).toList();
            double age = unapplied.isEmpty() ? 0 : oldestAgeSeconds(unapplied, committed, earliest);
            return new Freshness(Status.OK, lag.total(), lag.byPartition(), age, paused, Instant.now(), null);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return Freshness.error("interrupted while computing freshness", paused);
        } catch (Exception failure) {
            return Freshness.error(failure.getClass().getSimpleName() + ": " + failure.getMessage(), paused);
        }
    }

    /**
     * One partition's offsets.
     *
     * @param committedOffset null when the group has never committed here
     */
    record PartitionPosition(int partition, long latestOffset, long earliestOffset, Long committedOffset) {
    }

    /** The computed lag, or the reason it cannot be trusted. */
    record Lag(long total, Map<Integer, Long> byPartition, List<Integer> laggingPartitions, String error) {

        static Lag error(String reason) {
            return new Lag(0, Map.of(), List.of(), reason);
        }
    }

    /**
     * Lag arithmetic, with no broker involved, so every failure branch below is reachable from a unit test. While this
     * logic lived inline behind live admin calls, none of it could be exercised without a running cluster — which
     * would have meant recording these cases as "covered" on the strength of a test that never reached them.
     */
    static Lag lagOf(List<PartitionPosition> positions) {
        var byPartition = new HashMap<Integer, Long>();
        var lagging = new ArrayList<Integer>();
        long total = 0;
        for (PartitionPosition position : positions) {
            // No committed offset yet: everything still on the partition is unapplied, counted from its earliest
            // available record rather than from zero, which would count records retention has already removed.
            long cursor = position.committedOffset() == null ? position.earliestOffset() : position.committedOffset();
            if (cursor < position.earliestOffset()) {
                // Records were removed by retention before they were consumed. Under replication factor 1 that is lost
                // money data; reporting a small lag would describe the gap as if it were nothing.
                return Lag.error("committed offset " + cursor + " is below the log start " + position.earliestOffset()
                        + " on partition " + position.partition() + ": records were removed before being applied");
            }
            long lag = Math.max(0, position.latestOffset() - cursor);
            byPartition.put(position.partition(), lag);
            total += lag;
            if (lag > 0) {
                lagging.add(position.partition());
            }
        }
        return new Lag(total, Map.copyOf(byPartition), List.copyOf(lagging), null);
    }

    /** Clamps a future record timestamp to zero rather than reporting a negative age (a clock anomaly upstream). */
    static double ageSecondsFrom(long recordTimestampMillis, long nowMillis) {
        return Math.max(0, (nowMillis - recordTimestampMillis) / 1000.0);
    }

    /**
     * The age of the oldest record no consumer has applied yet, read with a group-less consumer assigned directly to
     * the lagging partitions so it never disturbs the apply group's offsets.
     */
    private double oldestAgeSeconds(List<TopicPartition> lagging,
            Map<TopicPartition, org.apache.kafka.clients.consumer.OffsetAndMetadata> committed,
            Map<TopicPartition, ListOffsetsResult.ListOffsetsResultInfo> earliest) {
        Map<String, Object> config = new HashMap<>(kafkaProperties.buildConsumerProperties());
        config.remove("group.id");
        config.put("enable.auto.commit", false);
        config.put("client.id", "ledger-freshness-probe");

        long oldestTimestamp = Long.MAX_VALUE;
        try (Consumer<String, String> consumer =
                new KafkaConsumer<>(config, new StringDeserializer(), new StringDeserializer())) {
            consumer.assign(lagging);
            for (TopicPartition tp : lagging) {
                var offsetAndMetadata = committed.get(tp);
                consumer.seek(tp, offsetAndMetadata == null ? earliest.get(tp).offset() : offsetAndMetadata.offset());
            }
            var polled = consumer.poll(CALL_TIMEOUT);
            for (ConsumerRecord<String, String> record : polled) {
                oldestTimestamp = Math.min(oldestTimestamp, record.timestamp());
            }
        }
        if (oldestTimestamp == Long.MAX_VALUE) {
            return 0;   // lag was reported but nothing could be fetched; the caller still sees the lag itself
        }
        return ageSecondsFrom(oldestTimestamp, System.currentTimeMillis());
    }
}
