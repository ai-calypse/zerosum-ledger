// decision: D05-4 — docs/step_05_instruments_fake_providers.md#decisions-and-outputs
package dev.zerosum.instrument.store;

import dev.zerosum.outbox.OutboxCleanupJob;
import dev.zerosum.outbox.OutboxFactory;
import dev.zerosum.outbox.OutboxMetrics;
import dev.zerosum.outbox.OutboxProperties;
import dev.zerosum.outbox.OutboxRelay;
import dev.zerosum.outbox.OutboxRelayLoop;
import dev.zerosum.outbox.OutboxStatsQuery;
import dev.zerosum.outbox.OutboxWriter;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * Wires the outbox into instrument-service (D03-5, D05-4).
 *
 * <p>{@code libs/outbox} lives in its own package, outside this service's component scan, so its beans are declared
 * here rather than discovered. That is deliberate in the library: a service states which parts of the outbox it
 * actually runs.
 *
 * <p><strong>The relay starts with S05-T09.</strong> Until the collection policy existed, only the writer was wired,
 * and that was the right call — a relay with no producer would have dialled Kafka on every test context to publish
 * an empty table. It is the wrong call now: every attempt transition appends a payment event, and without the relay
 * those events accumulate in the table and reach Kafka never, so order-service's mapper sees no charge and the ledger
 * never learns that money moved. The failure is silent at both ends, which is why it is wired in the same change as
 * its first producer.
 *
 * <p>{@code @EnableScheduling} is required for {@link OutboxCleanupJob}: without it the annotation is inert and
 * cleanup silently never runs.
 */
@Configuration
@EnableScheduling
@EnableConfigurationProperties(OutboxProperties.class)
class InstrumentOutboxConfiguration {

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
     * The Kafka producer is taken as {@link org.springframework.beans.factory.ObjectProvider} of {@code Object} — here
     * simply as {@code Object} by bean name — and handed straight to the library, so no class in this service names a
     * Kafka producer type. The M4(b) rule forbids that dependency, and instrument-service is squarely inside it: this
     * service publishes the payment events the whole ledger is built from.
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

    /** decision: D03-7 — the same stats query order-service uses; S05-T10's freshness sum reads the local age from it. */
    @Bean
    OutboxStatsQuery outboxStatsQuery(JdbcTemplate template) {
        return OutboxFactory.stats(template);
    }

    /**
     * Injected rather than read from {@code Instant.now()}, so a test can control the clock instead of sleeping, and
     * so every timestamp written by a transition comes from one source.
     */
    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }
}
