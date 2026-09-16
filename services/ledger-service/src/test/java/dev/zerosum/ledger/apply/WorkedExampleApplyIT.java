package dev.zerosum.ledger.apply;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.zerosum.contracts.GoldenPayloads;
import dev.zerosum.ledger.support.ApplyTestDriver;
import dev.zerosum.ledger.support.LedgerQueries;
import dev.zerosum.ledger.support.LedgerTestDatabase;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;
import java.util.TreeMap;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** M1(d)/M5: golden O1–O7 and the O8 variant applied through the engine match the D01-9 expected balances. */
@Tag("integration")
class WorkedExampleApplyIT {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    @Test
    void perOrderModeReproducesTheExpectedBalancesAfterO1ToO7() throws SQLException {
        try (LedgerTestDatabase db = LedgerTestDatabase.start()) {
            ApplyTestDriver driver = ApplyTestDriver.create(db.dataSource(LedgerTestDatabase.APP));
            for (int i = 1; i <= 7; i++) {
                ApplyBatchResult result = driver.applyOne(GoldenPayloads.byId("O" + i));
                assertEquals(1, result.countOf(ApplyOutcome.Status.APPLIED), "O" + i + " " + result.outcomes());
            }
            assertScenario(db, "after_O1_O7");
        }
    }

    @Test
    void batchedModeReproducesTheSameBalancesIncludingTheO8Variant() throws SQLException {
        try (LedgerTestDatabase db = LedgerTestDatabase.start()) {
            ApplyTestDriver driver = ApplyTestDriver.create(db.dataSource(LedgerTestDatabase.APP));
            List<String> payloads = new java.util.ArrayList<>();
            for (int i = 1; i <= 8; i++) {
                payloads.add(GoldenPayloads.byId("O" + i));
            }
            ApplyBatchResult result = driver.applyBatch(payloads);
            assertEquals(8, result.countOf(ApplyOutcome.Status.APPLIED), result.outcomes().toString());
            assertScenario(db, "after_O1_O8_variant");
        }
    }

    private static void assertScenario(LedgerTestDatabase db, String scenarioId) throws SQLException {
        JsonNode scenarios = JSON.readTree(GoldenPayloads.expectedBalances()).get("scenarios");
        JsonNode scenario = null;
        for (JsonNode candidate : scenarios) {
            if (candidate.get("id").asString().equals(scenarioId)) {
                scenario = candidate;
            }
        }
        TreeMap<String, Long> expected = new TreeMap<>();
        for (JsonNode balance : scenario.get("balances")) {
            expected.put(balance.get("entity_id").asString() + "/" + balance.get("account").asString() + "/"
                    + balance.get("currency").asString(), balance.get("signed_minor").asLong());
        }
        try (Connection c = db.connect(LedgerTestDatabase.VERIFIER)) {
            assertEquals(expected, LedgerQueries.balances(c), scenarioId);
            assertEquals(List.of(), LedgerQueries.i2Violations(c), "I2");
            assertEquals(List.of(), LedgerQueries.i3Violations(c), "I3");
            assertEquals(List.of(), LedgerQueries.i4Violations(c), "I4");
            assertEquals(List.of(), LedgerQueries.chainLinkViolations(c), "chain links");
            assertTrue(LedgerQueries.count(c, "SELECT count(*) FROM entity_changelog") > 0);
            assertEquals(0, LedgerQueries.count(c, "SELECT count(*) FROM quarantined_orders"));
        }
    }
}
