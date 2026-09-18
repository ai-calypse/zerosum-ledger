package dev.zerosum.instrument.api;

import static org.assertj.core.api.Assertions.assertThat;

import com.networknt.schema.InputFormat;
import com.networknt.schema.SchemaRegistry;
import com.networknt.schema.SpecificationVersion;
import dev.zerosum.testsupport.ZsTestDatabase;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
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
 * S09: the read-only list endpoints the operator dashboard draws from — recent attempts, attempt totals, reconciliation
 * runs and payout runs — their roles, their refusals, and their conformance to {@code openapi/instrument-service.yaml}.
 *
 * <p>Rows are inserted directly: these endpoints only read, and the flows that write them are tested where they live.
 */
@Tag("integration")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class DashboardListsIT {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final SchemaRegistry SCHEMAS = SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_2020_12);

    private static final ZsTestDatabase DB = ZsTestDatabase.start(
            "instruments", "services/instrument-service/src/main/resources/db/migration");

    private static final String READER = "test-reader-token";

    @LocalServerPort
    private int port;

    @Autowired
    private JdbcClient db;

    @DynamicPropertySource
    static void context(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", DB::jdbcUrl);
        registry.add("spring.datasource.username", DB::app);
        registry.add("spring.datasource.password", () -> DB.password(DB.app()));
        registry.add("spring.flyway.enabled", () -> "false");
        registry.add("zs.auth.reader-token", () -> READER);
        registry.add("zs.auth.admin-token", () -> "test-admin-token");
        registry.add("zs.auth.writer-tokens", () -> "trip-simulator:test-writer-token");
        // Pinned to an address nothing answers on. With the Compose stack up, localhost:9092 is the LIVE broker: a
        // payment event this context emits is consumed by the running order-service and booked into the real ledger.
        // That happened once (S09, docs/results/s09/dashboard.md); the pin makes it impossible.
        registry.add("spring.kafka.bootstrap-servers", () -> "127.0.0.1:1");
        registry.add("zs.policy.consumer.enabled", () -> "false");
    }

    @Test
    @DisplayName("recent attempts are newest first, filter by kind, and validate against the spec")
    void recentAttempts() {
        UUID charge = attempt("CHARGE", "SUCCEEDED", 1_500);
        UUID payout = attempt("PAYOUT", "PENDING", 4_000);

        JsonNode all = read("/v1/payment-attempts?limit=500");
        List<String> ids = all.valueStream().map(a -> a.get("attempt_id").asString()).toList();
        assertThat(ids).contains(charge.toString(), payout.toString());
        assertThat(ids.indexOf(payout.toString())).as("newest first").isLessThan(ids.indexOf(charge.toString()));
        assertThat(all.get(0).has("instrument_token")).as("the token is never returned (D00-8)").isFalse();

        JsonNode payouts = read("/v1/payment-attempts?kind=PAYOUT");
        assertThat(payouts.valueStream().map(a -> a.get("kind").asString())).containsOnly("PAYOUT");
        assertThat(read("/v1/payment-attempts?limit=1")).hasSize(1);

        assertThat(errors("/v1/payment-attempts", get("/v1/payment-attempts", READER).body())).isEmpty();
    }

    @Test
    @DisplayName("the summary counts attempts and their money by kind, provider, status and currency")
    void attemptSummary() {
        long before = total("CHARGE", "DECLINED", "attempts");
        long amountBefore = total("CHARGE", "DECLINED", "amount_minor");
        attempt("CHARGE", "DECLINED", 700);
        attempt("CHARGE", "DECLINED", 300);

        assertThat(total("CHARGE", "DECLINED", "attempts")).isEqualTo(before + 2);
        assertThat(total("CHARGE", "DECLINED", "amount_minor")).isEqualTo(amountBefore + 1_000);
        assertThat(errors("/v1/payment-attempts/summary", get("/v1/payment-attempts/summary", READER).body()))
                .isEmpty();
    }

    @Test
    @DisplayName("reconciliation runs carry their breaks counted by type")
    void reconciliationRuns() {
        UUID run = reconciliationRun();
        breakOf(run, "AMOUNT_MISMATCH", "UNEXPLAINED");
        breakOf(run, "AMOUNT_MISMATCH", "UNEXPLAINED");
        breakOf(run, "MISSING_IN_REPORT", "OPEN");

        JsonNode listed = find(read("/v1/reconciliation-runs"), run);
        assertThat(listed.get("lines_matched").asInt()).isEqualTo(14);
        assertThat(listed.get("breaks_by_type").get("AMOUNT_MISMATCH").asLong()).isEqualTo(2);
        assertThat(listed.get("breaks_by_type").get("MISSING_IN_REPORT").asLong()).isEqualTo(1);
        assertThat(errors("/v1/reconciliation-runs", get("/v1/reconciliation-runs", READER).body())).isEmpty();
    }

    @Test
    @DisplayName("payout runs fold their results into outcome counts and the total paid, refusals included")
    void payoutRuns() {
        UUID completed = payoutRun("COMPLETED", null, """
                [{"entity_id":"driver:D1","outcome":"PAID","amount_minor":2500,"as_of_seq":4,"attempt_id":null},
                 {"entity_id":"driver:D2","outcome":"PAID","amount_minor":1200,"as_of_seq":9,"attempt_id":null},
                 {"entity_id":"driver:D3","outcome":"SKIPPED_BELOW_MINIMUM","amount_minor":40,"as_of_seq":2,
                  "attempt_id":null}]""");
        UUID refused = payoutRun("REFUSED", "ledger_stale", "[]");

        JsonNode runs = read("/v1/payout-runs");
        JsonNode paid = find(runs, completed);
        assertThat(paid.get("outcomes").get("PAID").asLong()).isEqualTo(2);
        assertThat(paid.get("outcomes").get("SKIPPED_BELOW_MINIMUM").asLong()).isEqualTo(1);
        assertThat(paid.get("paid_minor").asLong()).as("only PAID amounts count").isEqualTo(3_700);
        assertThat(find(runs, refused).get("refusal_code").asString()).isEqualTo("ledger_stale");
        assertThat(errors("/v1/payout-runs", get("/v1/payout-runs", READER).body())).isEmpty();
    }

    @Test
    @DisplayName("every list needs a reader token, and a bad limit or kind is refused rather than clamped")
    void refusals() {
        for (String path : List.of("/v1/payment-attempts", "/v1/payment-attempts/summary", "/v1/reconciliation-runs",
                "/v1/payout-runs")) {
            assertThat(get(path, null).status()).as(path).isEqualTo(401);
        }
        for (String path : List.of("/v1/payment-attempts?limit=0", "/v1/payment-attempts?limit=501",
                "/v1/payment-attempts?limit=ten", "/v1/payment-attempts?kind=CHARGEBACK",
                "/v1/reconciliation-runs?limit=-1", "/v1/payout-runs?limit=9999")) {
            Response response = get(path, READER);
            assertThat(response.status()).as(path).isEqualTo(400);
            assertThat(JSON.readTree(response.body()).get("code").asString()).isEqualTo("invalid_query");
            assertThat(validate("#/components/schemas/Problem", response.body())).isEmpty();
        }
    }

    private long total(String kind, String status, String field) {
        long sum = 0;
        for (JsonNode row : read("/v1/payment-attempts/summary").get("totals")) {
            if (row.get("kind").asString().equals(kind) && row.get("status").asString().equals(status)) {
                sum += row.get(field).asLong();
            }
        }
        return sum;
    }

    private static JsonNode find(JsonNode list, UUID runId) {
        return list.valueStream().filter(run -> run.get("run_id").asString().equals(runId.toString())).findFirst()
                .orElseThrow(() -> new AssertionError(runId + " not listed in " + list));
    }

    private UUID attempt(String kind, String status, long amount) {
        UUID attemptId = UUID.randomUUID();
        db.sql("""
                INSERT INTO payment_attempts (attempt_id, kind, order_group_id, source_order_id, entity_id, provider,
                                              instrument_token, currency, amount_minor, status)
                VALUES (:id, :kind, :group, :order, :entity, :provider, 'tok_secret', 'USD', :amount, :status)
                """)
                .param("id", attemptId)
                .param("kind", kind)
                .param("group", "trip_" + attemptId.toString().substring(0, 8))
                .param("order", "PAYOUT".equals(kind) ? null : UUID.randomUUID())
                .param("entity", "PAYOUT".equals(kind) ? "driver:D_" + attemptId : "rider:R_" + attemptId)
                .param("provider", "PAYOUT".equals(kind) ? "fakebank" : "fakecard")
                .param("amount", amount)
                .param("status", status)
                .update();
        return attemptId;
    }

    private UUID reconciliationRun() {
        UUID runId = UUID.randomUUID();
        db.sql("""
                INSERT INTO reconciliation_runs (run_id, idempotency_key, request_hash, provider, report_date, report_id,
                                                 content_hash, status, report_lines, lines_matched, breaks_found, settled)
                VALUES (:id, :key, '\\x00'::bytea, 'fakecard', :date, 'rpt_1', 'h', 'COMPLETED', '[]'::jsonb, 14, 3, true)
                """)
                .param("id", runId)
                .param("key", "recon-" + runId)
                // (provider, report_date) is unique, so each run takes a day of its own.
                .param("date", java.sql.Date.valueOf(java.time.LocalDate.of(2026, 1, 1)
                        .plusDays(Math.floorMod(runId.getLeastSignificantBits(), 3000))))
                .update();
        return runId;
    }

    private void breakOf(UUID runId, String type, String status) {
        db.sql("""
                INSERT INTO reconciliation_breaks (run_id, break_type, status, currency, report_gross_minor,
                                                   ledger_gross_minor)
                VALUES (:run, :type, :status, 'USD', 1501, 1500)
                """)
                .param("run", runId).param("type", type).param("status", status).update();
    }

    private UUID payoutRun(String status, String refusal, String results) {
        UUID runId = UUID.randomUUID();
        db.sql("""
                INSERT INTO payout_runs (run_id, idempotency_key, request_hash, request_hash_version, currency, status,
                                         refusal_code, attempts_created, results, completed_at)
                VALUES (:id, :key, '\\x00'::bytea, 1, 'USD', :status, :refusal, 0, :results::jsonb, now())
                """)
                .param("id", runId)
                .param("key", "payout-" + runId)
                .param("status", status)
                .param("refusal", refusal)
                .param("results", results)
                .update();
        return runId;
    }

    private JsonNode read(String path) {
        Response response = get(path, READER);
        assertThat(response.status()).as(path + ": " + response.body()).isEqualTo(200);
        return JSON.readTree(response.body());
    }

    /** Validates a 200 body against the operation's published response schema. */
    private List<String> errors(String path, String body) {
        JsonNode schema = spec().get("paths").get(path).get("get").get("responses").get("200")
                .get("content").get("application/json").get("schema");
        var document = JSON.createObjectNode();
        document.setAll((tools.jackson.databind.node.ObjectNode) schema);
        document.set("components", spec().get("components"));
        return SCHEMAS.getSchema(document).validate(body, InputFormat.JSON).stream()
                .map(e -> e.getKeyword() + " at " + e.getInstanceLocation() + ": " + e.getMessage()).toList();
    }

    private List<String> validate(String ref, String body) {
        var document = JSON.createObjectNode();
        document.put("$ref", ref);
        document.set("components", spec().get("components"));
        return SCHEMAS.getSchema(document).validate(body, InputFormat.JSON).stream()
                .map(e -> e.getKeyword() + " at " + e.getInstanceLocation() + ": " + e.getMessage()).toList();
    }

    private static JsonNode spec() {
        Path spec = ZsTestDatabase.ROOT.resolve("openapi/instrument-service.yaml");
        try {
            return JSON.valueToTree(new Yaml().load(Files.readString(spec)));
        } catch (java.io.IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
    }

    private record Response(int status, String body) {
    }

    private Response get(String uri, String token) {
        RestClient.RequestHeadersSpec<?> request = RestClient.create("http://localhost:" + port).get().uri(uri);
        if (token != null) {
            request = request.header("Authorization", "Bearer " + token);
        }
        return request.exchange((req, response) -> new Response(response.getStatusCode().value(),
                new String(response.getBody().readAllBytes())), false);
    }
}
