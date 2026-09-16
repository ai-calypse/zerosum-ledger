package dev.zerosum.ledger.invariants;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.zerosum.contracts.GoldenPayloads;
import dev.zerosum.ledger.support.ApplyTestDriver;
import dev.zerosum.ledger.support.LedgerTestDatabase;
import java.util.List;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * TB5: the invariant units need only select privileges, so they run under the read-only {@code verifier} role (D00-4).
 * This is what lets the S06 verifier reuse them without any write access to the ledger.
 */
@Tag("integration")
class InvariantQueriesVerifierRoleIT {

    @Test
    void theInvariantUnitsRunUnderTheReadOnlyVerifierRole() {
        try (LedgerTestDatabase db = LedgerTestDatabase.start()) {
            ApplyTestDriver driver = ApplyTestDriver.create(db.dataSource(LedgerTestDatabase.APP));
            for (int i = 1; i <= 7; i++) {
                driver.applyOne(GoldenPayloads.byId("O" + i));
            }

            InvariantQueries queries = new InvariantQueries(
                    JdbcClient.create(db.dataSource(LedgerTestDatabase.VERIFIER)));

            assertEquals(List.of(), queries.i2Violations(), "I2 under the verifier role");
            assertEquals(List.of(), queries.i3Violations(), "I3 under the verifier role");
            assertEquals(List.of(), queries.i4Violations(), "I4 under the verifier role");
            assertEquals(0, queries.unresolvedQuarantineCount());
            assertTrue(queries.nonZeroClearingBalances().isEmpty(),
                    "clearing accounts are back to zero after the full worked example");
        }
    }

    @Test
    void theClearingReportUsesTheChartOfAccountsClassification() {
        try (LedgerTestDatabase db = LedgerTestDatabase.start()) {
            ApplyTestDriver driver = ApplyTestDriver.create(db.dataSource(LedgerTestDatabase.APP));
            driver.applyOne(GoldenPayloads.byId("O1"));
            driver.applyOne(GoldenPayloads.byId("O2"));   // parks money in provider:fakecard clearing

            InvariantQueries queries = new InvariantQueries(
                    JdbcClient.create(db.dataSource(LedgerTestDatabase.VERIFIER)));
            List<InvariantQueries.ClearingBalance> clearing = queries.nonZeroClearingBalances();

            assertFalse(clearing.isEmpty(), "money in flight must appear");
            for (InvariantQueries.ClearingBalance balance : clearing) {
                assertTrue(dev.zerosum.money.ChartOfAccounts.isClearing(balance.accountCode()),
                        "only clearing accounts are reported: " + balance);
                assertTrue(balance.signedMinor() != 0, "zero balances are not reported: " + balance);
            }
        }
    }
}
