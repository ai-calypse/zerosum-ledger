package dev.zerosum.order.outbox;

import dev.zerosum.contracts.kafka.TopicDefinitions;
import dev.zerosum.contracts.kafka.TopicDefinitions.TopicDefinition;
import java.util.List;
import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Declares the topics order-service uses (D04-1): it publishes money orders through the relay, consumes payment
 * events, and dead-letters those events.
 *
 * <p>The money-orders topic is declared here and in ledger-service on purpose — creation is idempotent, and a
 * service that had to wait for another to create its topic would make start-up order part of the contract.
 */
@Configuration
class KafkaTopicsConfig {

    @Bean
    List<NewTopic> orderTopics() {
        return List.of(newTopic(TopicDefinitions.MONEY_ORDERS),
                newTopic(TopicDefinitions.PAYMENT_EVENTS),
                newTopic(TopicDefinitions.dlqFor(TopicDefinitions.PAYMENT_EVENTS)));
    }

    private static NewTopic newTopic(TopicDefinition definition) {
        return new NewTopic(definition.name(), definition.partitions(), TopicDefinitions.LOCAL_REPLICATION_FACTOR)
                .configs(definition.configs());
    }
}
