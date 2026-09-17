package dev.zerosum.ledger.kafka;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.zerosum.contracts.GoldenPayloads;
import dev.zerosum.contracts.kafka.TopicDefinitions;
import dev.zerosum.ledger.support.LedgerQueries;
import dev.zerosum.ledger.support.LedgerTestDatabase;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

/**
 * A poison record is quarantined and dead-lettered, and the partition keeps moving (D04-4).
 *
 * <p>"Keeps moving" is the part that matters operationally: a poison record that blocked its partition would stop
 * every valid order behind it, turning one malformed message into an outage.
 */
class PoisonRecordIT extends LedgerPipelineTestBase {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    // No @AfterAll teardown: KAFKA and DB are statics on the shared base (see LedgerListenerIT).

    @Test
    void aPoisonRecordIsQuarantinedAndDeadLetteredWhileValidOrdersBehindItApply() throws SQLException {
        long quarantinedBefore = quarantinedCount();

        // A valid order, then bytes that are not an order at all, then another valid order — on one partition, by
        // publishing all three under the same key.
        String group = "trip_poison_" + UUID.randomUUID();
        String first = GoldenPayloads.byId("O1");
        String second = GoldenPayloads.byId("O2");
        publish(JSON.readTree(first).get("order_group_id").asString(), first);
        publish(group, "this is not json at all");
        publish(JSON.readTree(second).get("order_group_id").asString(), second);

        awaitUntil(() -> quarantinedCount() > quarantinedBefore,
                "the undecodable record was never quarantined");

        try (Connection c = DB.connect(LedgerTestDatabase.VERIFIER)) {
            // Both valid orders are present. Asserted as presence rather than as a delta: this class shares its
            // database with the other pipeline tests, so the goldens may already have been applied by one of them —
            // which is itself harmless, because applying an order twice is a no-op.
            assertEquals(1, LedgerQueries.count(c,
                    "SELECT count(*) FROM applied_orders WHERE order_id = '"
                            + JSON.readTree(first).get("order_id").asString() + "'"));
            assertEquals(1, LedgerQueries.count(c,
                    "SELECT count(*) FROM applied_orders WHERE order_id = '"
                            + JSON.readTree(second).get("order_id").asString() + "'"));

            // The quarantine row carries the Kafka coordinates, which is what a redelivery conflicts on and what an
            // operator follows back to the record.
            long undecodable = LedgerQueries.count(c,
                    "SELECT count(*) FROM quarantined_orders WHERE error_code = 'UNDECODABLE_PAYLOAD'"
                            + " AND kafka_topic IS NOT NULL AND kafka_offset IS NOT NULL");
            assertTrue(undecodable >= 1, "the undecodable record must be quarantined with its position");
        }

        List<ConsumerRecord<String, String>> dead = drainDlq();
        assertFalse(dead.isEmpty(), "the poison record must reach the dead-letter topic");
        ConsumerRecord<String, String> letter = dead.stream()
                .filter(r -> "this is not json at all".equals(r.value()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("the poison payload is not on the DLQ: " + dead.size()
                        + " records found"));

        assertEquals(group, letter.key(), "the original key is preserved, so a re-publish rejoins its order group");
        assertEquals("payments.money-orders.v1", header(letter, "zs-dlq-original-topic"));
        assertEquals("UNDECODABLE_PAYLOAD", header(letter, "zs-dlq-error-code"));
        assertEquals("POISON", header(letter, "zs-dlq-classification"));
        assertTrue(header(letter, "zs-dlq-original-offset") != null, "the original offset must be recorded");
    }

    @Test
    void thePartitionKeepsMovingAfterPoison() {
        // A valid order published after the poison one still applies, which is only true if the offset advanced past
        // the poison record rather than the listener retrying it forever.
        String payload = GoldenPayloads.byId("O5");
        String orderId = JSON.readTree(payload).get("order_id").asString();
        publish(JSON.readTree(payload).get("order_group_id").asString(), payload);

        awaitUntil(() -> appliedById(orderId) == 1,
                "an order published after a poison record never applied: the partition is blocked");
    }

    private static long appliedById(String orderId) {
        try (Connection c = DB.connect(LedgerTestDatabase.VERIFIER)) {
            return LedgerQueries.count(c, "SELECT count(*) FROM applied_orders WHERE order_id = '" + orderId + "'");
        } catch (SQLException failure) {
            throw new IllegalStateException(failure);
        }
    }

    private static long quarantinedCount() {
        try (Connection c = DB.connect(LedgerTestDatabase.VERIFIER)) {
            return LedgerQueries.count(c, "SELECT count(*) FROM quarantined_orders");
        } catch (SQLException failure) {
            throw new IllegalStateException(failure);
        }
    }

    private static String header(ConsumerRecord<String, String> record, String name) {
        var header = record.headers().lastHeader(name);
        return header == null ? null : new String(header.value(), StandardCharsets.UTF_8);
    }

    private static List<ConsumerRecord<String, String>> drainDlq() {
        Map<String, Object> config = new HashMap<>();
        config.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers());
        config.put(ConsumerConfig.GROUP_ID_CONFIG, "dlq-reader-" + UUID.randomUUID());
        config.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        config.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false);
        var records = new ArrayList<ConsumerRecord<String, String>>();
        try (var consumer = new KafkaConsumer<>(config, new StringDeserializer(), new StringDeserializer())) {
            consumer.subscribe(List.of(TopicDefinitions.dlqFor(TopicDefinitions.MONEY_ORDERS).name()));
            long deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos();
            int empty = 0;
            while (System.nanoTime() < deadline && empty < 3) {
                var polled = consumer.poll(Duration.ofMillis(500));
                if (polled.isEmpty()) {
                    empty++;
                } else {
                    empty = 0;
                    polled.forEach(records::add);
                }
            }
        }
        return records;
    }
}
