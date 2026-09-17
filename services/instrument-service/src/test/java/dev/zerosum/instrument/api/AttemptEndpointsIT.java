package dev.zerosum.instrument.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.networknt.schema.InputFormat;
import com.networknt.schema.SchemaRegistry;
import com.networknt.schema.SpecificationVersion;
import dev.zerosum.instrument.store.AttemptTransitions;
import dev.zerosum.instrument.store.IllegalTransitions;
import dev.zerosum.testsupport.ZsTestDatabase;
import io.micrometer.core.instrument.MeterRegistry;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.client.RestClient;
import org.yaml.snakeyaml.Yaml;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * S05-T08: the attempt read and cancel endpoints, the roles behind them (§0.3 C9), and their conformance to
 * {@code openapi/instrument-service.yaml}.
 *
 * <p>These are the first authenticated endpoints in this service. The 401 and 403 cases are tested here because a
 * service that authenticates nobody, or everybody, looks identical from a test that always sends the right token.
 */
@Tag("integration")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class AttemptEndpointsIT {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final SchemaRegistry SCHEMAS = SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_2020_12);

    private static final ZsTestDatabase DB = ZsTestDatabase.start(
            "instruments", "services/instrument-service/src/main/resources/db/migration");

    private static final String READER = "test-reader-token";
    private static final String ADMIN = "test-admin-token";
    private static final String WRITER = "trip-simulator:test-writer-token";

    @LocalServerPort
    private int port;

    @Autowired
    private JdbcClient db;

    @Autowired
    private AttemptTransitions transitions;

    @Autowired
    private MeterRegistry meters;

    @DynamicPropertySource
    static void context(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", DB::jdbcUrl);
        registry.add("spring.datasource.username", DB::app);
        registry.add("spring.datasource.password", () -> DB.password(DB.app()));
        // The fixture already migrated as the owner; the application role has no DDL rights, by design.
        registry.add("spring.flyway.enabled", () -> "false");
        registry.add("zs.auth.reader-token", () -> READER);
        registry.add("zs.auth.admin-token", () -> ADMIN);
        registry.add("zs.auth.writer-tokens", () -> WRITER);
    }

    @Test
    @DisplayName("the read returns the attempt with its history in sequence order")
    void readReturnsHistoryInSequenceOrder() {
        UUID attemptId = createAttempt("CHARGE", "CREATED");
        transitions.apply(new AttemptTransitions.Transition(attemptId, 0, "CREATED", "SUBMITTING",
                "submitted by the collection policy", null, null, null));
        transitions.apply(new AttemptTransitions.Transition(attemptId, 1, "SUBMITTING", "SUCCEEDED",
                "charge.succeeded webhook", "ch_1", null, null));

        JsonNode body = JSON.readTree(get("/v1/payment-attempts/" + attemptId, READER).body);

        assertThat(body.get("attempt").get("status").asString()).isEqualTo("SUCCEEDED");
        assertThat(body.get("attempt").get("provider_ref").asString()).isEqualTo("ch_1");
        assertThat(body.get("attempt").has("instrument_token"))
                .as("the instrument token is never returned: it would land in every client log (D00-8)").isFalse();

        JsonNode history = body.get("transitions");
        assertThat(history).hasSize(2);
        assertThat(history.get(0).get("seq").asInt()).isEqualTo(1);
        assertThat(history.get(0).get("to_status").asString()).isEqualTo("SUBMITTING");
        assertThat(history.get(1).get("seq").asInt()).isEqualTo(2);
        assertThat(history.get(1).get("from_status").asString()).isEqualTo("SUBMITTING");
        assertThat(history.get(1).get("to_status").asString()).isEqualTo("SUCCEEDED");
        assertThat(history.get(1).get("cause").asString()).isEqualTo("charge.succeeded webhook");
    }

    @Test
    @DisplayName("cancel from CREATED succeeds and is recorded in the history")
    void cancelFromCreated() {
        UUID attemptId = createAttempt("PAYOUT", "CREATED");

        Response response = post("/v1/payment-attempts/" + attemptId + "/cancel", ADMIN);

        assertThat(response.status).isEqualTo(200);
        JsonNode body = JSON.readTree(response.body);
        assertThat(body.get("attempt").get("status").asString()).isEqualTo("CANCELLED");
        assertThat(body.get("transitions")).hasSize(1);
        assertThat(body.get("transitions").get(0).get("to_status").asString()).isEqualTo("CANCELLED");
        assertThat(outboxRows(attemptId)).as("a cancellation moves no money, so it emits no payment event").isZero();
    }

    @Test
    @DisplayName("cancel from SUBMITTING returns the not_cancellable conflict with the current status")
    void cancelFromSubmittingConflicts() {
        UUID attemptId = createAttempt("PAYOUT", "SUBMITTING");

        Response response = post("/v1/payment-attempts/" + attemptId + "/cancel", ADMIN);

        assertThat(response.status).isEqualTo(409);
        JsonNode problem = JSON.readTree(response.body);
        assertThat(problem.get("code").asString()).isEqualTo("not_cancellable");
        assertThat(problem.get("detail").asString()).contains("SUBMITTING");
        assertThat(status(attemptId)).isEqualTo("SUBMITTING");
    }

    @Test
    @DisplayName("without a token both endpoints answer 401, not 404 or 200")
    void missingTokenIsUnauthorized() {
        UUID attemptId = createAttempt("CHARGE", "CREATED");

        Response read = get("/v1/payment-attempts/" + attemptId, null);
        assertThat(read.status).isEqualTo(401);
        assertThat(JSON.readTree(read.body).get("code").asString()).isEqualTo("unauthorized");
        assertThat(post("/v1/payment-attempts/" + attemptId + "/cancel", null).status).isEqualTo(401);
        // An unrecognised token is no better than none.
        assertThat(get("/v1/payment-attempts/" + attemptId, "not-a-real-token").status).isEqualTo(401);
    }

    @Test
    @DisplayName("a reader may read an attempt but not cancel one")
    void wrongRoleIsForbidden() {
        UUID attemptId = createAttempt("CHARGE", "CREATED");

        assertThat(get("/v1/payment-attempts/" + attemptId, READER).status).isEqualTo(200);

        Response cancel = post("/v1/payment-attempts/" + attemptId + "/cancel", READER);
        assertThat(cancel.status).isEqualTo(403);
        assertThat(JSON.readTree(cancel.body).get("code").asString()).isEqualTo("forbidden");
        assertThat(status(attemptId)).as("a refused cancellation changes nothing").isEqualTo("CREATED");
        // The admin role is not implied by writer: cancelling is not a writer operation (D03-4 hierarchy).
        assertThat(post("/v1/payment-attempts/" + attemptId + "/cancel", "test-writer-token").status).isEqualTo(403);
    }

    @Test
    @DisplayName("an unknown attempt is 404 on both endpoints, and a malformed id is 400")
    void unknownAndMalformedIds() {
        UUID missing = UUID.randomUUID();

        Response read = get("/v1/payment-attempts/" + missing, READER);
        assertThat(read.status).isEqualTo(404);
        assertThat(JSON.readTree(read.body).get("code").asString()).isEqualTo("attempt_not_found");
        assertThat(post("/v1/payment-attempts/" + missing + "/cancel", ADMIN).status).isEqualTo(404);

        Response malformed = get("/v1/payment-attempts/not-a-uuid", READER);
        assertThat(malformed.status).isEqualTo(400);
        assertThat(JSON.readTree(malformed.body).get("code").asString()).isEqualTo("invalid_attempt_id");
    }

    @Nested
    @DisplayName("responses conform to openapi/instrument-service.yaml")
    class OpenApiConformance {

        @Test
        @DisplayName("the attempt document and a problem both validate against the published schemas")
        void responsesValidate() {
            JsonNode spec = openApi();
            UUID attemptId = createAttempt("REFUND", "CREATED");

            String detail = get("/v1/payment-attempts/" + attemptId, READER).body;
            assertThat(errors(spec, schemaRef(spec, "/v1/payment-attempts/{attempt_id}", "get"), detail)).isEmpty();

            String conflict = post("/v1/payment-attempts/" + createAttempt("CHARGE", "SUBMITTING") + "/cancel", ADMIN).body;
            assertThat(errors(spec, "#/components/schemas/Problem", conflict)).isEmpty();

            String cancelled = post("/v1/payment-attempts/" + attemptId + "/cancel", ADMIN).body;
            assertThat(errors(spec, schemaRef(spec, "/v1/payment-attempts/{attempt_id}/cancel", "post"), cancelled))
                    .isEmpty();
        }

        private JsonNode openApi() {
            Path spec = ZsTestDatabase.ROOT.resolve("openapi/instrument-service.yaml");
            assertThat(spec).exists();
            try {
                return JSON.valueToTree(new Yaml().load(Files.readString(spec)));
            } catch (java.io.IOException e) {
                throw new java.io.UncheckedIOException(e);
            }
        }

        private String schemaRef(JsonNode spec, String path, String method) {
            return spec.get("paths").get(path).get(method).get("responses").get("200")
                    .get("content").get("application/json").get("schema").get("$ref").asString();
        }

        /** Validates against a document whose root is the operation's {@code $ref}, carrying the whole components
         * block so internal references resolve without a network lookup. */
        private List<String> errors(JsonNode spec, String ref, String json) {
            var document = JSON.createObjectNode();
            document.put("$ref", ref);
            document.set("components", spec.get("components"));
            return SCHEMAS.getSchema(document).validate(json, InputFormat.JSON).stream()
                    .map(e -> e.getKeyword() + " at " + e.getInstanceLocation() + ": " + e.getMessage())
                    .toList();
        }
    }

    @Nested
    @DisplayName("the transition service refuses what the table forbids")
    class TableEnforcement {

        @Test
        @DisplayName("a move no machine draws is refused, logged, counted, and writes nothing")
        void illegalMoveIsRefused() {
            UUID attemptId = createAttempt("CHARGE", "CREATED");
            double before = illegalCount();

            // CREATED -> SUCCEEDED is a real status change the guard would happily apply: the attempt is in CREATED
            // and the version matches. Only the table knows a charge cannot succeed without being submitted.
            assertThatThrownBy(() -> transitions.apply(new AttemptTransitions.Transition(attemptId, 0, "CREATED",
                    "SUCCEEDED", "a caller that skipped the table", "ch_9", null, null)))
                    .isInstanceOf(AttemptTransitions.IllegalTransitionException.class);

            assertThat(status(attemptId)).as("the throw rolled the guarded update back").isEqualTo("CREATED");
            assertThat(historyRows(attemptId)).isZero();
            assertThat(outboxRows(attemptId)).as("no payment event for a status the attempt never reached").isZero();
            assertThat(illegalCount()).isEqualTo(before + 1);
        }

        @Test
        @DisplayName("a caller whose from-status is merely out of date still gets LostRace, not a refusal")
        void staleCallerStillLosesTheRace() {
            UUID attemptId = createAttempt("CHARGE", "SUBMITTING");
            double before = illegalCount();

            var result = transitions.apply(new AttemptTransitions.Transition(attemptId, 0, "CREATED", "SUBMITTING",
                    "a sweeper working from a stale read", null, null, null));

            assertThat(result).isInstanceOf(AttemptTransitions.Result.LostRace.class);
            assertThat(illegalCount()).as("losing a race is normal operation, not a defect to alert on")
                    .isEqualTo(before);
        }

        private double illegalCount() {
            return meters.find(IllegalTransitions.COUNTER).counters().stream()
                    .mapToDouble(io.micrometer.core.instrument.Counter::count).sum();
        }
    }

    private UUID createAttempt(String kind, String status) {
        UUID attemptId = UUID.randomUUID();
        db.sql("""
                INSERT INTO payment_attempts (attempt_id, kind, order_group_id, source_order_id, entity_id, provider,
                                              instrument_token, currency, amount_minor, status)
                VALUES (:id, :kind, :group, :order, :entity, :provider, 'tok_secret', 'USD', 2500, :status)
                """)
                .param("id", attemptId)
                .param("kind", kind)
                .param("group", "trip_" + attemptId.toString().substring(0, 8))
                // A payout has no source order; the uniqueness key (kind, source_order_id, entity_id, currency)
                // would otherwise collide across payouts for one driver.
                .param("order", "PAYOUT".equals(kind) ? null : UUID.randomUUID())
                .param("entity", "PAYOUT".equals(kind) ? "driver:D_" + attemptId : "rider:R_" + attemptId)
                .param("provider", "PAYOUT".equals(kind) ? "fakebank" : "fakecard")
                .param("status", status)
                .update();
        return attemptId;
    }

    private String status(UUID attemptId) {
        return db.sql("SELECT status FROM payment_attempts WHERE attempt_id = :id")
                .param("id", attemptId).query(String.class).single();
    }

    private int historyRows(UUID attemptId) {
        return db.sql("SELECT count(*) FROM attempt_transitions WHERE attempt_id = :id")
                .param("id", attemptId).query(Integer.class).single();
    }

    private int outboxRows(UUID attemptId) {
        return db.sql("SELECT count(*) FROM outbox WHERE payload->>'attempt_id' = :id")
                .param("id", attemptId.toString()).query(Integer.class).single();
    }

    private record Response(int status, String body) {
    }

    private Response get(String uri, String token) {
        return exchange(RestClient.create("http://localhost:" + port).get().uri(uri), token);
    }

    private Response post(String uri, String token) {
        return exchange(RestClient.create("http://localhost:" + port).post().uri(uri), token);
    }

    private Response exchange(RestClient.RequestHeadersSpec<?> request, String token) {
        if (token != null) {
            request = request.header("Authorization", "Bearer " + token);
        }
        return request.exchange((req, response) -> new Response(response.getStatusCode().value(),
                new String(response.getBody().readAllBytes())), false);
    }
}
