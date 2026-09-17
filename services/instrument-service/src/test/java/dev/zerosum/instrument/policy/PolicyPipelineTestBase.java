package dev.zerosum.instrument.policy;

import dev.zerosum.contracts.GoldenPayloads;
import dev.zerosum.contracts.kafka.TopicDefinitions;
import dev.zerosum.instrument.adapter.ProviderStub;
import dev.zerosum.testsupport.ZsTestDatabase;
import java.nio.file.Files;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.Tag;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.utility.DockerImageName;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * A real broker, a real database, a real provider socket and the real listener, for the S05-T09 policy tests.
 *
 * <p>Nothing stands in for the parts under test. The claims that matter — one attempt however many times an order
 * arrives, one provider call, one payment event — are true because of a database constraint, an optimistic guard and
 * a broker's per-key ordering, none of which a mocked listener would exercise.
 *
 * <p><strong>The topics are deliberately not pre-created here.</strong> The service's own {@code NewTopic} beans must
 * provision them, and the broker has auto-creation off, so a regression to a {@code List<NewTopic>} bean — which
 * {@code KafkaAdmin} cannot see, and which this repository has already shipped once — fails these tests instead of
 * quietly publishing nothing.
 *
 * <p>The containers are static on this base class, so the subclasses share one broker and one database while running
 * their own Spring contexts with their own kill-switch settings.
 */
@Tag("integration")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
abstract class PolicyPipelineTestBase {

    protected static final JsonMapper JSON = JsonMapper.builder().build();

    protected static final String WRITER_TOKEN = "test-writer-token";
    protected static final String WRITER = "trip-simulator:" + WRITER_TOKEN;

    /** The production default. Stated here so a test that depends on it does not silently follow a config change. */
    protected static final Duration READ_TIMEOUT = Duration.ofSeconds(5);

    protected static final ZsTestDatabase DB = ZsTestDatabase.start(
            "instruments", "services/instrument-service/src/main/resources/db/migration");
    protected static final KafkaContainer KAFKA = startKafka();
    protected static final ProviderStub PROVIDER = ProviderStub.start();

    @DynamicPropertySource
    static void pipeline(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", DB::jdbcUrl);
        registry.add("spring.datasource.username", DB::app);
        registry.add("spring.datasource.password", () -> DB.password(DB.app()));
        // The fixture already migrated as the owner; the application role has no DDL rights, by design.
        registry.add("spring.flyway.enabled", () -> "false");
        registry.add("spring.kafka.bootstrap-servers", KAFKA::getBootstrapServers);
        registry.add("zs.auth.writer-tokens", () -> WRITER);
        registry.add("zs.auth.reader-token", () -> "test-reader-token");
        registry.add("zs.auth.admin-token", () -> "test-admin-token");
        // Both adapters point at the one stub: it routes by path exactly as fake-providers does.
        registry.add("zs.instruments.fakecard-base-url", PROVIDER::baseUrl);
        registry.add("zs.instruments.fakebank-base-url", PROVIDER::baseUrl);
        registry.add("zs.instruments.read-timeout", () -> READ_TIMEOUT.toMillis() + "ms");
        registry.add("zs.policy.consumer.enabled", () -> "true");
    }

    /** Publishes as the order-service relay does: keyed by the order group, so a group stays on one partition. */
    protected static void publish(String orderGroupId, String payload) {
        try (var producer = new KafkaProducer<>(
                Map.of("bootstrap.servers", KAFKA.getBootstrapServers(), "acks", "all"),
                new StringSerializer(), new StringSerializer())) {
            producer.send(new ProducerRecord<>(TopicDefinitions.MONEY_ORDERS.name(), orderGroupId, payload))
                    .get(30, TimeUnit.SECONDS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(interrupted);
        } catch (Exception failure) {
            throw new IllegalStateException("could not publish to " + TopicDefinitions.MONEY_ORDERS.name(), failure);
        }
    }

    /**
     * Everything the outbox relay has published to the payment-events topic so far.
     *
     * <p>Read from the broker rather than from the outbox table on purpose: the table would be just as full if the
     * relay were never wired at all, which is exactly the defect this step had to fix.
     */
    protected static List<String> publishedPaymentEvents(Duration atMost) {
        Map<String, Object> config = Map.of(
                "bootstrap.servers", KAFKA.getBootstrapServers(),
                "group.id", "policy-it-reader-" + UUID.randomUUID(),
                "auto.offset.reset", "earliest",
                "enable.auto.commit", "false");
        var events = new java.util.ArrayList<String>();
        try (var consumer = new KafkaConsumer<>(config, new StringDeserializer(), new StringDeserializer())) {
            consumer.subscribe(List.of(TopicDefinitions.PAYMENT_EVENTS.name()));
            long deadline = System.nanoTime() + atMost.toNanos();
            while (System.nanoTime() < deadline) {
                ConsumerRecords<String, String> records = consumer.poll(Duration.ofMillis(500));
                for (ConsumerRecord<String, String> record : records) {
                    events.add(record.value());
                }
            }
        }
        return List.copyOf(events);
    }

    /** A golden money order moved to its own group, so one scenario's attempts cannot collide with another's. */
    protected static String goldenOrder(String goldenId, String orderGroupId, UUID orderId, UUID adjustsOrderId) {
        ObjectNode order = (ObjectNode) JSON.readTree(GoldenPayloads.byId(goldenId));
        order.put("order_group_id", orderGroupId);
        order.put("order_id", orderId.toString());
        if (adjustsOrderId != null) {
            order.put("adjusts_order_id", adjustsOrderId.toString());
        }
        // The idempotency key belongs to the producing system; keeping it unique matches what a real producer does.
        ((ObjectNode) order.get("source")).put("idempotency_key", orderGroupId + ":" + orderId);
        return order.toString();
    }

    protected static UUID orderIdOf(String payload) {
        return UUID.fromString(JSON.readTree(payload).get("order_id").asString());
    }

    /** The pipeline is asynchronous, so every assertion needs a deadline rather than a sleep. */
    protected static void awaitUntil(BooleanSupplier condition, Supplier<String> whatWasExpected) {
        long deadline = System.nanoTime() + Duration.ofSeconds(90).toNanos();
        while (System.nanoTime() < deadline) {
            if (condition.getAsBoolean()) {
                return;
            }
            sleep(Duration.ofMillis(100));
        }
        throw new AssertionError(whatWasExpected.get());
    }

    /** Holds a condition for a while: a duplicate that was going to appear would appear after the first, not before. */
    protected static void assertStays(BooleanSupplier condition, String message) {
        for (int i = 0; i < 10; i++) {
            if (!condition.getAsBoolean()) {
                throw new AssertionError(message);
            }
            sleep(Duration.ofMillis(200));
        }
    }

    protected static void sleep(Duration duration) {
        try {
            Thread.sleep(duration.toMillis());
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    private static KafkaContainer startKafka() {
        var container = new KafkaContainer(
                DockerImageName.parse(composeKafkaImage()).asCompatibleSubstituteFor("apache/kafka"));
        container.start();
        return container;
    }

    /** Pinned to the compose image, so the test and the deployed stack never run different broker versions. */
    private static String composeKafkaImage() {
        var pattern = Pattern.compile("^\\s*image:\\s*(apache/kafka:\\S+)\\s*$");
        try {
            return Files.readAllLines(ZsTestDatabase.ROOT.resolve("docker-compose.yml")).stream()
                    .map(pattern::matcher)
                    .filter(Matcher::matches)
                    .map(match -> match.group(1))
                    .findFirst()
                    .orElseThrow(() -> new IllegalStateException("no apache/kafka image in docker-compose.yml"));
        } catch (java.io.IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
    }
}
