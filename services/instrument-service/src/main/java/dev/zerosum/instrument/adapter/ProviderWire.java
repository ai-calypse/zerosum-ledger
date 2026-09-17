package dev.zerosum.instrument.adapter;

/**
 * The provider payloads, as they appear on the wire (D05-1).
 *
 * <p>Declared here rather than imported from fake-providers on purpose: an adapter's job is to translate someone
 * else's format. Sharing the type would make our own refactor able to change what we believe the provider said, and
 * a real provider would never ship us its classes.
 */
final class ProviderWire {

    private ProviderWire() {
    }

    record ChargeRequest(String client_reference, String instrument_token, long amount_minor, String currency) {
    }

    record ChargeResponse(String charge_id, String client_reference, String status, String decline_code,
            long amount_minor, String currency, long fee_minor, long refunded_minor) {
    }

    record RefundRequest(String charge_id, String client_reference, long amount_minor) {
    }

    record RefundResponse(String refund_id, String charge_id, String client_reference, String status,
            String failure_code, long amount_minor) {
    }

    record PayoutRequest(String client_reference, String destination_token, long amount_minor, String currency) {
    }

    record PayoutResponse(String payout_id, String client_reference, String status, String return_code,
            long amount_minor, String currency) {
    }

    record SettlementReportResponse(String report_id, String provider, String report_date,
            java.util.List<SettlementLineResponse> lines, java.util.List<SettlementTotalsResponse> totals,
            String content_hash) {
    }

    record SettlementLineResponse(String provider_ref, String client_reference, String kind, String currency,
            long gross_minor, long fee_minor) {
    }

    record SettlementTotalsResponse(String currency, long gross_minor, long fee_minor, long net_minor) {
    }
}
