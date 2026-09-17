package dev.zerosum.fakeproviders.admin;

import dev.zerosum.fakeproviders.bank.FakeBankApi.PayoutResponse;
import dev.zerosum.fakeproviders.card.FakeCardApi.ChargeResponse;
import dev.zerosum.fakeproviders.card.FakeCardApi.RefundResponse;
import dev.zerosum.fakeproviders.faults.FaultLog;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/** Wire types for the admin API (D05-2; §0.3 E2, E3). Snake_case, like the provider APIs. */
public final class AdminApi {

    private AdminApi() {
    }

    /**
     * Provider-side truth: what the simulated providers actually did, what faults were injected into them, and what
     * is still waiting to be delivered. A test asserts against this rather than against its own expectations, which
     * is the only way an assertion can catch the ledger and the provider disagreeing (I7).
     */
    public record Truth(String entity_id, List<ChargeResponse> charges, List<RefundResponse> refunds,
            List<PayoutResponse> payouts, Map<String, Long> fault_counts, List<FaultLog.Entry> faults,
            WebhookQueue webhook_queue) {
    }

    /** Redelivery-queue status (§0.3 E3): what quiesce has to wait for. */
    public record WebhookQueue(int pending, List<Delivery> deliveries) {
    }

    public record Delivery(String event_id, String provider, String event_type, String provider_ref, int attempts,
            Integer last_status, boolean held, Instant next_attempt_at) {
    }
}
