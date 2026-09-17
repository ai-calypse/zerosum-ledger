package dev.zerosum.outbox;

import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Wiring shared by the relay tests, so each test shows only what it is actually asserting. */
final class OutboxTestSupport {

    private OutboxTestSupport() {
    }

    /**
     * Test settings. The send timeout is deliberately short: a test that waits out order-service's 10 s would spend
     * most of its time proving the JDK's clock works.
     */
    static OutboxProperties properties(int batchSize, Duration sendTimeout) {
        return new OutboxProperties(batchSize, Duration.ofMillis(20), sendTimeout, Duration.ofMillis(20),
                Duration.ofMillis(200), Duration.ofHours(1), Duration.ofMinutes(10), 1_000);
    }

    static JdbcTemplate template(DataSource dataSource) {
        return new JdbcTemplate(dataSource);
    }

    static PlatformTransactionManager transactions(DataSource dataSource) {
        return new DataSourceTransactionManager(dataSource);
    }

    /** Metrics reading the oldest age straight from the database, exactly as the production gauge does. */
    static OutboxMetrics metrics(MeterRegistry registry, JdbcTemplate template) {
        return new OutboxMetrics(registry, () -> oldestUnpublishedAgeSeconds(template));
    }

    static double oldestUnpublishedAgeSeconds(JdbcTemplate template) {
        Double age = template.queryForObject(
                "SELECT COALESCE(EXTRACT(EPOCH FROM (now() - MIN(created_at))), 0) FROM outbox WHERE published_at IS NULL",
                Double.class);
        return age == null ? 0 : age;
    }

    /**
     * Appends rows through the real writer inside one committed transaction, which is how a money order and its
     * outbox row commit together.
     */
    static void append(PlatformTransactionManager transactionManager, DataSource dataSource, String topic,
            List<Map.Entry<String, String>> keyedPayloads) {
        var writer = new OutboxWriter(JdbcClient.create(dataSource));
        new TransactionTemplate(transactionManager).executeWithoutResult(status ->
                keyedPayloads.forEach(entry -> writer.append(topic, entry.getKey(), entry.getValue(),
                        Map.of("schema", "zerosum.money_order.v1", "order_id", orderIdOf(entry.getValue())))));
    }

    /** A payload whose order id is visible, so a consumed record can be traced back to the row that produced it. */
    static String payload(String orderId, String group) {
        return "{\"order_id\":\"" + orderId + "\",\"order_group_id\":\"" + group + "\"}";
    }

    static String orderIdOf(String payload) {
        var matcher = java.util.regex.Pattern.compile("\"order_id\"\\s*:\\s*\"([^\"]+)\"").matcher(payload);
        if (!matcher.find()) {
            throw new IllegalArgumentException("payload has no order_id: " + payload);
        }
        return matcher.group(1);
    }

    static long unpublishedCount(JdbcTemplate template) {
        Long count = template.queryForObject("SELECT count(*) FROM outbox WHERE published_at IS NULL", Long.class);
        return count == null ? 0 : count;
    }

    static long publishedCount(JdbcTemplate template) {
        Long count = template.queryForObject("SELECT count(*) FROM outbox WHERE published_at IS NOT NULL", Long.class);
        return count == null ? 0 : count;
    }

    static void clear(JdbcTemplate template) {
        template.update("DELETE FROM outbox");
    }

    static double counter(MeterRegistry registry, String name) {
        var counter = registry.find(name).counter();
        return counter == null ? 0 : counter.count();
    }
}
