package dev.zerosum.fakeproviders.card;

import static org.assertj.core.api.Assertions.assertThat;

import dev.zerosum.fakeproviders.card.FakeCardApi.ChargeRequest;
import dev.zerosum.fakeproviders.card.FakeCardApi.ChargeResponse;
import dev.zerosum.fakeproviders.card.FakeCardApi.RefundRequest;
import dev.zerosum.fakeproviders.card.FakeCardApi.RefundResponse;
import dev.zerosum.fakeproviders.shared.MagicTokens;
import dev.zerosum.fakeproviders.support.FakeProvidersIT;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** S05-T01 evidence: charge outcomes, persistence, fees, and the refund ceiling. */
class FakeCardIT extends FakeProvidersIT {

    @Test
    @DisplayName("the ok token captures, records the row, and charges the simulated fee")
    void chargeSucceeds() {
        String reference = UUID.randomUUID().toString();
        ChargeResponse charge = charge(reference, MagicTokens.CARD_OK, 10_000);

        assertThat(charge.status()).isEqualTo("SUCCEEDED");
        assertThat(charge.charge_id()).startsWith("ch_");
        // 290 bps of 10000 = 290, plus the 30 fixed minor units (D01-3).
        assertThat(charge.fee_minor()).isEqualTo(320);

        assertThat(db.sql("SELECT status FROM card_charges WHERE charge_id = ?").param(charge.charge_id())
                .query(String.class).single()).isEqualTo("SUCCEEDED");
        assertThat(eventTypes(reference)).containsExactly("charge.succeeded");
    }

    @Test
    @DisplayName("the decline token is refused, collects no fee, and is still recorded")
    void chargeDeclines() {
        String reference = UUID.randomUUID().toString();
        ChargeResponse charge = charge(reference, MagicTokens.CARD_DECLINE_INSUFFICIENT_FUNDS, 2_500);

        assertThat(charge.status()).isEqualTo("DECLINED");
        assertThat(charge.decline_code()).isEqualTo("insufficient_funds");
        // Nothing was captured, so nothing is owed in fees.
        assertThat(charge.fee_minor()).isZero();
        assertThat(eventTypes(reference)).containsExactly("charge.declined");
    }

    @Test
    @DisplayName("the processing-error token declines deterministically rather than failing at random")
    void processingErrorDeclines() {
        String reference = UUID.randomUUID().toString();
        ChargeResponse charge = charge(reference, MagicTokens.CARD_PROCESSING_ERROR, 500);

        assertThat(charge.status()).isEqualTo("DECLINED");
        assertThat(charge.decline_code()).isEqualTo("processing_error");
    }

    @Test
    @DisplayName("partial refunds are allowed up to the captured amount, and the next one fails")
    void refundsAreCappedAtTheCapture() {
        String reference = UUID.randomUUID().toString();
        ChargeResponse charge = charge(reference, MagicTokens.CARD_OK, 1_000);

        assertThat(refund(charge.charge_id(), reference, 600).status()).isEqualTo("SUCCEEDED");
        assertThat(refund(charge.charge_id(), reference, 400).status()).isEqualTo("SUCCEEDED");

        RefundResponse overdrawn = refund(charge.charge_id(), reference, 1);
        assertThat(overdrawn.status()).isEqualTo("FAILED");
        assertThat(overdrawn.failure_code()).isEqualTo("amount_exceeds_capture");

        assertThat(db.sql("SELECT refunded_minor FROM card_charges WHERE charge_id = ?").param(charge.charge_id())
                .query(Long.class).single()).isEqualTo(1_000L);
    }

    @Test
    @DisplayName("a refund is refused for a charge that was never captured")
    void declinedChargeCannotBeRefunded() {
        String reference = UUID.randomUUID().toString();
        ChargeResponse declined = charge(reference, MagicTokens.CARD_DECLINE_INSUFFICIENT_FUNDS, 900);

        RefundResponse refund = refund(declined.charge_id(), reference, 900);

        assertThat(refund.status()).isEqualTo("FAILED");
        assertThat(refund.failure_code()).isEqualTo("charge_not_captured");
    }

    @Test
    @DisplayName("lookup by client reference returns every matching charge")
    void lookupByClientReference() {
        String reference = UUID.randomUUID().toString();
        charge(reference, MagicTokens.CARD_OK, 100);

        ChargeResponse[] found = get("/fakecard/v1/charges?client_reference=" + reference)
                .as(ChargeResponse[].class);

        assertThat(found).hasSize(1);
        assertThat(found[0].client_reference()).isEqualTo(reference);
    }

    @Test
    @DisplayName("an unknown charge id is a 404, not an empty success")
    void unknownChargeIsNotFound() {
        assertThat(get("/fakecard/v1/charges/ch_missing").status()).isEqualTo(404);
    }

    private ChargeResponse charge(String reference, String token, long amountMinor) {
        return post("/fakecard/v1/charges", new ChargeRequest(reference, token, amountMinor, "USD"), null)
                .as(ChargeResponse.class);
    }

    private RefundResponse refund(String chargeId, String reference, long amountMinor) {
        return post("/fakecard/v1/refunds", new RefundRequest(chargeId, reference, amountMinor), null)
                .as(RefundResponse.class);
    }

    private List<String> eventTypes(String clientReference) {
        return db.sql("SELECT event_type FROM provider_events WHERE client_reference = ? ORDER BY occurred_at")
                .param(clientReference).query(String.class).list();
    }
}
