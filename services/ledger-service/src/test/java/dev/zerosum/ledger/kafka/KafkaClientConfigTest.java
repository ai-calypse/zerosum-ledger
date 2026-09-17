package dev.zerosum.ledger.kafka;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import java.util.Map;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.kafka.autoconfigure.KafkaProperties;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.junit.jupiter.SpringExtension;

/**
 * The D04-2 client properties, read from what the application actually built (S04-T01 instruction 7).
 *
 * <p>This exists because configuration that binds to nothing fails silently. In S03 a {@code kafka:} block sat one
 * level off in the YAML, bound to a key nothing read, and the result was a listener that started, subscribed, and
 * consumed nothing for two full test runs. Asserting the effective values is the cheap check that turns that class of
 * mistake into a failing test instead of a mystery.
 */
@Tag("unit")
@ExtendWith(SpringExtension.class)
@SpringBootTest(classes = KafkaClientConfigTest.PropertiesOnly.class)
@TestPropertySource(locations = "classpath:application.yml")
class KafkaClientConfigTest {

    @Autowired
    private KafkaProperties properties;

    @org.springframework.boot.context.properties.EnableConfigurationProperties(KafkaProperties.class)
    static class PropertiesOnly {
    }

    @Test
    void theConsumerRunsWithTheSettingsD042Selected() {
        Map<String, Object> consumer = properties.buildConsumerProperties();

        assertEquals("ledger-apply", consumer.get("group.id"), "the ledger apply group (D04-1)");
        assertEquals("earliest", consumer.get("auto.offset.reset"));
        assertEquals(Boolean.FALSE, consumer.get("enable.auto.commit"),
                "offsets are committed after the apply transaction, never on a timer");
        assertEquals("read_committed", consumer.get("isolation.level"));
        assertEquals(500, consumer.get("max.poll.records"));
    }

    @Test
    void theDeadLetterProducerIsIdempotentAndWaitsForEveryReplica() {
        Map<String, Object> producer = properties.buildProducerProperties();

        assertEquals("all", producer.get("acks"), "a dead-letter record acknowledged by one replica can still be lost");
        assertEquals("true", String.valueOf(producer.get("enable.idempotence")));
    }

    @Test
    void offsetsAreAcknowledgedByTheListenerAfterTheDatabaseCommit() {
        // MANUAL plus batch is what S04-T02 depends on: the container must hand the listener an Acknowledgment and
        // must not commit on its own schedule, or a crash could skip records the ledger never applied.
        assertEquals("MANUAL", String.valueOf(properties.getListener().getAckMode()));
        assertEquals("BATCH", String.valueOf(properties.getListener().getType()));
        assertFalse(Boolean.TRUE.equals(properties.getConsumer().getEnableAutoCommit()));
    }
}
