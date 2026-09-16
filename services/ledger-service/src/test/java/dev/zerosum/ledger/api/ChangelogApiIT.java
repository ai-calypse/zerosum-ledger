package dev.zerosum.ledger.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.zerosum.ledger.support.ApplyTestDriver;
import dev.zerosum.ledger.support.MoneyOrderPayloads;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** M6 (a): every changelog row links to its money order and source idempotency key, and paging is exhaustive (D02-7). */
class ChangelogApiIT extends LedgerApiTestBase {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final String ENTITY = "rider:R_PAGING";
    private static final int ORDERS = 25;

    @BeforeAll
    static void applyAKnownNumberOfRows() {
        ApplyTestDriver driver = applyDriver();
        for (int i = 0; i < ORDERS; i++) {
            driver.applyOne(MoneyOrderPayloads.transfer(UUID.randomUUID(), "paging_" + i,
                    ENTITY, "receivable", "driver:D_PAGING", "payable", "USD", 100L + i));
        }
    }

    @Test
    void pagingReturnsEveryRowExactlyOnceInSequenceOrder() {
        List<Long> seen = new ArrayList<>();
        Long after = null;
        int pages = 0;
        while (true) {
            JsonNode page = JSON.readTree(http().get()
                    .uri("/v1/entities/{id}/changelog?limit=7" + (after == null ? "" : "&after_seq=" + after), ENTITY)
                    .retrieve().body(String.class));
            for (JsonNode row : page.get("rows")) {
                seen.add(row.get("seq").asLong());
            }
            pages++;
            JsonNode next = page.get("next_after_seq");
            if (next == null || next.isNull()) {
                break;
            }
            after = next.asLong();
            assertTrue(pages < 20, "paging must terminate");
        }
        assertEquals(ORDERS, seen.size(), "every row exactly once");
        assertEquals(seen.stream().sorted().toList(), seen, "rows arrive in sequence order");
        assertEquals(seen.stream().distinct().count(), seen.size(), "no row is repeated across pages");
        assertEquals(1L, seen.get(0));
    }

    @Test
    void everyRowCarriesItsOrderAndSourceIdempotencyKey() {
        JsonNode page = JSON.readTree(http().get()
                .uri("/v1/entities/{id}/changelog", ENTITY).retrieve().body(String.class));
        assertFalse(page.get("rows").isEmpty());
        for (JsonNode row : page.get("rows")) {
            assertNotNull(UUID.fromString(row.get("order_id").asString()), "order_id is a UUID");
            assertFalse(row.get("source").get("system").asString().isBlank(), "source system (M6 a)");
            assertFalse(row.get("source").get("idempotency_key").asString().isBlank(), "idempotency key (M6 a)");
            assertTrue(row.get("seq").asLong() > 0);
            assertFalse(row.get("recorded_at").asString().isBlank());
        }
    }

    @Test
    void aRequestPastTheLastSequenceIsAnEmptyPageNotANotFound() {
        JsonNode page = JSON.readTree(http().get()
                .uri("/v1/entities/{id}/changelog?after_seq=100000", ENTITY).retrieve().body(String.class));
        assertTrue(page.get("rows").isEmpty());
        JsonNode next = page.get("next_after_seq");
        assertTrue(next == null || next.isNull(), "no cursor past the end");
    }

    @Test
    void invalidCursorsAndLimitsAreRejected() {
        assertEquals("invalid_limit", problemCode("/v1/entities/" + ENTITY + "/changelog?limit=0"));
        assertEquals("invalid_limit", problemCode("/v1/entities/" + ENTITY + "/changelog?limit=501"));
        assertEquals("invalid_limit", problemCode("/v1/entities/" + ENTITY + "/changelog?limit=abc"));
        assertEquals("invalid_cursor", problemCode("/v1/entities/" + ENTITY + "/changelog?after_seq=-1"));
        assertEquals("invalid_cursor", problemCode("/v1/entities/" + ENTITY + "/changelog?after_seq=abc"));
        assertEquals("entity_not_found", problemCode("/v1/entities/rider:NOBODY/changelog"));
    }

    private String problemCode(String uri) {
        return http().get().uri(uri).exchange((request, response) ->
                JSON.readTree(new String(response.getBody().readAllBytes())).get("code").asString(), false);
    }
}
