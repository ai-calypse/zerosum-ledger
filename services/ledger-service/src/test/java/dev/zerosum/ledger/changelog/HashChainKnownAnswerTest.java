package dev.zerosum.ledger.changelog;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.zerosum.ledger.store.LedgerStore.ChangelogRow;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The canonical form (D02-6) is pinned by {@code hash-chain/known-answer.json}. Those digests were produced by a
 * separate reference implementation written from the written specification, so this is a cross-implementation check and
 * not the hasher confirming itself. A change to the form breaks this test, which is the point: the vectors are the
 * portable contract S06 and S09 check themselves against, and changing the form needs a new version and an ADR.
 */
class HashChainKnownAnswerTest {

    private static final HexFormat HEX = HexFormat.of();

    private final ChangelogHasher hasher = new ChangelogHasher();
    private final ChainVerifier verifier = new ChainVerifier(hasher);

    @Test
    void everyVectorMatchesTheHasherAndTheStatedForm() {
        JsonNode file = vectors();
        assertEquals(ChangelogHasher.FORM_VERSION, (short) file.get("form_version").asInt(),
                "the vector file pins the form version this build produces");
        assertEquals("SHA-256", file.get("algorithm").asString());

        Map<String, byte[]> heads = new HashMap<>();
        int rows = 0;
        for (JsonNode row : file.get("rows")) {
            String entityId = row.get("entity_id").asString();
            byte[] previous = heads.get(entityId);
            JsonNode statedPrev = row.get("prev_hash");

            if (previous == null) {
                assertTrue(statedPrev.isNull(), "the first row of " + entityId + " must state a null previous hash");
            } else {
                assertEquals(HEX.formatHex(previous), statedPrev.asString(),
                        "the vector's previous hash must be the prior row's hash for " + entityId);
            }

            byte[] computed = hasher.hash(previous, entityId, row.get("seq").asLong(),
                    UUID.fromString(row.get("order_id").asString()), row.get("account_code").asString(),
                    row.get("currency").asString(), row.get("delta_minor").asLong(),
                    row.get("balance_after_minor").asLong());

            assertEquals(row.get("row_hash").asString(), HEX.formatHex(computed),
                    "hash mismatch for " + entityId + " seq " + row.get("seq").asLong());
            heads.put(entityId, computed);
            rows++;
        }
        assertEquals(7, rows, "every vector row must be checked");
    }

    @Test
    void theVectorsKeepTheCoverageTheSpecificationRequires() {
        List<JsonNode> rows = new ArrayList<>();
        vectors().get("rows").forEach(rows::add);

        assertTrue(rows.stream().anyMatch(r -> r.get("prev_hash").isNull()), "a first row of an entity");
        assertTrue(rows.stream().anyMatch(r -> r.get("delta_minor").asLong() < 0), "a negative delta");
        assertTrue(rows.stream().anyMatch(r -> r.get("balance_after_minor").asLong() < 0), "a negative balance");

        Map<String, List<String>> currenciesPerEntity = new LinkedHashMap<>();
        for (JsonNode row : rows) {
            currenciesPerEntity
                    .computeIfAbsent(row.get("entity_id").asString(), k -> new ArrayList<>())
                    .add(row.get("currency").asString());
        }
        assertTrue(currenciesPerEntity.values().stream().anyMatch(cs -> cs.stream().distinct().count() > 1),
                "an entity whose chain spans several currencies");
    }

    @Test
    void theVectorChainsVerifyWithTheProductionVerifier() {
        Map<String, List<ChangelogRow>> byEntity = new LinkedHashMap<>();
        for (JsonNode row : vectors().get("rows")) {
            byEntity.computeIfAbsent(row.get("entity_id").asString(), k -> new ArrayList<>()).add(new ChangelogRow(
                    row.get("entity_id").asString(), row.get("seq").asLong(),
                    UUID.fromString(row.get("order_id").asString()), row.get("account_code").asString(),
                    row.get("currency").asString(), row.get("delta_minor").asLong(),
                    row.get("balance_after_minor").asLong(), ChangelogHasher.FORM_VERSION,
                    row.get("prev_hash").isNull() ? null : HEX.parseHex(row.get("prev_hash").asString()),
                    HEX.parseHex(row.get("row_hash").asString())));
        }
        byEntity.forEach((entityId, rows) -> {
            ChainVerifier.Result result = verifier.verify(rows);
            assertNull(result.firstBadSeq(), entityId + ": " + result.detail());
            assertTrue(result.consistent(), entityId);
            assertEquals(rows.size(), result.rowsChecked());
        });
    }

    private static JsonNode vectors() {
        try (InputStream in = HashChainKnownAnswerTest.class.getClassLoader()
                .getResourceAsStream("hash-chain/known-answer.json")) {
            if (in == null) {
                throw new IllegalStateException("hash-chain/known-answer.json is not on the test classpath");
            }
            return JsonMapper.builder().build().readTree(new String(in.readAllBytes(), StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
