package dev.zerosum.fakeproviders.card;

import java.util.List;

/** Wire types for the FakeCard API (master §5.6). Amounts are minor units, as every provider API states them. */
public final class FakeCardApi {

    private FakeCardApi() {
    }

    public record ChargeRequest(String client_reference, String instrument_token, Long amount_minor, String currency) {
    }

    public record ChargeResponse(String charge_id, String client_reference, String status, String decline_code,
            long amount_minor, String currency, long fee_minor, long refunded_minor) {
    }

    public record RefundRequest(String charge_id, String client_reference, Long amount_minor) {
    }

    public record RefundResponse(String refund_id, String charge_id, String client_reference, String status,
            String failure_code, long amount_minor) {
    }

    /**
     * One settled day (D06-1, master §5.9 "Settlement").
     *
     * <p>Totals are derived from the lines that are actually served, so a report stays arithmetically consistent even
     * after a discrepancy knob has corrupted it. That is deliberate: a discrepancy must be visible only by comparing
     * the report against our own attempts, which is what forces the reconciler to do real matching instead of
     * checking the provider's own addition.
     */
    public record SettlementReportResponse(String report_id, String provider, String report_date,
            List<SettlementLineResponse> lines, List<SettlementTotalsResponse> totals, String content_hash) {
    }

    /** @param gross_minor negative on a refund line: a refund reduces the day's gross and returns no fee (master §5.9) */
    public record SettlementLineResponse(String provider_ref, String client_reference, String kind, String currency,
            long gross_minor, long fee_minor) {
    }

    public record SettlementTotalsResponse(String currency, long gross_minor, long fee_minor, long net_minor) {
    }
}
