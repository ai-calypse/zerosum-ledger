package dev.zerosum.outbox;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * Recovery after a relay is lost: rows committed while the broker was unreachable are published by a freshly built
 * relay, with nothing carried over in memory.
 *
 * <p><strong>What this does not prove.</strong> M4(a) as written in the step requires SIGKILL of the order-service
 * container and a restart of the process. This test kills the relay <em>object</em> and its producer, not the JVM, so
 * it proves the recovery mechanism — durability of committed rows, and a new relay finding them — without proving
 * that a process death loses nothing on the way. The container-level test is recorded as deferred rather than counted
 * as a pass; see the S03-T05 evidence note.
 */
@Tag("integration")
class RelayRestartRecoveryIT {

    private static final String TOPIC = "payments.money-orders.restart.v1";
    private static final int ORDERS = 20;

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
        OutboxTestSupport.clear(template);
    }

    @AfterAll
    static void stop() {
        kafka.close();
        db.close();
    }

    @Test
    void ordersCommittedWhileTheBrokerWasDownArePublishedByAFreshRelay() {
        var appended = new ArrayList<Map.Entry<String, String>>();
        for (int n = 1; n <= ORDERS; n++) {
            String key = "trip-r" + (n % 4);
            appended.add(Map.entry(key, OutboxTestSupport.payload("o-r-" + n, key)));
        }

        kafka.pause();
        var firstRelay = newRelay();
        try {
            // Orders keep being accepted while the broker is down: the commit only needs the database.
            OutboxTestSupport.append(transactionManager, dataSource, TOPIC, appended);
            assertEquals(ORDERS, OutboxTestSupport.unpublishedCount(template));
            assertThrows(RuntimeException.class, firstRelay::publishBatch);
            assertEquals(0, OutboxTestSupport.publishedCount(template),
                    "nothing may be marked published while the broker is unreachable");
        } finally {
            kafka.unpause();
        }

        // The relay is lost along with everything it held in memory. Only the committed rows survive.
        firstRelay = null;

        long restartedAt = System.nanoTime();
        var recoveredRelay = newRelay();
        long deadline = restartedAt + Duration.ofSeconds(90).toNanos();
        while (OutboxTestSupport.unpublishedCount(template) > 0 && System.nanoTime() < deadline) {
            try {
                recoveredRelay.publishBatch();
            } catch (RuntimeException retryable) {
                // Exactly what the production loop does: back off and try again rather than skip a row.
            }
        }
        Duration toDrain = Duration.ofNanos(System.nanoTime() - restartedAt);

        assertEquals(0, OutboxTestSupport.unpublishedCount(template),
                "every committed order must be published after the restart");

        Set<String> publishedIds = kafka.drain(TOPIC, Duration.ofSeconds(30)).stream()
                .map(record -> OutboxTestSupport.orderIdOf(record.value()))
                .collect(Collectors.toSet());
        Set<String> expected = appended.stream()
                .map(entry -> OutboxTestSupport.orderIdOf(entry.getValue()))
                .collect(Collectors.toSet());
        assertTrue(publishedIds.containsAll(expected),
                "missing after restart: " + expected.stream().filter(id -> !publishedIds.contains(id)).toList());

        // The M4(a) bound is 5 s from restart (master must-have M4). Measured from constructing the replacement relay,
        // so the producer's own start-up cost is inside the budget rather than excluded from it.
        System.out.println("ZS-RESTART orders=" + ORDERS + " drainMillis=" + toDrain.toMillis() + " boundMillis=5000");
        assertTrue(toDrain.compareTo(Duration.ofSeconds(5)) < 0,
                "M4(a) bound is 5 s from restart; the backlog took " + toDrain.toMillis() + " ms to drain");
    }

    private static OutboxRelay newRelay() {
        return new OutboxRelay(OutboxTestSupport.template(dataSource), kafka.template(),
                OutboxTestSupport.properties(100, Duration.ofSeconds(3)),
                OutboxTestSupport.metrics(new SimpleMeterRegistry(), template),
                OutboxTestSupport.transactions(dataSource));
    }
}
