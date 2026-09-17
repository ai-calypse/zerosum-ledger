package dev.zerosum.verifier;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

/**
 * The ledger invariants I2, I3 and I4 (master §8.3), as plain SQL over the ledger database.
 *
 * <p><strong>The SQL is copied from {@code InvariantQueries} (D02-8), deliberately, and the copy is the point of
 * this comment.</strong> That class is a Spring {@code @Component} inside ledger-service; importing it would put the
 * whole service — its auto-configuration, its Kafka client, its migrations — behind a command-line tool, and would
 * make the verifier depend on the service whose books it is supposed to audit independently. D02-8 anticipated this
 * and wrote each check as a standalone {@code SELECT} needing only {@code SELECT} privileges, "so the S06 verifier
 * can reuse them unchanged". The cost is that a change to one must be made in the other; the benefit is that the
 * verifier can be pointed at a database whose service is not running, which is the situation it exists for.
 *
 * <p>No check takes a lock, and the caller supplies the snapshot: {@link VerifierMain} runs all of them inside one
 * read-only repeatable-read transaction, so every figure describes the same instant. Running them in separate
 * transactions against a live ledger would report violations that are only the gap between two queries.
 */
final class LedgerInvariants {

    /**
     * Violations are listed, not just counted, because "I3 failed" sends an operator to a table with millions of
     * rows. This bounds how many identifiers are read back; the check still fails on the first one.
     */
    private static final int MAX_REPORTED = 1_000;

    private LedgerInvariants() {
    }

    /** One invariant's result. Empty {@link #violations()} is a pass. */
    record Check(String id, String what, List<String> violations, boolean truncated) {

        boolean passed() {
            return violations.isEmpty();
        }

        String detail() {
            if (passed()) {
                return "OK";
            }
            String shown = String.join(", ", violations.subList(0, Math.min(20, violations.size())));
            String more = violations.size() > 20 ? ", … " + (violations.size() - 20) + " more" : "";
            String cap = truncated ? " (list truncated at " + MAX_REPORTED + "; the real count is higher)" : "";
            return "VIOLATED, " + violations.size() + cap + ": " + shown + more;
        }
    }

    /** What the checks ran over, so that "0 violations" over an empty ledger cannot be mistaken for a clean ledger. */
    record Scope(long entities, long accounts, long changelogRows, long appliedOrders) {

        boolean isEmpty() {
            return accounts == 0 && changelogRows == 0;
        }

        @Override
        public String toString() {
            return "entities=" + entities + " accounts=" + accounts + " changelog_rows=" + changelogRows
                    + " applied_orders=" + appliedOrders;
        }
    }

    static Scope scope(Connection connection) throws SQLException {
        return new Scope(count(connection, "entities"), count(connection, "accounts"),
                count(connection, "entity_changelog"), count(connection, "applied_orders"));
    }

    static List<Check> checkAll(Connection connection) throws SQLException {
        return List.of(i2(connection), i3(connection), i4(connection));
    }

    /** I2: the global sum of balances per currency is zero. Returns the currencies that violate it. */
    private static Check i2(Connection connection) throws SQLException {
        return check(connection, "I2", "global per-currency sum of ledger balances is 0",
                "SELECT currency FROM accounts GROUP BY currency HAVING sum(balance_minor) <> 0");
    }

    /**
     * I3: every account balance equals the sum of its changelog deltas, <em>and</em> every stored running balance is
     * correct. Both halves matter: the stored balances can agree with the deltas while one row's
     * {@code balance_after_minor} is wrong, and that is still an inconsistency an audit would trip over.
     */
    private static Check i3(Connection connection) throws SQLException {
        Check balances = check(connection, "I3", "account balance = Σ its changelog deltas", """
                SELECT a.entity_id || '/' || a.account_code || '/' || a.currency
                FROM accounts a LEFT JOIN (
                  SELECT entity_id, account_code, currency, sum(delta_minor) AS total
                  FROM entity_changelog GROUP BY entity_id, account_code, currency) d
                  ON d.entity_id = a.entity_id AND d.account_code = a.account_code AND d.currency = a.currency
                WHERE a.balance_minor <> coalesce(d.total, 0)""");
        Check running = check(connection, "I3", "balance_after_minor is a correct running sum", """
                SELECT entity_id || '#' || seq FROM (
                  SELECT entity_id, seq, balance_after_minor,
                         sum(delta_minor) OVER (PARTITION BY entity_id, account_code, currency ORDER BY seq) AS running
                  FROM entity_changelog) t
                WHERE balance_after_minor <> running""");

        var violations = new ArrayList<>(balances.violations());
        violations.addAll(running.violations());
        return new Check("I3", "account balance = Σ changelog deltas, and balance_after_minor is a correct running sum",
                List.copyOf(violations), balances.truncated() || running.truncated());
    }

    /** I4: per-entity changelog sequence numbers are gapless and start at 1. */
    private static Check i4(Connection connection) throws SQLException {
        return check(connection, "I4", "changelog seq is gapless per entity", """
                SELECT entity_id FROM entity_changelog GROUP BY entity_id
                HAVING max(seq) <> count(*) OR min(seq) <> 1""");
    }

    private static Check check(Connection connection, String id, String what, String sql) throws SQLException {
        var violations = new ArrayList<String>();
        try (Statement statement = connection.createStatement()) {
            statement.setMaxRows(MAX_REPORTED);
            try (ResultSet rows = statement.executeQuery(sql)) {
                while (rows.next()) {
                    violations.add(rows.getString(1));
                }
            }
        }
        return new Check(id, what, List.copyOf(violations), violations.size() == MAX_REPORTED);
    }

    private static long count(Connection connection, String table) throws SQLException {
        try (Statement statement = connection.createStatement();
                ResultSet rows = statement.executeQuery("SELECT count(*) FROM " + table)) {
            rows.next();
            return rows.getLong(1);
        }
    }
}
