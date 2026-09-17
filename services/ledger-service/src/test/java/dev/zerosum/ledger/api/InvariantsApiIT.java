package dev.zerosum.ledger.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.zerosum.contracts.GoldenPayloads;
import dev.zerosum.ledger.support.ApplyTestDriver;
import dev.zerosum.ledger.support.LedgerTestDatabase;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The invariants endpoint over a ledger whose state this test drives from clean to quarantined to corrupted (D02-8).
 *
 * <p>It does not share {@link LedgerApiTestBase}'s database: quarantining a record and bypassing the append-only
 * triggers would leak into the read-only API tests. The steps run in order against one database, because each builds on
 * the state the previous one left.
 */
@Tag("integration")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class InvariantsApiIT {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final LedgerTestDatabase DB = LedgerTestDatabase.start();

    @LocalServerPort
    private int port;

    @DynamicPropertySource
    static void ledgerDatabase(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", DB::jdbcUrl);
        registry.add("spring.datasource.username", () -> LedgerTestDatabase.APP);
        registry.add("spring.datasource.password", () -> DB.password(LedgerTestDatabase.APP));
        registry.add("spring.flyway.enabled", () -> "false");
        // No broker in this test, so the money-order listener stays stopped rather than dialling one.
        registry.add("ledger.consumer.enabled", () -> "false");
        registry.add("zs.auth.reader-token", () -> "test-reader-token");
    }

    @Test
    @Order(1)
    void aPartialWorkedExampleLeavesAClearingAccountNonZero() {
        ApplyTestDriver driver = ApplyTestDriver.create(DB.dataSource(LedgerTestDatabase.APP));
        driver.applyOne(GoldenPayloads.byId("O1"));
        driver.applyOne(GoldenPayloads.byId("O2"));   // the card charge parks money in provider:fakecard clearing

        JsonNode report = invariants();
        assertTrue(report.get("consistent").asBoolean(), report.toString());
        assertTrue(report.get("i2_non_zero_currencies").isEmpty(), "I2 holds even mid-scenario");
        assertFalse(report.get("non_zero_clearing_balances").isEmpty(),
                "money in flight must show as a non-zero clearing balance");
        for (JsonNode balance : report.get("non_zero_clearing_balances")) {
            assertTrue(balance.get("account").asString().endsWith("clearing"), balance.toString());
            assertTrue(balance.get("signed_minor").asLong() != 0);
        }
    }

    @Test
    @Order(2)
    void theCompleteWorkedExampleIsConsistentWithEveryClearingAccountBackToZero() {
        ApplyTestDriver driver = ApplyTestDriver.create(DB.dataSource(LedgerTestDatabase.APP));
        for (int i = 3; i <= 7; i++) {
            driver.applyOne(GoldenPayloads.byId("O" + i));
        }

        JsonNode report = invariants();
        assertTrue(report.get("consistent").asBoolean(), report.toString());
        assertTrue(report.get("i2_non_zero_currencies").isEmpty(), "I2");
        assertTrue(report.get("i3_violations").isEmpty(), "I3");
        assertTrue(report.get("i4_violations").isEmpty(), "I4");
        assertEquals(0, report.get("unresolved_quarantined_count").asLong());
        assertTrue(report.get("non_zero_clearing_balances").isEmpty(),
                "clearing accounts return to zero once money stops moving");
        assertFalse(report.get("i5").get("evaluated").asBoolean(), "this endpoint never claims I5 passed");
        assertFalse(report.get("i5").get("reason").asString().isBlank());
    }

    @Test
    @Order(3)
    void aQuarantinedRecordIsCountedWhileUnresolved() throws SQLException {
        ApplyTestDriver.create(DB.dataSource(LedgerTestDatabase.APP)).applyOne("{ not a money order");

        assertEquals(1, invariants().get("unresolved_quarantined_count").asLong());

        // D02-9: an operator resolving the row takes it out of the count.
        try (Connection c = DB.connect(LedgerTestDatabase.APP); Statement st = c.createStatement()) {
            st.executeUpdate("UPDATE quarantined_orders SET resolved_at = now() WHERE resolved_at IS NULL");
        }
        assertEquals(0, invariants().get("unresolved_quarantined_count").asLong(), "resolved rows are not counted");
    }

    @Test
    @Order(4)
    void adeliberatelyCorruptedLedgerReportsI3AndI4Violations() throws SQLException {
        // Bypassing the append-only triggers needs a superuser session, which exists only in this container.
        try (Connection c = DB.superuser(); Statement st = c.createStatement()) {
            st.execute("ALTER TABLE entity_changelog DISABLE TRIGGER ALL");
            try {
                st.executeUpdate("UPDATE entity_changelog SET delta_minor = delta_minor + 7 "
                        + "WHERE entity_id = 'rider:R1' AND seq = 1");
                st.executeUpdate("DELETE FROM entity_changelog WHERE entity_id = 'driver:D1' AND seq = 1");
            } finally {
                st.execute("ALTER TABLE entity_changelog ENABLE TRIGGER ALL");
            }
        }

        JsonNode report = invariants();
        assertFalse(report.get("consistent").asBoolean(), "a corrupted ledger is never consistent");
        assertFalse(report.get("i3_violations").isEmpty(), "the balance no longer equals the sum of deltas");
        assertFalse(report.get("i4_violations").isEmpty(), "driver:D1 now has a sequence gap");
    }

    private JsonNode invariants() {
        return JSON.readTree(RestClient.builder().baseUrl("http://localhost:" + port)
                .defaultHeader("Authorization", "Bearer test-reader-token").build()
                .get().uri("/v1/invariants").retrieve().body(String.class));
    }
}
