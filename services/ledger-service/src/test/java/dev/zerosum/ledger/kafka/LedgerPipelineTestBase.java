package dev.zerosum.ledger.kafka;

import dev.zerosum.contracts.kafka.TopicDefinitions;
import dev.zerosum.ledger.support.LedgerTestDatabase;
import java.nio.file.Files;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.regex.Pattern;
import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.Tag;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * A real broker, a real database and the real listener, for the S04-T02 pipeline tests.
 *
 * <p>The listener is the thing under test, so nothing here stands in for it: orders are produced to the topic exactly
 * as the outbox relay produces them, keyed by order group (ADR-0007), and the assertions read the ledger's own state
 * afterwards.
 */
@Tag("integration")
@SpringBootTest
abstract class LedgerPipelineTestBase {

    protected static final LedgerTestDatabase DB = LedgerTestDatabase.start();
    protected static final org.testcontainers.kafka.KafkaContainer KAFKA = startKafka();

    @DynamicPropertySource
    static void pipeline(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", DB::jdbcUrl);
        registry.add("spring.datasource.username", () -> LedgerTestDatabase.APP);
        registry.add("spring.datasource.password", () -> DB.password(LedgerTestDatabase.APP));
        registry.add("spring.flyway.enabled", () -> "false");
        registry.add("spring.kafka.bootstrap-servers", KAFKA::getBootstrapServers);
        registry.add("zs.auth.reader-token", () -> "test-reader-token");
        // The listener is what these tests exercise, so unlike every other ledger context test it stays on.
        registry.add("ledger.consumer.enabled", () -> "true");
    }

    /** Publishes with the order group as the key, which is what the relay does (ADR-0007). */
    protected static RecordMetadata publish(String orderGroupId, String payload) {
        try (var producer = producer()) {
            return producer.send(new ProducerRecord<>(TopicDefinitions.MONEY_ORDERS.name(), orderGroupId, payload))
                    .get(30, TimeUnit.SECONDS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(interrupted);
        } catch (Exception failure) {
            throw new IllegalStateException("could not publish to " + TopicDefinitions.MONEY_ORDERS.name(), failure);
        }
    }

    protected static KafkaProducer<String, String> producer() {
        return new KafkaProducer<>(Map.of("bootstrap.servers", KAFKA.getBootstrapServers(), "acks", "all"),
                new StringSerializer(), new StringSerializer());
    }

    /** Waits for the listener to catch up; the pipeline is asynchronous, so every assertion needs a deadline. */
    protected static void awaitUntil(BooleanSupplier condition, String whatWasExpected) {
        long deadline = System.nanoTime() + Duration.ofSeconds(90).toNanos();
        while (System.nanoTime() < deadline) {
            if (condition.getAsBoolean()) {
                return;
            }
            sleep(250);
        }
        throw new AssertionError(whatWasExpected);
    }

    protected static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    private static org.testcontainers.kafka.KafkaContainer startKafka() {
        var container = new org.testcontainers.kafka.KafkaContainer(
                org.testcontainers.utility.DockerImageName.parse(composeKafkaImage())
                        .asCompatibleSubstituteFor("apache/kafka"));
        container.start();
        // Created from the shared definitions, so these tests run against the contracted partition count rather than
        // an auto-created single-partition topic.
        try (var admin = Admin.create(Map.of("bootstrap.servers", container.getBootstrapServers()))) {
            admin.createTopics(List.of(new NewTopic(TopicDefinitions.MONEY_ORDERS.name(),
                            TopicDefinitions.MONEY_ORDERS.partitions(), TopicDefinitions.LOCAL_REPLICATION_FACTOR)))
                    .all().get(60, TimeUnit.SECONDS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(interrupted);
        } catch (Exception failure) {
            throw new IllegalStateException("could not create the money-orders topic", failure);
        }
        return container;
    }

    private static String composeKafkaImage() {
        var pattern = Pattern.compile("^\\s*image:\\s*(apache/kafka:\\S+)\\s*$");
        try {
            return Files.readAllLines(LedgerTestDatabase.ROOT.resolve("docker-compose.yml")).stream()
                    .map(pattern::matcher)
                    .filter(java.util.regex.Matcher::matches)
                    .map(m -> m.group(1))
                    .findFirst()
                    .orElseThrow(() -> new IllegalStateException("no apache/kafka image in docker-compose.yml"));
        } catch (java.io.IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
    }
}
