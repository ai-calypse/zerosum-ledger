package dev.zerosum.ledger.apply;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.zerosum.ledger.support.ApplyTestDriver;
import dev.zerosum.ledger.support.LedgerQueries;
import dev.zerosum.ledger.support.LedgerTestDatabase;
import dev.zerosum.ledger.support.MoneyOrderPayloads;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * A backend terminated mid-transaction is a transient failure (D02-4): the engine retries on a fresh connection and the
 * order is applied exactly once. Money is never quarantined for a lost connection.
 */
@Tag("integration")
class ApplyConnectionLossIT {

    private static final String ENTITY = "rider:RKILL";

    @Test
    void terminatingTheApplyBackendStillAppliesTheOrderExactlyOnce() throws Exception {
        try (LedgerTestDatabase db = LedgerTestDatabase.start()) {
            ApplyTestDriver seeder = ApplyTestDriver.create(db.dataSource(LedgerTestDatabase.APP));
            seeder.applyOne(MoneyOrderPayloads.transfer(UUID.randomUUID(), "trip_kill_seed",
                    ENTITY, "receivable", "platform:main", "revenue", "USD", 100));

            UUID orderId = UUID.randomUUID();
            String payload = MoneyOrderPayloads.transfer(orderId, "trip_kill",
                    ENTITY, "receivable", "platform:main", "revenue", "USD", 700);

            try (Connection blocker = db.connect(LedgerTestDatabase.APP)) {
                blocker.setAutoCommit(false);
                try (Statement st = blocker.createStatement()) {
                    st.execute("SELECT entity_id FROM entities WHERE entity_id = '" + ENTITY + "' FOR UPDATE");
                }
                var executor = Executors.newSingleThreadExecutor();
                try {
                    Future<ApplyBatchResult> applying = executor.submit(
                            () -> ApplyTestDriver.create(db.dataSource(LedgerTestDatabase.APP)).applyOne(payload));
                    // Kill the backend that is waiting for the entity lock, then release the lock so the retry succeeds.
                    int terminated = terminateWaitingBackend(db);
                    assertTrue(terminated > 0, "no waiting apply backend found to terminate");
                    blocker.commit();

                    ApplyBatchResult result = applying.get();
                    assertEquals(1, result.countOf(ApplyOutcome.Status.APPLIED), result.outcomes().toString());
                    assertTrue(result.attempts() >= 2, result.toString());
                } finally {
                    executor.shutdownNow();
                }
            }

            try (Connection c = db.connect(LedgerTestDatabase.VERIFIER)) {
                assertEquals(1, LedgerQueries.count(c,
                        "SELECT count(*) FROM applied_orders WHERE order_id = '" + orderId + "'"));
                assertEquals(800L, LedgerQueries.balances(c).get(ENTITY + "/receivable/USD"));
                assertEquals(0, LedgerQueries.count(c, "SELECT count(*) FROM quarantined_orders"));
                assertEquals(java.util.List.of(), LedgerQueries.i3Violations(c));
            }
        }
    }

    /** Terminates the ledger_app backend waiting on a lock; retries briefly until the apply thread is actually waiting. */
    private static int terminateWaitingBackend(LedgerTestDatabase db) throws SQLException, InterruptedException {
        for (int attempt = 0; attempt < 50; attempt++) {
            try (Connection superuser = db.superuser()) {
                long killed = LedgerQueries.count(superuser, """
                        SELECT count(*) FROM (
                          SELECT pg_terminate_backend(pid) FROM pg_stat_activity
                          WHERE usename = 'ledger_app' AND wait_event_type = 'Lock' AND state = 'active') t""");
                if (killed > 0) {
                    return (int) killed;
                }
            }
            Thread.sleep(100);
        }
        return 0;
    }
}
