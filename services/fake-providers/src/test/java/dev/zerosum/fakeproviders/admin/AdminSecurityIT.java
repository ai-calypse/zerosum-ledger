package dev.zerosum.fakeproviders.admin;

import static org.assertj.core.api.Assertions.assertThat;

import dev.zerosum.fakeproviders.admin.AdminApi.Truth;
import dev.zerosum.fakeproviders.card.FakeCardApi.ChargeRequest;
import dev.zerosum.fakeproviders.faults.FaultLog;
import dev.zerosum.fakeproviders.shared.MagicTokens;
import dev.zerosum.fakeproviders.support.FakeProvidersIT;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * S05-T03 evidence: the admin surface refuses what it should, validates what it accepts, and reports the truth
 * (TB4, §0.3 E2, E3).
 *
 * <p>A fault endpoint that could be called without a token would let anyone who reached the network break the
 * providers; a knob payload accepted with a typo in it would inject nothing and still report success.
 */
class AdminSecurityIT extends FakeProvidersIT {

    @AfterEach
    void clearProfiles() {
        put("/admin/faults/fakecard", Map.of(), ADMIN_TOKEN);
        put("/admin/faults/fakebank", Map.of(), ADMIN_TOKEN);
    }

    @Test
    @DisplayName("without a token both admin endpoints answer 401, never a silent no-op")
    void missingTokenIsRefused() {
        assertThat(put("/admin/faults/fakecard", Map.of("http_500_rate", 1), null).status()).isEqualTo(401);
        assertThat(get("/admin/truth", null).status()).isEqualTo(401);
        // The refusal must also not have applied the profile on the way out.
        assertThat(activeSeed("fakecard")).isNotEqualTo(1L);
    }

    @Test
    @DisplayName("a non-admin token is 403: reader rights do not reach fault injection")
    void readerTokenIsForbidden() {
        assertThat(put("/admin/faults/fakecard", Map.of("seed", 1), READER_TOKEN).status()).isEqualTo(403);
        assertThat(get("/admin/truth", READER_TOKEN).status()).isEqualTo(403);
    }

    @Test
    @DisplayName("an unknown knob name is named back to the caller rather than ignored")
    void unknownFieldIsRefused() {
        Response response = put("/admin/faults/fakecard", Map.of("http_500_rate", 0.1, "wobble_rate", 1),
                ADMIN_TOKEN);

        assertThat(response.status()).isEqualTo(400);
        assertThat(response.body()).contains("wobble_rate");
    }

    @Test
    @DisplayName("an out-of-range rate is refused and the previous profile stays active")
    void outOfRangeRateIsRefusedAndChangesNothing() {
        assertThat(put("/admin/faults/fakecard", Map.of("seed", 4242), ADMIN_TOKEN).status()).isEqualTo(200);

        Response tooHigh = put("/admin/faults/fakecard", Map.of("http_500_rate", 1.5, "seed", 7), ADMIN_TOKEN);
        Response negative = put("/admin/faults/fakecard", Map.of("webhook_drop_rate", -0.1, "seed", 8), ADMIN_TOKEN);

        assertThat(tooHigh.status()).isEqualTo(400);
        assertThat(tooHigh.body()).contains("http_500_rate");
        assertThat(negative.status()).isEqualTo(400);
        assertThat(negative.body()).contains("webhook_drop_rate");
        // Validation happens before anything is replaced, so a rejected payload leaves the simulation alone.
        assertThat(activeSeed("fakecard")).isEqualTo(4242L);
    }

    @Test
    @DisplayName("a processing delay at or above the ADR-0010 quiet period is refused")
    void processingDelayBeyondTheQuietPeriodIsRefused() {
        Response response = put("/admin/faults/fakebank", Map.of("max_processing_delay_ms", 60_000), ADMIN_TOKEN);

        // A bank that processed a payout after the resolver decided resubmission was safe would duplicate money.
        assertThat(response.status()).isEqualTo(400);
        assertThat(response.body()).contains("max_processing_delay_ms");
    }

    @Test
    @DisplayName("an unknown provider is a 404, not a profile nobody reads")
    void unknownProviderIsNotFound() {
        assertThat(put("/admin/faults/fakecrypto", Map.of("seed", 1), ADMIN_TOKEN).status()).isEqualTo(404);
    }

    @Test
    @DisplayName("truth reports the provider's own record of a charge, by client reference")
    void truthReportsTheCharge() {
        String reference = "truth-" + UUID.randomUUID();
        post("/fakecard/v1/charges", new ChargeRequest(reference, MagicTokens.CARD_OK, 3_300L, "USD"), null);

        Truth truth = truth(reference);

        assertThat(truth.client_reference()).isEqualTo(reference);
        assertThat(truth.charges()).hasSize(1);
        assertThat(truth.charges().getFirst().amount_minor()).isEqualTo(3_300L);
        assertThat(truth.charges().getFirst().status()).isEqualTo("SUCCEEDED");
        assertThat(truth.payouts()).isEmpty();
    }

    /**
     * CR-S05-02: the defect a deployment found, as a test.
     *
     * <p>A charge is stored against the client reference the caller sent — the attempt id — because this provider is
     * never told a ledger entity id. Querying by entity therefore matched nothing for every entity, and the empty
     * list read as "no charge occurred" rather than "this filter cannot match". The parameter is now the reference,
     * and the old one is refused rather than ignored: being quietly ignored is what produced the clean bill of
     * health in the first place.
     */
    @Test
    @DisplayName("ground truth is found by client reference, and entity_id is refused rather than matching nothing")
    void groundTruthIsQueryableByClientReference() {
        // The shape of the real thing: the attempt id is what instrument-service sends as the client reference.
        String attemptId = UUID.randomUUID().toString();
        post("/fakecard/v1/charges", new ChargeRequest(attemptId, MagicTokens.CARD_OK, 2_500L, "USD"), null);

        Truth byReference = truth(attemptId);
        assertThat(byReference.charges()).hasSize(1);
        assertThat(byReference.charges().getFirst().client_reference()).isEqualTo(attemptId);

        Response byEntity = get("/admin/truth?entity_id=rider:R1", ADMIN_TOKEN);
        assertThat(byEntity.status()).isEqualTo(400);
        assertThat(byEntity.body()).contains("client_reference");
    }

    @Test
    @DisplayName("every injected fault appears once in the log, and the counters agree with it")
    void faultLogMatchesTheCounters() {
        long before = faultsOfType("http_500");
        assertThat(put("/admin/faults/fakecard", Map.of("http_500_rate", 1, "seed", 5), ADMIN_TOKEN).status())
                .isEqualTo(200);

        for (int i = 0; i < 3; i++) {
            assertThat(post("/fakecard/v1/charges",
                    new ChargeRequest("fault-" + UUID.randomUUID(), MagicTokens.CARD_OK, 100L, "USD"), null)
                    .status()).isEqualTo(500);
        }
        put("/admin/faults/fakecard", Map.of(), ADMIN_TOKEN);

        Truth truth = truth(null);
        long logged = truth.faults().stream().filter(entry -> entry.fault_type().equals("http_500")).count();
        // Exactly three: one row per injected fault, not one per request and not one per poll.
        assertThat(logged).isEqualTo(before + 3);
        assertThat(truth.fault_counts().get("http_500")).isEqualTo(logged);
        assertThat(truth.faults()).extracting(FaultLog.Entry::seed).contains(5L);
    }

    @Test
    @DisplayName("the summary counts truth by status and shows each provider's active profile, admin only")
    void summaryCountsTruthAndShowsTheActiveProfile() {
        assertThat(get("/admin/summary", null).status()).isEqualTo(401);
        assertThat(get("/admin/summary", READER_TOKEN).status()).isEqualTo(403);

        AdminApi.Summary before = get("/admin/summary", ADMIN_TOKEN).as(AdminApi.Summary.class);
        post("/fakecard/v1/charges", new ChargeRequest("sum-" + UUID.randomUUID(), MagicTokens.CARD_OK, 1_200L, "USD"),
                null);
        post("/fakecard/v1/charges", new ChargeRequest("sum-" + UUID.randomUUID(),
                MagicTokens.CARD_DECLINE_INSUFFICIENT_FUNDS, 800L, "USD"), null);
        assertThat(put("/admin/faults/fakecard", Map.of("webhook_drop_rate", 0.25, "seed", 11), ADMIN_TOKEN).status())
                .isEqualTo(200);

        AdminApi.Summary after = get("/admin/summary", ADMIN_TOKEN).as(AdminApi.Summary.class);
        assertThat(count(after, "SUCCEEDED") - count(before, "SUCCEEDED")).isEqualTo(1);
        assertThat(count(after, "DECLINED") - count(before, "DECLINED")).isEqualTo(1);
        assertThat(amount(after, "SUCCEEDED") - amount(before, "SUCCEEDED")).isEqualTo(1_200L);
        assertThat(after.profiles()).containsOnlyKeys("fakebank", "fakecard");
        assertThat(((Number) after.profiles().get("fakecard").get("webhook_drop_rate")).doubleValue()).isEqualTo(0.25);
        assertThat(((Number) after.profiles().get("fakecard").get("seed")).longValue()).isEqualTo(11L);
        assertThat(((Number) after.profiles().get("fakebank").get("webhook_drop_rate")).doubleValue()).isZero();
    }

    private static long count(AdminApi.Summary summary, String status) {
        return summary.charges().stream().filter(t -> t.status().equals(status)).mapToLong(AdminApi.StatusTotal::count)
                .sum();
    }

    private static long amount(AdminApi.Summary summary, String status) {
        return summary.charges().stream().filter(t -> t.status().equals(status))
                .mapToLong(AdminApi.StatusTotal::amount_minor).sum();
    }

    private Truth truth(String clientReference) {
        String path = clientReference == null ? "/admin/truth" : "/admin/truth?client_reference=" + clientReference;
        return get(path, ADMIN_TOKEN).as(Truth.class);
    }

    private long faultsOfType(String type) {
        return db.sql("SELECT count(*) FROM fault_log WHERE fault_type = ?").param(type).query(Long.class).single();
    }

    private Long activeSeed(String provider) {
        return db.sql("SELECT seed FROM fault_profiles WHERE provider = ?").param(provider)
                .query(Long.class).optional().orElse(null);
    }
}
