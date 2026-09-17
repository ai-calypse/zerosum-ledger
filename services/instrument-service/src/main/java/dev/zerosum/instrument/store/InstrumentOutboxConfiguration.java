// decision: D05-4 — docs/step_05_instruments_fake_providers.md#decisions-and-outputs
package dev.zerosum.instrument.store;

import dev.zerosum.outbox.OutboxFactory;
import dev.zerosum.outbox.OutboxProperties;
import dev.zerosum.outbox.OutboxWriter;
import java.time.Clock;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Wires the outbox writer into instrument-service (D03-5, D05-4).
 *
 * <p>{@code libs/outbox} lives in its own package, outside this service's component scan, so its beans are declared
 * here rather than discovered. That is deliberate in the library: a service states which parts of the outbox it
 * actually runs.
 *
 * <p><strong>Only the writer is wired.</strong> The relay, its metrics and the cleanup job are not, because nothing
 * publishes payment events yet — the collection policy that produces them is S05-T09. A relay started now would dial
 * Kafka, need topic provisioning and a broker on every test context, and publish an empty table; wiring it with its
 * first producer keeps the failure modes attached to the code that causes them.
 */
@Configuration
@EnableConfigurationProperties(OutboxProperties.class)
class InstrumentOutboxConfiguration {

    @Bean
    OutboxWriter outboxWriter(JdbcClient jdbc) {
        return OutboxFactory.writer(jdbc);
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
