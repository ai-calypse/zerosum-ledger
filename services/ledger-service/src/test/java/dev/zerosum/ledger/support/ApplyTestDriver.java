package dev.zerosum.ledger.support;

import dev.zerosum.ledger.apply.ApplyBatchResult;
import dev.zerosum.ledger.apply.ApplyRecord;
import dev.zerosum.ledger.apply.LedgerApplyEngine;
import dev.zerosum.ledger.apply.LedgerApplyProperties;
import dev.zerosum.ledger.apply.OrderDecoder;
import dev.zerosum.ledger.apply.RetryClassifier;
import dev.zerosum.ledger.changelog.ChangelogHasher;
import dev.zerosum.ledger.store.LedgerStore;
import java.time.Duration;
import java.util.List;
import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;

/**
 * In-process driver for the apply engine (D02-3): wires the engine against a {@link DataSource} without a Spring
 * context, so integration tests, the stress harness (S02-T03) and the SP1 runner (S02-T07) share one entrypoint.
 * Source positions are null unless a test supplies them.
 */
public final class ApplyTestDriver {

    /** Test defaults; production values live in application.yml (D02-4). */
    public static final LedgerApplyProperties DEFAULT_PROPERTIES = new LedgerApplyProperties(
            Duration.ofSeconds(2), Duration.ofSeconds(5), 10, Duration.ofMillis(100), Duration.ofSeconds(5), 1);

    private final LedgerApplyEngine engine;

    private ApplyTestDriver(LedgerApplyEngine engine) {
        this.engine = engine;
    }

    public static ApplyTestDriver create(DataSource dataSource) {
        return create(dataSource, DEFAULT_PROPERTIES);
    }

    public static ApplyTestDriver create(DataSource dataSource, LedgerApplyProperties properties) {
        JdbcTemplate template = new JdbcTemplate(dataSource);
        LedgerStore store = new LedgerStore(JdbcClient.create(dataSource), template);
        return new ApplyTestDriver(new LedgerApplyEngine(new OrderDecoder(), store, new ChangelogHasher(),
                new RetryClassifier(), properties, new DataSourceTransactionManager(dataSource)));
    }

    public LedgerApplyEngine engine() {
        return engine;
    }

    /** Applies one payload per call (per-order mode). */
    public ApplyBatchResult applyEachSeparately(List<String> payloads) {
        ApplyBatchResult last = null;
        for (String payload : payloads) {
            last = engine.applyOne(ApplyRecord.of(payload));
        }
        return last;
    }

    /** Applies all payloads as one batch. */
    public ApplyBatchResult applyBatch(List<String> payloads) {
        return engine.apply(payloads.stream().map(ApplyRecord::of).toList());
    }

    public ApplyBatchResult applyOne(String payload) {
        return engine.applyOne(ApplyRecord.of(payload));
    }

    public ApplyBatchResult apply(List<ApplyRecord> records) {
        return engine.apply(records);
    }
}
