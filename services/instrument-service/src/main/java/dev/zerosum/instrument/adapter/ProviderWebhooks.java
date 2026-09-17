// decision: D05-3 — docs/step_05_instruments_fake_providers.md#decisions-and-outputs
package dev.zerosum.instrument.adapter;

import dev.zerosum.instrument.core.InstrumentExceptions.InvalidSignatureException;
import dev.zerosum.instrument.core.ProviderEvent;
import dev.zerosum.instrument.core.ProviderId;
import dev.zerosum.instrument.core.ProviderStatus;
import dev.zerosum.instrument.core.WebhookRequest;
import dev.zerosum.money.Money;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import tools.jackson.databind.JsonNode;

/**
 * Verifies and normalizes an inbound provider webhook (D05-3, TB2, master §5.11).
 *
 * <p>Both adapters share this because both providers are reached the same way; the provider-specific part is the
 * event vocabulary, and that is a switch below rather than two copies of an HMAC.
 *
 * <p><strong>Deliberately not the sender's class.</strong> fake-providers has its own {@code WebhookSigner} and this
 * service must not depend on it: an adapter's job is to implement someone else's format, and sharing the type would
 * let a refactor on our side silently change what we believe the provider signed. The two are kept honest by a shared
 * test vector — {@code WebhookSignatureTest} here and {@code WebhookSignerTest} there assert the same hex over the
 * same bytes, so format drift fails a test rather than a delivery.
 *
 * <p>The signature covers {@code <unix seconds>.<raw body>}. The raw bytes are verified, never a re-serialized body:
 * two JSON encodings that mean the same thing do not have the same bytes, so a verifier that re-encoded would reject
 * honest deliveries. The timestamp is inside the signed string, so a captured delivery cannot be replayed later by
 * rewriting the header.
 */
final class ProviderWebhooks {

    static final String SIGNATURE_HEADER = "ZS-Signature";

    /** decision: D05-3 — master §5.11 and TB2 give webhooks a 300 s tolerance. */
    static final Duration TOLERANCE = Duration.ofSeconds(300);

    private ProviderWebhooks() {
    }

    /**
     * @throws InvalidSignatureException if the signature or its timestamp does not verify; nothing is parsed
     * @throws IllegalArgumentException  if the body is not the payload this provider sends
     */
    static ProviderEvent parse(WebhookRequest request, ProviderId provider, List<String> secrets, Instant now) {
        if (!verify(request.header(SIGNATURE_HEADER), request.body(), secrets, now)) {
            // No detail about which check failed: a verifier that says "stale" versus "wrong secret" tells a forger
            // which half to fix.
            throw new InvalidSignatureException("the " + SIGNATURE_HEADER + " header did not verify");
        }
        JsonNode payload = json(request.body());
        String declaredProvider = text(payload, "provider");
        if (!provider.id().equals(declaredProvider)) {
            // A fakecard event delivered to the fakebank path would otherwise be parsed with the wrong vocabulary.
            throw new IllegalArgumentException(
                    "a " + declaredProvider + " event was delivered to the " + provider + " endpoint");
        }
        String failureCode = payload.path("failure_code").isNull() ? null : payload.path("failure_code").asString(null);
        return new ProviderEvent(provider, text(payload, "event_id"), text(payload, "provider_ref"),
                text(payload, "client_reference"), status(text(payload, "event_type")),
                Money.of(payload.path("amount_minor").asLong(), text(payload, "currency")),
                Instant.parse(text(payload, "occurred_at")), Optional.ofNullable(failureCode));
    }

    /** True when the header carries a fresh timestamp and a signature made with one of the configured secrets. */
    static boolean verify(String header, byte[] body, List<String> secrets, Instant now) {
        if (header == null || secrets.isEmpty()) {
            return false;
        }
        long seconds = 0;
        String presented = null;
        for (String part : header.split(",")) {
            String[] pair = part.strip().split("=", 2);
            if (pair.length != 2) {
                return false;
            }
            switch (pair[0]) {
                case "t" -> {
                    try {
                        seconds = Long.parseLong(pair[1]);
                    } catch (NumberFormatException malformed) {
                        return false;
                    }
                }
                case "v1" -> presented = pair[1];
                default -> {
                    // Ignored rather than refused, so a future signature version can share the header.
                }
            }
        }
        // abs(): a timestamp far in the future is as unacceptable as one far in the past, and only the absolute
        // difference makes a clock the sender controls unable to buy itself an unbounded replay window.
        if (presented == null
                || Duration.between(Instant.ofEpochSecond(seconds), now).abs().compareTo(TOLERANCE) > 0) {
            return false;
        }
        byte[] presentedBytes = presented.getBytes(StandardCharsets.UTF_8);
        byte[] signed = signedString(seconds, body);
        boolean match = false;
        // Every candidate is compared and the result read only afterwards, so neither the comparison nor which
        // secret matched is visible in the time taken.
        for (String secret : secrets) {
            byte[] expected = HexFormat.of().formatHex(hmac(secret, signed)).getBytes(StandardCharsets.UTF_8);
            match |= MessageDigest.isEqual(presentedBytes, expected);
        }
        return match;
    }

    /** The provider event vocabulary both fake providers emit (D05-2), mapped onto the normalized statuses. */
    private static ProviderStatus status(String eventType) {
        return switch (eventType) {
            case "charge.succeeded", "refund.succeeded" -> ProviderStatus.SUCCEEDED;
            case "charge.declined" -> ProviderStatus.DECLINED;
            case "refund.failed", "payout.failed" -> ProviderStatus.FAILED;
            case "payout.settled" -> ProviderStatus.SETTLED;
            case "payout.returned" -> ProviderStatus.RETURNED;
            // Refused rather than ignored: an event type we have no meaning for must not be acknowledged as applied.
            default -> throw new IllegalArgumentException("unmapped provider event type: " + eventType);
        };
    }

    private static JsonNode json(byte[] body) {
        try {
            return ProviderHttp.JSON.readTree(body);
        } catch (RuntimeException malformed) {
            // The message carries no payload: a body that failed to bind may still be something a forger sent.
            throw new IllegalArgumentException("the webhook body is not JSON this provider sends");
        }
    }

    private static String text(JsonNode payload, String field) {
        String value = payload.path(field).asString(null);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("the webhook body has no " + field);
        }
        return value;
    }

    private static byte[] signedString(long seconds, byte[] body) {
        byte[] prefix = (seconds + ".").getBytes(StandardCharsets.UTF_8);
        byte[] signed = new byte[prefix.length + body.length];
        System.arraycopy(prefix, 0, signed, 0, prefix.length);
        System.arraycopy(body, 0, signed, prefix.length, body.length);
        return signed;
    }

    private static byte[] hmac(String secret, byte[] payload) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return mac.doFinal(payload);
        } catch (Exception failure) {
            throw new IllegalStateException("could not compute the webhook signature", failure);
        }
    }
}
