package dev.zerosum.ledger.kafka;

import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Wires the listener's failure policy (D04-4).
 *
 * <p>{@code @EnableScheduling} is required for {@link ListenerResumeProbe}: without it the annotation is inert and a
 * paused listener would stay paused forever, which is worse than not pausing at all.
 *
 * <p>Boot applies a {@code CommonErrorHandler} bean to the auto-configured listener container factory, so declaring
 * one here is enough — no hand-built factory is needed.
 */
@Configuration
@EnableScheduling
class LedgerKafkaConfig {

    /**
     * The admin client the freshness calculator reads offsets with (D04-5). Built from the same bootstrap settings as
     * every other client, so freshness cannot be measured against a different cluster from the one being consumed.
     *
     * <p>Named explicitly: calling this method {@code kafkaAdmin} collided with the bean name Boot's
     * {@code KafkaAutoConfiguration} registers for its own {@code KafkaAdmin}, and a same-name different-type
     * definition is rejected outright — which took down every Spring context in this service, including four suites
     * that had nothing to do with freshness.
     */
    @Bean(name = "freshnessAdminClient", destroyMethod = "close")
    org.apache.kafka.clients.admin.Admin freshnessAdminClient(
            org.springframework.boot.kafka.autoconfigure.KafkaProperties properties) {
        // buildAdminProperties() takes no argument on Boot 4.1; passing an SslBundles-shaped null did not compile.
        return org.apache.kafka.clients.admin.Admin.create(properties.buildAdminProperties());
    }

    @Bean
    PauseOnFailureErrorHandler pauseOnFailureErrorHandler(MeterRegistry meters) {
        return new PauseOnFailureErrorHandler(meters);
    }

    /**
     * Ledger-service may hold a Kafka producer: the M4(b) publish-path rule constrains <em>producing</em> services,
     * where an order published outside the outbox could be an order that never committed. Ledger-service publishes no
     * orders — only dead-letter copies of records it has already quarantined in its own transaction — so the concern
     * the rule exists for does not arise. Order-service's own DLQ stays deferred for exactly the opposite reason: a
     * producer in that codebase would sit inside the package the rule guards.
     */
    @Bean
    DlqPublisher dlqPublisher(KafkaTemplate<String, String> kafka) {
        return new DlqPublisher(kafka);
    }
}
