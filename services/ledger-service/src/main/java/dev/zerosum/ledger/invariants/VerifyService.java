package dev.zerosum.ledger.invariants;

import dev.zerosum.ledger.api.LedgerApiProperties;
import dev.zerosum.ledger.changelog.ChainVerifier;
import dev.zerosum.ledger.changelog.ChangelogHasher;
import dev.zerosum.ledger.store.LedgerStore;
import dev.zerosum.ledger.store.LedgerStore.AccountBalance;
import dev.zerosum.ledger.store.LedgerStore.ChangelogRow;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Rebuilds one entity's balances from its changelog and verifies its hash chain (M6 (b), I5).
 *
 * <p>Runs in a single read-only REPEATABLE READ transaction with the D02-8 operational statement timeout, and streams
 * the changelog in sequence order at a bounded fetch size, so a very large entity is never materialized in full. Three
 * things are checked as the rows stream past: every row's stored running balance, the hash chain, and finally the
 * rebuilt balances against the stored accounts. The lowest failing sequence number wins, because several rows can be
 * bad at once.
 */
@Service
public class VerifyService {

    private final LedgerStore store;
    private final ChainVerifier chainVerifier;
    private final ChangelogHasher hasher;
    private final JdbcTemplate template;
    private final LedgerApiProperties properties;
    private final TransactionTemplate transactions;

    public VerifyService(LedgerStore store, ChainVerifier chainVerifier, ChangelogHasher hasher, JdbcTemplate template,
            LedgerApiProperties properties, PlatformTransactionManager transactionManager) {
        this.store = store;
        this.chainVerifier = chainVerifier;
        this.hasher = hasher;
        this.template = template;
        this.properties = properties;
        this.transactions = new TransactionTemplate(transactionManager);
        this.transactions.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
        this.transactions.setReadOnly(true);
    }

    /** What verify found. {@code firstBadSeq} and {@code failure} are null exactly when {@code consistent} is true. */
    public record Result(String entityId, boolean consistent, long rowsChecked, Long firstBadSeq, String failure,
            String detail) {
    }

    /** Empty when the entity does not exist. */
    public Optional<Result> verify(String entityId) {
        return Optional.ofNullable(transactions.execute(status -> {
            template.execute("SET LOCAL statement_timeout = '"
                    + properties.operationalStatementTimeout().toMillis() + "ms'");
            if (store.readBalancesSnapshot(entityId).isEmpty()) {
                return null;
            }
            return verifyStreamed(entityId);
        }));
    }

    private Result verifyStreamed(String entityId) {
        Map<String, Long> rebuilt = new HashMap<>();
        List<ChangelogRow> forChain = new ArrayList<>(properties.verifyFetchSize());
        long[] rowsChecked = {0};
        Long[] firstBad = {null};
        String[] failure = {null, null};          // failure name, detail

        template.query(connection -> {
            var ps = connection.prepareStatement("""
                    SELECT entity_id, seq, order_id, account_code, currency, delta_minor, balance_after_minor,
                           hash_version, prev_hash, row_hash
                    FROM entity_changelog WHERE entity_id = ? ORDER BY seq""",
                    ResultSet.TYPE_FORWARD_ONLY, ResultSet.CONCUR_READ_ONLY);
            ps.setString(1, entityId);
            ps.setFetchSize(properties.verifyFetchSize());
            return ps;
        }, (ResultSet rs) -> {
            ChangelogRow row = read(rs);
            rowsChecked[0]++;
            forChain.add(row);

            // Rebuild the running balance for this account and compare it with the value stored on the row.
            String key = row.accountCode() + "/" + row.currency();
            long running = Math.addExact(rebuilt.getOrDefault(key, 0L), row.deltaMinor());
            rebuilt.put(key, running);
            if (running != row.balanceAfterMinor() && firstBad[0] == null) {
                firstBad[0] = row.seq();
                failure[0] = "RUNNING_BALANCE_MISMATCH";
                failure[1] = "row " + row.seq() + " stores balance_after " + row.balanceAfterMinor()
                        + " but the changelog rebuilds to " + running;
            }
        });

        ChainVerifier.Result chain = chainVerifier.verify(forChain);
        if (!chain.consistent() && (firstBad[0] == null || chain.firstBadSeq() < firstBad[0])) {
            firstBad[0] = chain.firstBadSeq();
            failure[0] = chain.failure().name();
            failure[1] = chain.detail();
        }

        // Finally the stored balances, which can disagree even when every row's own running balance is correct.
        if (firstBad[0] == null) {
            for (AccountBalance stored : store.readBalancesSnapshot(entityId).orElseThrow().accounts()) {
                long expected = rebuilt.getOrDefault(stored.accountCode() + "/" + stored.currency(), 0L);
                if (expected != stored.signedMinor()) {
                    failure[0] = "BALANCE_MISMATCH";
                    failure[1] = stored.accountCode() + "/" + stored.currency() + " stores " + stored.signedMinor()
                            + " but the changelog rebuilds to " + expected;
                    firstBad[0] = rowsChecked[0];
                    break;
                }
            }
        }

        return new Result(entityId, firstBad[0] == null, rowsChecked[0], firstBad[0], failure[0], failure[1]);
    }

    private static ChangelogRow read(ResultSet rs) throws SQLException {
        return new ChangelogRow(rs.getString("entity_id"), rs.getLong("seq"), rs.getObject("order_id", UUID.class),
                rs.getString("account_code"), rs.getString("currency").strip(), rs.getLong("delta_minor"),
                rs.getLong("balance_after_minor"), rs.getShort("hash_version"), rs.getBytes("prev_hash"),
                rs.getBytes("row_hash"));
    }
}
