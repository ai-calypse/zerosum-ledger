package dev.zerosum.fakeproviders.card;

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
}
