package dev.zerosum.order.order;

import dev.zerosum.money.OrderCandidate;
import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Hashes the canonical form of a create-order request (D03-3), which decides whether a repeated idempotency key is a
 * replay or a key reuse.
 *
 * <p><strong>Canonical form, version 1</strong> — SHA-256 over, in this fixed order: the form version as one byte; the
 * source system; the idempotency key; the type; the reason; the order group; a presence byte for the adjusted order,
 * followed by its 16 bytes; the effective time as 8 bytes of epoch seconds and 4 bytes of nanos; the entry count as 4
 * bytes, then each entry in order as entity ID, account, currency and an 8-byte signed amount; and finally the metadata
 * as canonical JSON. Every string is length-prefixed in UTF-8, so no field boundary is ambiguous.
 *
 * <p>The form is built from the <em>parsed</em> request, never from raw bytes, which is what makes reordered JSON keys
 * and whitespace irrelevant. Absent and null metadata both normalize to <code>{}</code>. Entry order is significant,
 * because line numbers are part of the order. Server-assigned fields are never hashed.
 *
 * <p>This is a binary form rather than RFC 8785 JSON canonicalization. It satisfies the same requirements — sorted
 * object keys, one representation for absent and null, integers without a fractional part — with no ambiguity about
 * JSON escaping, and it needs no new dependency, so D00-1's pins are untouched. It follows the D02-6 hasher's approach,
 * which is already proven in this repository against an independent implementation.
 *
 * <p>Keys never expire (M3 (e)), so this form can never change in place: a later change needs a new version, and a
 * stored row is always compared using the version it was written under.
 */
@Component
public class RequestHasher {

    /** decision: D03-3 — canonical form version, stored per row in {@code money_orders.request_hash_version}. */
    public static final short FORM_VERSION = 1;

    private static final String ALGORITHM = "SHA-256";
    private static final JsonMapper JSON = JsonMapper.builder().build();

    public byte[] hash(NewOrder order) {
        try {
            return MessageDigest.getInstance(ALGORITHM).digest(canonicalBytes(order, FORM_VERSION));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(ALGORITHM + " is required", e);
        }
    }

    /**
     * The canonical bytes that are hashed, with the form version as the first byte. Package-private so the pinned
     * vectors can assert on the form itself rather than only on digests.
     */
    static byte[] canonicalBytes(NewOrder order, short formVersion) {
        ByteArrayOutputStream out = new ByteArrayOutputStream(256);
        out.write((byte) formVersion);
        writeString(out, order.sourceSystem());
        writeString(out, order.idempotencyKey());
        writeString(out, order.type());
        writeString(out, order.reason());
        writeString(out, order.orderGroupId());

        if (order.adjustsOrderId() == null) {
            out.write((byte) 0);
        } else {
            out.write((byte) 1);
            out.writeBytes(ByteBuffer.allocate(16)
                    .putLong(order.adjustsOrderId().getMostSignificantBits())
                    .putLong(order.adjustsOrderId().getLeastSignificantBits())
                    .array());
        }

        out.writeBytes(ByteBuffer.allocate(12)
                .putLong(order.effectiveAt().getEpochSecond())
                .putInt(order.effectiveAt().getNano())
                .array());

        List<OrderCandidate.Entry> entries = order.entries();
        out.writeBytes(ByteBuffer.allocate(4).putInt(entries.size()).array());
        for (OrderCandidate.Entry entry : entries) {
            writeString(out, entry.entityId());
            writeString(out, entry.account());
            writeString(out, entry.currency());
            out.writeBytes(ByteBuffer.allocate(8).putLong(entry.amountMinor()).array());
        }

        writeString(out, canonicalJson(order.metadataJson()));
        return out.toByteArray();
    }

    /**
     * Metadata as canonical JSON: object keys sorted, array order preserved, integral numbers written without a
     * fractional part. Absent, blank and null all normalize to the empty object.
     */
    static String canonicalJson(String json) {
        if (json == null || json.isBlank() || "null".equals(json.strip())) {
            return "{}";
        }
        StringBuilder canonical = new StringBuilder();
        write(JSON.readTree(json), canonical);
        return canonical.toString();
    }

    private static void write(JsonNode node, StringBuilder out) {
        if (node.isObject()) {
            List<String> names = new ArrayList<>();
            node.propertyNames().forEach(names::add);
            names.sort(String::compareTo);
            out.append('{');
            for (int i = 0; i < names.size(); i++) {
                if (i > 0) {
                    out.append(',');
                }
                out.append(quote(names.get(i))).append(':');
                write(node.get(names.get(i)), out);
            }
            out.append('}');
        } else if (node.isArray()) {
            out.append('[');
            for (int i = 0; i < node.size(); i++) {
                if (i > 0) {
                    out.append(',');
                }
                write(node.get(i), out);
            }
            out.append(']');
        } else if (node.isNumber()) {
            // An integral value always renders without a fractional part, so 2000 and 2000.0 cannot hash differently.
            out.append(node.canConvertToExactIntegral() ? String.valueOf(node.asLong()) : node.decimalValue().toString());
        } else if (node.isTextual()) {
            out.append(quote(node.asString()));
        } else if (node.isBoolean()) {
            out.append(node.asBoolean());
        } else {
            out.append("null");
        }
    }

    private static String quote(String value) {
        StringBuilder out = new StringBuilder(value.length() + 2).append('"');
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                default -> {
                    if (c < 0x20) {
                        out.append(String.format("\\u%04x", (int) c));
                    } else {
                        out.append(c);
                    }
                }
            }
        }
        return out.append('"').toString();
    }

    private static void writeString(ByteArrayOutputStream out, String value) {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        out.writeBytes(ByteBuffer.allocate(4).putInt(bytes.length).array());
        out.writeBytes(bytes);
    }
}
