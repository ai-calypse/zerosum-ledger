package dev.zerosum.instrument.adapter;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * S05-T11 evidence: what the receiver's signature check accepts and refuses (D05-3, TB2, M9(a)).
 *
 * <p>These are the cases that decide whether a forged or replayed webhook can move money, so they are stated here as
 * cheap unit tests rather than only inside an integration run.
 */
class WebhookSignatureTest {

    private static final String CURRENT = "current-secret";
    private static final String PREVIOUS = "previous-secret";
    private static final byte[] BODY = "{\"event_id\":\"evt_1\",\"amount_minor\":100}".getBytes(StandardCharsets.UTF_8);

    /**
     * The shared vector. {@code WebhookSignerTest} in fake-providers asserts the same hex over the same bytes with
     * the same secret, so if either side's signed string, algorithm or encoding drifts, one of the two fails.
     *
     * <p>That is what keeps the two implementations honest without instrument-service depending on the simulator's
     * classes — which it must not, because an adapter implements someone else's format rather than sharing ours.
     */
    static final String VECTOR_SECRET = "zs-test-webhook-secret";
    static final long VECTOR_TIMESTAMP = 1_758_000_000L;
    static final String VECTOR_BODY = "{\"event_id\":\"evt_vector\",\"provider\":\"fakecard\",\"amount_minor\":4200}";
    static final String VECTOR_SIGNATURE = "7b6b4a91608bea3e2a83e8ad32d98425e7e62d649195b98f925aa065d7ab33b9";

    @Test
    @DisplayName("the shared vector verifies, byte for byte, hex for hex")
    void sharedVectorVerifies() {
        byte[] body = VECTOR_BODY.getBytes(StandardCharsets.UTF_8);
        String header = "t=" + VECTOR_TIMESTAMP + ",v1=" + VECTOR_SIGNATURE;

        assertThat(ProviderWebhooks.verify(header, body, List.of(VECTOR_SECRET),
                Instant.ofEpochSecond(VECTOR_TIMESTAMP))).isTrue();
        // And our own signer reproduces it, so the test vector and the code agree in both directions.
        assertThat(TestWebhookSigner.header(Instant.ofEpochSecond(VECTOR_TIMESTAMP), body, VECTOR_SECRET))
                .isEqualTo(header);
    }

    @Test
    @DisplayName("a signature made with the current secret verifies")
    void verifiesWhatWasSigned() {
        Instant now = Instant.now();

        assertThat(ProviderWebhooks.verify(TestWebhookSigner.header(now, BODY, CURRENT), BODY, List.of(CURRENT), now))
                .isTrue();
    }

    @Test
    @DisplayName("a tampered body fails, even by one digit")
    void tamperedBodyFails() {
        Instant now = Instant.now();
        String header = TestWebhookSigner.header(now, BODY, CURRENT);

        byte[] tampered = "{\"event_id\":\"evt_1\",\"amount_minor\":900}".getBytes(StandardCharsets.UTF_8);

        assertThat(ProviderWebhooks.verify(header, tampered, List.of(CURRENT), now)).isFalse();
    }

    @Test
    @DisplayName("during rotation both the current and the previous secret verify")
    void rotationAcceptsEitherSecret() {
        Instant now = Instant.now();

        assertThat(ProviderWebhooks.verify(TestWebhookSigner.header(now, BODY, CURRENT), BODY,
                List.of(CURRENT, PREVIOUS), now)).isTrue();
        assertThat(ProviderWebhooks.verify(TestWebhookSigner.header(now, BODY, PREVIOUS), BODY,
                List.of(CURRENT, PREVIOUS), now)).isTrue();
    }

    @Test
    @DisplayName("a wrong secret fails, and a service with no secret configured accepts nothing")
    void wrongOrAbsentSecretFails() {
        Instant now = Instant.now();
        String header = TestWebhookSigner.header(now, BODY, CURRENT);

        assertThat(ProviderWebhooks.verify(header, BODY, List.of("someone-elses-secret"), now)).isFalse();
        // Fails closed: an unconfigured receiver refuses every webhook rather than accepting every webhook.
        assertThat(ProviderWebhooks.verify(header, BODY, List.of(), now)).isFalse();
    }

    @Test
    @DisplayName("a timestamp outside the 300 s tolerance fails in either direction")
    void staleAndFutureTimestampsFail() {
        Instant signedAt = Instant.now();
        String header = TestWebhookSigner.header(signedAt, BODY, CURRENT);

        // Captured today, replayed tomorrow: the timestamp is inside the signature, so it cannot be rewritten.
        assertThat(ProviderWebhooks.verify(header, BODY, List.of(CURRENT), signedAt.plus(Duration.ofSeconds(301))))
                .isFalse();
        // A clock far in the future is not a licence for an unbounded replay window either.
        assertThat(ProviderWebhooks.verify(header, BODY, List.of(CURRENT), signedAt.minus(Duration.ofSeconds(301))))
                .isFalse();
        // Ordinary skew inside the tolerance is not treated as an attack.
        assertThat(ProviderWebhooks.verify(header, BODY, List.of(CURRENT), signedAt.plusSeconds(120))).isTrue();
    }

    @Test
    @DisplayName("a missing or malformed header is refused rather than parsed optimistically")
    void malformedHeaderFails() {
        Instant now = Instant.now();

        assertThat(ProviderWebhooks.verify(null, BODY, List.of(CURRENT), now)).isFalse();
        assertThat(ProviderWebhooks.verify("nonsense", BODY, List.of(CURRENT), now)).isFalse();
        assertThat(ProviderWebhooks.verify("t=notanumber,v1=abcd", BODY, List.of(CURRENT), now)).isFalse();
        // A timestamp with no signature at all must never count as signed.
        assertThat(ProviderWebhooks.verify("t=" + now.getEpochSecond(), BODY, List.of(CURRENT), now)).isFalse();
    }
}
