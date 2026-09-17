package dev.zerosum.instrument.api;

import static org.assertj.core.api.Assertions.assertThat;

import com.networknt.schema.InputFormat;
import com.networknt.schema.SchemaRegistry;
import com.networknt.schema.SpecificationVersion;
import dev.zerosum.instrument.adapter.ProviderStub;
import dev.zerosum.instrument.adapter.TestWebhookSigner;
import dev.zerosum.testsupport.ZsTestDatabase;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.client.RestClient;
import org.yaml.snakeyaml.Yaml;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * S05-T11 evidence: the webhook receiver over real HTTP, against a real database (M9(a), §0.3 C21).
 *
 * <p>Every claim here is one a forged or replayed delivery would break: a signature that does not verify writes
 * nothing, one event id applies once however many times it arrives, and an event that outruns our state is recorded
 * and then caught up so each payment event is emitted exactly once.
 *
 * <p>The outbox is asserted directly rather than only the final status. A status can be right while the events that
 * told the ledger how it got there are missing or doubled, and the ledger is what pays people.
 */
@Tag("integration")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class WebhookReceiverIT {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final SchemaRegistry SCHEMAS = SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_2020_12);

    private static final ZsTestDatabase DB = ZsTestDatabase.start(
            "instruments", "services/instrument-service/src/main/resources/db/migration");
    private static final ProviderStub PROVIDER = ProviderStub.start();

    /** decision: D05-3 — `current,previous`, exactly as a rotation configures both sides. */
    private static final String CURRENT_SECRET = "test-current-secret";
    private static final String PREVIOUS_SECRET = "test-previous-secret";

    @LocalServerPort
    private int port;

    @Autowired
    private JdbcClient db;

    @DynamicPropertySource
    static void context(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", DB::jdbcUrl);
        registry.add("spring.datasource.username", DB::app);
        registry.add("spring.datasource.password", () -> DB.password(DB.app()));
        // The fixture already migrated as the owner; the application role has no DDL rights, by design.
        registry.add("spring.flyway.enabled", () -> "false");
        registry.add("zs.auth.reader-token", () -> "test-reader-token");
        registry.add("zs.auth.admin-token", () -> "test-admin-token");
        registry.add("zs.auth.writer-tokens", () -> "trip-simulator:test-writer-token");
        registry.add("zs.webhooks.secrets", () -> CURRENT_SECRET + "," + PREVIOUS_SECRET);
        // The ahead-of-state resolution asks the provider what really happened; this stub is what answers.
        registry.add("zs.instruments.fakecard-base-url", PROVIDER::baseUrl);
        registry.add("zs.instruments.fakebank-base-url", PROVIDER::baseUrl);
    }

    @Test
    @DisplayName("a correctly signed webhook is recorded, applied, and emits its payment event once")
    void validWebhookIsRecordedAndApplied() {
        UUID attemptId = charge("SUBMITTING");
        String eventId = "evt_" + UUID.randomUUID();

        Response response = post("fakecard", chargeSucceeded(eventId, attemptId), CURRENT_SECRET, Instant.now());

        assertThat(response.status).isEqualTo(200);
        assertThat(JSON.readTree(response.body).get("disposition").asString()).isEqualTo("applied");
        assertThat(JSON.readTree(response.body).get("event_id").asString()).isEqualTo(eventId);
        assertThat(status(attemptId)).isEqualTo("SUCCEEDED");
        assertThat(recorded(eventId)).isEqualTo(1);
        assertThat(recordedAttempt(eventId)).isEqualTo(attemptId);
        assertThat(outboxEvents(attemptId)).containsExactly("CHARGE_SUCCEEDED");
    }

    @Test
    @DisplayName("the previous secret is accepted during a rotation")
    void previousSecretIsAccepted() {
        UUID attemptId = charge("SUBMITTING");

        Response response = post("fakecard", chargeSucceeded("evt_" + UUID.randomUUID(), attemptId),
                PREVIOUS_SECRET, Instant.now());

        assertThat(response.status).isEqualTo(200);
        assertThat(status(attemptId)).isEqualTo("SUCCEEDED");
    }

    @Test
    @DisplayName("a forged, tampered, unsigned or stale delivery is 400 and writes nothing")
    void unverifiedDeliveriesAreRefused() {
        UUID attemptId = charge("SUBMITTING");
        String eventId = "evt_" + UUID.randomUUID();
        byte[] body = chargeSucceeded(eventId, attemptId);

        assertThat(post("fakecard", body, "someone-elses-secret", Instant.now()).status).isEqualTo(400);
        assertThat(post("fakecard", body, CURRENT_SECRET, Instant.now().minus(Duration.ofSeconds(301))).status)
                .as("a captured delivery replayed outside the 300 s tolerance").isEqualTo(400);
        assertThat(post("fakecard", body, CURRENT_SECRET, Instant.now().plus(Duration.ofSeconds(301))).status)
                .as("a timestamp far in the future buys no replay window either").isEqualTo(400);
        assertThat(postSigned("fakecard", body, null).status).as("no signature header at all").isEqualTo(400);

        // A body altered after signing: the signature is real, the bytes are not the ones it covers.
        byte[] tampered = new String(body, StandardCharsets.UTF_8).replace("2500", "250000")
                .getBytes(StandardCharsets.UTF_8);
        Response tamperedResponse = postSigned("fakecard", tampered,
                TestWebhookSigner.header(Instant.now(), body, CURRENT_SECRET));

        assertThat(tamperedResponse.status).isEqualTo(400);
        assertThat(JSON.readTree(tamperedResponse.body).get("code").asString()).isEqualTo("invalid_signature");
        assertThat(recorded(eventId)).as("nothing a stranger sent is ever written down").isZero();
        assertThat(status(attemptId)).isEqualTo("SUBMITTING");
        assertThat(outboxEvents(attemptId)).isEmpty();
    }

    @Test
    @DisplayName("one event delivered many times, sequentially and concurrently, is recorded once and applied once")
    void redeliveryIsRecordedOnceAndAppliedOnce() {
        UUID attemptId = charge("SUBMITTING");
        String eventId = "evt_" + UUID.randomUUID();
        byte[] body = chargeSucceeded(eventId, attemptId);

        for (int delivery = 0; delivery < 5; delivery++) {
            assertThat(post("fakecard", body, CURRENT_SECRET, Instant.now()).status).isEqualTo(200);
        }
        concurrently(4, () -> post("fakecard", body, CURRENT_SECRET, Instant.now()).status);

        assertThat(recorded(eventId)).as("the primary key, not a check, is what makes this one").isEqualTo(1);
        assertThat(status(attemptId)).isEqualTo("SUCCEEDED");
        assertThat(transitionRows(attemptId)).isEqualTo(1);
        assertThat(outboxEvents(attemptId)).as("one payment event for one movement of money")
                .containsExactly("CHARGE_SUCCEEDED");

        // A *different* event id claiming a status the attempt already holds is non-advancing, not a second event.
        Response stale = post("fakecard", chargeSucceeded("evt_" + UUID.randomUUID(), attemptId), CURRENT_SECRET,
                Instant.now());

        assertThat(stale.status).isEqualTo(200);
        assertThat(JSON.readTree(stale.body).get("disposition").asString()).isEqualTo("ignored_stale");
        assertThat(outboxEvents(attemptId)).containsExactly("CHARGE_SUCCEEDED");
    }

    @Test
    @DisplayName("payout.returned while PENDING is recorded, resolved by lookup, and emits each event exactly once")
    void aheadOfStateIsResolvedThroughTheIntermediateStates() {
        UUID attemptId = payout("PENDING");
        PROVIDER.lookupBody = """
                [{"payout_id":"po_1","client_reference":"%s","status":"RETURNED","return_code":"R01",
                  "amount_minor":2500,"currency":"USD"}]
                """.formatted(attemptId);
        String eventId = "evt_" + UUID.randomUUID();

        Response response = post("fakebank", payoutReturned(eventId, attemptId), CURRENT_SECRET, Instant.now());

        assertThat(response.status).isEqualTo(200);
        assertThat(JSON.readTree(response.body).get("disposition").asString()).isEqualTo("ahead_of_state");
        assertThat(recorded(eventId)).as("recorded before anything is applied, and never dropped").isEqualTo(1);

        await(() -> "RETURNED".equals(status(attemptId)), "the attempt caught up to RETURNED");

        // The whole point of §0.3 C21: the ledger sees the payout settle before it sees it come back, so it is not
        // asked to reverse an order it never created — and neither event is emitted twice.
        assertThat(outboxEvents(attemptId)).containsExactly("PAYOUT_SETTLED", "PAYOUT_RETURNED");
        assertThat(transitionRows(attemptId)).isEqualTo(2);
        assertThat(recorded(eventId)).isEqualTo(1);
        assertThat(failureCode(attemptId)).as("the return code rides with the transition that needs it")
                .isEqualTo("R01");
    }

    @Test
    @DisplayName("a webhook for an attempt we do not have is recorded unmatched, answered 2xx, and never 500s")
    void unknownAttemptIsRecordedNotDropped() {
        String eventId = "evt_" + UUID.randomUUID();
        UUID strangerAttempt = UUID.randomUUID();

        Response response = post("fakecard", chargeSucceeded(eventId, strangerAttempt), CURRENT_SECRET, Instant.now());

        assertThat(response.status).isEqualTo(200);
        assertThat(JSON.readTree(response.body).get("disposition").asString()).isEqualTo("unmatched");
        assertThat(recorded(eventId)).isEqualTo(1);
        assertThat(recordedAttempt(eventId)).as("the unmatched marker: recorded, attached to no attempt").isNull();

        // A client reference that is not even one of our ids is the same case, not a parse failure.
        String notAUuid = "evt_" + UUID.randomUUID();
        Response nonsense = post("fakecard", chargeBody(notAUuid, "not-an-attempt-id", "charge.succeeded", null),
                CURRENT_SECRET, Instant.now());

        assertThat(nonsense.status).isEqualTo(200);
        assertThat(recorded(notAUuid)).isEqualTo(1);
    }

    @Test
    @DisplayName("an event this attempt's machine cannot take is recorded and counted, not applied")
    void illegalEventIsRecordedAndCounted() {
        UUID attemptId = charge("SUBMITTING");
        String eventId = "evt_" + UUID.randomUUID();

        // A card charge reported as settled: a provider or adapter contract violation, worth an alert.
        Response response = post("fakecard", chargeBody(eventId, attemptId.toString(), "payout.settled", null),
                CURRENT_SECRET, Instant.now());

        assertThat(response.status).isEqualTo(200);
        assertThat(JSON.readTree(response.body).get("disposition").asString()).isEqualTo("illegal");
        assertThat(status(attemptId)).isEqualTo("SUBMITTING");
        assertThat(outboxEvents(attemptId)).isEmpty();
        // Recorded and answered 2xx deliberately: an event we can never apply would otherwise be redelivered for
        // ever, and the row plus the counter are what make it visible instead.
        assertThat(recorded(eventId)).isEqualTo(1);
    }

    @Test
    @DisplayName("a webhook path no adapter serves is 404, and a signed body for the wrong provider is 400")
    void unknownProviderAndMismatchedPayload() {
        UUID attemptId = charge("SUBMITTING");
        byte[] body = chargeSucceeded("evt_" + UUID.randomUUID(), attemptId);

        Response unknown = post("fakecrypto", body, CURRENT_SECRET, Instant.now());
        assertThat(unknown.status).isEqualTo(404);
        assertThat(JSON.readTree(unknown.body).get("code").asString()).isEqualTo("unknown_provider");

        // A fakecard event delivered to the fakebank path would otherwise be read with the wrong vocabulary.
        Response mismatched = post("fakebank", body, CURRENT_SECRET, Instant.now());
        assertThat(mismatched.status).isEqualTo(400);
        assertThat(JSON.readTree(mismatched.body).get("code").asString()).isEqualTo("invalid_webhook");
        assertThat(status(attemptId)).isEqualTo("SUBMITTING");
    }

    @Test
    @DisplayName("responses conform to openapi/instrument-service.yaml")
    void responsesConformToTheContract() {
        JsonNode spec = openApi();
        UUID attemptId = charge("SUBMITTING");

        String ack = post("fakecard", chargeSucceeded("evt_" + UUID.randomUUID(), attemptId), CURRENT_SECRET,
                Instant.now()).body;
        assertThat(errors(spec, "#/components/schemas/WebhookAck", ack)).isEmpty();

        String problem = post("fakecard", chargeSucceeded("evt_" + UUID.randomUUID(), attemptId), "wrong-secret",
                Instant.now()).body;
        assertThat(errors(spec, "#/components/schemas/Problem", problem)).isEmpty();
    }

    // --- the wire ----------------------------------------------------------------------------------------------

    private record Response(int status, String body) {
    }

    private Response post(String provider, byte[] body, String secret, Instant signedAt) {
        return postSigned(provider, body, TestWebhookSigner.header(signedAt, body, secret));
    }

    private Response postSigned(String provider, byte[] body, String signature) {
        var request = RestClient.create("http://localhost:" + port)
                .post().uri("/v1/webhooks/" + provider)
                .contentType(MediaType.APPLICATION_JSON)
                .body(body);
        if (signature != null) {
            request = request.header("ZS-Signature", signature);
        }
        return request.exchange((req, response) -> new Response(response.getStatusCode().value(),
                new String(response.getBody().readAllBytes(), StandardCharsets.UTF_8)), false);
    }

    /** The payload fake-providers sends, field for field (D05-2). */
    private static byte[] chargeBody(String eventId, String clientReference, String eventType, String failureCode) {
        return ("{\"event_id\":\"" + eventId + "\",\"provider\":\"fakecard\",\"event_type\":\"" + eventType
                + "\",\"provider_ref\":\"ch_1\",\"client_reference\":\"" + clientReference
                + "\",\"amount_minor\":2500,\"currency\":\"USD\",\"failure_code\":"
                + (failureCode == null ? "null" : "\"" + failureCode + "\"")
                + ",\"occurred_at\":\"2026-01-01T00:00:00Z\"}").getBytes(StandardCharsets.UTF_8);
    }

    private static byte[] chargeSucceeded(String eventId, UUID attemptId) {
        return chargeBody(eventId, attemptId.toString(), "charge.succeeded", null);
    }

    private static byte[] payoutReturned(String eventId, UUID attemptId) {
        return ("{\"event_id\":\"" + eventId + "\",\"provider\":\"fakebank\",\"event_type\":\"payout.returned\","
                + "\"provider_ref\":\"po_1\",\"client_reference\":\"" + attemptId
                + "\",\"amount_minor\":2500,\"currency\":\"USD\",\"failure_code\":\"R01\","
                + "\"occurred_at\":\"2026-01-01T00:00:00Z\"}").getBytes(StandardCharsets.UTF_8);
    }

    // --- the database ------------------------------------------------------------------------------------------

    private UUID charge(String status) {
        return createAttempt("CHARGE", "fakecard", status, null);
    }

    private UUID payout(String status) {
        return createAttempt("PAYOUT", "fakebank", status, "po_1");
    }

    private UUID createAttempt(String kind, String provider, String status, String providerRef) {
        UUID attemptId = UUID.randomUUID();
        db.sql("""
                INSERT INTO payment_attempts (attempt_id, kind, order_group_id, source_order_id, entity_id, provider,
                                              instrument_token, currency, amount_minor, status, provider_ref)
                VALUES (:id, :kind, :group, :order, :entity, :provider, 'tok_secret', 'USD', 2500, :status, :ref)
                """)
                .param("id", attemptId)
                .param("kind", kind)
                .param("group", "trip_" + attemptId.toString().substring(0, 8))
                // A payout has no source order, and its own (entity, currency) so the in-flight index cannot collide.
                .param("order", "PAYOUT".equals(kind) ? null : UUID.randomUUID())
                .param("entity", "PAYOUT".equals(kind) ? "driver:D" + attemptId : "rider:R" + attemptId)
                .param("provider", provider)
                .param("status", status)
                .param("ref", providerRef)
                .update();
        return attemptId;
    }

    private String status(UUID attemptId) {
        return db.sql("SELECT status FROM payment_attempts WHERE attempt_id = :id")
                .param("id", attemptId).query(String.class).single();
    }

    private String failureCode(UUID attemptId) {
        return db.sql("SELECT failure_code FROM payment_attempts WHERE attempt_id = :id")
                .param("id", attemptId).query(String.class).optional().orElse(null);
    }

    private int recorded(String providerEventId) {
        return db.sql("SELECT count(*) FROM provider_events WHERE provider_event_id = :id")
                .param("id", providerEventId).query(Integer.class).single();
    }

    private UUID recordedAttempt(String providerEventId) {
        return db.sql("SELECT attempt_id FROM provider_events WHERE provider_event_id = :id")
                .param("id", providerEventId).query(UUID.class).optional().orElse(null);
    }

    private int transitionRows(UUID attemptId) {
        return db.sql("SELECT count(*) FROM attempt_transitions WHERE attempt_id = :id")
                .param("id", attemptId).query(Integer.class).single();
    }

    /** The payment events this attempt put on the outbox, in the order they were written. */
    private List<String> outboxEvents(UUID attemptId) {
        return db.sql("SELECT payload->>'event_type' FROM outbox WHERE payload->>'attempt_id' = :id ORDER BY id")
                .param("id", attemptId.toString()).query(String.class).list();
    }

    // --- helpers -----------------------------------------------------------------------------------------------

    private static void concurrently(int threads, Callable<Integer> call) {
        try (var pool = Executors.newFixedThreadPool(threads)) {
            List<Future<Integer>> results = pool.invokeAll(java.util.Collections.nCopies(threads, call));
            for (Future<Integer> result : results) {
                assertThat(result.get()).isEqualTo(200);
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(interrupted);
        } catch (Exception failure) {
            throw new IllegalStateException(failure);
        }
    }

    /** The resolution runs off the request thread, so an assertion on it needs a deadline rather than a sleep. */
    private static void await(BooleanSupplier condition, String expectation) {
        long deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos();
        while (System.nanoTime() < deadline) {
            if (condition.getAsBoolean()) {
                return;
            }
            try {
                Thread.sleep(100);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(interrupted);
            }
        }
        throw new AssertionError(expectation + " never happened");
    }

    private static JsonNode openApi() {
        Path spec = ZsTestDatabase.ROOT.resolve("openapi/instrument-service.yaml");
        assertThat(spec).exists();
        try {
            return JSON.valueToTree(new Yaml().load(Files.readString(spec)));
        } catch (java.io.IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
    }

    private static List<String> errors(JsonNode spec, String ref, String json) {
        var document = JSON.createObjectNode();
        document.put("$ref", ref);
        document.set("components", spec.get("components"));
        return SCHEMAS.getSchema(document).validate(json, InputFormat.JSON).stream()
                .map(e -> e.getKeyword() + " at " + e.getInstanceLocation() + ": " + e.getMessage())
                .toList();
    }
}
