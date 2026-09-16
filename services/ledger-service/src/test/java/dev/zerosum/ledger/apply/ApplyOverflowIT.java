package dev.zerosum.ledger.apply;

import static org.junit.jupiter.api.Assertions.assertEquals;

import dev.zerosum.ledger.support.ApplyTestDriver;
import dev.zerosum.ledger.support.LedgerQueries;
import dev.zerosum.ledger.support.LedgerTestDatabase;
import dev.zerosum.ledger.support.MoneyOrderPayloads;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** A balance overflow is non-transient: in a batch it is isolated and quarantined, and the other orders still apply. */
@Tag("integration")
class ApplyOverflowIT {

    @Test
    void overflowingOrderIsIsolatedAndQuarantinedWhileTheRestOfTheBatchApplies() throws SQLException {
        try (LedgerTestDatabase db = LedgerTestDatabase.start()) {
            ApplyTestDriver driver = ApplyTestDriver.create(db.dataSource(LedgerTestDatabase.APP));
            // Seed a balance close to the long ceiling; the per-entry cap alone can't reach it (D01-1).
            try (Connection owner = db.connect(LedgerTestDatabase.OWNER); Statement st = owner.createStatement()) {
                st.execute("INSERT INTO entities (entity_id, kind) VALUES ('rider:ROVF', 'rider')");
                st.execute("INSERT INTO accounts (entity_id, account_code, currency, normal_side, balance_minor)"
                        + " VALUES ('rider:ROVF', 'receivable', 'USD', 'DEBIT', " + (Long.MAX_VALUE - 10) + ")");
            }

            String overflowing = MoneyOrderPayloads.transfer(UUID.randomUUID(), "trip_ovf",
                    "rider:ROVF", "receivable", "platform:main", "revenue", "USD", 1_000_000_000_000L);
            String healthy = MoneyOrderPayloads.transfer(UUID.randomUUID(), "trip_ok",
                    "rider:ROK", "receivable", "platform:main", "revenue", "USD", 2500);

            ApplyBatchResult result = driver.applyBatch(List.of(overflowing, healthy));
            assertEquals(QuarantineCode.ARITHMETIC_OVERFLOW, result.outcomes().get(0).errorCode(),
                    result.outcomes().toString());
            assertEquals(ApplyOutcome.Status.APPLIED, result.outcomes().get(1).status(), result.outcomes().toString());

            try (Connection c = db.connect(LedgerTestDatabase.VERIFIER)) {
                assertEquals(1, LedgerQueries.count(c,
                        "SELECT count(*) FROM quarantined_orders WHERE error_code = 'ARITHMETIC_OVERFLOW'"));
                assertEquals(2500L, LedgerQueries.balances(c).get("rider:ROK/receivable/USD"));
                assertEquals(Long.MAX_VALUE - 10, LedgerQueries.balances(c).get("rider:ROVF/receivable/USD"),
                        "the overflowing order changed nothing");
                assertEquals(1, LedgerQueries.count(c, "SELECT count(*) FROM applied_orders"));
            }
        }
    }
}
