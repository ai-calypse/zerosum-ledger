package dev.zerosum.fakeproviders.bank;

import static org.assertj.core.api.Assertions.assertThat;

import dev.zerosum.fakeproviders.bank.FakeBankApi.PayoutRequest;
import dev.zerosum.fakeproviders.bank.FakeBankApi.PayoutResponse;
import dev.zerosum.fakeproviders.shared.MagicTokens;
import dev.zerosum.fakeproviders.support.FakeProvidersIT;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.TestPropertySource;

/**
 * S05-T02 evidence: the payout lifecycle on a compressed banking day.
 *
 * <p>The banking day is zero here, so each transition falls due immediately and the real scheduler still performs it.
 * Calling the transition directly would test the arithmetic and skip the wiring that actually moves payouts in the
 * running stack.
 */
@TestPropertySource(properties = {"zs.fakebank.banking-day=0s", "zs.fakebank.lifecycle-interval=100ms"})
class FakeBankLifecycleIT extends FakeProvidersIT {

    private static final Duration PATIENCE = Duration.ofSeconds(20);

    @Test
    @DisplayName("the ok token settles")
    void settles() {
        String reference = submit(MagicTokens.BANK_OK);

        assertThat(awaitStatus(reference, "SETTLED").settled_at()).isNotNull();
        assertThat(eventTypes(reference)).containsExactly("payout.settled");
    }

    @Test
    @DisplayName("the account-closed token fails, and failure is not settlement")
    void fails() {
        String reference = submit(MagicTokens.BANK_FAIL_ACCOUNT_CLOSED);

        PayoutResponse payout = awaitStatus(reference, "FAILED");
        assertThat(payout.return_code()).isEqualTo("account_closed");
        assertThat(payout.settled_at()).isNull();
        assertThat(eventTypes(reference)).containsExactly("payout.failed");
    }

    @Test
    @DisplayName("the R01 token settles first and is returned afterwards, each event exactly once")
    void settlesThenReturns() {
        String reference = submit(MagicTokens.BANK_RETURN_R01);

        PayoutResponse returned = awaitStatus(reference, "RETURNED");
        assertThat(returned.return_code()).isEqualTo("R01");
        // Money that arrived and was then taken back: the ledger has to survive both, so the simulator must not
        // shortcut straight to returned.
        assertThat(returned.settled_at()).isNotNull();
        assertThat(returned.returned_at()).isNotNull();
        assertThat(eventTypes(reference)).containsExactly("payout.settled", "payout.returned");
    }

    private String submit(String token) {
        String reference = UUID.randomUUID().toString();
        post("/fakebank/v1/payouts", new PayoutRequest(reference, token, 25_000L, "USD"), null);
        return reference;
    }

    private PayoutResponse awaitStatus(String clientReference, String expected) {
        Instant deadline = Instant.now().plus(PATIENCE);
        PayoutResponse last = null;
        while (Instant.now().isBefore(deadline)) {
            PayoutResponse[] payouts = get("/fakebank/v1/payouts?client_reference=" + clientReference)
                    .as(PayoutResponse[].class);
            if (payouts.length == 1) {
                last = payouts[0];
                if (expected.equals(last.status())) {
                    return last;
                }
            }
            try {
                Thread.sleep(100);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(interrupted);
            }
        }
        throw new AssertionError("payout for " + clientReference + " never reached " + expected
                + "; last seen: " + (last == null ? "none" : last.status()));
    }

    private List<String> eventTypes(String clientReference) {
        return db.sql("SELECT event_type FROM provider_events WHERE client_reference = ? ORDER BY occurred_at")
                .param(clientReference).query(String.class).list();
    }
}
