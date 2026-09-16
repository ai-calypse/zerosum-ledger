package dev.zerosum.ledger.invariants;

import dev.zerosum.ledger.api.LedgerApiProperties;
import dev.zerosum.ledger.invariants.InvariantQueries.ClearingBalance;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Runs every invariant query for one response inside a single read-only REPEATABLE READ transaction (D02-8), so I2, I3,
 * I4, the quarantine count and the clearing report all describe the same snapshot even while applies commit.
 *
 * <p>The transaction raises {@code statement_timeout} above the global apply limit, because these reads scan (§0.3 C19).
 * This endpoint is operational rather than interactive: a long snapshot on a busy database delays vacuum, which the
 * OpenAPI description states.
 */
@Service
public class InvariantsService {

    private final InvariantQueries queries;
    private final JdbcTemplate template;
    private final LedgerApiProperties properties;
    private final TransactionTemplate transactions;

    public InvariantsService(InvariantQueries queries, JdbcTemplate template, LedgerApiProperties properties,
            PlatformTransactionManager transactionManager) {
        this.queries = queries;
        this.template = template;
        this.properties = properties;
        this.transactions = new TransactionTemplate(transactionManager);
        this.transactions.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
        this.transactions.setReadOnly(true);
    }

    /**
     * I5 is not evaluated across every entity here. Scanning every chain would rehash the whole changelog, which cannot
     * meet the P4 target for this endpoint on a large ledger; per-entity verify and the S06 verifier own it instead
     * (D02-8). The report says so rather than implying I5 passed.
     */
    public Report report() {
        return transactions.execute(status -> {
            template.execute("SET LOCAL statement_timeout = '"
                    + properties.operationalStatementTimeout().toMillis() + "ms'");
            return new Report(queries.i2Violations(), queries.i3Violations(), queries.i4Violations(),
                    queries.unresolvedQuarantineCount(), queries.nonZeroClearingBalances());
        });
    }

    /**
     * @param i2Currencies               currencies whose global sum is not zero; empty means I2 holds
     * @param i3Accounts                 accounts or rows whose balances disagree with the changelog; empty means I3 holds
     * @param i4Entities                 entities with a sequence gap; empty means I4 holds
     * @param unresolvedQuarantinedCount unresolved quarantine rows (D02-9)
     * @param nonZeroClearingBalances    clearing accounts that have not returned to zero (§0.3 C13)
     */
    public record Report(List<String> i2Currencies, List<String> i3Accounts, List<String> i4Entities,
            long unresolvedQuarantinedCount, List<ClearingBalance> nonZeroClearingBalances) {

        public boolean consistent() {
            return i2Currencies.isEmpty() && i3Accounts.isEmpty() && i4Entities.isEmpty();
        }
    }
}
