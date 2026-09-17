// decision: D04-1 — docs/step_04_kafka_pipeline.md#decisions-and-outputs
package dev.zerosum.instrument.policy;

import dev.zerosum.contracts.kafka.TopicDefinitions;
import dev.zerosum.contracts.kafka.TopicDefinitions.TopicDefinition;
import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Declares the topics instrument-service uses (D04-1): it consumes money orders and publishes payment events through
 * the outbox relay.
 *
 * <p>Both are also declared by other services. That is intentional — creation is idempotent, and a service that had
 * to start after another would make start-up order part of the contract.
 *
 * <p><strong>Individual {@link NewTopic} beans, never a {@code List<NewTopic>}.</strong> Spring's {@code KafkaAdmin}
 * collects beans <em>of type</em> {@code NewTopic}; a bean whose type is {@code List} is invisible to it. This
 * repository has already shipped that defect once: declared as a list, no topic was provisioned by the running
 * services at all, and the consumer sat on {@code UNKNOWN_TOPIC_OR_PARTITION} while the outbox backed up. The broker
 * has {@code auto.create.topics.enable=false}, so nothing covers for it.
 */
@Configuration
class KafkaTopicsConfig {

    /** Consumed by the instrument-policy consumer (S05-T09). */
    @Bean
    NewTopic instrumentMoneyOrdersTopic() {
        return newTopic(TopicDefinitions.MONEY_ORDERS);
    }

    /** Produced by this service's outbox relay: the payment events every attempt transition emits (D05-5). */
    @Bean
    NewTopic instrumentPaymentEventsTopic() {
        return newTopic(TopicDefinitions.PAYMENT_EVENTS);
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
