package dev.zerosum.ledger.kafka;

import dev.zerosum.contracts.kafka.TopicDefinitions;
import dev.zerosum.contracts.kafka.TopicDefinitions.TopicDefinition;
import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Declares the topics ledger-service uses (D04-1).
 *
 * <p>Ledger-service consumes money orders and dead-letters to that topic's DLQ. Order-service declares the same
 * money-orders topic, which is intentional: creation is idempotent, and neither service should have to start after
 * the other for the pipeline to come up.
 */
@Configuration
class KafkaTopicsConfig {

    /**
     * Declared as individual {@link NewTopic} beans, not as a {@code List<NewTopic>}.
     *
     * <p>Spring's {@code KafkaAdmin} looks up beans <em>of type</em> {@code NewTopic}; a bean whose type is
     * {@code List} is invisible to it. Declared as a list, these topics were never provisioned by the running
     * services at all — the consumer sat on {@code UNKNOWN_TOPIC_OR_PARTITION} while the outbox backed up, and
     * {@code TopicProvisioningIT} did not catch it because that test creates topics through the admin client
     * directly and so never exercised this path.
     */
    @Bean
    NewTopic moneyOrdersTopic() {
        return newTopic(TopicDefinitions.MONEY_ORDERS);
    }

    @Bean
    NewTopic moneyOrdersDlqTopic() {
        return newTopic(TopicDefinitions.dlqFor(TopicDefinitions.MONEY_ORDERS));
    }

    /**
     * Partitions and retention come from the shared definition rather than from this file, so two services cannot
     * create the same topic with different shapes depending on which one wins the race.
     */
    private static NewTopic newTopic(TopicDefinition definition) {
        return new NewTopic(definition.name(), definition.partitions(), TopicDefinitions.LOCAL_REPLICATION_FACTOR)
                .configs(definition.configs());
    }
}
