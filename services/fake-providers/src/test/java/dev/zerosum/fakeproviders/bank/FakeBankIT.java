package dev.zerosum.fakeproviders.bank;

import static org.assertj.core.api.Assertions.assertThat;

import dev.zerosum.fakeproviders.bank.FakeBankApi.PayoutRequest;
import dev.zerosum.fakeproviders.bank.FakeBankApi.PayoutResponse;
import dev.zerosum.fakeproviders.shared.MagicTokens;
import dev.zerosum.fakeproviders.support.FakeProvidersIT;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.TestPropertySource;

/**
 * S05-T02 evidence: acceptance semantics and the deliberate absence of idempotency.
 *
 * <p>The banking day is an hour here so a payout stays PENDING for the whole test; the transitions themselves are
 * covered by {@link FakeBankLifecycleIT}.
 */
@TestPropertySource(properties = "zs.fakebank.banking-day=1h")
class FakeBankIT extends FakeProvidersIT {

    @Test
    @DisplayName("a payout is accepted with 202 and pending, never with a settled answer")
    void acceptsAsPending() {
        String reference = UUID.randomUUID().toString();
        Response response = submit(reference, MagicTokens.BANK_OK, 50_000);

        assertThat(response.status()).isEqualTo(202);
        PayoutResponse payout = response.as(PayoutResponse.class);
        assertThat(payout.status()).isEqualTo("PENDING");
        assertThat(payout.payout_id()).startsWith("po_");
        assertThat(payout.settled_at()).isNull();

        // Acceptance is not an outcome, so nothing is emitted yet.
        assertThat(eventCount(reference)).isZero();
    }

    @Test
    @DisplayName("two submissions of one client reference create two payouts, and lookup shows both")
    void duplicatesAreAcceptedDeliberately() {
        String reference = UUID.randomUUID().toString();
        submit(reference, MagicTokens.BANK_OK, 1_000);
        submit(reference, MagicTokens.BANK_OK, 1_000);

        PayoutResponse[] found = get("/fakebank/v1/payouts?client_reference=" + reference)
                .as(PayoutResponse[].class);

        // This is the asymmetry with FakeCard that forces the resolver to look up before resubmitting. A provider
        // that quietly deduplicated here would make a blind retry look safe.
        assertThat(found).hasSize(2);
        assertThat(found[0].payout_id()).isNotEqualTo(found[1].payout_id());
    }

    @Test
    @DisplayName("a payout with no amount is rejected rather than accepted and lost")
    void rejectsInvalidPayout() {
        Response response = post("/fakebank/v1/payouts",
                new PayoutRequest(UUID.randomUUID().toString(), MagicTokens.BANK_OK, null, "USD"), null);

        assertThat(response.status()).isEqualTo(400);
    }

    private Response submit(String reference, String token, long amountMinor) {
        return post("/fakebank/v1/payouts", new PayoutRequest(reference, token, amountMinor, "USD"), null);
    }

    private int eventCount(String clientReference) {
        return db.sql("SELECT count(*) FROM provider_events WHERE client_reference = ?").param(clientReference)
                .query(Integer.class).single();
    }
}
