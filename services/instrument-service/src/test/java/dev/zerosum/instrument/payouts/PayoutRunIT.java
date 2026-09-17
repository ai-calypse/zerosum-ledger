package dev.zerosum.instrument.payouts;

import static org.assertj.core.api.Assertions.assertThat;

import com.networknt.schema.InputFormat;
import com.networknt.schema.SchemaRegistry;
import com.networknt.schema.SpecificationVersion;
import dev.zerosum.instrument.adapter.TestWebhookSigner;
import dev.zerosum.testsupport.ZsTestDatabase;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;
import org.yaml.snakeyaml.Yaml;
import tools.jackson.databind.JsonNode;

/**
 * S05-T10 evidence: payout runs, and the two acceptance criteria they carry (M10(a), M10(c)).
 *
 * <p>This is the path that sends money <em>out</em>, so the assertions are made against the strongest available
 * witness rather than against our own bookkeeping:
 *
 * <ul>
 *   <li><strong>M10(a)</strong> is asserted with <em>concurrency</em>, not a second sequential call. A sequential
 *       test would pass just as happily with the uniqueness implemented as a {@code SELECT} followed by an
 *       {@code INSERT} — which is precisely the implementation that pays a driver twice under load. Four runs start
 *       together and exactly one attempt exists afterwards, because the {@code one_inflight_payout} partial unique
 *       index decided it.</li>
 *   <li><strong>M10(c)</strong> is asserted on the <em>sum</em> of the three stage ages, with each stage individually
 *       inside the threshold. A run that compared only the worst stage would pass a simpler test and pay drivers
 *       against a pipeline nine seconds behind.</li>
 *   <li><strong>M10(b)</strong> is asserted on the payment events a returned payout emits, each validated against
 *       D01-8. The compensating order itself is order-service's mapper (golden O8), not this service's to create.</li>
 * </ul>
 */
class PayoutRunIT extends PayoutTestBase {

    private static final SchemaRegistry SCHEMAS = SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_2020_12);

    @Test
    @DisplayName("an eligible driver is paid the balance the ledger reported, and the payout reaches PENDING")
    void eligibleDriverIsPaidTheLedgerBalance() {
        String driver = someDriver();
        register(driver, "fakebank", "tok_bank");
        SERVICES.payable(driver, "USD", 5_000);

        Response created = postRun("USD", someKey());

        assertThat(created.status()).isEqualTo(201);
        JsonNode run = JSON.readTree(created.body());
        assertThat(run.get("status").asString()).isEqualTo("COMPLETED");
        assertThat(run.get("replayed").asBoolean()).isFalse();
        assertThat(run.get("attempts_created").asInt()).isEqualTo(1);

        JsonNode mine = resultFor(run, driver);
        assertThat(mine.get("outcome").asString()).isEqualTo("PAID");
        // The amount is the ledger's figure at run time (ADR-0004), not anything this service computed.
        assertThat(mine.get("amount_minor").asLong()).isEqualTo(5_000);
        assertThat(mine.get("as_of_seq").asLong()).as("the sequence the balance was read at, kept for audit")
                .isEqualTo(42);

        UUID attemptId = UUID.fromString(mine.get("attempt_id").asString());
        assertThat(amountOf(attemptId)).isEqualTo(5_000);

        // Accepted is not settled: the bank answers 202 and the attempt waits in PENDING for a webhook.
        awaitUntil(() -> "PENDING".equals(status(attemptId)), () -> "the payout never reached PENDING");
        assertThat(providerRef(attemptId)).as("the bank's own reference, carried onto the attempt").isEqualTo("po_1");

        // D05-5: PAYOUT_ACCEPTED is emitted on the non-terminal move into PENDING, so the ledger learns about money
        // already committed to the driver rather than waiting a banking day for a terminal status.
        assertThat(outboxEventTypes(attemptId)).containsExactly("PAYOUT_ACCEPTED");
        assertThat(schemaErrors(outboxPayloads(attemptId).get(0))).as("the event validates against D01-8").isEmpty();

        assertThat(SERVICES.lastAuthorization)
                .as("the freshness and balance reads carry this service's reader token (D03-4)")
                .isEqualTo("Bearer " + READER_TOKEN);
    }

    @Test
    @DisplayName("below the minimum, unknown to the ledger, and no disbursing instrument are each skipped with a reason")
    void ineligibleDriversAreSkippedWithTheirReason() {
        String poor = someDriver();
        register(poor, "fakebank", "tok_bank");
        SERVICES.payable(poor, "USD", 50);            // under the 100 minor-unit minimum

        String unknownToLedger = someDriver();
        register(unknownToLedger, "fakebank", "tok_bank");   // no balance registered, so the ledger answers 404

        String cardOnly = someDriver();
        register(cardOnly, "fakecard", "tok_card");   // FakeCard cannot disburse
        SERVICES.payable(cardOnly, "USD", 9_000);

        Response created = postRun("USD", someKey());

        assertThat(created.status()).isEqualTo(201);
        JsonNode run = JSON.readTree(created.body());
        assertThat(run.get("attempts_created").asInt()).as("nobody was eligible").isZero();

        assertThat(resultFor(run, poor).get("outcome").asString()).isEqualTo("SKIPPED_BELOW_MINIMUM");
        assertThat(resultFor(run, poor).get("amount_minor").asLong())
                .as("what they were owed is reported even when it is not enough").isEqualTo(50);
        assertThat(resultFor(run, unknownToLedger).get("outcome").asString()).isEqualTo("SKIPPED_NO_BALANCE");
        // Capabilities decide, not the provider's name: a driver registered only at a card provider is a
        // registration problem, which is a different fix from a balance problem.
        assertThat(resultFor(run, cardOnly).get("outcome").asString()).isEqualTo("SKIPPED_NO_TOKEN");

        assertThat(payoutAttempts(poor)).isZero();
        assertThat(payoutAttempts(unknownToLedger)).isZero();
        assertThat(payoutAttempts(cardOnly)).isZero();
    }

    @Nested
    @DisplayName("M10(a): at most one in-flight payout per driver and currency")
    class OneInFlightPayout {

        @Test
        @DisplayName("four runs racing for one driver produce exactly one payout, and the losers say so")
        void concurrentRunsProduceExactlyOnePayout() throws Exception {
            String driver = someDriver();
            register(driver, "fakebank", "tok_bank");
            SERVICES.payable(driver, "USD", 7_500);

            int runs = 4;
            var start = new CountDownLatch(1);
            ExecutorService pool = Executors.newFixedThreadPool(runs);
            List<Future<Response>> started = new ArrayList<>();
            try {
                for (int i = 0; i < runs; i++) {
                    // Distinct keys: these are four genuinely different runs, not a replay. Same-key replay is the
                    // Idempotency group below, and conflating the two would leave the index untested.
                    started.add(pool.submit(() -> {
                        start.await();
                        return postRun("USD", someKey());
                    }));
                }
                start.countDown();

                List<JsonNode> responses = new ArrayList<>();
                for (Future<Response> pending : started) {
                    Response response = pending.get(60, TimeUnit.SECONDS);
                    assertThat(response.status()).as("a run that loses the race is still a successful run")
                            .isEqualTo(201);
                    responses.add(JSON.readTree(response.body()));
                }

                long paid = responses.stream()
                        .filter(run -> "PAID".equals(resultFor(run, driver).get("outcome").asString())).count();
                long skipped = responses.stream()
                        .filter(run -> "SKIPPED_IN_FLIGHT".equals(resultFor(run, driver).get("outcome").asString()))
                        .count();

                assertThat(paid).as("exactly one run paid the driver").isEqualTo(1);
                assertThat(skipped).as("every other run was refused by the index, and reported it")
                        .isEqualTo(runs - 1);
            } finally {
                pool.shutdownNow();
            }

            // The claim M10(a) actually makes, read from the table rather than from the responses.
            assertThat(inFlightPayouts(driver, "USD")).isEqualTo(1);
            assertThat(payoutAttempts(driver)).isEqualTo(1);

            UUID attemptId = onlyPayoutOf(driver);
            awaitUntil(() -> "PENDING".equals(status(attemptId)), () -> "the one payout never reached PENDING");
            assertThat(outboxEventTypes(attemptId)).as("one movement of money, one payment event")
                    .containsExactly("PAYOUT_ACCEPTED");
            // A duplicate that was going to appear would appear after the first, not before.
            assertStays(() -> payoutAttempts(driver) == 1, "a second payout attempt appeared for the driver");
        }

        @Test
        @DisplayName("a later run skips a driver whose payout is still in flight")
        void sequentialRunSkipsAnInFlightDriver() {
            String driver = someDriver();
            register(driver, "fakebank", "tok_bank");
            SERVICES.payable(driver, "USD", 3_000);

            JsonNode first = JSON.readTree(postRun("USD", someKey()).body());
            assertThat(resultFor(first, driver).get("outcome").asString()).isEqualTo("PAID");

            JsonNode second = JSON.readTree(postRun("USD", someKey()).body());

            assertThat(resultFor(second, driver).get("outcome").asString()).isEqualTo("SKIPPED_IN_FLIGHT");
            assertThat(second.get("attempts_created").asInt()).isZero();
            assertThat(payoutAttempts(driver)).isEqualTo(1);
        }
    }

    @Nested
    @DisplayName("M10(c): a stale or unmeasurable pipeline refuses the run")
    class Freshness {

        @Test
        @DisplayName("staleness above 5 s is 409 ledger_stale and creates no attempt")
        void stalePipelineIsRefused() {
            String driver = fundedDriver();
            SERVICES.ledgerUnappliedAgeSeconds = 9.0;
            String key = someKey();

            Response refused = postRun("USD", key);

            assertThat(refused.status()).isEqualTo(409);
            assertThat(JSON.readTree(refused.body()).get("code").asString()).isEqualTo("ledger_stale");
            assertThat(payoutAttempts(driver)).as("a refused run creates no attempts").isZero();

            // The refusal is recorded, so "why did nobody get paid" is answered by a row.
            UUID runId = runFor(key);
            assertThat(runStatus(runId)).isEqualTo("REFUSED");
            assertThat(runRefusalCode(runId)).isEqualTo("ledger_stale");
        }

        @Test
        @DisplayName("three individually fresh stages that add up past 5 s are still refused")
        void theStagesAreSummedNotMaxed() {
            String driver = fundedDriver();
            // No single stage is over the 5 s threshold; the pipeline is nine seconds behind.
            SERVICES.orderOutboxAgeSeconds = 3.0;
            SERVICES.ledgerUnappliedAgeSeconds = 3.0;

            Response refused = postRun("USD", someKey());

            assertThat(refused.status()).as("summing the stages is what makes this a refusal").isEqualTo(409);
            assertThat(JSON.readTree(refused.body()).get("code").asString()).isEqualTo("ledger_stale");
            assertThat(payoutAttempts(driver)).isZero();

            // And the same driver is paid once the pipeline is inside the threshold, so the refusal was the ages
            // and not something else about this driver.
            SERVICES.orderOutboxAgeSeconds = 1.0;
            SERVICES.ledgerUnappliedAgeSeconds = 1.0;
            JsonNode ran = JSON.readTree(postRun("USD", someKey()).body());
            assertThat(resultFor(ran, driver).get("outcome").asString()).isEqualTo("PAID");
        }

        @Test
        @DisplayName("a component that cannot be measured fails closed, with the same code")
        void unmeasurableComponentsFailClosed() {
            String driver = fundedDriver();
            int callsBefore = BANK.requestCount();

            // The ledger could not measure its own lag and says so rather than reporting a comfortable zero.
            SERVICES.ledgerStatus = "error";
            Response ledgerDown = postRun("USD", someKey());
            assertThat(ledgerDown.status()).isEqualTo(409);
            assertThat(JSON.readTree(ledgerDown.body()).get("code").asString()).isEqualTo("ledger_stale");

            // order-service unreachable.
            SERVICES.ledgerStatus = "ok";
            SERVICES.forcedOutboxStatsStatus = 503;
            Response ordersDown = postRun("USD", someKey());
            assertThat(ordersDown.status()).isEqualTo(409);
            assertThat(JSON.readTree(ordersDown.body()).get("code").asString()).isEqualTo("ledger_stale");

            assertThat(payoutAttempts(driver)).as("neither outage paid anybody").isZero();
            assertThat(BANK.requestCount()).as("ground truth: no bank was called at all")
                    .isEqualTo(callsBefore);
        }
    }

    @Nested
    @DisplayName("D03-3 idempotency: the stored run is the idempotency record")
    class Idempotency {

        @Test
        @DisplayName("the same key and body replays the original run and creates no second payout")
        void replayReturnsTheOriginalRun() {
            String driver = someDriver();
            register(driver, "fakebank", "tok_bank");
            SERVICES.payable(driver, "USD", 4_200);
            String key = someKey();

            Response first = postRun("USD", key);
            assertThat(first.status()).isEqualTo(201);
            JsonNode original = JSON.readTree(first.body());
            String attemptId = resultFor(original, driver).get("attempt_id").asString();

            Response replay = postRun("USD", key);

            assertThat(replay.status()).isEqualTo(200);
            assertThat(replay.replayed()).isEqualTo("true");
            JsonNode replayed = JSON.readTree(replay.body());
            assertThat(replayed.get("run_id").asString()).isEqualTo(original.get("run_id").asString());
            assertThat(replayed.get("replayed").asBoolean()).isTrue();
            // The stored per-driver outcomes come back intact: they cannot be recomputed, which is why they are
            // stored at all.
            assertThat(resultFor(replayed, driver).get("outcome").asString()).isEqualTo("PAID");
            assertThat(resultFor(replayed, driver).get("amount_minor").asLong()).isEqualTo(4_200);
            assertThat(resultFor(replayed, driver).get("attempt_id").asString()).isEqualTo(attemptId);

            assertThat(payoutAttempts(driver)).as("a replay pays nobody a second time").isEqualTo(1);
        }

        @Test
        @DisplayName("the same key with a different body is 422, not a replay")
        void reusedKeyWithADifferentBodyIsRefused() {
            String driver = fundedDriver();
            String key = someKey();
            assertThat(postRun("USD", key).status()).isEqualTo(201);

            Response mismatched = postRun("EUR", key);

            assertThat(mismatched.status()).isEqualTo(422);
            assertThat(JSON.readTree(mismatched.body()).get("code").asString()).isEqualTo("idempotency_key_reused");
            // Answering with the USD run's result would hide the caller's bug behind a success.
            assertThat(payoutAttempts(driver)).isEqualTo(1);
        }

        @Test
        @DisplayName("a run without an idempotency key is refused outright")
        void keyIsRequired() {
            String driver = fundedDriver();

            Response noKey = postRun("USD", null);

            assertThat(noKey.status()).isEqualTo(400);
            assertThat(JSON.readTree(noKey.body()).get("code").asString()).isEqualTo("idempotency_key_missing");
            assertThat(payoutAttempts(driver)).isZero();
        }
    }

    @Nested
    @DisplayName("roles and validation")
    class Access {

        @Test
        @DisplayName("a reader may not start a payout run, and an unauthenticated caller gets 401")
        void writerRoleIsRequired() {
            String driver = fundedDriver();

            assertThat(postRun("USD", someKey(), null).status()).isEqualTo(401);
            Response asReader = postRun("USD", someKey(), READER_TOKEN);
            assertThat(asReader.status()).isEqualTo(403);
            assertThat(JSON.readTree(asReader.body()).get("code").asString()).isEqualTo("forbidden");

            assertThat(payoutAttempts(driver)).as("a refused caller moves no money").isZero();
        }

        @Test
        @DisplayName("a currency outside the D01-7 allow-list is 422")
        void currencyMustBeAllowed() {
            Response refused = postRun("XXX", someKey());

            assertThat(refused.status()).isEqualTo(422);
            assertThat(JSON.readTree(refused.body()).get("code").asString()).isEqualTo("unsupported_currency");
        }
    }

    @Nested
    @DisplayName("M10(b): a returned payout emits the events a compensating order is built from")
    class ReturnedPayout {

        /**
         * The events, not the order. The mapping from {@code PAYOUT_RETURNED} to a money order that re-credits the
         * driver's {@code payable} belongs to <strong>order-service's mapper</strong> (D03-6, golden O8), and
         * instrument-service publishes facts only — it never creates money orders. What is provable here is that the
         * facts are emitted, once each, in the right order, and valid against D01-8.
         */
        @Test
        @DisplayName("settled then returned, each emitted exactly once and each valid against D01-8")
        void aReturnedPayoutEmitsSettledThenReturned() {
            String driver = someDriver();
            register(driver, "fakebank", "tok_bank");
            SERVICES.payable(driver, "USD", 5_000);

            JsonNode run = JSON.readTree(postRun("USD", someKey()).body());
            UUID attemptId = UUID.fromString(resultFor(run, driver).get("attempt_id").asString());
            awaitUntil(() -> "PENDING".equals(status(attemptId)), () -> "the payout never reached PENDING");

            assertThat(webhook(attemptId, "payout.settled", null)).isEqualTo(200);
            assertThat(status(attemptId)).isEqualTo("SETTLED");

            assertThat(webhook(attemptId, "payout.returned", "R01")).isEqualTo(200);
            assertThat(status(attemptId)).isEqualTo("RETURNED");

            // The ledger must see the payout settle before it sees it come back, or it would be asked to reverse an
            // order that was never created.
            assertThat(outboxEventTypes(attemptId))
                    .containsExactly("PAYOUT_ACCEPTED", "PAYOUT_SETTLED", "PAYOUT_RETURNED");

            List<String> payloads = outboxPayloads(attemptId);
            for (String payload : payloads) {
                assertThat(schemaErrors(payload)).as("every payout event validates against D01-8: %s", payload)
                        .isEmpty();
            }

            JsonNode returned = JSON.readTree(payloads.get(2));
            assertThat(returned.get("entity_id").asString()).as("the driver whose payable is re-credited")
                    .isEqualTo(driver);
            assertThat(returned.get("money").get("amount_minor").asLong()).isEqualTo(5_000);
            assertThat(returned.get("failure_code").asString()).isEqualTo("R01");
            assertThat(returned.get("event_id").asString())
                    .as("deterministic, so a redelivery maps to one order rather than two")
                    .isEqualTo(attemptId + ":PAYOUT_RETURNED");
            assertThat(returned.has("source_order_id"))
                    .as("a payout comes from a run, not an order (D01-8 forbids the field)").isFalse();
        }

        private int webhook(UUID attemptId, String eventType, String failureCode) {
            byte[] body = ("{\"event_id\":\"evt_" + UUID.randomUUID() + "\",\"provider\":\"fakebank\","
                    + "\"event_type\":\"" + eventType + "\",\"provider_ref\":\"po_1\","
                    + "\"client_reference\":\"" + attemptId + "\",\"amount_minor\":5000,\"currency\":\"USD\","
                    + "\"failure_code\":" + (failureCode == null ? "null" : "\"" + failureCode + "\"")
                    + ",\"occurred_at\":\"2026-01-01T00:00:00Z\"}").getBytes(StandardCharsets.UTF_8);
            return RestClient.create("http://localhost:" + port)
                    .post().uri("/v1/webhooks/fakebank")
                    .contentType(MediaType.APPLICATION_JSON)
                    .header("ZS-Signature", TestWebhookSigner.header(Instant.now(), body, WEBHOOK_SECRET))
                    .body(body)
                    .exchange((req, response) -> response.getStatusCode().value(), false);
        }
    }

    @Nested
    @DisplayName("responses conform to openapi/instrument-service.yaml")
    class OpenApiConformance {

        @Test
        @DisplayName("a completed run and a refusal both validate against the published schemas")
        void responsesValidate() {
            JsonNode spec = openApi();
            String driver = fundedDriver();
            String key = someKey();

            String created = postRun("USD", key).body();
            assertThat(errors(spec, schemaRef(spec, "201"), created)).isEmpty();

            // The same key, so this is a genuine replay and the 200 schema is the one actually being checked.
            String replayed = postRun("USD", key).body();
            assertThat(errors(spec, schemaRef(spec, "200"), replayed)).isEmpty();

            SERVICES.ledgerUnappliedAgeSeconds = 30;
            String refused = postRun("USD", someKey()).body();
            assertThat(errors(spec, "#/components/schemas/Problem", refused)).isEmpty();
            assertThat(payoutAttempts(driver)).isEqualTo(1);
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

        private String schemaRef(JsonNode spec, String status) {
            return spec.get("paths").get("/v1/payout-runs").get("post").get("responses").get(status)
                    .get("content").get("application/json").get("schema").get("$ref").asString();
        }

        /**
         * Validates against a document whose root is the operation's {@code $ref}, carrying the whole components
         * block so internal references resolve without a network lookup.
         */
        private List<String> errors(JsonNode spec, String ref, String json) {
            var document = JSON.createObjectNode();
            document.put("$ref", ref);
            document.set("components", spec.get("components"));
            return SCHEMAS.getSchema(document).validate(json, InputFormat.JSON).stream()
                    .map(e -> e.getKeyword() + " at " + e.getInstanceLocation() + ": " + e.getMessage())
                    .toList();
        }
    }

    // --- helpers ---------------------------------------------------------------------------------------------------

    /** A driver with a bank instrument and enough on their payable to be paid. */
    private String fundedDriver() {
        String driver = someDriver();
        register(driver, "fakebank", "tok_bank");
        SERVICES.payable(driver, "USD", 5_000);
        return driver;
    }

    private UUID onlyPayoutOf(String entityId) {
        return db.sql("SELECT attempt_id FROM payment_attempts WHERE kind = 'PAYOUT' AND entity_id = :entity")
                .param("entity", entityId).query(UUID.class).single();
    }

    private UUID runFor(String idempotencyKey) {
        return db.sql("SELECT run_id FROM payout_runs WHERE idempotency_key = :key")
                .param("key", idempotencyKey).query(UUID.class).single();
    }
}
