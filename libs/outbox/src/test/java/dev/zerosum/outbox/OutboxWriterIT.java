package dev.zerosum.outbox;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.zerosum.outbox.arch.OutboxTableAssertions;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.Map;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The writer's transaction guarantee (D03-5): a row is appended inside the caller's transaction or not at all.
 *
 * <p>Built through a real Spring context rather than {@code new OutboxWriter(...)}, because
 * {@code @Transactional(MANDATORY)} is enforced by a proxy: a hand-constructed writer would silently accept a call
 * with no transaction and this test would pass while proving nothing about how the class is actually wired.
 */
@Tag("integration")
class OutboxWriterIT {

    private static OutboxTestDatabase db;
    private static AnnotationConfigApplicationContext spring;
    private static OutboxWriter writer;
    private static JdbcTemplate template;
    private static TransactionTemplate transactions;

    @BeforeAll
    static void start() {
        db = OutboxTestDatabase.start();
        WriterConfiguration.source = db.dataSource(OutboxTestDatabase.APP);
        spring = new AnnotationConfigApplicationContext(WriterConfiguration.class);
        writer = spring.getBean(OutboxWriter.class);
        template = spring.getBean(JdbcTemplate.class);
        transactions = new TransactionTemplate(spring.getBean(PlatformTransactionManager.class));
    }

    @AfterAll
    static void stop() {
        spring.close();
        db.close();
    }

    @BeforeEach
    void clear() {
        OutboxTestSupport.clear(template);
    }

    @Test
    void appendingWithoutATransactionThrows() {
        // MANDATORY, not REQUIRED: a writer that quietly opened its own transaction would commit the outbox row even
        // when the order it belongs to rolled back, which is precisely the failure the outbox pattern exists to stop.
        assertThrows(IllegalTransactionStateException.class,
                () -> writer.append("t", "k", "{}", Map.of()));
        assertEquals(0, OutboxTestSupport.unpublishedCount(template), "nothing may be written without a transaction");
    }

    @Test
    void aRolledBackTransactionLeavesNoRow() {
        transactions.executeWithoutResult(status -> {
            writer.append("payments.money-orders.v1", "trip-1", OutboxTestSupport.payload("o-1", "trip-1"), Map.of());
            status.setRollbackOnly();
        });
        assertEquals(0, OutboxTestSupport.unpublishedCount(template),
                "the outbox row must roll back with the order it belongs to");
    }

    @Test
    void aCommittedTransactionLeavesAnUnpublishedRowWithItsHeaders() {
        long id = transactions.execute(status -> writer.append("payments.money-orders.v1", "trip-2",
                OutboxTestSupport.payload("o-2", "trip-2"), Map.of("schema", "zerosum.money_order.v1")));

        Map<String, Object> row = template.queryForMap("SELECT * FROM outbox WHERE id = ?", id);
        assertEquals("payments.money-orders.v1", row.get("topic"));
        assertEquals("trip-2", row.get("message_key"));
        assertNull(row.get("published_at"), "a freshly appended row is unpublished until the relay acknowledges it");
        assertTrue(row.get("headers").toString().contains("zerosum.money_order.v1"), "headers must survive the write");
        assertTrue(row.get("created_at") != null, "created_at is what publish lag is measured from");
    }

    @Test
    void theOutboxTableMatchesWhatTheLibraryRequires() throws SQLException {
        // The fixture exists because each producing service owns a copy of the migration (candidate A) and copies
        // drift. This database was migrated from order-service's real migration directory, so the assertion runs
        // against the file a service actually ships, not a copy made for the test.
        try (Connection connection = db.connect(OutboxTestDatabase.OWNER)) {
            OutboxTableAssertions.assertTableShape(connection);
            OutboxTableAssertions.assertPartialIndexOnUnpublished(connection);
        }
    }

    @Configuration
    @EnableTransactionManagement
    static class WriterConfiguration {

        static DataSource source;

        @Bean
        DataSource dataSource() {
            return source;
        }

        @Bean
        JdbcClient jdbcClient(DataSource dataSource) {
            return JdbcClient.create(dataSource);
        }

        @Bean
        JdbcTemplate jdbcTemplate(DataSource dataSource) {
            return new JdbcTemplate(dataSource);
        }

        @Bean
        PlatformTransactionManager transactionManager(DataSource dataSource) {
            return new DataSourceTransactionManager(dataSource);
        }

        @Bean
        OutboxWriter outboxWriter(JdbcClient jdbcClient) {
            return OutboxFactory.writer(jdbcClient);
        }
    }
}
