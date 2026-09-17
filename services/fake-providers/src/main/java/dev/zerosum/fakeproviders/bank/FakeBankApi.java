package dev.zerosum.fakeproviders.bank;

import java.time.Instant;

/** Wire types for the FakeBank API (master §5.6). */
public final class FakeBankApi {

    private FakeBankApi() {
    }

    public record PayoutRequest(String client_reference, String destination_token, Long amount_minor,
            String currency) {
    }

    public record PayoutResponse(String payout_id, String client_reference, String status, String return_code,
            long amount_minor, String currency, Instant accepted_at, Instant settled_at, Instant returned_at) {
    }
}
