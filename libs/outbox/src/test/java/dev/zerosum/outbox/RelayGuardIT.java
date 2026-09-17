package dev.zerosum.outbox;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;
import javax.sql.DataSource;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * The single-instance guard (ADR-0008): a second relay must not publish concurrently, because {@code SKIP LOCKED}
 * alone would let it send a later batch while the first still holds earlier rows, breaking per-key order.
 */
@Tag("integration")
class RelayGuardIT {

    private static final String TOPIC = "payments.money-orders.guard.v1";

    private static OutboxTestDatabase db;
    private static OutboxTestKafka kafka;
    private static DataSource dataSource;
    private static JdbcTemplate template;
    private static PlatformTransactionManager transactionManager;

    @BeforeAll
    static void start() {
        db = OutboxTestDatabase.start();
        kafka = OutboxTestKafka.start();
        kafka.createTopic(TOPIC, 3);
        dataSource = db.dataSource(OutboxTestDatabase.APP);
        template = OutboxTestSupport.template(dataSource);
        transactionManager = OutboxTestSupport.transactions(dataSource);
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
    void aRelayWhoseGuardIsHeldElsewhereSkipsTheBatchAndCountsIt() throws SQLException {
        OutboxTestSupport.append(transactionManager, dataSource, TOPIC,
                List.of(Map.entry("trip-g", OutboxTestSupport.payload("o-g-1", "trip-g"))));
        var registry = new SimpleMeterRegistry();
        var relay = relay(registry);

        // Hold the very lock the relay takes, from another connection, so the skip is deterministic rather than a race
        // two relays might or might not lose.
        try (Connection holder = db.connect(OutboxTestDatabase.APP)) {
            holder.setAutoCommit(false);
            try (var statement = holder.prepareStatement("SELECT pg_try_advisory_xact_lock(?)")) {
                statement.setLong(1, OutboxRelay.ADVISORY_LOCK_KEY);
                try (var rs = statement.executeQuery()) {
                    assertTrue(rs.next() && rs.getBoolean(1), "the test must hold the guard for this to mean anything");
                }
            }

            OutboxRelay.BatchResult result = relay.publishBatch();

            assertTrue(result.skippedByGuard(), "the batch must be skipped while another holder has the guard");
            assertEquals(0, result.published());
            assertEquals(1, OutboxTestSupport.unpublishedCount(template), "a skipped batch must publish nothing");
            assertEquals(1, OutboxTestSupport.counter(registry, OutboxMetrics.GUARD_SKIPS),
                    "the skip must be counted, so a misconfigured second relay is visible rather than silent");
            holder.rollback();
        }

        // Once the guard is free the same relay publishes normally.
        assertEquals(1, relay.publishBatch().published());
    }

    @Test
    void twoRelaysOverOneDatabasePreservePerKeyOrder() throws InterruptedException {
        var appended = new ArrayList<Map.Entry<String, String>>();
        for (int group = 1; group <= 4; group++) {
            for (int n = 1; n <= 25; n++) {
                String key = "trip-c" + group;
                appended.add(Map.entry(key, OutboxTestSupport.payload("o-c" + group + "-" + n, key)));
            }
        }
        OutboxTestSupport.append(transactionManager, dataSource, TOPIC, appended);

        var registry = new SimpleMeterRegistry();
        var skips = new AtomicInteger();
        var start = new CountDownLatch(1);
        var workers = new ArrayList<Thread>();
        for (int i = 0; i < 2; i++) {
            // Separate templates and transaction managers: two relays in one process here, but they contend on the
            // database exactly as two containers would.
            var relay = new OutboxRelay(OutboxTestSupport.template(dataSource), kafka.template(),
                    OutboxTestSupport.properties(5, Duration.ofSeconds(5)),
                    OutboxTestSupport.metrics(new SimpleMeterRegistry(), template),
                    OutboxTestSupport.transactions(dataSource));
            var worker = new Thread(() -> {
                awaitQuietly(start);
                long deadline = System.nanoTime() + Duration.ofSeconds(90).toNanos();
                while (System.nanoTime() < deadline && OutboxTestSupport.unpublishedCount(template) > 0) {
                    try {
                        if (relay.publishBatch().skippedByGuard()) {
                            skips.incrementAndGet();
                        }
                    } catch (RuntimeException failure) {
                        // A contended batch may fail; production retries too, so the loop simply continues.
                    }
                }
            }, "relay-" + i);
            workers.add(worker);
            worker.start();
        }
        start.countDown();
        for (Thread worker : workers) {
            worker.join(Duration.ofSeconds(120).toMillis());
        }

        assertEquals(0, OutboxTestSupport.unpublishedCount(template), "both relays together must drain the outbox");
        assertTrue(skips.get() > 0,
                "with two relays contending, the guard must have skipped at least one batch; skips=" + skips.get());

        List<ConsumerRecord<String, String>> records = kafka.drain(TOPIC, Duration.ofSeconds(30));
        assertFalse(records.isEmpty());
        OutboxRelayIT.assertOrderWithinEachKey(records);
        assertTrue(records.stream().map(record -> OutboxTestSupport.orderIdOf(record.value())).distinct().count() == 100,
                "every appended row must reach the topic at least once");
    }

    private static OutboxRelay relay(SimpleMeterRegistry registry) {
        return new OutboxRelay(template, kafka.template(), OutboxTestSupport.properties(100, Duration.ofSeconds(5)),
                OutboxTestSupport.metrics(registry, template), transactionManager);
    }

    private static void awaitQuietly(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }
}
