package dev.zerosum.order.outbox;

import dev.zerosum.order.order.OrderStore;
import dev.zerosum.outbox.OutboxCleanupJob;
import dev.zerosum.outbox.OutboxFactory;
import dev.zerosum.outbox.OutboxMetrics;
import dev.zerosum.outbox.OutboxProperties;
import dev.zerosum.outbox.OutboxRelay;
import dev.zerosum.outbox.OutboxRelayLoop;
import dev.zerosum.outbox.OutboxWriter;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.Optional;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * Wires the outbox into order-service (D03-5).
 *
 * <p>{@code @EnableScheduling} is required for {@link OutboxCleanupJob}: without it the annotation is inert and
 * cleanup silently never runs.
 */
@Configuration
@EnableScheduling
@EnableConfigurationProperties(OutboxProperties.class)
class OrderOutboxConfiguration {

    /** The topic money orders are published to (master §5.4). */
    static final String MONEY_ORDERS_TOPIC = "payments.money-orders.v1";

    @Bean
    OutboxWriter outboxWriter(JdbcClient jdbc) {
        return OutboxFactory.writer(jdbc);
    }

    @Bean
    OutboxMetrics outboxMetrics(MeterRegistry registry, JdbcTemplate template) {
        // Read at scrape time, so the gauge stays truthful while the relay is stuck — which is when it matters.
        return OutboxFactory.metrics(registry, () -> oldestUnpublishedAgeSeconds(template));
    }

    private static double oldestUnpublishedAgeSeconds(JdbcTemplate template) {
        Double age = template.queryForObject(
                "SELECT COALESCE(EXTRACT(EPOCH FROM (now() - MIN(created_at))), 0) FROM outbox WHERE published_at IS NULL",
                Double.class);
        return age == null ? 0 : age;
    }

    /**
     * The Kafka producer is taken as {@link org.springframework.beans.factory.ObjectProvider} of {@code Object} and
     * handed straight to the library, so no class in this service names a Kafka producer type. The M4(b) rule forbids
     * that dependency, and it caught this very method when the template was a declared parameter.
     */
    @Bean
    OutboxRelay outboxRelay(JdbcTemplate template,
            @org.springframework.beans.factory.annotation.Qualifier("kafkaTemplate") Object kafkaTemplate,
            OutboxProperties properties, OutboxMetrics metrics, PlatformTransactionManager transactionManager) {
        return OutboxFactory.relay(template, kafkaTemplate, properties, metrics, transactionManager);
    }

    @Bean
    OutboxRelayLoop outboxRelayLoop(OutboxRelay relay, OutboxProperties properties) {
        return OutboxFactory.loop(relay, properties);
    }

    @Bean
    OutboxCleanupJob outboxCleanupJob(OutboxRelay relay) {
        return OutboxFactory.cleanupJob(relay);
    }

    @Bean
    OrderStore.CreatedOrderPublisher createdOrderPublisher(OutboxWriter writer) {
        return new MoneyOrderPublisher(writer, MONEY_ORDERS_TOPIC);
    }

    @Bean
    OrderStore orderStore(JdbcClient jdbc, JdbcTemplate template, dev.zerosum.order.order.RequestHasher hasher,
            dev.zerosum.order.order.OrderStoreProperties properties, PlatformTransactionManager transactionManager,
            OrderStore.CreatedOrderPublisher publisher) {
        return new OrderStore(jdbc, template, hasher, properties, transactionManager, Optional.of(publisher));
    }
}
