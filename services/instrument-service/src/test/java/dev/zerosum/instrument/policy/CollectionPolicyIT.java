package dev.zerosum.instrument.policy;

import static org.assertj.core.api.Assertions.assertThat;

import dev.zerosum.contracts.ContractSchemas;
import dev.zerosum.contracts.GoldenPayloads;
import dev.zerosum.contracts.kafka.TopicDefinitions;
import dev.zerosum.instrument.core.SubmitResult;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.apache.kafka.clients.admin.Admin;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * The collection policy end to end (S05-T09): money orders in, provider calls out, payment events on Kafka.
 *
 * <p>This is the task where money starts moving, so the assertions are deliberately made against ground truth rather
 * than against our own bookkeeping wherever the two could differ: the provider's request count says whether a payer
 * was charged, and the payment-events topic says whether the ledger will ever hear about it. A test that only read
 * {@code payment_attempts} would pass just as happily with the outbox relay unwired, which is exactly the state this
 * step found the service in.
 */
class CollectionPolicyIT extends PolicyPipelineTestBase {

    @LocalServerPort
    private int port;

    @Autowired
    private JdbcClient db;

    @Autowired
    private AttemptOutcomes outcomes;

    @Autowired
    private AttemptSubmitter submitter;

    @Autowired
    private PolicyQuarantine quarantine;

    @BeforeEach
    void registerTheRidersInstrument() {
        PROVIDER.stallMillis = 0;
        // Through the real endpoint, so the registration operation is covered by the scenario that depends on it
        // rather than by a test of its own that could drift from how the policy actually reads tokens.
        Response registered = post("/v1/instrument-tokens", WRITER_TOKEN,
                """
                {"entity_id":"rider:R1","provider":"fakecard","token":"tok_R1"}""");
        assertThat(registered.status()).isEqualTo(200);
        assertThat(registered.body()).as("the instrument token is never echoed back (D00-8)").doesNotContain("tok_R1");
    }

    @Test
    @DisplayName("the service provisions its own topics, so the relay and the consumer have somewhere to go")
    void topicsAreProvisionedByTheService() throws Exception {
        // The broker has auto-creation off and this test never creates a topic itself: these exist only because
        // KafkaAdmin found the service's individual NewTopic beans. Declared as a List<NewTopic> they would be
        // invisible to it, and the symptom would be an outbox that fills up while everything reports healthy.
        try (var admin = Admin.create(Map.of("bootstrap.servers", KAFKA.getBootstrapServers()))) {
            Set<String> topics = admin.listTopics().names().get(30, TimeUnit.SECONDS);
            assertThat(topics).contains(TopicDefinitions.MONEY_ORDERS.name(), TopicDefinitions.PAYMENT_EVENTS.name());

            var description = admin.describeTopics(List.of(TopicDefinitions.PAYMENT_EVENTS.name()))
                    .allTopicNames().get(30, TimeUnit.SECONDS).get(TopicDefinitions.PAYMENT_EVENTS.name());
            assertThat(description.partitions()).as("the contracted partition count, not an auto-created default")
                    .hasSize(TopicDefinitions.PAYMENT_EVENTS.partitions());
        }
    }

    @Test
    @DisplayName("the golden trip charges the rider once however often it is delivered, and its adjustment refunds")
    void goldenTripChargesOnceAndItsAdjustmentRefunds() {
        String charge = GoldenPayloads.byId("O1");
        UUID chargeOrder = orderIdOf(charge);
        String group = JSON.readTree(charge).get("order_group_id").asString();
        int callsBefore = PROVIDER.requestCount();

        // Three deliveries of the identical record, exactly as a redelivering broker would.
        publish(group, charge);
        publish(group, charge);
        publish(group, charge);

        awaitUntil(() -> "SUCCEEDED".equals(status(chargeOrder)),
                () -> "the charge never reached SUCCEEDED; it is " + status(chargeOrder));
        // Held for a moment: a second attempt, if one were coming, would arrive after the first, not before.
        assertStays(() -> attemptCount(chargeOrder) == 1, "a redelivered order must not create a second attempt");

        assertThat(attemptCount(chargeOrder)).isEqualTo(1);
        assertThat(kindOf(chargeOrder)).isEqualTo("CHARGE");
        assertThat(amountOf(chargeOrder)).isEqualTo(2_500);
        assertThat(PROVIDER.requestCount() - callsBefore)
                .as("ground truth: one provider call for three deliveries — the payer is charged once")
                .isEqualTo(1);
        assertThat(outboxRows(attemptOf(chargeOrder))).as("one money-moving transition, one payment event").isEqualTo(1);

        // The adjustment. Same group, so the broker keeps it behind the charge on one partition (ADR-0007).
        String adjustment = GoldenPayloads.byId("O3");
        UUID refundOrder = orderIdOf(adjustment);
        publish(group, adjustment);

        awaitUntil(() -> "SUCCEEDED".equals(status(refundOrder)),
                () -> "the refund never reached SUCCEEDED; it is " + status(refundOrder));
        assertThat(kindOf(refundOrder)).isEqualTo("REFUND");
        assertThat(amountOf(refundOrder)).as("a refund of the adjusted amount, not of the fare").isEqualTo(300);
        assertThat(blockedOnCapture(refundOrder)).isFalse();

        // What actually left the building. The relay had to be wired for any of this to exist on the topic.
        List<JsonNode> events = publishedPaymentEventsFor(attemptOf(chargeOrder), attemptOf(refundOrder));
        assertThat(eventTypes(events, attemptOf(chargeOrder))).containsExactly("CHARGE_SUCCEEDED");
        assertThat(eventTypes(events, attemptOf(refundOrder))).containsExactly("REFUND_SUCCEEDED");
        for (JsonNode event : events) {
            assertThat(ContractSchemas.validate(ContractSchemas.PAYMENT_EVENT_V1, event.toString()))
                    .as("every published payment event must satisfy the D01-8 contract: %s", event).isEmpty();
        }
    }

    @Test
    @DisplayName("an adjustment during an unresolved capture is blocked, then released and submitted when it succeeds")
    void refundBlockedOnAnUnresolvedCaptureIsReleasedWhenTheChargeSucceeds() {
        // Below the read timeout: the capture is slow, not lost, so the whole path runs unattended.
        PROVIDER.stallMillis = 3_000;
        String group = "trip_slowcapture_" + UUID.randomUUID();
        String charge = goldenOrder("O1", group, UUID.randomUUID(), null);
        UUID chargeOrder = orderIdOf(charge);

        publish(group, charge);
        awaitUntil(() -> "SUBMITTING".equals(status(chargeOrder)),
                () -> "the charge never reached SUBMITTING; it is " + status(chargeOrder));

        String adjustment = goldenOrder("O3", group, UUID.randomUUID(), chargeOrder);
        UUID refundOrder = orderIdOf(adjustment);
        publish(group, adjustment);

        awaitUntil(() -> status(refundOrder) != null, () -> "the refund was never created");
        assertThat(blockedOnCapture(refundOrder))
                .as("a refund whose capture is unresolved waits rather than refunding money nobody took").isTrue();
        assertThat(status(refundOrder)).isEqualTo("CREATED");

        PROVIDER.stallMillis = 0;
        awaitUntil(() -> "SUCCEEDED".equals(status(chargeOrder)),
                () -> "the charge never resolved; it is " + status(chargeOrder));
        awaitUntil(() -> "SUCCEEDED".equals(status(refundOrder)),
                () -> "the blocked refund was never released and submitted; it is " + status(refundOrder));

        assertThat(blockedOnCapture(refundOrder)).isFalse();
        assertThat(amountOf(refundOrder)).as("sized when the capture resolved, against what it captured").isEqualTo(300);
    }

    @Test
    @DisplayName("a capture slower than the read timeout goes UNKNOWN and its refund stays blocked until it resolves")
    void aChargeSlowerThanTheReadTimeoutLeavesItsRefundBlocked() {
        PROVIDER.stallMillis = READ_TIMEOUT.plusSeconds(3).toMillis();
        String group = "trip_timeout_" + UUID.randomUUID();
        String charge = goldenOrder("O1", group, UUID.randomUUID(), null);
        UUID chargeOrder = orderIdOf(charge);
        String adjustment = goldenOrder("O3", group, UUID.randomUUID(), chargeOrder);
        UUID refundOrder = orderIdOf(adjustment);

        publish(group, charge);
        publish(group, adjustment);

        awaitUntil(() -> status(refundOrder) != null, () -> "the refund was never created");
        assertThat(blockedOnCapture(refundOrder)).isTrue();

        awaitUntil(() -> "UNKNOWN".equals(status(chargeOrder)),
                () -> "a capture past the read timeout must become UNKNOWN, not fail; it is " + status(chargeOrder));
        assertStays(() -> "CREATED".equals(status(refundOrder)) && blockedOnCapture(refundOrder),
                "an unresolved capture is not a resolved one: the refund must keep waiting");

        PROVIDER.stallMillis = 0;

        // The provider is asked again and says the charge went through. In production the S05-T12 resolver makes that
        // call and hands the answer to exactly these two lines; that resolver does not exist yet, so the test plays
        // its part. Everything after this point — the transition, the event, the release, the refund's own submission
        // — is the service's own code on its own path.
        submitter.submitLater(outcomes.apply(attemptOf(chargeOrder), new SubmitResult.Succeeded("ch_resolved")));

        awaitUntil(() -> "SUCCEEDED".equals(status(refundOrder)),
                () -> "the refund was never released after the capture resolved; it is " + status(refundOrder));
        assertThat(blockedOnCapture(refundOrder)).isFalse();
        assertThat(amountOf(refundOrder)).isEqualTo(300);
        assertThat(status(chargeOrder)).isEqualTo("SUCCEEDED");
    }

    @Nested
    @DisplayName("poison records are quarantined and the partition keeps moving")
    class Quarantine {

        @Test
        @DisplayName("undecodable bytes and a schema-invalid order are each quarantined, and the good order behind them applies")
        void poisonDoesNotBlockThePartition() {
            String group = "trip_poison_" + UUID.randomUUID();
            String undecodable = "these bytes are not a money order " + UUID.randomUUID();
            ObjectNode invalid = (ObjectNode) JSON.readTree(GoldenPayloads.byId("O1"));
            invalid.put("order_group_id", group);
            invalid.put("order_id", UUID.randomUUID().toString());
            invalid.put("type", "NONSENSE");   // not in the D01-8 enum

            String good = goldenOrder("O1", group, UUID.randomUUID(), null);
            UUID goodOrder = orderIdOf(good);

            // All three on one partition, because they share the key: that is what makes "the partition keeps
            // moving" a real claim rather than an accident of partitioning.
            publish(group, undecodable);
            publish(group, invalid.toString());
            publish(group, good);

            awaitUntil(() -> status(goodOrder) != null,
                    () -> "a poison record must not block the orders behind it on its partition");

            assertThat(quarantineCodeFor(undecodable)).isEqualTo("undecodable");
            assertThat(quarantineCodeFor(invalid.toString())).isEqualTo("schema_invalid");
            assertThat(attemptCount(UUID.fromString(invalid.get("order_id").asString())))
                    .as("poison must never become an attempt").isZero();
        }

        @Test
        @DisplayName("the same Kafka coordinates are quarantined once, not once per delivery")
        void sameCoordinatesAreQuarantinedOnce() {
            // The redelivery the conflict clause guards is the same record at the same offset, after a rebalance or a
            // crash (§0.3 C25). Driven directly, because a broker will not hand the same offset twice in one run.
            String payload = "duplicate poison " + UUID.randomUUID();
            quarantine.quarantine(payload, "undecodable", "first", "t-dup", 0, 7L);
            quarantine.quarantine(payload, "undecodable", "second", "t-dup", 0, 7L);

            assertThat(quarantineRowsFor(payload)).isEqualTo(1);
        }
    }

    @Nested
    @DisplayName("instrument registration")
    class Registration {

        @Test
        @DisplayName("a provider no adapter serves is refused, so no attempt can be created that could never be sent")
        void unknownProviderIsRefused() {
            Response response = post("/v1/instrument-tokens", WRITER_TOKEN,
                    """
                    {"entity_id":"rider:R9","provider":"not-a-provider","token":"tok_9"}""");

            assertThat(response.status()).isEqualTo(400);
            assertThat(JSON.readTree(response.body()).get("code").asString()).isEqualTo("unknown_provider");
        }

        @Test
        @DisplayName("an entity id that could never appear in a money order is refused")
        void malformedEntityIsRefused() {
            Response response = post("/v1/instrument-tokens", WRITER_TOKEN,
                    """
                    {"entity_id":"R1","provider":"fakecard","token":"tok_9"}""");

            assertThat(response.status()).isEqualTo(400);
            assertThat(JSON.readTree(response.body()).get("code").asString()).isEqualTo("invalid_registration");
        }

        @Test
        @DisplayName("registering an instrument is a writer operation, not a reader one")
        void rolesAreEnforced() {
            String body = """
                    {"entity_id":"rider:R8","provider":"fakecard","token":"tok_8"}""";

            assertThat(post("/v1/instrument-tokens", null, body).status()).isEqualTo(401);
            assertThat(post("/v1/instrument-tokens", "test-reader-token", body).status()).isEqualTo(403);
        }
    }

    // ---- queries -----------------------------------------------------------------------------------------------

    private String status(UUID sourceOrderId) {
        return db.sql("SELECT status FROM payment_attempts WHERE source_order_id = :id")
                .param("id", sourceOrderId).query(String.class).optional().orElse(null);
    }

    private String kindOf(UUID sourceOrderId) {
        return db.sql("SELECT kind FROM payment_attempts WHERE source_order_id = :id")
                .param("id", sourceOrderId).query(String.class).single();
    }

    private long amountOf(UUID sourceOrderId) {
        return db.sql("SELECT amount_minor FROM payment_attempts WHERE source_order_id = :id")
                .param("id", sourceOrderId).query(Long.class).single();
    }

    private boolean blockedOnCapture(UUID sourceOrderId) {
        return db.sql("SELECT blocked_on_capture FROM payment_attempts WHERE source_order_id = :id")
                .param("id", sourceOrderId).query(Boolean.class).single();
    }

    private UUID attemptOf(UUID sourceOrderId) {
        return db.sql("SELECT attempt_id FROM payment_attempts WHERE source_order_id = :id")
                .param("id", sourceOrderId).query(UUID.class).single();
    }

    private int attemptCount(UUID sourceOrderId) {
        return db.sql("SELECT count(*) FROM payment_attempts WHERE source_order_id = :id")
                .param("id", sourceOrderId).query(Integer.class).single();
    }

    private int outboxRows(UUID attemptId) {
        return db.sql("SELECT count(*) FROM outbox WHERE payload->>'attempt_id' = :id")
                .param("id", attemptId.toString()).query(Integer.class).single();
    }

    private int quarantineRowsFor(String payload) {
        return db.sql("SELECT count(*) FROM quarantined_records WHERE convert_from(payload, 'UTF8') = :payload")
                .param("payload", payload).query(Integer.class).single();
    }

    private String quarantineCodeFor(String payload) {
        return db.sql("SELECT error_code FROM quarantined_records WHERE convert_from(payload, 'UTF8') = :payload")
                .param("payload", payload).query(String.class).optional()
                .orElseThrow(() -> new AssertionError("nothing was quarantined for: " + payload));
    }

    /** Reads the payment-events topic and keeps the events for the attempts this test cares about. */
    private List<JsonNode> publishedPaymentEventsFor(UUID... attemptIds) {
        Set<String> wanted = java.util.Arrays.stream(attemptIds).map(UUID::toString)
                .collect(java.util.stream.Collectors.toSet());
        return publishedPaymentEvents(Duration.ofSeconds(5)).stream()
                .map(JSON::readTree)
                .filter(event -> wanted.contains(event.get("attempt_id").asString()))
                .toList();
    }

    private static List<String> eventTypes(List<JsonNode> events, UUID attemptId) {
        return events.stream()
                .filter(event -> attemptId.toString().equals(event.get("attempt_id").asString()))
                .map(event -> event.get("event_type").asString())
                .toList();
    }

    // ---- HTTP --------------------------------------------------------------------------------------------------

    private record Response(int status, String body) {
    }

    private Response post(String uri, String token, String body) {
        var request = RestClient.create("http://localhost:" + port).post().uri(uri)
                .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                .body(body);
        if (token != null) {
            request = request.header("Authorization", "Bearer " + token);
        }
        return request.exchange((req, response) -> new Response(response.getStatusCode().value(),
                new String(response.getBody().readAllBytes())), false);
    }
}
