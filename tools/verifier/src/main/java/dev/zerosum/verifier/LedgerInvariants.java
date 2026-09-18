package dev.zerosum.verifier;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;

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
 * read-only repeatable-read transaction on the ledger database, so every ledger figure describes the same instant.
 * Running them in separate transactions against a live ledger would report violations that are only the gap between
 * two queries.
 */
final class LedgerInvariants {

    static final String I2 = "global per-currency sum of ledger balances is 0";
    static final String I3 = "account balance = Σ changelog deltas, and balance_after_minor is a correct running sum";
    static final String I4 = "changelog seq is gapless per entity";

    private LedgerInvariants() {
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
        return new Scope(Check.scalar(connection, "SELECT count(*) FROM entities"),
                Check.scalar(connection, "SELECT count(*) FROM accounts"),
                Check.scalar(connection, "SELECT count(*) FROM entity_changelog"),
                Check.scalar(connection, "SELECT count(*) FROM applied_orders"));
    }

    /** I2: the global sum of balances per currency is zero. Lists the currencies that violate it. */
    static Check i2(Connection connection) throws SQLException {
        Check.Rows currencies = Check.rows(connection,
                "SELECT currency FROM accounts GROUP BY currency HAVING sum(balance_minor) <> 0");
        return Check.evaluated("I2", I2, currencies.count(), Check.metrics("nonzero_currencies", currencies.count()),
                currencies.sample());
    }

    /**
     * I3: every account balance equals the sum of its changelog deltas, <em>and</em> every stored running balance is
     * correct. Both halves matter: the stored balances can agree with the deltas while one row's
     * {@code balance_after_minor} is wrong, and that is still an inconsistency an audit would trip over.
     */
    static Check i3(Connection connection) throws SQLException {
        Check.Rows balances = Check.rows(connection, """
                SELECT a.entity_id || '/' || a.account_code || '/' || a.currency
                FROM accounts a LEFT JOIN (
                  SELECT entity_id, account_code, currency, sum(delta_minor) AS total
                  FROM entity_changelog GROUP BY entity_id, account_code, currency) d
                  ON d.entity_id = a.entity_id AND d.account_code = a.account_code AND d.currency = a.currency
                WHERE a.balance_minor <> coalesce(d.total, 0)""");
        Check.Rows running = Check.rows(connection, """
                SELECT entity_id || '#' || seq FROM (
                  SELECT entity_id, seq, balance_after_minor,
                         sum(delta_minor) OVER (PARTITION BY entity_id, account_code, currency ORDER BY seq) AS running
                  FROM entity_changelog) t
                WHERE balance_after_minor <> running""");
        var sample = new ArrayList<>(balances.sample());
        sample.addAll(running.sample());
        return Check.evaluated("I3", I3, balances.count() + running.count(),
                Check.metrics("accounts_mismatched", balances.count(), "running_sum_rows_wrong", running.count()),
                sample);
    }

    /** I4: per-entity changelog sequence numbers are gapless and start at 1. */
    static Check i4(Connection connection) throws SQLException {
        Check.Rows entities = Check.rows(connection, """
                SELECT entity_id FROM entity_changelog GROUP BY entity_id
                HAVING max(seq) <> count(*) OR min(seq) <> 1""");
        return Check.evaluated("I4", I4, entities.count(), Check.metrics("entities_with_gaps", entities.count()),
                entities.sample());
    }
}
