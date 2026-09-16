package dev.zerosum.ledger.support;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeMap;

/** Read helpers and invariant checks (I2–I4) for ledger tests; they need only SELECT privileges. */
public final class LedgerQueries {

    private LedgerQueries() {
    }

    /** Signed balances keyed {@code entity/account/currency}. */
    public static TreeMap<String, Long> balances(Connection c) throws SQLException {
        TreeMap<String, Long> out = new TreeMap<>();
        try (Statement st = c.createStatement();
                ResultSet rs = st.executeQuery("SELECT entity_id, account_code, currency, balance_minor FROM accounts")) {
            while (rs.next()) {
                out.put(rs.getString(1) + "/" + rs.getString(2) + "/" + rs.getString(3).strip(), rs.getLong(4));
            }
        }
        return out;
    }

    public static long count(Connection c, String sql) throws SQLException {
        try (Statement st = c.createStatement(); ResultSet rs = st.executeQuery(sql)) {
            rs.next();
            return rs.getLong(1);
        }
    }

    public static long lastSeq(Connection c, String entityId) throws SQLException {
        return count(c, "SELECT last_seq FROM entities WHERE entity_id = '" + entityId + "'");
    }

    /** I2: the global sum per currency is zero. Returns the currencies that violate it. */
    public static List<String> i2Violations(Connection c) throws SQLException {
        return strings(c, "SELECT currency FROM accounts GROUP BY currency HAVING sum(balance_minor) <> 0");
    }

    /** I3: every account balance equals the sum of its changelog deltas, and running balances are correct. */
    public static List<String> i3Violations(Connection c) throws SQLException {
        List<String> out = new ArrayList<>(strings(c, """
                SELECT a.entity_id || '/' || a.account_code || '/' || a.currency
                FROM accounts a LEFT JOIN (
                  SELECT entity_id, account_code, currency, sum(delta_minor) AS total
                  FROM entity_changelog GROUP BY entity_id, account_code, currency) d
                ON d.entity_id = a.entity_id AND d.account_code = a.account_code AND d.currency = a.currency
                WHERE a.balance_minor <> coalesce(d.total, 0)"""));
        out.addAll(strings(c, """
                SELECT entity_id || '#' || seq FROM (
                  SELECT entity_id, seq, balance_after_minor,
                         sum(delta_minor) OVER (PARTITION BY entity_id, account_code, currency ORDER BY seq) AS running
                  FROM entity_changelog) t
                WHERE balance_after_minor <> running"""));
        return out;
    }

    /** I4: per-entity sequence numbers are gapless and start at 1. */
    public static List<String> i4Violations(Connection c) throws SQLException {
        return strings(c, """
                SELECT entity_id FROM entity_changelog GROUP BY entity_id
                HAVING max(seq) <> count(*) OR min(seq) <> 1""");
    }

    /** The prev_hash of every row equals the previous row's row_hash (full chain verification arrives in S02-T06). */
    public static List<String> chainLinkViolations(Connection c) throws SQLException {
        return strings(c, """
                SELECT entity_id || '#' || seq FROM (
                  SELECT entity_id, seq, prev_hash, lag(row_hash) OVER (PARTITION BY entity_id ORDER BY seq) AS expected
                  FROM entity_changelog) t
                WHERE prev_hash IS DISTINCT FROM expected""");
    }

    private static List<String> strings(Connection c, String sql) throws SQLException {
        List<String> out = new ArrayList<>();
        try (Statement st = c.createStatement(); ResultSet rs = st.executeQuery(sql)) {
            while (rs.next()) {
                out.add(rs.getString(1));
            }
        }
        return out;
    }
}
