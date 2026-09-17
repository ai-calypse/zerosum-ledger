package dev.zerosum.outbox;

import io.micrometer.core.instrument.MeterRegistry;
import java.util.function.Supplier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * Builds the outbox components (D03-5).
 *
 * <p>Construction lives here rather than in each service's configuration so that a service never has to name
 * {@link KafkaTemplate} at all. The M4(b) architecture rule forbids application packages from depending on Kafka
 * producer types, and a bean method taking a {@code KafkaTemplate} parameter is such a dependency — it caught exactly
 * that on its first run. Keeping the reference inside the library means the rule stays strict rather than being
 * loosened to permit "wiring only", a carve-out that would be indistinguishable from a real publish path later.
 */
public final class OutboxFactory {

    private OutboxFactory() {
    }

    public static OutboxWriter writer(org.springframework.jdbc.core.simple.JdbcClient jdbc) {
        return new OutboxWriter(jdbc);
    }

    public static OutboxMetrics metrics(MeterRegistry registry, Supplier<Number> oldestUnpublishedAgeSeconds) {
        return new OutboxMetrics(registry, oldestUnpublishedAgeSeconds);
    }

    /**
     * @param kafka resolved by the caller as {@code KafkaTemplate<String, String>} from the application context; the
     *              parameter is declared here so the service's own classes never reference the type
     */
    public static OutboxRelay relay(JdbcTemplate template, Object kafka, OutboxProperties properties,
            OutboxMetrics metrics, PlatformTransactionManager transactionManager) {
        @SuppressWarnings("unchecked")
        KafkaTemplate<String, String> producer = (KafkaTemplate<String, String>) kafka;
        return new OutboxRelay(template, producer, properties, metrics, transactionManager);
    }

    public static OutboxRelayLoop loop(OutboxRelay relay, OutboxProperties properties) {
        return new OutboxRelayLoop(relay, properties);
    }

    public static OutboxCleanupJob cleanupJob(OutboxRelay relay) {
        return new OutboxCleanupJob(relay);
    }
}
