package dev.zerosum.ledger.changelog;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * The per-entity hash chain (D02-6, should-have S1). One implementation is shared by apply and by verification.
 *
 * <p><strong>Canonical form, version 1</strong> — SHA-256 over, in this order:
 *
 * <ol>
 *   <li>the form version as one byte;</li>
 *   <li>the previous row hash: one byte 0 for the first row of an entity, otherwise one byte 1 followed by its 32 bytes;</li>
 *   <li>the entity ID: 4-byte big-endian length, then UTF-8 bytes;</li>
 *   <li>the sequence number: 8 bytes big-endian;</li>
 *   <li>the order ID: 16 bytes, most significant half first;</li>
 *   <li>the account code: 4-byte length, then UTF-8 bytes;</li>
 *   <li>the currency: 4-byte length, then UTF-8 bytes;</li>
 *   <li>the delta in minor units: 8 bytes big-endian, two's complement;</li>
 *   <li>the balance after the entry: 8 bytes big-endian, two's complement.</li>
 * </ol>
 *
 * <p>The database-assigned {@code recorded_at} is deliberately <em>not</em> hashed: including it would force a read-back
 * inside the apply transaction, and it carries no audit meaning the other fields don't already fix. Lengths are explicit,
 * so no field boundary is ambiguous. S02-T06 freezes this form and pins it with known-answer vectors.
 */
@Component
public class ChangelogHasher {

    /** decision: D02-6 — canonical form version stored per row in {@code entity_changelog.hash_version}. */
    public static final short FORM_VERSION = 1;

    private static final String ALGORITHM = "SHA-256";

    public byte[] hash(byte[] previousHash, String entityId, long seq, UUID orderId, String accountCode, String currency,
            long deltaMinor, long balanceAfterMinor) {
        ByteArrayOutputStream out = new ByteArrayOutputStream(128);
        out.write((byte) FORM_VERSION);
        if (previousHash == null) {
            out.write((byte) 0);
        } else {
            out.write((byte) 1);
            out.writeBytes(previousHash);
        }
        writeString(out, entityId);
        out.writeBytes(longBytes(seq));
        out.writeBytes(ByteBuffer.allocate(16)
                .putLong(orderId.getMostSignificantBits())
                .putLong(orderId.getLeastSignificantBits())
                .array());
        writeString(out, accountCode);
        writeString(out, currency);
        out.writeBytes(longBytes(deltaMinor));
        out.writeBytes(longBytes(balanceAfterMinor));
        try {
            return MessageDigest.getInstance(ALGORITHM).digest(out.toByteArray());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(ALGORITHM + " is required", e);
        }
    }

    private static void writeString(ByteArrayOutputStream out, String value) {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        out.writeBytes(ByteBuffer.allocate(4).putInt(bytes.length).array());
        out.writeBytes(bytes);
    }

    private static byte[] longBytes(long value) {
        return ByteBuffer.allocate(8).putLong(value).array();
    }
}
