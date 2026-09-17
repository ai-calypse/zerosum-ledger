package dev.zerosum.instrument.payouts;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * The payouts kill switch, off (D05-11).
 *
 * <p>A separate class because the switch is read at startup: a flag that could change under a payout already on the
 * wire would make "was it on when we called the bank?" unanswerable, which is not a question to leave open about
 * money leaving the building. The database is shared with {@link PayoutRunIT}; only the Spring context differs.
 *
 * <p><strong>A frozen payout run is refused outright</strong>, unlike a frozen collection, which still creates its
 * attempt. The asymmetry is deliberate and is the whole content of this test: a money order cannot be replayed, so a
 * collection must keep the attempt, whereas a payout run can simply be run again once the switch is back on — so
 * leaving payouts queued in {@code CREATED} would mean a freeze that still had money staged to leave.
 *
 * <p>The assertion that matters is the negative one, and it is made against the bank's own request count rather than
 * against our records. "We did not write down a payout" and "we did not pay anybody" are different claims, and only
 * the second is what a freeze promises.
 */
class PayoutKillSwitchIT extends PayoutTestBase {

    @DynamicPropertySource
    static void killSwitchOff(DynamicPropertyRegistry registry) {
        registry.add("zs.kill-switches.payouts-enabled", () -> "false");
    }

    @Test
    @DisplayName("with payouts frozen the run is refused, no attempt is created and no bank is called")
    void frozenPayoutsRefuseTheRunEntirely() {
        String driver = someDriver();
        register(driver, "fakebank", "tok_bank");
        SERVICES.payable(driver, "USD", 5_000);
        int callsBefore = BANK.requestCount();
        String key = someKey();

        Response refused = postRun("USD", key);

        assertThat(refused.status()).isEqualTo(409);
        assertThat(JSON.readTree(refused.body()).get("code").asString()).isEqualTo("payouts_disabled");

        assertThat(payoutAttempts(driver)).as("a frozen run stages nothing for later").isZero();
        assertThat(BANK.requestCount()).as("ground truth: a frozen payout reaches no bank at all")
                .isEqualTo(callsBefore);

        // The freeze is checked before freshness, so a stale pipeline cannot mask why nobody was paid.
        UUID runId = db.sql("SELECT run_id FROM payout_runs WHERE idempotency_key = :key")
                .param("key", key).query(UUID.class).single();
        assertThat(runStatus(runId)).isEqualTo("REFUSED");
        assertThat(runRefusalCode(runId)).isEqualTo("payouts_disabled");
        assertThat(SERVICES.lastAuthorization).as("the freshness read is never even made when payouts are frozen")
                .isNull();
    }
}
