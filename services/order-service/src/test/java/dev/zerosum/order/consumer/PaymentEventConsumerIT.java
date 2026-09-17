package dev.zerosum.order.consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.zerosum.contracts.GoldenPayloads;
import dev.zerosum.order.support.OrderTestDatabase;
import java.nio.file.Files;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.function.BooleanSupplier;
import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.utility.DockerImageName;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * The payment-event consumer end to end (D03-6, ADR-0006): a real broker, a real database, and the real listener.
 *
 * <p>A mocked listener could not prove the claim that matters — that an event delivered three times produces exactly
 * one order (I8) — because the uniqueness that makes it true lives in the database, not in the consumer.
 */
@Tag("integration")
@SpringBootTest
class PaymentEventConsumerIT {

    private static final String TOPIC = "payments.payment-events.v1";
    private static final JsonMapper JSON = JsonMapper.builder().build();

    private static final OrderTestDatabase DB = OrderTestDatabase.start();
    private static final KafkaContainer KAFKA = startKafka();

    @Autowired
    private JdbcTemplate template;

    @DynamicPropertySource
    static void context(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", DB::jdbcUrl);
        registry.add("spring.datasource.username", () -> OrderTestDatabase.APP);
        registry.add("spring.datasource.password", () -> DB.password(OrderTestDatabase.APP));
        registry.add("spring.flyway.enabled", () -> "false");
        registry.add("spring.kafka.bootstrap-servers", KAFKA::getBootstrapServers);
        registry.add("zs.auth.writer-tokens", () -> "trip-simulator:w");
        registry.add("zs.auth.reader-token", () -> "r");
        registry.add("zs.auth.admin-token", () -> "a");
        registry.add("zs.consumer.enabled", () -> "true");
        registry.add("zs.consumer.payment-events-topic", () -> TOPIC);
        registry.add("zs.consumer.group-id", () -> "order-service-mapper-it");
    }

    @AfterAll
    static void stop() {
        KAFKA.stop();
        DB.close();
    }

    @BeforeEach
    void clear() {
        template.update("DELETE FROM outbox");
        // As the OWNER: orders_app deliberately has no DELETE on the quarantine table (V5), so the runtime role
        // cannot erase the evidence — which the test below asserts rather than works around.
        clearQuarantineAsOwner();
    }

    @Test
    void anEventDeliveredThreeTimesProducesExactlyOneOrderAndOneOutboxRow() {
        String event = GoldenPayloads.byId("EV-O5");   // PAYOUT_ACCEPTED, a mapper-only order type
        String eventId = JSON.readTree(event).get("event_id").asString();

        // Three deliveries of the identical record, exactly as a redelivering broker would.
        for (int i = 0; i < 3; i++) {
            send(eventId, event);
        }

        assertEventually(() -> ordersFor(eventId) == 1,
                () -> "expected exactly one order for " + eventId + " but found " + ordersFor(eventId));
        // Held for a moment: if a second delivery were going to create a duplicate, it would do so after the first.
        assertStable(() -> ordersFor(eventId) == 1, "a redelivered event must not create a second order");

        assertEquals(1, ordersFor(eventId));
        assertEquals(1, outboxRows(), "exactly one outbox row, written in the same transaction as the order");
        assertEquals("DISBURSEMENT", template.queryForObject(
                "SELECT type FROM money_orders WHERE idempotency_key = ?", String.class, eventId));
        assertEquals("instrument-service", template.queryForObject(
                "SELECT source_system FROM money_orders WHERE idempotency_key = ?", String.class, eventId),
                "a mapped order is attributed to the internal mapper principal, never to a caller");

        // The published form must satisfy the D01-8 contract, not merely be well-formed JSON. Without this the
        // acceptance gate covering *published* orders would rest on the API path alone, and the mapper path writes
        // its outbox payload through the same publisher but from different inputs.
        String published = template.queryForObject("SELECT payload::text FROM outbox", String.class);
        assertEquals(List.of(), dev.zerosum.contracts.ContractSchemas.validate(
                        dev.zerosum.contracts.ContractSchemas.MONEY_ORDER_V1, published),
                "the published money order must validate against the D01-8 schema: " + published);
    }

    @Test
    void anEventThatDeclinesProducesNoOrderAndIsNotQuarantined() {
        String event = GoldenPayloads.byId("EV-CHARGE_DECLINED");
        String eventId = JSON.readTree(event).get("event_id").asString();

        send(eventId, event);

        // No order is the correct outcome, not a failure: nothing moved, so there is nothing to book.
        assertEventually(this::consumerIsIdle, () -> "the consumer never caught up");
        assertEquals(0, ordersFor(eventId));
        assertEquals(0, quarantined(), "a decline is a normal outcome and must not be quarantined");
    }

    @Test
    void anUnmappableEventIsQuarantinedPerDeliveryAndThePartitionKeepsMoving() {
        var broken = (ObjectNode) JSON.readTree(GoldenPayloads.byId("EV-O2"));
        broken.put("event_type", "CHARGE_REVERSED");   // schema-invalid: not in the enum
        String eventId = broken.get("event_id").asString();

        send(eventId, broken.toString());
        send(eventId, broken.toString());   // redelivered

        assertEventually(() -> quarantined() >= 1, () -> "the poison event was never quarantined");
        assertEquals(0, ordersFor(eventId), "poison must never become an order");

        // The partition keeps moving: a good event after the poison is still processed.
        String good = GoldenPayloads.byId("EV-O7");
        String goodId = JSON.readTree(good).get("event_id").asString();
        send(goodId, good);
        assertEventually(() -> ordersFor(goodId) == 1,
                () -> "a poison record must not block the events behind it");

        assertEquals(2, quarantined(),
                "two deliveries sit at two offsets, and the conflict key is the coordinates, so each is recorded; "
                        + "the same offset twice is covered separately below");
    }

    @Test
    void quarantiningTheSameCoordinatesTwiceWritesOneRow() {
        // The redelivery the ON CONFLICT clause actually guards against is the same record at the same offset, which
        // happens on a rebalance or a retry. Driven directly, because a broker will not hand the same offset twice
        // within one test run.
        var store = new QuarantineStore(JdbcClient.create(template.getDataSource()));
        store.quarantine("ev-dup", "{}", "schema_invalid", "first", "t-dup", 0, 7L);
        store.quarantine("ev-dup", "{}", "schema_invalid", "second", "t-dup", 0, 7L);

        assertEquals(1, quarantined(), "the same Kafka coordinates must be quarantined once, not once per delivery");
    }

    @Test
    void theRuntimeRoleCannotDeleteQuarantinedEvidence() {
        // Resolving a poison record means setting resolved_at, never removing the row. The grant is what enforces it,
        // so this asserts the privilege rather than trusting the migration comment.
        DataAccessException refused =
                assertThrows(DataAccessException.class, () -> template.update("DELETE FROM quarantined_events"));
        assertEquals("42501", sqlStateOf(refused),
                "deleting quarantined evidence must fail on privileges (insufficient_privilege): " + refused);
    }

    /** The SQLSTATE from anywhere in the cause chain: Spring reports 42501 as a BadSqlGrammarException. */
    private static String sqlStateOf(Throwable failure) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof SQLException sql && sql.getSQLState() != null) {
                return sql.getSQLState();
            }
        }
        return null;
    }

    private static void clearQuarantineAsOwner() {
        try (Connection connection = DB.connect(OrderTestDatabase.OWNER);
                var statement = connection.createStatement()) {
            statement.executeUpdate("DELETE FROM quarantined_events");
        } catch (SQLException failure) {
            throw new IllegalStateException("could not clear quarantined_events as the owner", failure);
        }
    }

    private int ordersFor(String idempotencyKey) {
        Integer count = template.queryForObject(
                "SELECT count(*) FROM money_orders WHERE idempotency_key = ?", Integer.class, idempotencyKey);
        return count == null ? 0 : count;
    }

    private int outboxRows() {
        Integer count = template.queryForObject("SELECT count(*) FROM outbox", Integer.class);
        return count == null ? 0 : count;
    }

    private int quarantined() {
        Integer count = template.queryForObject("SELECT count(*) FROM quarantined_events", Integer.class);
        return count == null ? 0 : count;
    }

    private boolean consumerIsIdle() {
        // Nothing observable to wait for when an event produces no order, so this waits for the listener to have had
        // a fair chance rather than asserting on a state that never changes.
        sleep(Duration.ofSeconds(2));
        return true;
    }

    private static void send(String key, String payload) {
        Map<String, Object> config = Map.of(
                "bootstrap.servers", KAFKA.getBootstrapServers(),
                "acks", "all");
        try (var producer = new KafkaProducer<>(config, new StringSerializer(), new StringSerializer())) {
            producer.send(new ProducerRecord<>(TOPIC, key, payload)).get(30, java.util.concurrent.TimeUnit.SECONDS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(interrupted);
        } catch (Exception failure) {
            throw new IllegalStateException("could not produce to " + TOPIC, failure);
        }
    }

    private static void assertEventually(BooleanSupplier condition, java.util.function.Supplier<String> message) {
        long deadline = System.nanoTime() + Duration.ofSeconds(60).toNanos();
        while (System.nanoTime() < deadline) {
            if (condition.getAsBoolean()) {
                return;
            }
            sleep(Duration.ofMillis(250));
        }
        throw new AssertionError(message.get());
    }

    private static void assertStable(BooleanSupplier condition, String message) {
        for (int i = 0; i < 8; i++) {
            assertTrue(condition.getAsBoolean(), message);
            sleep(Duration.ofMillis(250));
        }
    }

    private static void sleep(Duration duration) {
        try {
            Thread.sleep(duration.toMillis());
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    private static KafkaContainer startKafka() {
        var container = new KafkaContainer(DockerImageName.parse(composeKafkaImage())
                .asCompatibleSubstituteFor("apache/kafka"));
        container.start();
        try (var admin = Admin.create(Map.of("bootstrap.servers", container.getBootstrapServers()))) {
            admin.createTopics(List.of(new NewTopic(TOPIC, 1, (short) 1))).all()
                    .get(30, java.util.concurrent.TimeUnit.SECONDS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(interrupted);
        } catch (Exception failure) {
            throw new IllegalStateException("could not create " + TOPIC, failure);
        }
        return container;
    }

    /** Pinned to the compose image, so the test and the deployed stack never run different broker versions. */
    private static String composeKafkaImage() {
        var pattern = Pattern.compile("^\\s*image:\\s*(apache/kafka:\\S+)\\s*$");
        try {
            return Files.readAllLines(OrderTestDatabase.ROOT.resolve("docker-compose.yml")).stream()
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
