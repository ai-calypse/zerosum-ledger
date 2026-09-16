package dev.zerosum.ledger.apply;

import static org.junit.jupiter.api.Assertions.assertEquals;

import dev.zerosum.contracts.GoldenPayloads;
import dev.zerosum.ledger.support.ApplyTestDriver;
import dev.zerosum.ledger.support.LedgerQueries;
import dev.zerosum.ledger.support.LedgerTestDatabase;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeMap;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** M5 (a) at ledger level: applying each order three times — sequentially, inside one batch and across batches. */
@Tag("integration")
class ApplyDuplicateIT {

    private static LedgerTestDatabase db;
    private static ApplyTestDriver driver;

    @BeforeAll
    static void start() {
        db = LedgerTestDatabase.start();
        driver = ApplyTestDriver.create(db.dataSource(LedgerTestDatabase.APP));
    }

    @AfterAll
    static void stop() {
        db.close();
    }

    @Test
    void repeatedApplicationLeavesBalancesSequencesAndChangelogUnchanged() throws SQLException {
        List<String> payloads = new ArrayList<>();
        for (int i = 1; i <= 7; i++) {
            payloads.add(GoldenPayloads.byId("O" + i));
        }

        // First application, per order.
        for (String payload : payloads) {
            assertEquals(1, driver.applyOne(payload).countOf(ApplyOutcome.Status.APPLIED));
        }
        TreeMap<String, Long> balancesAfterFirst;
        long changelogAfterFirst;
        long riderSeqAfterFirst;
        try (Connection c = db.connect(LedgerTestDatabase.VERIFIER)) {
            balancesAfterFirst = LedgerQueries.balances(c);
            changelogAfterFirst = LedgerQueries.count(c, "SELECT count(*) FROM entity_changelog");
            riderSeqAfterFirst = LedgerQueries.lastSeq(c, "rider:R1");
        }

        // Second application: same payloads again, one at a time.
        for (String payload : payloads) {
            ApplyBatchResult result = driver.applyOne(payload);
            assertEquals(1, result.countOf(ApplyOutcome.Status.DUPLICATE), result.outcomes().toString());
        }
        // Third application: the whole set as one batch, with every payload repeated inside the batch.
        List<String> repeated = new ArrayList<>(payloads);
        repeated.addAll(payloads);
        ApplyBatchResult batch = driver.applyBatch(repeated);
        assertEquals(repeated.size(), batch.countOf(ApplyOutcome.Status.DUPLICATE), batch.outcomes().toString());

        try (Connection c = db.connect(LedgerTestDatabase.VERIFIER)) {
            assertEquals(balancesAfterFirst, LedgerQueries.balances(c), "balances after duplicates");
            assertEquals(changelogAfterFirst, LedgerQueries.count(c, "SELECT count(*) FROM entity_changelog"));
            assertEquals(riderSeqAfterFirst, LedgerQueries.lastSeq(c, "rider:R1"));
            assertEquals(7, LedgerQueries.count(c, "SELECT count(*) FROM applied_orders"));
            assertEquals(List.of(), LedgerQueries.i2Violations(c));
            assertEquals(List.of(), LedgerQueries.i3Violations(c));
            assertEquals(List.of(), LedgerQueries.i4Violations(c));
        }
    }
}
