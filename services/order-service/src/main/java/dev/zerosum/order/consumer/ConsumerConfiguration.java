package dev.zerosum.order.consumer;

import dev.zerosum.order.order.OrderStore;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Wires the payment-event consumer (D03-6).
 *
 * <p>No dead-letter publisher is configured here. Publishing to a DLQ needs a producer, and the dead-letter topic
 * naming is S04-T03's decision (D04-4), so poison is quarantined in this service's own database and the partition
 * keeps moving. The quarantine row holds the payload, so nothing is lost by deferring the DLQ.
 */
@Configuration
class ConsumerConfiguration {

    @Bean
    QuarantineStore quarantineStore(JdbcClient jdbc) {
        return new QuarantineStore(jdbc);
    }

    @Bean
    PaymentEventListener paymentEventListener(OrderStore store, QuarantineStore quarantine, MeterRegistry meters) {
        return new PaymentEventListener(store, quarantine, meters);
    }
}
