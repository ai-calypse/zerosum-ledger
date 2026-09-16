package dev.zerosum.ledger.apply;

import static org.junit.jupiter.api.Assertions.assertEquals;

import dev.zerosum.ledger.support.ApplyTestDriver;
import dev.zerosum.ledger.support.LedgerQueries;
import dev.zerosum.ledger.support.LedgerTestDatabase;
import dev.zerosum.ledger.support.MoneyOrderPayloads;
import dev.zerosum.money.generate.OrderGenerator;
import dev.zerosum.money.generate.Seed;
import dev.zerosum.money.generate.SeededExtension;
import dev.zerosum.money.generate.StreamPerturbation;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeMap;
import java.util.UUID;
import java.util.random.RandomGenerator;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

/**
 * Seeded order sequences with shuffles and duplicates produce identical balances in per-order and batched mode, and
 * satisfy I2–I4 (M5 (b)–(d)). The seed is printed per ADR-0009.
 */
@Tag("integration")
@ExtendWith(SeededExtension.class)
class SeededApplyPermutationIT {

    private static final int ORDERS = 120;

    @Test
    void perOrderAndBatchedModeAgreeUnderShufflingAndDuplicates(Seed seed) throws SQLException {
        RandomGenerator rng = seed.random();
        OrderGenerator generator = new OrderGenerator(rng, seed.value());
        List<String> payloads = new ArrayList<>(ORDERS);
        for (int i = 0; i < ORDERS; i++) {
            payloads.add(MoneyOrderPayloads.render(UUID.randomUUID(), "gen_" + (i % 7), generator.valid()));
        }
        // 20% duplicates and a bounded shuffle, so both runs see the same multiset in different orders.
        List<String> perturbed = StreamPerturbation.perturb(payloads, rng, 20, 6);

        TreeMap<String, Long> perOrder;
        try (LedgerTestDatabase db = LedgerTestDatabase.start()) {
            ApplyTestDriver driver = ApplyTestDriver.create(db.dataSource(LedgerTestDatabase.APP));
            for (String payload : perturbed) {
                driver.applyOne(payload);
            }
            perOrder = assertConsistent(db, payloads.size());
        }

        TreeMap<String, Long> batched;
        try (LedgerTestDatabase db = LedgerTestDatabase.start()) {
            ApplyTestDriver driver = ApplyTestDriver.create(db.dataSource(LedgerTestDatabase.APP));
            for (int i = 0; i < perturbed.size(); i += 25) {
                driver.applyBatch(perturbed.subList(i, Math.min(i + 25, perturbed.size())));
            }
            batched = assertConsistent(db, payloads.size());
        }

        assertEquals(perOrder, batched, "per-order and batched balances must agree");
    }

    private static TreeMap<String, Long> assertConsistent(LedgerTestDatabase db, int uniqueOrders) throws SQLException {
        try (Connection c = db.connect(LedgerTestDatabase.VERIFIER)) {
            assertEquals(List.of(), LedgerQueries.i2Violations(c), "I2");
            assertEquals(List.of(), LedgerQueries.i3Violations(c), "I3");
            assertEquals(List.of(), LedgerQueries.i4Violations(c), "I4");
            assertEquals(List.of(), LedgerQueries.chainLinkViolations(c), "chain links");
            assertEquals(uniqueOrders, LedgerQueries.count(c, "SELECT count(*) FROM applied_orders"));
            assertEquals(0, LedgerQueries.count(c, "SELECT count(*) FROM quarantined_orders"));
            return LedgerQueries.balances(c);
        }
    }
}
