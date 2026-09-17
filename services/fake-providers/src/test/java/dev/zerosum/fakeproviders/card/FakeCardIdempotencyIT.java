package dev.zerosum.fakeproviders.card;

import static org.assertj.core.api.Assertions.assertThat;

import dev.zerosum.fakeproviders.card.FakeCardApi.ChargeRequest;
import dev.zerosum.fakeproviders.card.FakeCardApi.ChargeResponse;
import dev.zerosum.fakeproviders.shared.MagicTokens;
import dev.zerosum.fakeproviders.support.FakeProvidersIT;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** S05-T01 evidence: the idempotency guarantee, including under concurrency. */
class FakeCardIdempotencyIT extends FakeProvidersIT {

    /** Recorded in the evidence notes: the burst size the concurrency case runs at. */
    private static final int BURST = 16;

    @Test
    @DisplayName("the same key and body replays the stored response and creates no second charge")
    void sameKeyReplays() {
        String reference = UUID.randomUUID().toString();
        String key = UUID.randomUUID().toString();
        var request = new ChargeRequest(reference, MagicTokens.CARD_OK, 4_200L, "USD");

        Response first = post("/fakecard/v1/charges", request, key);
        Response second = post("/fakecard/v1/charges", request, key);

        assertThat(second.as(ChargeResponse.class).charge_id())
                .isEqualTo(first.as(ChargeResponse.class).charge_id());
        assertThat(second.replayHeader()).isEqualTo("true");
        assertThat(first.replayHeader()).isNull();
        assertThat(chargeCount(reference)).isEqualTo(1);
    }

    @Test
    @DisplayName("the same key with a different body is refused rather than replayed")
    void sameKeyDifferentBodyIsRefused() {
        String key = UUID.randomUUID().toString();
        post("/fakecard/v1/charges",
                new ChargeRequest(UUID.randomUUID().toString(), MagicTokens.CARD_OK, 100L, "USD"), key);

        Response mismatch = post("/fakecard/v1/charges",
                new ChargeRequest(UUID.randomUUID().toString(), MagicTokens.CARD_OK, 999L, "USD"), key);

        // Replaying the first response here would hide the caller's bug behind a success.
        assertThat(mismatch.status()).isEqualTo(422);
    }

    @Test
    @DisplayName("a concurrent burst with one key produces exactly one charge")
    void concurrentBurstChargesOnce() throws Exception {
        String reference = UUID.randomUUID().toString();
        String key = UUID.randomUUID().toString();
        var request = new ChargeRequest(reference, MagicTokens.CARD_OK, 7_500L, "USD");

        List<Future<Response>> results;
        try (ExecutorService pool = Executors.newFixedThreadPool(BURST)) {
            List<Callable<Response>> calls = Collections.nCopies(BURST,
                    () -> post("/fakecard/v1/charges", request, key));
            results = pool.invokeAll(calls);
        }

        for (Future<Response> result : results) {
            assertThat(result.get().status()).isEqualTo(200);
        }
        // The primary key on (provider, idempotency_key) is what makes this one, not a check-then-insert.
        assertThat(chargeCount(reference)).isEqualTo(1);
    }

    private int chargeCount(String clientReference) {
        return db.sql("SELECT count(*) FROM card_charges WHERE client_reference = ?").param(clientReference)
                .query(Integer.class).single();
    }
}
