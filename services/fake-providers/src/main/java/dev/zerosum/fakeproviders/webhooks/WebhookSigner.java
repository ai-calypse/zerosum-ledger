package dev.zerosum.fakeproviders.webhooks;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * Webhook signing and verification (D05-3, master §5.11 "Authentication", TB2).
 *
 * <p>Signed string: {@code <unix seconds>.<raw body>}, HMAC-SHA256, hex. One header carries both parts:
 *
 * <pre>ZS-Signature: t=1758000000,v1=9f86d081...</pre>
 *
 * <p>The timestamp is inside the signed string, not merely alongside it, so a captured delivery cannot be replayed
 * later by rewriting the header. The raw bytes are signed, never a re-serialised body: two JSON encodings that mean
 * the same thing do not have the same bytes, and a verifier that re-encoded would reject honest deliveries.
 *
 * <p><strong>Rotation.</strong> {@code ZS_WEBHOOK_SECRETS=current,previous} (D00-8, master §5.14). The sender signs
 * with the first secret; a verifier accepts any configured secret, which is what lets a secret be replaced without a
 * synchronised restart of both sides. Every candidate is compared before the result is read, so a verifier does not
 * leak through timing which secret matched.
 */
public final class WebhookSigner {

    public static final String HEADER = "ZS-Signature";

    /** decision: D05-3 — master §5.11 gives webhooks a 300 s tolerance. */
    public static final Duration TOLERANCE = Duration.ofSeconds(300);

    private WebhookSigner() {
    }

    public static String header(Instant timestamp, byte[] body, String secret) {
        long seconds = timestamp.getEpochSecond();
        return "t=" + seconds + ",v1=" + HexFormat.of().formatHex(hmac(secret, signedString(seconds, body)));
    }

    /** True when the header carries a fresh timestamp and a signature made with one of the configured secrets. */
    public static boolean verify(String header, byte[] body, List<String> secrets, Instant now) {
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
                    // An unknown element is ignored rather than rejected, so a future signature version can be added
                    // to the same header without breaking verifiers that only understand v1.
                }
            }
        }
        if (presented == null
                || Duration.between(Instant.ofEpochSecond(seconds), now).abs().compareTo(TOLERANCE) > 0) {
            return false;
        }
        byte[] presentedBytes = presented.getBytes(StandardCharsets.UTF_8);
        byte[] signed = signedString(seconds, body);
        boolean match = false;
        for (String secret : secrets) {
            byte[] expected = HexFormat.of().formatHex(hmac(secret, signed)).getBytes(StandardCharsets.UTF_8);
            match |= MessageDigest.isEqual(presentedBytes, expected);
        }
        return match;
    }

    private static byte[] signedString(long seconds, byte[] body) {
        var buffer = new ByteArrayOutputStream();
        buffer.writeBytes((seconds + ".").getBytes(StandardCharsets.UTF_8));
        buffer.writeBytes(body);
        return buffer.toByteArray();
    }

    private static byte[] hmac(String secret, byte[] payload) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return mac.doFinal(payload);
        } catch (Exception failure) {
            // A signing failure must never degrade into an unsigned delivery.
            throw new IllegalStateException("could not sign the webhook payload", failure);
        }
    }
}
