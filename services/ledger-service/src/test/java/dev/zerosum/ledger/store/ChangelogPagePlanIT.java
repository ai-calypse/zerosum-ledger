package dev.zerosum.ledger.store;

import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.zerosum.ledger.support.ApplyTestDriver;
import dev.zerosum.ledger.support.LedgerTestDatabase;
import dev.zerosum.ledger.support.MoneyOrderPayloads;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Hot entities have very long changelogs, so the page query must walk the {@code (entity_id, seq)} primary key rather
 * than scanning (S02-T04). This runs the real statement from {@link LedgerStore#CHANGELOG_PAGE_SQL} through EXPLAIN and
 * records the plan; sharing the constant means the evidence cannot drift from the code.
 */
@Tag("integration")
class ChangelogPagePlanIT {

    private static final String ENTITY = "rider:R_PLAN";
    private static final int ORDERS = 500;

    @Test
    void thePageQueryWalksThePrimaryKeyRange() throws SQLException {
        try (LedgerTestDatabase db = LedgerTestDatabase.start()) {
            ApplyTestDriver driver = ApplyTestDriver.create(db.dataSource(LedgerTestDatabase.APP));
            List<String> batch = new ArrayList<>();
            for (int i = 0; i < ORDERS; i++) {
                batch.add(MoneyOrderPayloads.transfer(UUID.randomUUID(), "plan_" + i,
                        ENTITY, "receivable", "driver:D_PLAN", "payable", "USD", 100L + i));
                if (batch.size() == 50) {
                    driver.applyBatch(List.copyOf(batch));
                    batch.clear();
                }
            }

            String sql = LedgerStore.CHANGELOG_PAGE_SQL
                    .replace(":entityId", "'" + ENTITY + "'")
                    .replace(":afterSeq", "250")
                    .replace(":limit", "100");

            List<String> plan = new ArrayList<>();
            try (Connection c = db.connect(LedgerTestDatabase.APP); Statement st = c.createStatement()) {
                st.execute("ANALYZE entity_changelog");
                st.execute("ANALYZE applied_orders");
                try (ResultSet rs = st.executeQuery("EXPLAIN " + sql)) {
                    while (rs.next()) {
                        plan.add(rs.getString(1));
                    }
                }
            }

            String text = String.join("\n", plan);
            System.out.println("ZS-PLAN changelog page (" + ORDERS + " rows for " + ENTITY + "):\n" + text);
            assertTrue(text.contains("entity_changelog_pkey"),
                    "the page query must use the (entity_id, seq) primary key; plan was:\n" + text);
            assertTrue(!text.contains("Seq Scan on entity_changelog"),
                    "the changelog must not be sequentially scanned; plan was:\n" + text);
        }
    }
}
