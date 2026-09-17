package dev.zerosum.instrument.payouts;

import static org.assertj.core.api.Assertions.assertThat;

import dev.zerosum.contracts.ContractSchemas;
import dev.zerosum.instrument.adapter.ProviderStub;
import dev.zerosum.testsupport.ZsTestDatabase;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * A real database, a real bank socket, real HTTP and the real endpoint, for the S05-T10 payout tests.
 *
 * <p>Nothing stands in for the parts under test. The claim that matters — <strong>one in-flight payout per driver
 * and currency, however many runs race</strong> — is true because of a partial unique index, and only a real
 * PostgreSQL with real concurrent transactions exercises it. A mocked store would pass while the index was missing.
 *
 * <p>The broker is deliberately absent ({@code zs.policy.consumer.enabled=false}). These tests assert payment events
 * as <strong>outbox rows</strong>, which is where a transition writes them; that the relay then delivers them to
 * Kafka is S05-T09's claim, proved against a real broker in {@code CollectionPolicyIT}, and repeating it here would
 * cost a container per class to re-prove someone else's property.
 *
 * <p>The containers are static on this base class, so the subclasses share one database while running their own
 * Spring contexts with their own kill-switch settings.
 */
@Tag("integration")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
abstract class PayoutTestBase {

    protected static final JsonMapper JSON = JsonMapper.builder().build();

    protected static final String WRITER_TOKEN = "test-writer-token";
    protected static final String READER_TOKEN = "test-reader-token";
    protected static final String ADMIN_TOKEN = "test-admin-token";
    protected static final String WEBHOOK_SECRET = "test-webhook-secret";

    protected static final ZsTestDatabase DB = ZsTestDatabase.start(
            "instruments", "services/instrument-service/src/main/resources/db/migration");

    /** FakeBank's socket: answers 202 with {@code po_1} on a payout, exactly as the provider does. */
    protected static final ProviderStub BANK = ProviderStub.start();

    /** order-service's outbox stats and the ledger's freshness and balances. */
    protected static final ServiceStub SERVICES = ServiceStub.start();

    @LocalServerPort
    protected int port;

    @Autowired
    protected JdbcClient db;

    @DynamicPropertySource
    static void context(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", DB::jdbcUrl);
        registry.add("spring.datasource.username", DB::app);
        registry.add("spring.datasource.password", () -> DB.password(DB.app()));
        // The fixture already migrated as the owner; the application role has no DDL rights, by design.
        registry.add("spring.flyway.enabled", () -> "false");
        registry.add("zs.auth.writer-tokens", () -> "trip-simulator:" + WRITER_TOKEN);
        registry.add("zs.auth.reader-token", () -> READER_TOKEN);
        registry.add("zs.auth.admin-token", () -> ADMIN_TOKEN);
        // No broker in these tests: the policy consumer would dial one on startup.
        registry.add("zs.policy.consumer.enabled", () -> "false");
        registry.add("zs.instruments.fakebank-base-url", BANK::baseUrl);
        registry.add("zs.instruments.fakecard-base-url", BANK::baseUrl);
        registry.add("zs.payouts.order-base-url", SERVICES::baseUrl);
        registry.add("zs.payouts.ledger-base-url", SERVICES::baseUrl);
        registry.add("zs.webhooks.secrets", () -> WEBHOOK_SECRET);
    }

    /**
     * A healthy, empty pipeline and no balances at all.
     *
     * <p>The reset is what keeps these tests independent of each other: a payout run pays <em>every</em> eligible
     * driver, and the database is shared, so a balance left behind by an earlier test would be picked up by a later
     * test's run. Clearing it means every driver but the one under test answers 404 and is skipped.
     */
    @BeforeEach
    void healthyPipeline() {
        SERVICES.reset();
        BANK.forcedStatus = 0;
        BANK.stallMillis = 0;
    }

    // --- the endpoint ---------------------------------------------------------------------------------------------

    protected record Response(int status, String body, String replayed) {
    }

    protected Response postRun(String currency, String idempotencyKey) {
        return postRun(currency, idempotencyKey, WRITER_TOKEN);
    }

    protected Response postRun(String currency, String idempotencyKey, String token) {
        var request = RestClient.create("http://localhost:" + port).post().uri("/v1/payout-runs")
                .contentType(MediaType.APPLICATION_JSON)
                .body("{\"currency\":\"" + currency + "\"}");
        if (idempotencyKey != null) {
            request = request.header("Idempotency-Key", idempotencyKey);
        }
        if (token != null) {
            request = request.header("Authorization", "Bearer " + token);
        }
        return request.exchange((req, response) -> new Response(response.getStatusCode().value(),
                new String(response.getBody().readAllBytes()),
                response.getHeaders().getFirst("Idempotent-Replayed")), false);
    }

    /** Registers a driver's instrument through the real endpoint, so the run reads tokens the way the API writes. */
    protected void register(String entityId, String provider, String token) {
        var response = RestClient.create("http://localhost:" + port).post().uri("/v1/instrument-tokens")
                .contentType(MediaType.APPLICATION_JSON)
                .header("Authorization", "Bearer " + WRITER_TOKEN)
                .body("{\"entity_id\":\"" + entityId + "\",\"provider\":\"" + provider + "\",\"token\":\"" + token
                        + "\"}")
                .exchange((req, res) -> res.getStatusCode().value(), false);
        assertThat(response).as("registering %s at %s", entityId, provider).isEqualTo(200);
    }

    /** A driver id nothing else in the suite will use, short enough for the D01-5 rule 3 pattern. */
    protected static String someDriver() {
        return "driver:D" + UUID.randomUUID().toString().substring(0, 8);
    }

    protected static String someKey() {
        return "payout-key-" + UUID.randomUUID();
    }

    /** This driver's line in a run response, which is where the outcome and the reason live. */
    protected static JsonNode resultFor(JsonNode run, String entityId) {
        for (JsonNode result : run.get("results")) {
            if (entityId.equals(result.get("entity_id").asString())) {
                return result;
            }
        }
        throw new AssertionError("the run reported nothing at all for " + entityId + ": " + run.get("results"));
    }

    // --- the database ---------------------------------------------------------------------------------------------

    protected String status(UUID attemptId) {
        return db.sql("SELECT status FROM payment_attempts WHERE attempt_id = :id")
                .param("id", attemptId).query(String.class).single();
    }

    protected String providerRef(UUID attemptId) {
        return db.sql("SELECT provider_ref FROM payment_attempts WHERE attempt_id = :id")
                .param("id", attemptId).query(String.class).optional().orElse(null);
    }

    protected long amountOf(UUID attemptId) {
        return db.sql("SELECT amount_minor FROM payment_attempts WHERE attempt_id = :id")
                .param("id", attemptId).query(Long.class).single();
    }

    /** Every payout attempt this driver has, whatever its status. */
    protected int payoutAttempts(String entityId) {
        return db.sql("SELECT count(*) FROM payment_attempts WHERE kind = 'PAYOUT' AND entity_id = :entity")
                .param("entity", entityId).query(Integer.class).single();
    }

    /** The attempts the {@code one_inflight_payout} index actually counts (M10(a)). */
    protected int inFlightPayouts(String entityId, String currency) {
        return db.sql("""
                SELECT count(*) FROM payment_attempts
                 WHERE kind = 'PAYOUT' AND entity_id = :entity AND currency = :currency
                   AND status IN ('CREATED','SUBMITTING','PENDING','UNKNOWN')
                """)
                .param("entity", entityId).param("currency", currency).query(Integer.class).single();
    }

    protected String runStatus(UUID runId) {
        return db.sql("SELECT status FROM payout_runs WHERE run_id = :id")
                .param("id", runId).query(String.class).single();
    }

    protected String runRefusalCode(UUID runId) {
        return db.sql("SELECT refusal_code FROM payout_runs WHERE run_id = :id")
                .param("id", runId).query(String.class).optional().orElse(null);
    }

    /** The payment events a transition wrote, oldest first. */
    protected List<String> outboxEventTypes(UUID attemptId) {
        return db.sql("""
                SELECT payload->>'event_type' FROM outbox WHERE payload->>'attempt_id' = :id ORDER BY id
                """)
                .param("id", attemptId.toString()).query(String.class).list();
    }

    protected List<String> outboxPayloads(UUID attemptId) {
        return db.sql("SELECT payload::text FROM outbox WHERE payload->>'attempt_id' = :id ORDER BY id")
                .param("id", attemptId.toString()).query(String.class).list();
    }

    /** decision: D01-8 — every payload this service emits must validate against the contract schema. */
    protected static List<ContractSchemas.SchemaError> schemaErrors(String payload) {
        return ContractSchemas.validate(ContractSchemas.PAYMENT_EVENT_V1, payload);
    }

    // --- waiting --------------------------------------------------------------------------------------------------

    /** Submission happens after the response, on the executor, so every assertion about it needs a deadline. */
    protected static void awaitUntil(BooleanSupplier condition, Supplier<String> whatWasExpected) {
        long deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos();
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
}
