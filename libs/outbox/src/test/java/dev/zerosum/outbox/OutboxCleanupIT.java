package dev.zerosum.outbox;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * Cleanup (D03-5): published rows past their retention are deleted, and unpublished rows never are.
 *
 * <p>No Kafka here. Rows are marked published directly, because what is being tested is the delete predicate, and a
 * broker would only add a way for the test to fail for an unrelated reason.
 */
@Tag("integration")
class OutboxCleanupIT {

    private static final String TOPIC = "payments.money-orders.cleanup.v1";

    private static OutboxTestDatabase db;
    private static DataSource dataSource;
    private static JdbcTemplate template;
    private static PlatformTransactionManager transactionManager;
    private static OutboxRelay relay;

    @BeforeAll
    static void start() {
        db = OutboxTestDatabase.start();
        dataSource = db.dataSource(OutboxTestDatabase.APP);
        template = OutboxTestSupport.template(dataSource);
        transactionManager = OutboxTestSupport.transactions(dataSource);
        // Retention is one hour, matching order-service's configured value.
        relay = new OutboxRelay(template, null, OutboxTestSupport.properties(100, Duration.ofSeconds(3)),
                OutboxTestSupport.metrics(new SimpleMeterRegistry(), template), transactionManager);
    }

    @AfterAll
    static void stop() {
        db.close();
    }

    @BeforeEach
    void clear() {
        OutboxTestSupport.clear(template);
    }

    @Test
    void publishedRowsPastRetentionGoAndEverythingElseStays() {
        appendRows(3, "old");
        appendRows(2, "recent");
        appendRows(4, "unpublished");

        markPublished("old", "2 hours");
        markPublished("recent", "5 minutes");

        int deleted = relay.cleanUp();

        assertEquals(3, deleted, "only the three rows published more than the one-hour retention ago may go");
        assertEquals(0, countOf("old"), "published rows past retention are deleted");
        assertEquals(2, countOf("recent"), "published rows inside retention stay");
        assertEquals(4, countOf("unpublished"), "unpublished rows must never be deleted, however old they are");
    }

    @Test
    void cleanupNeverTouchesAnUnpublishedRowHoweverOldItIs() {
        appendRows(5, "ancient-unpublished");
        // Backdate creation well past the retention: age alone must not be enough to delete a row that was never sent.
        template.update("UPDATE outbox SET created_at = now() - interval '30 days' WHERE message_key = ?",
                "ancient-unpublished");

        assertEquals(0, relay.cleanUp(), "an unpublished row is money not yet delivered; cleanup must not remove it");
        assertEquals(5, countOf("ancient-unpublished"));
        assertTrue(OutboxTestSupport.oldestUnpublishedAgeSeconds(template) > 0,
                "the row must still be visible to the freshness gauge");
    }

    @Test
    void deletionIsBoundedByTheCleanupBatchSize() {
        var bounded = new OutboxRelay(template, null,
                new OutboxProperties(100, Duration.ofMillis(20), Duration.ofSeconds(3), Duration.ofMillis(20),
                        Duration.ofMillis(200), Duration.ofHours(1), Duration.ofMinutes(10), 4),
                OutboxTestSupport.metrics(new SimpleMeterRegistry(), template), transactionManager);
        appendRows(10, "bulk");
        markPublished("bulk", "2 hours");

        // One pass deletes at most the batch size, so a large backlog never holds one long transaction.
        assertEquals(4, bounded.cleanUp());
        assertEquals(6, countOf("bulk"));
        assertEquals(4, bounded.cleanUp());
        assertEquals(2, bounded.cleanUp());
        assertEquals(0, bounded.cleanUp());
        assertEquals(0, countOf("bulk"));
    }

    private static void appendRows(int count, String key) {
        var rows = new java.util.ArrayList<Map.Entry<String, String>>();
        for (int n = 1; n <= count; n++) {
            rows.add(Map.entry(key, OutboxTestSupport.payload(key + "-" + n, key)));
        }
        OutboxTestSupport.append(transactionManager, dataSource, TOPIC, List.copyOf(rows));
    }

    private static void markPublished(String key, String ago) {
        template.update("UPDATE outbox SET published_at = now() - CAST(? AS interval) WHERE message_key = ?",
                ago, key);
    }

    private static int countOf(String key) {
        Integer count = template.queryForObject("SELECT count(*) FROM outbox WHERE message_key = ?", Integer.class, key);
        return count == null ? 0 : count;
    }
}
