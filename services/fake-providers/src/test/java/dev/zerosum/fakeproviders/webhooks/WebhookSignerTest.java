package dev.zerosum.fakeproviders.webhooks;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * D05-3 evidence: what the signature does and does not accept.
 *
 * <p>A unit test rather than only an integration one, because these are the cases a receiver has to get right and
 * they are cheap to state here: the same helper verifies deliveries in {@link WebhookSenderIT}.
 */
class WebhookSignerTest {

    private static final String CURRENT = "current-secret";
    private static final String PREVIOUS = "previous-secret";
    private static final byte[] BODY = "{\"event_id\":\"evt_1\",\"amount_minor\":100}".getBytes(StandardCharsets.UTF_8);

    /**
     * The shared vector (D05-3). {@code WebhookSignatureTest} in instrument-service asserts the same hex over the
     * same bytes with the same secret; the two implementations are deliberately separate — an adapter implements
     * someone else's format rather than importing it — so this pair of tests is what keeps them from drifting.
     */
    @Test
    @DisplayName("the shared vector signs to the hex the receiver expects")
    void sharedVectorMatchesTheReceiver() {
        byte[] body = "{\"event_id\":\"evt_vector\",\"provider\":\"fakecard\",\"amount_minor\":4200}"
                .getBytes(StandardCharsets.UTF_8);

        assertThat(WebhookSigner.header(Instant.ofEpochSecond(1_758_000_000L), body, "zs-test-webhook-secret"))
                .isEqualTo("t=1758000000,v1=7b6b4a91608bea3e2a83e8ad32d98425e7e62d649195b98f925aa065d7ab33b9");
    }

    @Test
    @DisplayName("a signature made with the current secret verifies")
    void verifiesWhatItSigned() {
        Instant now = Instant.now();

        String header = WebhookSigner.header(now, BODY, CURRENT);

        assertThat(WebhookSigner.verify(header, BODY, List.of(CURRENT), now)).isTrue();
    }

    @Test
    @DisplayName("a tampered body fails, even by one digit")
    void tamperedBodyFails() {
        Instant now = Instant.now();
        String header = WebhookSigner.header(now, BODY, CURRENT);

        byte[] tampered = "{\"event_id\":\"evt_1\",\"amount_minor\":900}".getBytes(StandardCharsets.UTF_8);

        assertThat(WebhookSigner.verify(header, tampered, List.of(CURRENT), now)).isFalse();
    }

    @Test
    @DisplayName("during rotation both the current and the previous secret verify")
    void rotationAcceptsEitherSecret() {
        Instant now = Instant.now();

        // What makes a rotation possible without restarting both sides at the same instant.
        assertThat(WebhookSigner.verify(WebhookSigner.header(now, BODY, CURRENT), BODY,
                List.of(CURRENT, PREVIOUS), now)).isTrue();
        assertThat(WebhookSigner.verify(WebhookSigner.header(now, BODY, PREVIOUS), BODY,
                List.of(CURRENT, PREVIOUS), now)).isTrue();
    }

    @Test
    @DisplayName("a wrong secret fails, and so does a signature with no configured secret at all")
    void wrongSecretFails() {
        Instant now = Instant.now();
        String header = WebhookSigner.header(now, BODY, CURRENT);

        assertThat(WebhookSigner.verify(header, BODY, List.of("someone-elses-secret"), now)).isFalse();
        assertThat(WebhookSigner.verify(header, BODY, List.of(), now)).isFalse();
    }

    @Test
    @DisplayName("a delivery captured today cannot be replayed tomorrow: the timestamp is inside the signature")
    void staleTimestampFails() {
        Instant signedAt = Instant.now().minus(Duration.ofHours(1));
        String header = WebhookSigner.header(signedAt, BODY, CURRENT);

        assertThat(WebhookSigner.verify(header, BODY, List.of(CURRENT), Instant.now())).isFalse();
        // Still valid inside the 300 s tolerance, so ordinary clock skew is not treated as an attack.
        assertThat(WebhookSigner.verify(header, BODY, List.of(CURRENT), signedAt.plusSeconds(120))).isTrue();
    }

    @Test
    @DisplayName("a malformed or absent header is refused rather than parsed optimistically")
    void malformedHeaderFails() {
        assertThat(WebhookSigner.verify(null, BODY, List.of(CURRENT), Instant.now())).isFalse();
        assertThat(WebhookSigner.verify("nonsense", BODY, List.of(CURRENT), Instant.now())).isFalse();
        assertThat(WebhookSigner.verify("t=notanumber,v1=abcd", BODY, List.of(CURRENT), Instant.now())).isFalse();
        // A timestamp with no signature must never be treated as signed.
        assertThat(WebhookSigner.verify("t=" + Instant.now().getEpochSecond(), BODY, List.of(CURRENT), Instant.now()))
                .isFalse();
    }
}
