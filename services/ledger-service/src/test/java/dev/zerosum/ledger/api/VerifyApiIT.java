package dev.zerosum.ledger.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.zerosum.ledger.support.ApplyTestDriver;
import dev.zerosum.ledger.support.LedgerTestDatabase;
import dev.zerosum.ledger.support.MoneyOrderPayloads;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * M6 (b): verify rebuilds an entity's balances from its changelog, compares them with what is stored and checks the
 * hash chain, reporting the lowest failing sequence number (D02-8).
 *
 * <p>Each tamper case gets its own entity, so the cases are independent of each other and of ordering. The database is
 * this class's own: tampering must not reach the read-only API tests.
 */
@Tag("integration")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class VerifyApiIT {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final LedgerTestDatabase DB = LedgerTestDatabase.start();
    private static final int ORDERS = 4;
    private static final List<String> ENTITIES =
            List.of("rider:R_CLEAN", "rider:R_DELTA", "rider:R_BALANCE", "rider:R_REMOVED", "rider:R_CONCURRENT");

    @LocalServerPort
    private int port;

    @DynamicPropertySource
    static void ledgerDatabase(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", DB::jdbcUrl);
        registry.add("spring.datasource.username", () -> LedgerTestDatabase.APP);
        registry.add("spring.datasource.password", () -> DB.password(LedgerTestDatabase.APP));
        registry.add("spring.flyway.enabled", () -> "false");
    }

    @BeforeAll
    static void seedOneChainPerCase() {
        ApplyTestDriver driver = ApplyTestDriver.create(DB.dataSource(LedgerTestDatabase.APP));
        for (String entity : ENTITIES) {
            for (int i = 0; i < ORDERS; i++) {
                driver.applyOne(MoneyOrderPayloads.transfer(UUID.randomUUID(), "verify_" + i,
                        entity, "receivable", "driver:D_VERIFY", "payable", "USD", 100L * (i + 1)));
            }
        }
    }

    @Test
    void aCleanEntityIsConsistentAndReportsEveryRowChecked() {
        JsonNode result = verify("rider:R_CLEAN");
        assertTrue(result.get("consistent").asBoolean(), result.toString());
        assertEquals(ORDERS, result.get("rows_checked").asLong(), "rows checked equals the changelog row count");
        assertTrue(result.get("first_bad_seq").isNull());
    }

    @Test
    void aTamperedDeltaIsDetected() throws SQLException {
        tamper("UPDATE entity_changelog SET delta_minor = delta_minor + 1 "
                + "WHERE entity_id = 'rider:R_DELTA' AND seq = 2");

        JsonNode result = verify("rider:R_DELTA");
        assertFalse(result.get("consistent").asBoolean());
        assertEquals(2L, result.get("first_bad_seq").asLong());
        assertFalse(result.get("failure").asString().isBlank());
    }

    @Test
    void aTamperedRunningBalanceIsDetectedEvenThoughTheDeltasStillSum() throws SQLException {
        tamper("UPDATE entity_changelog SET balance_after_minor = balance_after_minor + 5 "
                + "WHERE entity_id = 'rider:R_BALANCE' AND seq = 3");

        JsonNode result = verify("rider:R_BALANCE");
        assertFalse(result.get("consistent").asBoolean(), "a wrong running balance is still an inconsistency");
        assertEquals(3L, result.get("first_bad_seq").asLong());
    }

    @Test
    void aRemovedRowIsDetected() throws SQLException {
        tamper("DELETE FROM entity_changelog WHERE entity_id = 'rider:R_REMOVED' AND seq = 3");

        JsonNode result = verify("rider:R_REMOVED");
        assertFalse(result.get("consistent").asBoolean());
        assertEquals(3L, result.get("first_bad_seq").asLong(), "the gap is reported at the missing sequence");
    }

    @Test
    void verifyStaysConsistentWhileAppliesCommit() throws Exception {
        AtomicBoolean writing = new AtomicBoolean(true);
        CountDownLatch done = new CountDownLatch(1);
        Thread writer = new Thread(() -> {
            try {
                ApplyTestDriver driver = ApplyTestDriver.create(DB.dataSource(LedgerTestDatabase.APP));
                for (int i = 0; i < 40 && writing.get(); i++) {
                    driver.applyOne(MoneyOrderPayloads.transfer(UUID.randomUUID(), "concurrent_" + i,
                            "rider:R_CONCURRENT", "receivable", "driver:D_VERIFY", "payable", "USD", 10L + i));
                }
            } finally {
                done.countDown();
            }
        });
        writer.setDaemon(true);
        writer.start();

        for (int i = 0; i < 12; i++) {
            JsonNode result = verify("rider:R_CONCURRENT");
            assertTrue(result.get("consistent").asBoolean(),
                    "the snapshot must never report a half-applied state: " + result);
        }
        writing.set(false);
        assertTrue(done.await(60, TimeUnit.SECONDS), "the writer must finish");
    }

    @Test
    void anUnknownEntityIsNotFound() {
        String code = RestClient.create("http://localhost:" + port).post()
                .uri("/v1/entities/rider:NOBODY/verify")
                .exchange((request, response) ->
                        JSON.readTree(new String(response.getBody().readAllBytes())).get("code").asString(), false);
        assertEquals("entity_not_found", code);
    }

    private JsonNode verify(String entityId) {
        return JSON.readTree(RestClient.create("http://localhost:" + port)
                .post().uri("/v1/entities/{id}/verify", entityId).retrieve().body(String.class));
    }

    private void tamper(String sql) throws SQLException {
        try (Connection c = DB.superuser(); Statement st = c.createStatement()) {
            st.execute("ALTER TABLE entity_changelog DISABLE TRIGGER ALL");
            try {
                st.executeUpdate(sql);
            } finally {
                st.execute("ALTER TABLE entity_changelog ENABLE TRIGGER ALL");
            }
        }
    }
}
