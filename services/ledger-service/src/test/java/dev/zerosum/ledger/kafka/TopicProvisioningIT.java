package dev.zerosum.ledger.kafka;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.zerosum.contracts.kafka.TopicDefinitions;
import dev.zerosum.contracts.kafka.TopicDefinitions.TopicDefinition;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.common.config.ConfigResource;
import org.apache.kafka.common.errors.TopicExistsException;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * Topics are created from the D04-1 definitions with the partition count and retention the contract states.
 *
 * <p>The partition count is the part that matters: the key is the order group (ADR-0007), so a topic created with a
 * different count remaps groups to partitions and silently breaks per-group ordering. This asserts the created shape
 * rather than trusting that a {@code NewTopic} bean was declared.
 */
@Tag("integration")
class TopicProvisioningIT {

    private static final Path ROOT = Path.of(System.getProperty("zs.rootDir"));
    private static KafkaContainer kafka;
    private static Admin admin;

    @BeforeAll
    static void start() {
        kafka = new KafkaContainer(DockerImageName.parse(composeKafkaImage())
                .asCompatibleSubstituteFor("apache/kafka"));
        kafka.start();
        admin = Admin.create(Map.of("bootstrap.servers", kafka.getBootstrapServers()));
    }

    @AfterAll
    static void stop() {
        admin.close();
        kafka.stop();
    }

    @Test
    void everyDefinedTopicIsCreatedWithItsContractedShape() throws Exception {
        // Created exactly as each service's KafkaTopicsConfig does, from the shared definitions, and tolerant of
        // topics another test already created — the raw admin client surfaces TopicExistsException where Spring's
        // KafkaAdmin swallows it, and a test that only passes when it runs first is a defect of its own.
        createIgnoringExisting(TopicDefinitions.all().stream()
                .map(d -> new NewTopic(d.name(), d.partitions(), TopicDefinitions.LOCAL_REPLICATION_FACTOR)
                        .configs(d.configs()))
                .toList());

        awaitVisible(TopicDefinitions.all().stream().map(TopicDefinition::name).toList());
        var described = admin.describeTopics(TopicDefinitions.all().stream().map(TopicDefinition::name).toList())
                .allTopicNames().get(60, TimeUnit.SECONDS);

        for (TopicDefinition definition : TopicDefinitions.all()) {
            assertEquals(definition.partitions(), described.get(definition.name()).partitions().size(),
                    definition.name() + " must have the contracted partition count: changing it remaps order groups "
                            + "to partitions and breaks per-group ordering (ADR-0007)");

            var resource = new ConfigResource(ConfigResource.Type.TOPIC, definition.name());
            String retention = admin.describeConfigs(List.of(resource)).all().get(60, TimeUnit.SECONDS)
                    .get(resource).get("retention.ms").value();
            assertEquals(Long.toString(definition.retention().toMillis()), retention,
                    definition.name() + " retention must match its definition");
        }
    }

    @Test
    void theSecondServiceToDeclareATopicSeesExactlyTopicExistsException() throws Exception {
        // Both services declare the money-orders topic, so the second one to start must not fail the pipeline. It
        // must fail with *this* error specifically, because that is the one Spring's KafkaAdmin treats as success;
        // any other error would be a real provisioning fault being swallowed.
        var declaration = List.of(new NewTopic(TopicDefinitions.MONEY_ORDERS.name(),
                TopicDefinitions.MONEY_ORDERS.partitions(), TopicDefinitions.LOCAL_REPLICATION_FACTOR));
        createIgnoringExisting(declaration);   // whether or not another test got here first

        var duplicate = assertThrows(ExecutionException.class,
                () -> admin.createTopics(declaration).all().get(60, TimeUnit.SECONDS));

        assertInstanceOf(TopicExistsException.class, duplicate.getCause(),
                "re-declaring an existing topic must raise TopicExistsException and nothing else");
        assertEquals(TopicDefinitions.MONEY_ORDERS.partitions(),
                admin.describeTopics(List.of(TopicDefinitions.MONEY_ORDERS.name()))
                        .allTopicNames().get(60, TimeUnit.SECONDS)
                        .get(TopicDefinitions.MONEY_ORDERS.name()).partitions().size(),
                "a redundant declaration must not alter the topic");
    }

    /**
     * Waits until every topic is visible to the broker answering our describes.
     *
     * <p>{@code createTopics(...).get()} returns once the controller has accepted the creation, which is not the same
     * as the metadata having propagated — describing immediately afterwards can fail with
     * {@code UnknownTopicOrPartitionException}. It passed in isolation and failed in a busy full run, which is the
     * signature of a race rather than a broken assertion, so this waits on the condition instead of sleeping.
     */
    private static void awaitVisible(List<String> names) {
        long deadline = System.nanoTime() + java.time.Duration.ofSeconds(60).toNanos();
        while (System.nanoTime() < deadline) {
            try {
                if (admin.listTopics().names().get(30, TimeUnit.SECONDS).containsAll(names)) {
                    return;
                }
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(interrupted);
            } catch (Exception retryable) {
                // Metadata still settling; fall through to the retry below.
            }
            try {
                Thread.sleep(100);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(interrupted);
            }
        }
        throw new AssertionError("topics never became visible: " + names);
    }

    /** Creates topics the way a service does: an existing topic is success, anything else is a failure. */
    private static void createIgnoringExisting(List<NewTopic> topics) throws Exception {
        for (NewTopic topic : topics) {
            try {
                admin.createTopics(List.of(topic)).all().get(60, TimeUnit.SECONDS);
            } catch (ExecutionException existing) {
                if (!(existing.getCause() instanceof TopicExistsException)) {
                    throw existing;
                }
            }
        }
    }

    @Test
    void theDlqNameIsDerivedFromItsSourceRatherThanSpelledOutTwice() {
        assertEquals("payments.money-orders.v1.dlq", TopicDefinitions.dlqFor(TopicDefinitions.MONEY_ORDERS).name());
        assertEquals("payments.payment-events.v1.dlq", TopicDefinitions.dlqFor(TopicDefinitions.PAYMENT_EVENTS).name());
        // A DLQ is smaller and kept far longer than its source: it holds what an operator must still be able to read
        // next week.
        assertTrue(TopicDefinitions.dlqFor(TopicDefinitions.MONEY_ORDERS).retention()
                .compareTo(TopicDefinitions.MONEY_ORDERS.retention()) > 0);
    }

    private static String composeKafkaImage() {
        var pattern = Pattern.compile("^\\s*image:\\s*(apache/kafka:\\S+)\\s*$");
        try {
            return Files.readAllLines(ROOT.resolve("docker-compose.yml")).stream()
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
