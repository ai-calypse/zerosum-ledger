package dev.zerosum.fakeproviders.faults;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.zerosum.fakeproviders.admin.AdminApi.Truth;
import dev.zerosum.fakeproviders.bank.FakeBankApi.PayoutRequest;
import dev.zerosum.fakeproviders.card.FakeCardApi.ChargeRequest;
import dev.zerosum.fakeproviders.shared.MagicTokens;
import dev.zerosum.fakeproviders.support.FakeProvidersIT;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.test.context.TestPropertySource;
import org.springframework.web.client.RestClient;

/**
 * S05-T03 evidence: seeded reproducibility, and that the knobs actually reach the providers.
 *
 * <p>Reproducibility is the property everything else in this simulator rests on. If two runs with one seed can
 * disagree, no failure found by a chaos run can be replayed, and every later result becomes an anecdote.
 */
@TestPropertySource(properties = {"zs.fakebank.lifecycle-interval=100ms",
        "zs.faults.timeout-after-commit-withhold=2s"})
class FaultKnobDeterminismIT extends FakeProvidersIT {

    private static final int REQUESTS = 24;
    private static final Duration PATIENCE = Duration.ofSeconds(20);

    @AfterEach
    void clearProfiles() {
        // The database outlives the application context, so a profile left active here would inject faults into
        // whichever test class runs next.
        activate("fakecard", Map.of());
        activate("fakebank", Map.of());
    }

    @Test
    @DisplayName("the same seed and request sequence produce the same outcomes; a different seed does not")
    void seedDecidesTheOutcomeSequence() {
        List<Integer> first = runSequence(4_242_424_242L, "run-a");
        List<Integer> second = runSequence(4_242_424_242L, "run-b");
        List<Integer> other = runSequence(99L, "run-c");

        // Same seed, same request order: identical outcomes. The client references differ, which is the point —
        // the simulation is reproducible from the seed, not from the payloads.
        assertThat(second).isEqualTo(first);
        assertThat(first).contains(200).contains(500);
        assertThat(other).isNotEqualTo(first);
    }

    @Test
    @DisplayName("a profile with every rate at zero behaves exactly like no profile at all")
    void allRatesZeroInjectsNothing() {
        long before = faultCount();
        activate("fakecard", Map.of("seed", 7));

        for (int i = 0; i < 10; i++) {
            assertThat(charge("zero-" + UUID.randomUUID()).status()).isEqualTo(200);
        }

        assertThat(faultCount()).isEqualTo(before);
    }

    @Test
    @DisplayName("timeout after commit: the client gives up, and the charge is committed all the same")
    void timeoutAfterCommitCommitsAnyway() {
        activate("fakecard", Map.of("timeout_after_commit_rate", 1, "seed", 3));
        String reference = "timeout-" + UUID.randomUUID();

        // The read timeout is well under the 2 s withhold, so the client sees exactly what an adapter would see.
        var factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(2));
        factory.setReadTimeout(Duration.ofMillis(500));
        RestClient impatient = RestClient.builder().baseUrl("http://localhost:" + port())
                .requestFactory(factory).build();

        assertThatThrownBy(() -> impatient.post().uri("/fakecard/v1/charges")
                .contentType(MediaType.APPLICATION_JSON)
                .body(JSON.writeValueAsString(new ChargeRequest(reference, MagicTokens.CARD_OK, 1_500L, "USD")))
                .retrieve().body(String.class))
                .isInstanceOf(RuntimeException.class);

        // Ground truth disagrees with the caller, which is the whole reason UNKNOWN outcomes exist.
        activate("fakecard", Map.of());
        Truth truth = truth(reference);
        assertThat(truth.charges()).hasSize(1);
        assertThat(truth.charges().getFirst().status()).isEqualTo("SUCCEEDED");
        assertThat(truth.fault_counts()).containsKey("timeout_after_commit");
    }

    @Test
    @DisplayName("reset before commit: the caller learns nothing, and nothing was written")
    void resetBeforeCommitCommitsNothing() {
        activate("fakecard", Map.of("reset_before_commit_rate", 1, "seed", 13));
        String reference = "reset-" + UUID.randomUUID();

        boolean callerLearnedNothing;
        try {
            // Either the connection breaks while the body is read, or the body arrives empty. Both leave the caller
            // unable to tell whether the charge happened, which is the state this fault exists to produce.
            callerLearnedNothing = post("/fakecard/v1/charges",
                    new ChargeRequest(reference, MagicTokens.CARD_OK, 800L, "USD"), null).body().isBlank();
        } catch (RuntimeException transportFailure) {
            callerLearnedNothing = true;
        }

        activate("fakecard", Map.of());
        assertThat(callerLearnedNothing).isTrue();
        // Unlike timeout-after-commit, this one really did commit nothing: ground truth has no charge at all.
        assertThat(truth(reference).charges()).isEmpty();
        assertThat(truth(reference).fault_counts()).containsKey("reset_before_commit");
    }

    @Test
    @DisplayName("the latency knob delays the response and is recorded as an injected fault")
    void latencyKnobDelaysTheResponse() {
        long before = faultsOfType("latency");
        // Lognormal around a 300 ms median: with this seed a sample under 100 ms is six sigma away, so the bound
        // below is about the knob working, not about luck.
        activate("fakecard", Map.of("latency_p50_ms", 300, "latency_p95_ms", 400, "seed", 17));

        long start = System.nanoTime();
        assertThat(charge("latency-" + UUID.randomUUID()).status()).isEqualTo(200);
        Duration elapsed = Duration.ofNanos(System.nanoTime() - start);

        activate("fakecard", Map.of());
        assertThat(elapsed).isGreaterThan(Duration.ofMillis(100));
        assertThat(faultsOfType("latency")).isEqualTo(before + 1);
    }

    @Test
    @DisplayName("the banking-day and return-rate knobs reach FakeBank: a payout settles early and is returned")
    void bankKnobsDriveTheLifecycle() {
        // The configured banking day is 30 s; the knob sets zero, so reaching a terminal state in seconds is the
        // knob working rather than the configuration.
        activate("fakebank", Map.of("simulated_banking_day_seconds", 0, "return_rate", 1,
                "max_processing_delay_ms", 100, "seed", 11));
        String reference = "bank-knob-" + UUID.randomUUID();

        post("/fakebank/v1/payouts", new PayoutRequest(reference, MagicTokens.BANK_OK, 9_000L, "USD"), null);

        Instant deadline = Instant.now().plus(PATIENCE);
        String status = null;
        while (Instant.now().isBefore(deadline)) {
            List<?> payouts = truth(reference).payouts();
            if (!payouts.isEmpty()) {
                status = truth(reference).payouts().getFirst().status();
                if ("RETURNED".equals(status)) {
                    break;
                }
            }
            sleep();
        }
        assertThat(status).isEqualTo("RETURNED");
        assertThat(truth(reference).payouts().getFirst().return_code()).isEqualTo("R01");
    }

    /** One run: activate the profile (which resets the streams) and issue the same request sequence. */
    private List<Integer> runSequence(long seed, String prefix) {
        Map<String, Object> knobs = new LinkedHashMap<>();
        knobs.put("http_500_rate", 0.5);
        knobs.put("seed", seed);
        activate("fakecard", knobs);

        List<Integer> statuses = new ArrayList<>();
        for (int i = 0; i < REQUESTS; i++) {
            statuses.add(charge(prefix + "-" + i).status());
        }
        return statuses;
    }

    private Response charge(String reference) {
        return post("/fakecard/v1/charges", new ChargeRequest(reference, MagicTokens.CARD_OK, 1_000L, "USD"), null);
    }

    private void activate(String provider, Map<String, Object> knobs) {
        assertThat(put("/admin/faults/" + provider, knobs, ADMIN_TOKEN).status()).isEqualTo(200);
    }

    private Truth truth(String clientReference) {
        return get("/admin/truth?client_reference=" + clientReference, ADMIN_TOKEN).as(Truth.class);
    }

    private long faultCount() {
        return db.sql("SELECT count(*) FROM fault_log").query(Long.class).single();
    }

    private long faultsOfType(String type) {
        return db.sql("SELECT count(*) FROM fault_log WHERE fault_type = ?").param(type).query(Long.class).single();
    }

    private static void sleep() {
        try {
            Thread.sleep(100);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(interrupted);
        }
    }
}
