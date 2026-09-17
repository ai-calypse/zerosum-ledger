package dev.zerosum.fakeproviders.card;

import static org.assertj.core.api.Assertions.assertThat;

import dev.zerosum.fakeproviders.card.FakeCardApi.ChargeRequest;
import dev.zerosum.fakeproviders.card.FakeCardApi.ChargeResponse;
import dev.zerosum.fakeproviders.card.FakeCardApi.RefundRequest;
import dev.zerosum.fakeproviders.card.FakeCardApi.RefundResponse;
import dev.zerosum.fakeproviders.card.FakeCardApi.SettlementLineResponse;
import dev.zerosum.fakeproviders.card.FakeCardApi.SettlementReportResponse;
import dev.zerosum.fakeproviders.shared.MagicTokens;
import dev.zerosum.fakeproviders.support.FakeProvidersIT;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * S06-T01 evidence: the report FakeCard serves for a closed day (D06-1).
 *
 * <p>Days are closed by backdating the provider's own rows rather than by waiting for midnight or compressing a
 * clock. The report is derived from {@code created_at}, so moving a charge into the past is exactly what the passage
 * of a day would have done, and the test stays deterministic.
 */
class SettlementReportIT extends FakeProvidersIT {

    /**
     * Days that have already closed. They must be in the past relative to the real clock: a day is closed only once
     * its cutoff has passed, so a "future" fixture date is correctly refused as not ready and tests nothing.
     */
    private static final LocalDate CLOSED_DAY = LocalDate.of(2026, 3, 4);
    private static final LocalDate STABLE_DAY = LocalDate.of(2026, 3, 5);
    private static final LocalDate EMPTY_DAY = LocalDate.of(2026, 3, 6);
    private static final LocalDate DECLINED_DAY = LocalDate.of(2026, 3, 7);

    @Test
    @DisplayName("a closed day reports one line per capture and per refund, with totals derived from them")
    void reportsLinesAndTotals() {
        LocalDate day = CLOSED_DAY;
        String reference = UUID.randomUUID().toString();
        ChargeResponse charge = charge(reference, 10_000);
        RefundResponse refund = refund(charge.charge_id(), reference, 2_500);
        backdateCharge(charge.charge_id(), day);
        backdateRefund(refund.refund_id(), day);

        SettlementReportResponse report = report(day);

        assertThat(report.report_id()).isEqualTo("rpt_2026_03_04");
        assertThat(report.lines()).hasSize(2);

        SettlementLineResponse capture = line(report, charge.charge_id());
        assertThat(capture.kind()).isEqualTo("CHARGE");
        assertThat(capture.client_reference()).isEqualTo(reference);
        assertThat(capture.gross_minor()).isEqualTo(10_000);
        // 290 bps of 10000 plus the 30 fixed minor units (D01-3), as the capture recorded it.
        assertThat(capture.fee_minor()).isEqualTo(charge.fee_minor());

        SettlementLineResponse returned = line(report, refund.refund_id());
        assertThat(returned.kind()).isEqualTo("REFUND");
        // A refund reduces the day's gross and returns no fee (master §5.9).
        assertThat(returned.gross_minor()).isEqualTo(-2_500);
        assertThat(returned.fee_minor()).isZero();

        assertThat(report.totals()).hasSize(1);
        var totals = report.totals().getFirst();
        assertThat(totals.currency()).isEqualTo("USD");
        assertThat(totals.gross_minor()).isEqualTo(10_000 - 2_500);
        assertThat(totals.fee_minor()).isEqualTo(charge.fee_minor());
        assertThat(totals.net_minor()).isEqualTo(totals.gross_minor() - totals.fee_minor());
    }

    @Test
    @DisplayName("a report is generated once and then frozen: the same bytes and the same content hash")
    void reportIsStableAcrossFetches() {
        LocalDate day = STABLE_DAY;
        String reference = UUID.randomUUID().toString();
        backdateCharge(charge(reference, 4_000).charge_id(), day);

        String first = get("/fakecard/v1/settlement-reports/" + day).body();
        // A charge that lands after the report was generated must not change it: the day is already settled.
        backdateCharge(charge(UUID.randomUUID().toString(), 7_777).charge_id(), day);
        String second = get("/fakecard/v1/settlement-reports/" + day).body();

        assertThat(second).isEqualTo(first);
        assertThat(report(day).content_hash()).isEqualTo(report(day).content_hash());
    }

    @Test
    @DisplayName("an open day is refused, never served as an empty report")
    void openDayIsNotReady() {
        // Today has not closed. An empty report would be indistinguishable from a day on which nothing happened, and
        // a reconciler would book a settlement of zero against captures that are still arriving.
        var response = get("/fakecard/v1/settlement-reports/" + LocalDate.now());

        // The status is the contract: the FakeCard adapter maps 409 to ReportNotReadyException, which is what keeps
        // "still open" from being mistaken for "nothing settled". The reason string stays server-side, because this
        // simulator serves Spring's default error body rather than problem+json.
        assertThat(response.status()).isEqualTo(409);
    }

    @Test
    @DisplayName("a closed day with no activity is a valid report with zero lines")
    void emptyClosedDayStillSettles() {
        SettlementReportResponse report = report(EMPTY_DAY);

        // The cycle still closes: nothing settled, which is a fact rather than an error.
        assertThat(report.lines()).isEmpty();
        assertThat(report.totals()).isEmpty();
    }

    @Test
    @DisplayName("a declined charge never reaches the report, because no money was captured")
    void declinedChargesAreNotSettled() {
        LocalDate day = DECLINED_DAY;
        String reference = UUID.randomUUID().toString();
        var declined = post("/fakecard/v1/charges",
                new ChargeRequest(reference, MagicTokens.CARD_DECLINE_INSUFFICIENT_FUNDS, 900L, "USD"), null)
                .as(ChargeResponse.class);
        backdateCharge(declined.charge_id(), day);

        assertThat(report(day).lines()).isEmpty();
    }

    private SettlementReportResponse report(LocalDate day) {
        return get("/fakecard/v1/settlement-reports/" + day).as(SettlementReportResponse.class);
    }

    private static SettlementLineResponse line(SettlementReportResponse report, String providerRef) {
        return report.lines().stream()
                .filter(line -> line.provider_ref().equals(providerRef))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no line for " + providerRef + " in " + report.lines()));
    }

    private ChargeResponse charge(String reference, long amountMinor) {
        return post("/fakecard/v1/charges", new ChargeRequest(reference, MagicTokens.CARD_OK, amountMinor, "USD"),
                null).as(ChargeResponse.class);
    }

    private RefundResponse refund(String chargeId, String reference, long amountMinor) {
        return post("/fakecard/v1/refunds", new RefundRequest(chargeId, reference, amountMinor), null)
                .as(RefundResponse.class);
    }

    /** Moves a capture into a past day, which is what the passage of that day would have done. */
    private void backdateCharge(String chargeId, LocalDate day) {
        db.sql("UPDATE card_charges SET created_at = ? WHERE charge_id = ?")
                .params(Timestamp.valueOf(day.atTime(9, 30)), chargeId)
                .update();
    }

    private void backdateRefund(String refundId, LocalDate day) {
        db.sql("UPDATE card_refunds SET created_at = ? WHERE refund_id = ?")
                .params(Timestamp.valueOf(day.atTime(11, 15)), refundId)
                .update();
    }
}
