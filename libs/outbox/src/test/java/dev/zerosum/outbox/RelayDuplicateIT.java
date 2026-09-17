package dev.zerosum.outbox;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.sql.DataSource;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.dao.TransientDataAccessResourceException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * M4(c), relay side: a crash between send and mark produces duplicates, and the duplicates must be byte-identical.
 *
 * <p>The failure is injected by a test-scoped {@link JdbcTemplate} that fails the mark statement once. It is test
 * code, not a production flag: the relay has no "fail here" switch, and adding one would put a way to lose money into
 * the shipped artefact.
 */
@Tag("integration")
class RelayDuplicateIT {

    private static final String TOPIC = "payments.money-orders.dup.v1";

    private static OutboxTestDatabase db;
    private static OutboxTestKafka kafka;
    private static DataSource dataSource;
    private static JdbcTemplate plainTemplate;
    private static PlatformTransactionManager transactionManager;
    private static final AtomicBoolean failNextMark = new AtomicBoolean();

    @BeforeAll
    static void start() {
        db = OutboxTestDatabase.start();
        kafka = OutboxTestKafka.start();
        kafka.createTopic(TOPIC, 3);
        dataSource = db.dataSource(OutboxTestDatabase.APP);
        plainTemplate = OutboxTestSupport.template(dataSource);
        transactionManager = OutboxTestSupport.transactions(dataSource);
    }

    @AfterAll
    static void stop() {
        kafka.close();
        db.close();
    }

    @BeforeEach
    void clear() {
        OutboxTestSupport.clear(plainTemplate);
        failNextMark.set(false);
    }

    @Test
    void aFailedMarkResendsTheRowAndTheDuplicateIsByteIdentical() {
        OutboxTestSupport.append(transactionManager, dataSource, TOPIC,
                List.of(Map.entry("trip-d", OutboxTestSupport.payload("o-d-1", "trip-d")),
                        Map.entry("trip-d", OutboxTestSupport.payload("o-d-2", "trip-d"))));

        var registry = new SimpleMeterRegistry();
        var relay = new OutboxRelay(new FailingMarkTemplate(dataSource), kafka.template(),
                OutboxTestSupport.properties(100, Duration.ofSeconds(3)),
                OutboxTestSupport.metrics(registry, plainTemplate), transactionManager);

        // First pass: the records reach the broker, then the mark fails and the whole batch rolls back.
        failNextMark.set(true);
        assertThrows(TransientDataAccessResourceException.class, relay::publishBatch);
        assertEquals(2, OutboxTestSupport.unpublishedCount(plainTemplate),
                "a failed mark must leave the rows unpublished — that is what makes the resend happen");

        // Second pass: the same rows are sent again, because the relay never assumes an unacknowledged mark succeeded.
        OutboxRelay.BatchResult result = relay.publishBatch();
        assertEquals(2, result.published());
        assertEquals(0, OutboxTestSupport.unpublishedCount(plainTemplate));

        List<ConsumerRecord<String, String>> records = kafka.drain(TOPIC, Duration.ofSeconds(20));
        assertEquals(4, records.size(), "each row must appear twice: once before the failed mark and once after");

        for (String orderId : List.of("o-d-1", "o-d-2")) {
            List<ConsumerRecord<String, String>> copies = records.stream()
                    .filter(record -> orderId.equals(OutboxTestSupport.orderIdOf(record.value())))
                    .toList();
            assertEquals(2, copies.size(), orderId + " should have been sent twice");
            ConsumerRecord<String, String> first = copies.get(0);
            ConsumerRecord<String, String> second = copies.get(1);
            assertEquals(first.key(), second.key(), "duplicate keys must match, or they could land on different partitions");
            assertEquals(first.value(), second.value(), "duplicate payloads must be byte-identical");
            assertEquals(headerOf(first), headerOf(second), "duplicate order-id headers must match");
        }
        assertTrue(records.stream().allMatch(record -> "trip-d".equals(record.key())));
    }

    private static String headerOf(ConsumerRecord<String, String> record) {
        var header = record.headers().lastHeader("order_id");
        return header == null ? null : new String(header.value(), StandardCharsets.UTF_8);
    }

    /** Fails the relay's mark statement once, simulating a crash after the send and before the commit. */
    private static final class FailingMarkTemplate extends JdbcTemplate {

        private FailingMarkTemplate(DataSource dataSource) {
            super(dataSource);
        }

        @Override
        public int[] batchUpdate(String sql, List<? extends Object[]> batchArgs) {
            if (sql.startsWith("UPDATE outbox SET published_at") && failNextMark.compareAndSet(true, false)) {
                throw new TransientDataAccessResourceException("simulated crash between send and mark");
            }
            return super.batchUpdate(sql, batchArgs);
        }
    }
}
