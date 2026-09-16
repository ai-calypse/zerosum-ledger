package dev.zerosum.ledger.apply;

import static org.junit.jupiter.api.Assertions.assertEquals;

import dev.zerosum.ledger.support.ApplyTestDriver;
import dev.zerosum.ledger.support.LedgerQueries;
import dev.zerosum.ledger.support.LedgerTestDatabase;
import dev.zerosum.ledger.support.MoneyOrderPayloads;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.UUID;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** ADR-0004 (ledger half, D02-12): apply never rejects an order because a balance would go negative. */
@Tag("integration")
class NegativeBalanceIT {

    @Test
    void anOrderDrivingADriverPayableIntoDebitIsApplied() throws SQLException {
        try (LedgerTestDatabase db = LedgerTestDatabase.start()) {
            ApplyTestDriver driver = ApplyTestDriver.create(db.dataSource(LedgerTestDatabase.APP));
            // The driver is owed 1000, then a payout of 1800 is booked: the payable goes into debit (driver debt).
            assertEquals(1, driver.applyOne(MoneyOrderPayloads.transfer(UUID.randomUUID(), "trip_neg",
                    "rider:R9", "receivable", "driver:D9", "payable", "USD", 1000))
                    .countOf(ApplyOutcome.Status.APPLIED));
            assertEquals(1, driver.applyOne(MoneyOrderPayloads.transfer(UUID.randomUUID(), "payout_neg",
                    "driver:D9", "payable", "provider:fakebank", "payout_clearing", "USD", 1800))
                    .countOf(ApplyOutcome.Status.APPLIED));

            try (Connection c = db.connect(LedgerTestDatabase.VERIFIER)) {
                assertEquals(800L, LedgerQueries.balances(c).get("driver:D9/payable/USD"), "driver debt is allowed");
                assertEquals(0, LedgerQueries.count(c, "SELECT count(*) FROM quarantined_orders"));
                assertEquals(java.util.List.of(), LedgerQueries.i2Violations(c));
                assertEquals(java.util.List.of(), LedgerQueries.i3Violations(c));
            }
        }
    }
}
