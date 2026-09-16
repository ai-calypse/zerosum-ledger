package dev.zerosum.ledger.invariants;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.zerosum.ledger.api.LedgerApiProperties;
import dev.zerosum.ledger.changelog.ChainVerifier;
import dev.zerosum.ledger.changelog.ChangelogHasher;
import dev.zerosum.ledger.store.LedgerStore;
import dev.zerosum.ledger.support.ApplyTestDriver;
import dev.zerosum.ledger.support.LedgerTestDatabase;
import dev.zerosum.ledger.support.MoneyOrderPayloads;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;

/**
 * Informal timing of verify and the invariants report on a large entity, recorded as context only. This is deliberately
 * <em>not</em> a performance gate: P4 is measured in S07 on the reference environment, and this container runs with
 * {@code fsync=off}, so the numbers say only that neither read is pathological at this size.
 */
@Tag("integration")
class OperationalReadTimingIT {

    /** Matches the row count in the master's P4 reference point for verify. */
    private static final int ROWS = 10_000;
    private static final String ENTITY = "rider:R_TIMING";

    @Test
    void verifyAndInvariantsCompleteOnALargeEntity() {
        try (LedgerTestDatabase db = LedgerTestDatabase.start()) {
            DataSource dataSource = db.dataSource(LedgerTestDatabase.APP);
            ApplyTestDriver driver = ApplyTestDriver.create(dataSource);

            long seedStart = System.nanoTime();
            List<String> batch = new ArrayList<>();
            for (int i = 0; i < ROWS; i++) {
                batch.add(MoneyOrderPayloads.transfer(UUID.randomUUID(), "timing_" + i,
                        ENTITY, "receivable", "driver:D_TIMING", "payable", "USD", 100L + (i % 500)));
                if (batch.size() == 100) {
                    driver.applyBatch(List.copyOf(batch));
                    batch.clear();
                }
            }
            long seedMillis = (System.nanoTime() - seedStart) / 1_000_000;

            LedgerStore store = new LedgerStore(JdbcClient.create(dataSource), new JdbcTemplate(dataSource));
            LedgerApiProperties properties = new LedgerApiProperties(100, 500, Duration.ofSeconds(15), 500);
            var transactionManager = new DataSourceTransactionManager(dataSource);
            var queries = new InvariantQueries(JdbcClient.create(dataSource));
            var invariants = new InvariantsService(queries, new JdbcTemplate(dataSource), properties, transactionManager);
            var verify = new VerifyService(store, new ChainVerifier(new ChangelogHasher()), new ChangelogHasher(),
                    new JdbcTemplate(dataSource), properties, transactionManager);

            long verifyStart = System.nanoTime();
            VerifyService.Result result = verify.verify(ENTITY).orElseThrow();
            long verifyMillis = (System.nanoTime() - verifyStart) / 1_000_000;

            long invariantsStart = System.nanoTime();
            InvariantsService.Report report = invariants.report();
            long invariantsMillis = (System.nanoTime() - invariantsStart) / 1_000_000;

            System.out.printf("ZS-TIMING rows=%d seedMs=%d verifyMs=%d invariantsMs=%d rowsChecked=%d "
                            + "(context only; fsync=off, not a P4 measurement)%n",
                    ROWS, seedMillis, verifyMillis, invariantsMillis, result.rowsChecked());

            assertTrue(result.consistent(), "the generated entity must verify: " + result);
            assertEquals(ROWS, result.rowsChecked(), "verify streams every changelog row of the entity");
            assertTrue(report.consistent(), "invariants must hold over the generated ledger");
        }
    }
}
