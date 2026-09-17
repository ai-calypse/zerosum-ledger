package dev.zerosum.instrument.recon;

import static org.assertj.core.api.Assertions.assertThat;

import dev.zerosum.contracts.ContractSchemas;
import dev.zerosum.contracts.GoldenPayloads;
import dev.zerosum.instrument.core.Capabilities;
import dev.zerosum.instrument.core.Commands.ChargeCommand;
import dev.zerosum.instrument.core.Commands.DisburseCommand;
import dev.zerosum.instrument.core.Commands.LookupQuery;
import dev.zerosum.instrument.core.Commands.RefundCommand;
import dev.zerosum.instrument.core.LookupResult;
import dev.zerosum.instrument.core.PaymentInstrument;
import dev.zerosum.instrument.core.ProviderEvent;
import dev.zerosum.instrument.core.ProviderId;
import dev.zerosum.instrument.core.ProviderRegistry;
import dev.zerosum.instrument.core.SettlementReport;
import dev.zerosum.instrument.core.SubmitResult;
import dev.zerosum.instrument.core.WebhookRequest;
import dev.zerosum.testsupport.ZsTestDatabase;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * S06-T02/T03 evidence: a settlement report becomes a SETTLEMENT order, and a run is idempotent (M11(a)).
 *
 * <p>The provider is a stub rather than the real FakeCard container: what is under test here is the reconciler, its
 * transaction and the event it emits, and the adapter's own fidelity to FakeCard is the contract suite's job. The
 * database, the outbox writer, the HTTP endpoints, the role checks and the transaction boundary are all real.
 *
 * <p><strong>No amount is written into this test.</strong> Every figure comes from the golden {@code EV-O6} payload
 * that {@code libs/contracts} owns (D01-9), so a change to the worked example fails here instead of silently
 * disagreeing with the rest of the repository.
 *
 * <p>Each test uses its own day, report id and provider reference. A run is unique per {@code (provider,
 * report_date)}, so sharing a day would make the second test replay the first one's run instead of performing its
 * own — and the shared outbox means every count here is scoped to the test's own report.
 */
@Tag("integration")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ReconciliationRunIT {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private static final ZsTestDatabase DB = ZsTestDatabase.start(
            "instruments", "services/instrument-service/src/main/resources/db/migration");

    /** The settlement event the worked example expects (D01-9, master §5.2 O6). */
    private static final JsonNode GOLDEN = JSON.readTree(GoldenPayloads.byId("EV-O6"));

    private static final String ADMIN = "test-admin-token";
    private static final String READER = "test-reader-token";

    /** Distinct days per test, so each performs its own run rather than replaying another's. */
    private static final AtomicInteger DAYS = new AtomicInteger();

    /** What the stubbed provider serves. Set by each test before its run. */
    private static volatile SettlementReport served;

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
        registry.add("zs.auth.admin-token", () -> ADMIN);
        registry.add("zs.auth.reader-token", () -> READER);
        // No broker in this test: the policy consumer would dial one on startup.
        registry.add("zs.policy.consumer.enabled", () -> "false");
        // An exception the problem-detail handler does not recognise otherwise returns a bare 500 that logs nothing,
        // which hides the cause of exactly the failures this suite exists to catch.
        registry.add("server.error.include-message", () -> "always");
    }

    /** One test's day, report and the capture it settles. */
    private record Fixture(LocalDate day, String reportId, String providerRef, UUID attemptId) {
    }

    @Test
    @DisplayName("M11(a): a matching report books net cash and fees, and the event equals golden O6")
    void settlementReportProducesASettlementOrder() {
        // The golden report id, because this is the case that must equal O6 exactly.
        Fixture fixture = fixture(GOLDEN.get("report_id").asString());
        attempt(fixture, gold("gross_minor"), "SUCCEEDED");
        served = report(fixture, line(fixture, gold("gross_minor"), gold("fee_minor")));

        JsonNode run = JSON.readTree(create(fixture, "key-" + UUID.randomUUID(), 201).body());

        assertThat(run.get("status").asString()).isEqualTo("COMPLETED");
        assertThat(run.get("settled").asBoolean()).isTrue();
        assertThat(run.get("breaks_found").asInt()).as("a report that agrees produces no breaks").isZero();
        assertThat(run.get("lines_matched").asInt()).isEqualTo(1);

        List<String> payloads = outboxPayloads(fixture);
        assertThat(payloads).as("exactly one settlement event, for the report's one currency").hasSize(1);
        JsonNode event = JSON.readTree(payloads.getFirst());

        // Valid against the contract before anyone downstream sees it (D01-8).
        assertThat(ContractSchemas.validate(ContractSchemas.PAYMENT_EVENT_V1, payloads.getFirst())).isEmpty();

        // The identity rule of §0.3 C6: the event id is also the order group and the partition key.
        assertThat(event.get("event_id").asString()).isEqualTo(GOLDEN.get("event_id").asString());
        assertThat(event.get("order_group_id").asString()).isEqualTo(GOLDEN.get("event_id").asString());
        assertThat(event.get("event_type").asString()).isEqualTo("SETTLEMENT_RECEIVED");

        // The amounts the SETTLEMENT order books: +net to cash, +fee to processing fees, −gross off the provider's
        // clearing account (master §5.5). The mapper that performs that booking is order-service's and is covered by
        // its own golden test; what this asserts is that the event we emit is exactly the one it expects.
        for (String field : List.of("report_id", "currency", "gross_minor", "fee_minor", "net_minor")) {
            assertThat(event.get(field)).as("%s matches golden O6", field).isEqualTo(GOLDEN.get(field));
        }
        assertThat(event.get("net_minor").asLong() + event.get("fee_minor").asLong())
                .as("net + fee = gross, re-checked by the mapper before booking")
                .isEqualTo(event.get("gross_minor").asLong());

        // The settlement variant carries no attempt or entity (§0.3 C6).
        assertThat(event.has("attempt_id")).isFalse();
        assertThat(event.has("entity_id")).isFalse();
    }

    @Test
    @DisplayName("a run is idempotent: the same key returns the same run and books nothing twice")
    void runIsIdempotent() {
        Fixture fixture = fixture();
        attempt(fixture, gold("gross_minor"), "SUCCEEDED");
        served = report(fixture, line(fixture, gold("gross_minor"), gold("fee_minor")));
        String key = "key-" + UUID.randomUUID();

        JsonNode first = JSON.readTree(create(fixture, key, 201).body());
        var replay = create(fixture, key, 200);
        JsonNode second = JSON.readTree(replay.body());

        assertThat(second.get("run_id").asString()).isEqualTo(first.get("run_id").asString());
        assertThat(second.get("replayed").asBoolean()).isTrue();
        assertThat(replay.replayHeader()).isEqualTo("true");
        assertThat(outboxPayloads(fixture)).as("the settlement is booked once, not twice").hasSize(1);
        assertThat(breakRows(first.get("run_id").asString())).as("and no second set of breaks").isZero();
    }

    @Test
    @DisplayName("a second caller with a different key still reconciles the day only once")
    void runIdentityAdmitsOneRunPerDay() {
        Fixture fixture = fixture();
        attempt(fixture, gold("gross_minor"), "SUCCEEDED");
        served = report(fixture, line(fixture, gold("gross_minor"), gold("fee_minor")));

        JsonNode first = JSON.readTree(create(fixture, "key-" + UUID.randomUUID(), 201).body());
        JsonNode second = JSON.readTree(create(fixture, "key-" + UUID.randomUUID(), 200).body());

        assertThat(second.get("run_id").asString()).isEqualTo(first.get("run_id").asString());
        assertThat(outboxPayloads(fixture)).hasSize(1);
    }

    @Test
    @DisplayName("a duplicated line is persisted as its own typed break and readable through the API")
    void typedBreakIsPersistedAndListed() {
        Fixture fixture = fixture();
        attempt(fixture, gold("gross_minor"), "SUCCEEDED");
        var duplicated = line(fixture, gold("gross_minor"), gold("fee_minor"));
        served = report(fixture, duplicated, duplicated);

        JsonNode run = JSON.readTree(create(fixture, "key-" + UUID.randomUUID(), 201).body());
        assertThat(run.get("breaks_found").asInt()).isEqualTo(1);

        JsonNode breaks = JSON.readTree(
                get("/v1/reconciliation-runs/" + run.get("run_id").asString() + "/breaks", READER).body());

        assertThat(breaks).hasSize(1);
        // The type is the deliverable: "a break happened" would be satisfied by a matcher that types nothing.
        assertThat(breaks.get(0).get("break_type").asString()).isEqualTo("DUPLICATE_LINE");
        assertThat(breaks.get(0).get("status").asString()).isEqualTo("UNEXPLAINED");
        assertThat(breaks.get(0).get("provider_ref").asString()).isEqualTo(fixture.providerRef());
    }

    @Test
    @DisplayName("creating a run needs admin and a key; a refused request books nothing")
    void rolesAreEnforced() {
        Fixture fixture = fixture();
        attempt(fixture, gold("gross_minor"), "SUCCEEDED");
        served = report(fixture, line(fixture, gold("gross_minor"), gold("fee_minor")));

        // A reader may not book a settlement.
        assertThat(post("/v1/reconciliation-runs", body(fixture), "key-" + UUID.randomUUID(), READER).status())
                .isEqualTo(403);
        assertThat(post("/v1/reconciliation-runs", body(fixture), "key-" + UUID.randomUUID(), null).status())
                .isEqualTo(401);
        // A key is required on every POST that creates money state (master §5.6).
        assertThat(post("/v1/reconciliation-runs", body(fixture), null, ADMIN).status()).isEqualTo(400);

        assertThat(outboxPayloads(fixture)).isEmpty();
    }

    @Test
    @DisplayName("breaks for a run that does not exist are a 404, not an empty list")
    void unknownRunIsNotFound() {
        var response = get("/v1/reconciliation-runs/" + UUID.randomUUID() + "/breaks", READER);

        // "This run found nothing" and "there is no such run" are the same bytes and opposite facts.
        assertThat(response.status()).isEqualTo(404);
        assertThat(JSON.readTree(response.body()).get("code").asString()).isEqualTo("reconciliation_run_not_found");
    }

    // --- fixtures -----------------------------------------------------------------------------------------------

    private static long gold(String field) {
        return GOLDEN.get(field).asLong();
    }

    private static Fixture fixture() {
        return fixture("rpt_test_" + UUID.randomUUID().toString().substring(0, 8));
    }

    private static Fixture fixture(String reportId) {
        // Distinct days, so each test performs its own run; distinct provider references, so a capture settled by one
        // test is never seen as a cross-report duplicate by another.
        LocalDate day = LocalDate.of(2026, 9, 15).minusDays(DAYS.incrementAndGet());
        return new Fixture(day, reportId, "ch_" + UUID.randomUUID().toString().substring(0, 8), UUID.randomUUID());
    }

    private static SettlementReport.Line line(Fixture fixture, long gross, long fee) {
        return new SettlementReport.Line(fixture.providerRef(), fixture.attemptId().toString(), "CHARGE",
                GOLDEN.get("currency").asString(), gross, fee);
    }

    /** A report whose totals are derived from its own lines, which is how the provider actually serves one. */
    private static SettlementReport report(Fixture fixture, SettlementReport.Line... lines) {
        long gross = 0;
        long fee = 0;
        for (SettlementReport.Line line : lines) {
            gross += line.grossMinor();
            fee += line.feeMinor();
        }
        return new SettlementReport(new ProviderId("fakecard"), fixture.day(), fixture.reportId(), List.of(lines),
                List.of(new SettlementReport.Totals(GOLDEN.get("currency").asString(), gross, fee, gross - fee)));
    }

    /** An attempt inside the report's day, which the matcher should pair with the line by provider reference. */
    private void attempt(Fixture fixture, long amountMinor, String status) {
        db.sql("""
                INSERT INTO payment_attempts (attempt_id, kind, order_group_id, source_order_id, entity_id, provider,
                                              instrument_token, currency, amount_minor, status, provider_ref,
                                              created_at)
                VALUES (:id, 'CHARGE', :group, :order, :entity, 'fakecard', 'tok_card_ok', :currency, :amount,
                        :status, :ref, :createdAt)
                """)
                .param("id", fixture.attemptId())
                .param("group", "trip_" + fixture.attemptId().toString().substring(0, 8))
                .param("order", UUID.randomUUID())
                .param("entity", "rider:R_" + fixture.attemptId().toString().substring(0, 8))
                .param("currency", GOLDEN.get("currency").asString())
                .param("amount", amountMinor)
                .param("status", status)
                .param("ref", fixture.providerRef())
                .param("createdAt", Timestamp.valueOf(fixture.day().atTime(10, 0)))
                .update();
    }

    /** Scoped to this test's report: the outbox is shared by every test in the class. */
    private List<String> outboxPayloads(Fixture fixture) {
        return db.sql("SELECT payload::text FROM outbox WHERE payload->>'event_type' = 'SETTLEMENT_RECEIVED' "
                        + "AND payload->>'report_id' = :reportId ORDER BY id")
                .param("reportId", fixture.reportId())
                .query(String.class)
                .list();
    }

    private int breakRows(String runId) {
        return db.sql("SELECT count(*) FROM reconciliation_breaks WHERE run_id = :run::uuid")
                .param("run", runId).query(Integer.class).single();
    }

    // --- HTTP ---------------------------------------------------------------------------------------------------

    private record Response(int status, String body, String replayHeader) {
    }

    private static String body(Fixture fixture) {
        return "{\"provider\":\"fakecard\",\"report_date\":\"" + fixture.day() + "\"}";
    }

    private Response create(Fixture fixture, String key, int expectedStatus) {
        Response response = post("/v1/reconciliation-runs", body(fixture), key, ADMIN);
        assertThat(response.status()).as("POST /v1/reconciliation-runs: %s", response.body())
                .isEqualTo(expectedStatus);
        return response;
    }

    private Response post(String uri, String body, String key, String token) {
        var request = RestClient.create("http://localhost:" + port).post().uri(uri)
                .contentType(MediaType.APPLICATION_JSON).body(body);
        if (key != null) {
            request = request.header("Idempotency-Key", key);
        }
        if (token != null) {
            request = request.header("Authorization", "Bearer " + token);
        }
        return request.exchange((req, response) -> new Response(response.getStatusCode().value(),
                new String(response.getBody().readAllBytes()),
                response.getHeaders().getFirst("Idempotent-Replayed")), false);
    }

    private Response get(String uri, String token) {
        return RestClient.create("http://localhost:" + port).get().uri(uri)
                .header("Authorization", "Bearer " + token)
                .exchange((req, response) -> new Response(response.getStatusCode().value(),
                        new String(response.getBody().readAllBytes()), null), false);
    }

    /**
     * Replaces the provider registry with one serving {@link #served}.
     *
     * <p>{@code @Primary} rather than bean overriding: the real adapters stay registered, so this test cannot
     * accidentally pass because the application's own wiring was removed.
     */
    @TestConfiguration
    static class StubProviderConfiguration {

        @Bean
        @Primary
        ProviderRegistry stubProviderRegistry() {
            return new ProviderRegistry(List.of(new StubCard()));
        }
    }

    /** A provider that publishes settlement reports and nothing else this test needs. */
    private static final class StubCard implements PaymentInstrument {

        private static final ProviderId PROVIDER = new ProviderId("fakecard");

        @Override
        public ProviderId provider() {
            return PROVIDER;
        }

        @Override
        public Capabilities capabilities() {
            return new Capabilities(true, false, true, true, true, null);
        }

        @Override
        public SettlementReport settlementReport(LocalDate reportDate) {
            return served;
        }

        @Override
        public SubmitResult charge(ChargeCommand command) {
            throw new UnsupportedOperationException();
        }

        @Override
        public SubmitResult disburse(DisburseCommand command) {
            throw new UnsupportedOperationException();
        }

        @Override
        public SubmitResult refund(RefundCommand command) {
            throw new UnsupportedOperationException();
        }

        @Override
        public LookupResult lookup(LookupQuery query) {
            return new LookupResult.NotFound();
        }

        @Override
        public ProviderEvent parseWebhook(WebhookRequest request) {
            throw new UnsupportedOperationException();
        }
    }
}
