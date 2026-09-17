package dev.zerosum.outbox;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import javax.sql.DataSource;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.transaction.PlatformTransactionManager;

/** The relay's publish contract (D03-5): id order per key, and rows marked only after the broker acknowledges. */
@Tag("integration")
class OutboxRelayIT {

    private static final String ORDERED_TOPIC = "payments.money-orders.ordered.v1";
    private static final String PAUSED_TOPIC = "payments.money-orders.paused.v1";
    private static final String HEADERS_TOPIC = "payments.money-orders.headers.v1";

    private static OutboxTestDatabase db;
    private static OutboxTestKafka kafka;
    private static DataSource dataSource;
    private static JdbcTemplate template;
    private static PlatformTransactionManager transactionManager;
    private static KafkaTemplate<String, String> producer;
    private static SimpleMeterRegistry registry;
    private static OutboxRelay relay;

    @BeforeAll
    static void start() {
        db = OutboxTestDatabase.start();
        kafka = OutboxTestKafka.start();
        for (String topic : List.of(ORDERED_TOPIC, PAUSED_TOPIC, HEADERS_TOPIC)) {
            kafka.createTopic(topic, 3);
        }
        dataSource = db.dataSource(OutboxTestDatabase.APP);
        template = OutboxTestSupport.template(dataSource);
        transactionManager = OutboxTestSupport.transactions(dataSource);
        producer = kafka.template();
        registry = new SimpleMeterRegistry();
        relay = new OutboxRelay(template, producer, OutboxTestSupport.properties(100, Duration.ofSeconds(3)),
                OutboxTestSupport.metrics(registry, template), transactionManager);
    }

    @AfterAll
    static void stop() {
        kafka.close();
        db.close();
    }

    @BeforeEach
    void clear() {
        OutboxTestSupport.clear(template);
    }

    @Test
    void rowsArePublishedInIdOrderPerKeyAndMarkedOnlyAfterAcknowledgement() {
        var appended = new ArrayList<Map.Entry<String, String>>();
        for (int group = 1; group <= 3; group++) {
            for (int n = 1; n <= 10; n++) {
                String key = "trip-" + group;
                appended.add(Map.entry(key, OutboxTestSupport.payload("o-" + group + "-" + n, key)));
            }
        }
        OutboxTestSupport.append(transactionManager, dataSource, ORDERED_TOPIC, appended);

        OutboxRelay.BatchResult result = relay.publishBatch();

        assertEquals(30, result.published());
        assertEquals(0, OutboxTestSupport.unpublishedCount(template), "every row must be marked after acknowledgement");

        List<ConsumerRecord<String, String>> records = kafka.drain(ORDERED_TOPIC, Duration.ofSeconds(20));
        assertEquals(30, records.size(), "every appended row must reach the topic exactly once");
        assertOrderWithinEachKey(records);
    }

    @Test
    void whileTheBrokerIsUnreachableNothingIsMarkedAndTheBacklogDrainsAfterwards() {
        OutboxTestSupport.append(transactionManager, dataSource, PAUSED_TOPIC,
                List.of(Map.entry("trip-paused", OutboxTestSupport.payload("o-paused-1", "trip-paused")),
                        Map.entry("trip-paused", OutboxTestSupport.payload("o-paused-2", "trip-paused"))));

        kafka.pause();
        try {
            // The send times out rather than failing fast, which is what a real broker outage looks like to a producer.
            assertThrows(RuntimeException.class, relay::publishBatch);
            assertEquals(2, OutboxTestSupport.unpublishedCount(template),
                    "a batch the broker never acknowledged must leave every row unpublished");
            assertEquals(0, OutboxTestSupport.publishedCount(template));
            assertTrue(OutboxTestSupport.counter(registry, OutboxMetrics.SEND_FAILURES) > 0,
                    "the failure must be counted, since this is the signal an operator sees");
            assertTrue(OutboxTestSupport.oldestUnpublishedAgeSeconds(template) > 0,
                    "the oldest-unpublished age must grow while the broker is down (master degraded mode)");
        } finally {
            kafka.unpause();
        }

        // After the broker returns the backlog drains without intervention.
        long deadline = System.nanoTime() + Duration.ofSeconds(60).toNanos();
        while (OutboxTestSupport.unpublishedCount(template) > 0 && System.nanoTime() < deadline) {
            try {
                relay.publishBatch();
            } catch (RuntimeException retryable) {
                // The producer may still be recovering its connection; the loop is what production does too.
            }
        }
        assertEquals(0, OutboxTestSupport.unpublishedCount(template), "the backlog must drain once the broker returns");

        List<String> orderIds = kafka.drain(PAUSED_TOPIC, Duration.ofSeconds(20)).stream()
                .map(record -> OutboxTestSupport.orderIdOf(record.value()))
                .distinct()
                .toList();
        assertTrue(orderIds.containsAll(List.of("o-paused-1", "o-paused-2")),
                "both rows must reach the topic once the broker is back, found " + orderIds);
    }

    @Test
    void headersIncludingTheOrderIdSurviveToTheBroker() {
        OutboxTestSupport.append(transactionManager, dataSource, HEADERS_TOPIC,
                List.of(Map.entry("trip-h", OutboxTestSupport.payload("o-h-1", "trip-h"))));

        relay.publishBatch();

        List<ConsumerRecord<String, String>> records = kafka.drain(HEADERS_TOPIC, Duration.ofSeconds(20));
        assertEquals(1, records.size());
        var header = records.getFirst().headers().lastHeader("order_id");
        assertTrue(header != null, "the order id header must reach the broker");
        assertEquals("o-h-1", new String(header.value(), StandardCharsets.UTF_8));
    }

    /** Within one key the relay must preserve id order, which is what ADR-0007's per-group ordering depends on. */
    static void assertOrderWithinEachKey(List<ConsumerRecord<String, String>> records) {
        var lastOffsetPerKey = new java.util.HashMap<String, Long>();
        var lastSequencePerKey = new java.util.HashMap<String, Integer>();
        for (ConsumerRecord<String, String> record : records) {
            String orderId = OutboxTestSupport.orderIdOf(record.value());
            int sequence = Integer.parseInt(orderId.substring(orderId.lastIndexOf('-') + 1));
            Integer previous = lastSequencePerKey.put(record.key(), sequence);
            Long previousOffset = lastOffsetPerKey.put(record.key(), record.offset());
            if (previous != null && previousOffset != null && record.offset() > previousOffset) {
                assertTrue(sequence > previous,
                        "key " + record.key() + " went backwards: " + previous + " then " + sequence);
            }
        }
    }
}
