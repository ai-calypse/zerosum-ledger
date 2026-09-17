package dev.zerosum.instrument.adapter;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.HexFormat;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * Signs a webhook the way fake-providers does, for tests on this side of the boundary (D05-3).
 *
 * <p>Test scope on purpose: instrument-service never signs a webhook in production, and shipping a signer it does
 * not use would be an unused key-handling path in a money service. Verification is the only half that belongs in
 * {@code main}.
 */
public final class TestWebhookSigner {

    private TestWebhookSigner() {
    }

    public static String header(Instant timestamp, byte[] body, String secret) {
        long seconds = timestamp.getEpochSecond();
        byte[] prefix = (seconds + ".").getBytes(StandardCharsets.UTF_8);
        byte[] signed = new byte[prefix.length + body.length];
        System.arraycopy(prefix, 0, signed, 0, prefix.length);
        System.arraycopy(body, 0, signed, prefix.length, body.length);
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return "t=" + seconds + ",v1=" + HexFormat.of().formatHex(mac.doFinal(signed));
        } catch (Exception failure) {
            throw new IllegalStateException(failure);
        }
    }
}
