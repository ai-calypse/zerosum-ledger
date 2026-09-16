package dev.zerosum.ledger.invariants;

import dev.zerosum.money.ChartOfAccounts;
import java.util.List;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * The ledger-side invariant queries I2-I4, plus the operational counts the invariants endpoint reports (D02-8).
 *
 * <p>Each unit is a standalone SELECT that needs only select privileges and has no web or apply dependency, so the S06
 * verifier can reuse them unchanged. None of them takes a lock. The caller decides the snapshot: {@link InvariantsService}
 * runs them together in one read-only repeatable-read transaction so every figure describes the same state.
 */
@Component
public class InvariantQueries {

    private final JdbcClient jdbc;

    public InvariantQueries(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** I2: the global sum per currency is zero. Returns the currencies that violate it. */
    public List<String> i2Violations() {
        return jdbc.sql("SELECT currency FROM accounts GROUP BY currency HAVING sum(balance_minor) <> 0")
                .query(String.class).list();
    }

    /**
     * I3: every account balance equals the sum of its changelog deltas, and every stored running balance is correct.
     * Both halves matter: stored balances can agree with the deltas while one row's {@code balance_after_minor} is
     * wrong, and that is still an inconsistency.
     */
    public List<String> i3Violations() {
        List<String> violations = new java.util.ArrayList<>(jdbc.sql("""
                SELECT a.entity_id || '/' || a.account_code || '/' || a.currency
                FROM accounts a LEFT JOIN (
                  SELECT entity_id, account_code, currency, sum(delta_minor) AS total
                  FROM entity_changelog GROUP BY entity_id, account_code, currency) d
                  ON d.entity_id = a.entity_id AND d.account_code = a.account_code AND d.currency = a.currency
                WHERE a.balance_minor <> coalesce(d.total, 0)""").query(String.class).list());
        violations.addAll(jdbc.sql("""
                SELECT entity_id || '#' || seq FROM (
                  SELECT entity_id, seq, balance_after_minor,
                         sum(delta_minor) OVER (PARTITION BY entity_id, account_code, currency ORDER BY seq) AS running
                  FROM entity_changelog) t
                WHERE balance_after_minor <> running""").query(String.class).list());
        return List.copyOf(violations);
    }

    /** I4: per-entity sequence numbers are gapless and start at 1. */
    public List<String> i4Violations() {
        return jdbc.sql("""
                SELECT entity_id FROM entity_changelog GROUP BY entity_id
                HAVING max(seq) <> count(*) OR min(seq) <> 1""").query(String.class).list();
    }

    /** Unresolved quarantine rows only: a row is resolved once an operator sets {@code resolved_at} (D02-9). */
    public long unresolvedQuarantineCount() {
        return jdbc.sql("SELECT count(*) FROM quarantined_orders WHERE resolved_at IS NULL")
                .query(Long.class).single();
    }

    /**
     * Clearing accounts whose balance is not zero (§0.3 C13). Money rests in a clearing account only while it is in
     * flight, so a non-zero balance is the I9 signal. The account codes come from {@link ChartOfAccounts} (D01-6,
     * CR-S02-04), never from a copy in this service, and no age or settlement-cycle filter is applied here: the S06
     * verifier judges age and break matching.
     */
    public List<ClearingBalance> nonZeroClearingBalances() {
        return jdbc.sql("""
                SELECT entity_id, account_code, currency, balance_minor FROM accounts
                WHERE account_code = ANY(?) AND balance_minor <> 0
                ORDER BY entity_id, account_code, currency""")
                .param(ChartOfAccounts.clearingAccounts().toArray(String[]::new))
                .query((rs, rowNumber) -> new ClearingBalance(rs.getString("entity_id"), rs.getString("account_code"),
                        rs.getString("currency").strip(), rs.getLong("balance_minor")))
                .list();
    }

    /** One clearing account that has not returned to zero. */
    public record ClearingBalance(String entityId, String accountCode, String currency, long signedMinor) {
    }
}
