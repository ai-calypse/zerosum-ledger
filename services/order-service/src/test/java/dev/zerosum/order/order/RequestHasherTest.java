package dev.zerosum.order.order;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.zerosum.money.OrderCandidate.Entry;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Pinned vectors for the D03-3 canonical form. Idempotency keys never expire (M3 (e)), so a serializer or library
 * change that altered these digests would turn a legitimate retry into a key-reuse error years later; these vectors are
 * what catches that.
 */
class RequestHasherTest {

    private static final HexFormat HEX = HexFormat.of();
    private final RequestHasher hasher = new RequestHasher();

    private static NewOrder order(String metadataJson, List<Entry> entries) {
        return new NewOrder("trip-simulator", "trip_8f2c:completed", "COMMERCE", "trip.completed", "trip_8f2c", null,
                entries, metadataJson, Instant.parse("2026-09-15T10:04:11.201Z"));
    }

    private static List<Entry> threeEntries() {
        return List.of(Entry.of("rider:R1", "receivable", "USD", 2500),
                Entry.of("driver:D1", "payable", "USD", -2000),
                Entry.of("platform:main", "revenue", "USD", -500));
    }

    @Test
    void reorderedJsonKeysGiveTheSameHash() {
        byte[] a = hasher.hash(order("{\"trip_id\":\"trip_8f2c\",\"commission_bps\":2000}", threeEntries()));
        byte[] b = hasher.hash(order("{\"commission_bps\":2000,\"trip_id\":\"trip_8f2c\"}", threeEntries()));
        assertArrayEquals(a, b, "metadata key order must not change the hash");

        // Whitespace and an integral value written with a fractional part are likewise not semantic.
        byte[] c = hasher.hash(order("{\n  \"commission_bps\" : 2000.0,\n  \"trip_id\":\"trip_8f2c\"\n}", threeEntries()));
        assertArrayEquals(a, c, "whitespace and 2000 vs 2000.0 must not change the hash");
    }

    @Test
    void absentAndNullMetadataGiveTheSameHash() {
        byte[] absent = hasher.hash(order(null, threeEntries()));
        byte[] explicitNull = hasher.hash(order("null", threeEntries()));
        byte[] emptyObject = hasher.hash(order("{}", threeEntries()));
        assertArrayEquals(absent, explicitNull, "absent and null metadata are one representation");
        assertArrayEquals(absent, emptyObject, "an empty object is that same representation");
    }

    @Test
    void reorderedEntriesGiveADifferentHash() {
        List<Entry> swapped = List.of(threeEntries().get(1), threeEntries().get(0), threeEntries().get(2));
        assertNotEquals(HEX.formatHex(hasher.hash(order("{}", threeEntries()))),
                HEX.formatHex(hasher.hash(order("{}", swapped))),
                "entry order is significant, because line numbers are part of the order");
    }

    @Test
    void theVersionTagIsPartOfTheHashedBytes() {
        NewOrder order = order("{}", threeEntries());

        // The form version is genuinely the first byte of what gets hashed, not a comment about intent.
        assertEquals(RequestHasher.FORM_VERSION, RequestHasher.canonicalBytes(order, RequestHasher.FORM_VERSION)[0]);

        // And it participates: the same request under a different form version produces different bytes, so a future
        // version can never collide with a digest stored under version 1.
        byte[] asV1 = RequestHasher.canonicalBytes(order, (short) 1);
        byte[] asV2 = RequestHasher.canonicalBytes(order, (short) 2);
        assertNotEquals(HEX.formatHex(asV1), HEX.formatHex(asV2), "the version byte must change the hashed bytes");
        assertEquals(asV1.length, asV2.length, "only the version byte differs");

        // The published hash is the digest of exactly those bytes.
        assertArrayEquals(sha256(asV1), hasher.hash(order), "hash() digests the canonical bytes");
    }

    @Test
    void everyHashedFieldChangesTheDigest() {
        // Guards against a field being dropped from the canonical form: each client-supplied input must matter.
        NewOrder base = order("{}", threeEntries());
        byte[] baseline = hasher.hash(base);
        assertNotEquals(HEX.formatHex(baseline), HEX.formatHex(hasher.hash(new NewOrder("other-system",
                base.idempotencyKey(), base.type(), base.reason(), base.orderGroupId(), base.adjustsOrderId(),
                base.entries(), base.metadataJson(), base.effectiveAt()))), "source system");
        assertNotEquals(HEX.formatHex(baseline), HEX.formatHex(hasher.hash(new NewOrder(base.sourceSystem(),
                "other-key", base.type(), base.reason(), base.orderGroupId(), base.adjustsOrderId(), base.entries(),
                base.metadataJson(), base.effectiveAt()))), "idempotency key");
        assertNotEquals(HEX.formatHex(baseline), HEX.formatHex(hasher.hash(new NewOrder(base.sourceSystem(),
                base.idempotencyKey(), "REFUND", base.reason(), base.orderGroupId(), base.adjustsOrderId(),
                base.entries(), base.metadataJson(), base.effectiveAt()))), "type");
        assertNotEquals(HEX.formatHex(baseline), HEX.formatHex(hasher.hash(new NewOrder(base.sourceSystem(),
                base.idempotencyKey(), base.type(), base.reason(), "other_group", base.adjustsOrderId(),
                base.entries(), base.metadataJson(), base.effectiveAt()))), "order group");
        assertNotEquals(HEX.formatHex(baseline), HEX.formatHex(hasher.hash(new NewOrder(base.sourceSystem(),
                base.idempotencyKey(), base.type(), base.reason(), base.orderGroupId(),
                UUID.fromString("01996a3e-8f10-7c2a-9a1e-3d5c7b2e4f01"), base.entries(), base.metadataJson(),
                base.effectiveAt()))), "adjusts order id presence");
        assertNotEquals(HEX.formatHex(baseline), HEX.formatHex(hasher.hash(new NewOrder(base.sourceSystem(),
                base.idempotencyKey(), base.type(), base.reason(), base.orderGroupId(), base.adjustsOrderId(),
                base.entries(), "{\"a\":1}", base.effectiveAt()))), "metadata");
        assertNotEquals(HEX.formatHex(baseline), HEX.formatHex(hasher.hash(new NewOrder(base.sourceSystem(),
                base.idempotencyKey(), base.type(), base.reason(), base.orderGroupId(), base.adjustsOrderId(),
                base.entries(), base.metadataJson(), base.effectiveAt().plusNanos(1)))), "effective at");
    }

    @Test
    void canonicalMetadataSortsNestedKeysAndKeepsArrayOrder() {
        assertEquals("{\"a\":{\"x\":1,\"y\":[3,1,2]},\"b\":true}",
                RequestHasher.canonicalJson("{\"b\":true,\"a\":{\"y\":[3,1,2],\"x\":1}}"));
        assertEquals("{}", RequestHasher.canonicalJson(null));
        assertEquals("{}", RequestHasher.canonicalJson("   "));
        assertTrue(RequestHasher.canonicalJson("{\"s\":\"a\\\"b\"}").contains("\\\""), "quotes stay escaped");
        assertFalse(RequestHasher.canonicalJson("{\"n\":2000.0}").contains("."), "integral values lose the fraction");
    }

    private static byte[] sha256(byte[] bytes) {
        try {
            return java.security.MessageDigest.getInstance("SHA-256").digest(bytes);
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
