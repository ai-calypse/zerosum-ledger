package dev.zerosum.ledger.apply;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.zerosum.ledger.support.ApplyTestDriver;
import dev.zerosum.ledger.support.LedgerQueries;
import dev.zerosum.ledger.support.LedgerTestDatabase;
import dev.zerosum.ledger.support.MoneyOrderPayloads;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** D02-4: a held entity lock makes apply retry, and exhausting the schedule raises the typed exception. */
@Tag("integration")
class ApplyLockTimeoutRetryIT {

    private static final String ENTITY = "rider:RLOCK";

    private static LedgerTestDatabase db;

    @BeforeAll
    static void startAndSeedEntity() throws SQLException {
        db = LedgerTestDatabase.start();
        ApplyTestDriver.create(db.dataSource(LedgerTestDatabase.APP))
                .applyOne(MoneyOrderPayloads.transfer(UUID.randomUUID(), "trip_lock_seed",
                        ENTITY, "receivable", "platform:main", "revenue", "USD", 100));
    }

    @AfterAll
    static void stop() {
        db.close();
    }

    @Test
    void aLockHeldBrieflyCausesARetryAndTheOrderIsStillAppliedOnce() throws Exception {
        UUID orderId = UUID.randomUUID();
        String payload = MoneyOrderPayloads.transfer(orderId, "trip_lock_retry",
                ENTITY, "receivable", "platform:main", "revenue", "USD", 250);

        try (Connection blocker = db.connect(LedgerTestDatabase.APP)) {
            blocker.setAutoCommit(false);
            try (Statement st = blocker.createStatement()) {
                st.execute("SELECT entity_id FROM entities WHERE entity_id = '" + ENTITY + "' FOR UPDATE");
            }
            var executor = Executors.newSingleThreadExecutor();
            try {
                // Lock timeout is 2 s (test defaults); the lock is released before the retry schedule runs out.
                Future<ApplyBatchResult> applying = executor.submit(
                        () -> ApplyTestDriver.create(db.dataSource(LedgerTestDatabase.APP)).applyOne(payload));
                Thread.sleep(3_000);
                blocker.commit();

                ApplyBatchResult result = applying.get();
                assertEquals(1, result.countOf(ApplyOutcome.Status.APPLIED), result.outcomes().toString());
                assertTrue(result.lockTimeoutRetries() >= 1, "expected a lock-timeout retry, got " + result);
                assertTrue(result.attempts() >= 2, result.toString());
            } finally {
                executor.shutdownNow();
            }
        }

        try (Connection c = db.connect(LedgerTestDatabase.VERIFIER)) {
            assertEquals(1, LedgerQueries.count(c, "SELECT count(*) FROM applied_orders WHERE order_id = '" + orderId + "'"));
            assertEquals(350L, LedgerQueries.balances(c).get(ENTITY + "/receivable/USD"));
        }
    }

    @Test
    void aLockHeldPastTheWholeScheduleRaisesTheTypedExceptionAndAppliesNothing() throws Exception {
        UUID orderId = UUID.randomUUID();
        String payload = MoneyOrderPayloads.transfer(orderId, "trip_lock_exhausted",
                ENTITY, "receivable", "platform:main", "revenue", "USD", 400);
        LedgerApplyProperties impatient = new LedgerApplyProperties(Duration.ofMillis(200), Duration.ofSeconds(5),
                2, Duration.ofMillis(50), Duration.ofMillis(100), 1);

        try (Connection blocker = db.connect(LedgerTestDatabase.APP)) {
            blocker.setAutoCommit(false);
            try (Statement st = blocker.createStatement()) {
                st.execute("SELECT entity_id FROM entities WHERE entity_id = '" + ENTITY + "' FOR UPDATE");
            }
            ApplyTestDriver driver = ApplyTestDriver.create(db.dataSource(LedgerTestDatabase.APP), impatient);
            RetriesExhaustedException failure =
                    assertThrows(RetriesExhaustedException.class, () -> driver.applyOne(payload));
            assertEquals(2, failure.attempts());
            assertEquals(1, failure.batch().size());
            blocker.commit();
        }

        try (Connection c = db.connect(LedgerTestDatabase.VERIFIER)) {
            assertEquals(0, LedgerQueries.count(c, "SELECT count(*) FROM applied_orders WHERE order_id = '" + orderId + "'"));
            assertEquals(0, LedgerQueries.count(c, "SELECT count(*) FROM quarantined_orders"),
                    "a transient failure must never quarantine money");
        }
    }
}
