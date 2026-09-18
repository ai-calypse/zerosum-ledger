package dev.zerosum.verifier;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * The invariants that compare two stores (master §8.3): I1, I6, I6b, I7, I10 and I12.
 *
 * <p><strong>Each side is aggregated in its own database and compared here.</strong> The four service databases share
 * one PostgreSQL server only by deployment accident; a database link would make the check depend on that, and would
 * need a privilege the read-only role must not have. Each connection is the {@code verifier} role in a read-only
 * repeatable-read transaction, opened back to back by {@link VerifierMain}, so each database is read at one instant
 * and the four instants are milliseconds apart. That is why these checks are evidence only about a <em>quiesced</em>
 * system: on a live one, an order committed between two snapshots reads as missing.
 */
final class CrossStoreInvariants {

    static final String I1 = "every money order is zero-sum per currency";
    static final String I6 = "order ids in the orders DB = applied_orders, and 0 unresolved quarantine rows";
    static final String I6B = "every ledger balance = Σ entries for that account across all orders in the order store";
    static final String I7 = "provider ground truth ↔ attempts: one provider success per attempt, none unclaimed";
    static final String I10 = "0 attempts in SUBMITTING/UNKNOWN older than 5 min; 0 NEEDS_REVIEW";
    static final String I12 = "every break matches an injected report discrepancy, and every injection has its break";

    /** decision: D06-2 — the knob-to-break map, as the fault log spells the knob (Decision.faultType()). */
    static final Map<String, String> INJECTED_BREAK = Map.of(
            "report_missing_line", "MISSING_IN_REPORT",
            "report_off_by_one", "AMOUNT_MISMATCH",
            "report_duplicate_line", "DUPLICATE_LINE");

    /** Attempt statuses that mean "the provider moved this money". A payout is accepted once it is PENDING. */
    private static final Set<String> CLAIMS_SUCCESS = Set.of("SUCCEEDED", "SETTLED", "RETURNED");
    private static final Set<String> FAILED = Set.of("DECLINED", "FAILED", "CANCELLED");

    private CrossStoreInvariants() {
    }

    /** I1, on the orders database alone: per order and currency, the entries sum to zero. */
    static Check i1(Connection orders) throws SQLException {
        Check.Rows unbalanced = Check.rows(orders, """
                SELECT order_id::text || ': ' || string_agg(currency || ' sums to ' || total, ', ')
                FROM (SELECT order_id, currency, sum(amount_minor) AS total FROM money_order_entries
                      GROUP BY order_id, currency HAVING sum(amount_minor) <> 0) t
                GROUP BY order_id""");
        return Check.evaluated("I1", I1, unbalanced.count(), Check.metrics(
                "orders_checked", Check.scalar(orders, "SELECT count(*) FROM money_orders"),
                "unbalanced_orders", unbalanced.count()), unbalanced.sample());
    }

    /**
     * I6: the two id sets, both directions, by a streamed merge of two sorted cursors — neither set is held in memory.
     *
     * <p>Both sides are ordered by the {@code uuid} column and compared as text. PostgreSQL orders uuids by their bytes,
     * and the canonical lowercase-hex text form orders the same way, so the merge is exact.
     */
    static Check i6(Connection orders, Connection ledger) throws SQLException {
        long inStore = 0;
        long applied = 0;
        long missing = 0;
        long extra = 0;
        var sample = new ArrayList<String>();
        try (Statement o = orders.createStatement(); Statement l = ledger.createStatement()) {
            o.setFetchSize(10_000);
            l.setFetchSize(10_000);
            try (ResultSet ordered = o.executeQuery("SELECT order_id::text FROM money_orders ORDER BY order_id");
                    ResultSet appliedRows = l.executeQuery("SELECT order_id::text FROM applied_orders ORDER BY order_id")) {
                String a = next(ordered);
                String b = next(appliedRows);
                while (a != null || b != null) {
                    int cmp = a == null ? 1 : b == null ? -1 : a.compareTo(b);
                    if (cmp <= 0) {
                        inStore++;
                    }
                    if (cmp >= 0) {
                        applied++;
                    }
                    if (cmp < 0) {
                        missing++;
                        note(sample, "not applied: " + a);
                    } else if (cmp > 0) {
                        extra++;
                        note(sample, "applied, not in orders DB: " + b);
                    }
                    if (cmp <= 0) {
                        a = next(ordered);
                    }
                    if (cmp >= 0) {
                        b = next(appliedRows);
                    }
                }
            }
        }
        Check.Rows ledgerQuarantine = Check.rows(ledger, "SELECT 'ledger quarantine #' || quarantine_id || ' ' || "
                + "error_code FROM quarantined_orders WHERE resolved_at IS NULL ORDER BY quarantine_id");
        Check.Rows ordersQuarantine = Check.rows(orders, "SELECT 'orders quarantine #' || quarantine_id || ' ' || "
                + "error_code FROM quarantined_events WHERE resolved_at IS NULL ORDER BY quarantine_id");
        sample.addAll(ledgerQuarantine.sample());
        sample.addAll(ordersQuarantine.sample());
        long unresolved = ledgerQuarantine.count() + ordersQuarantine.count();
        return Check.evaluated("I6", I6, missing + extra + unresolved, Check.metrics(
                "orders_in_store", inStore, "applied_orders", applied, "missing_orders", missing,
                "extra_applied", extra, "unresolved_quarantined", unresolved), sample);
    }

    /**
     * I6b: each ledger balance against the sum of that account's entries over every order in the order store.
     * Deliberately independent of the changelog, which is what lets it catch a double apply that I3 cannot: an order
     * applied twice writes consistent changelog rows, so balance = Σ deltas still holds, but the balance is no longer
     * what the order store says it should be.
     */
    static Check i6b(Connection orders, Connection ledger) throws SQLException {
        var expected = new HashMap<String, Long>();
        try (Statement statement = orders.createStatement()) {
            statement.setFetchSize(10_000);
            try (ResultSet rows = statement.executeQuery("""
                    SELECT entity_id || '/' || account_code || '/' || currency, sum(amount_minor)
                    FROM money_order_entries GROUP BY entity_id, account_code, currency""")) {
                while (rows.next()) {
                    expected.put(rows.getString(1), rows.getLong(2));
                }
            }
        }
        long compared = 0;
        long drifting = 0;
        long drift = 0;
        var sample = new ArrayList<String>();
        try (Statement statement = ledger.createStatement()) {
            statement.setFetchSize(10_000);
            try (ResultSet rows = statement.executeQuery(
                    "SELECT entity_id || '/' || account_code || '/' || currency, balance_minor FROM accounts")) {
                while (rows.next()) {
                    compared++;
                    Long fromOrders = expected.remove(rows.getString(1));
                    long want = fromOrders == null ? 0 : fromOrders;
                    long diff = Math.subtractExact(rows.getLong(2), want);
                    if (diff != 0) {
                        drifting++;
                        drift = Math.addExact(drift, Math.absExact(diff));
                        note(sample, rows.getString(1) + " ledger=" + rows.getLong(2) + " orders=" + want);
                    }
                }
            }
        }
        // Accounts the order store implies but the ledger never opened: all of their money is missing.
        for (var left : new TreeMap<>(expected).entrySet()) {
            compared++;
            if (left.getValue() != 0) {
                drifting++;
                drift = Math.addExact(drift, Math.absExact(left.getValue()));
                note(sample, left.getKey() + " ledger=absent orders=" + left.getValue());
            }
        }
        return Check.evaluated("I6b", I6B, drifting, Check.metrics(
                "accounts_compared", compared, "accounts_drifting", drifting, "drift_abs_minor", drift), sample);
    }

    /**
     * I7: provider ground truth against our attempts, read straight from the fakeproviders database (the tables
     * {@code /admin/truth} itself reads), keyed by {@code client_reference}, which is the attempt id.
     *
     * <p>Card charges and refunds count when {@code SUCCEEDED}; bank payouts count unless {@code FAILED}, because a
     * payout FakeBank accepted has left the building even while it is still {@code PENDING}. "Duplicate" is every
     * provider success beyond the first for one attempt, and its minor units are everything beyond the smallest one.
     */
    static Check i7(Connection instruments, Connection providers) throws SQLException {
        record Truth(String kind, long successes, long totalMinor, long minMinor) {
        }
        var truth = new HashMap<String, Truth>();
        try (Statement statement = providers.createStatement()) {
            statement.setFetchSize(10_000);
            try (ResultSet rows = statement.executeQuery("""
                    SELECT 'CHARGE', client_reference, count(*), sum(amount_minor), min(amount_minor)
                      FROM card_charges WHERE status = 'SUCCEEDED' GROUP BY client_reference
                    UNION ALL
                    SELECT 'REFUND', client_reference, count(*), sum(amount_minor), min(amount_minor)
                      FROM card_refunds WHERE status = 'SUCCEEDED' GROUP BY client_reference
                    UNION ALL
                    SELECT 'PAYOUT', client_reference, count(*), sum(amount_minor), min(amount_minor)
                      FROM bank_payouts WHERE status <> 'FAILED' GROUP BY client_reference""")) {
                while (rows.next()) {
                    Truth row = new Truth(rows.getString(1), rows.getLong(3), rows.getLong(4), rows.getLong(5));
                    truth.merge(rows.getString(2), row, (x, y) -> new Truth(x.kind() + "+" + y.kind(),
                            x.successes() + y.successes(), x.totalMinor() + y.totalMinor(),
                            Math.min(x.minMinor(), y.minMinor())));
                }
            }
        }
        var attempts = new HashMap<String, String>();
        long withoutRecord = 0;
        var sample = new ArrayList<String>();
        try (Statement statement = instruments.createStatement()) {
            statement.setFetchSize(10_000);
            try (ResultSet rows = statement.executeQuery("SELECT attempt_id::text, kind, status FROM payment_attempts")) {
                while (rows.next()) {
                    String id = rows.getString(1);
                    String kind = rows.getString(2);
                    String status = rows.getString(3);
                    attempts.put(id, status);
                    boolean claims = CLAIMS_SUCCESS.contains(status) || ("PAYOUT".equals(kind) && "PENDING".equals(status));
                    if (claims && !truth.containsKey(id)) {
                        withoutRecord++;
                        note(sample, "attempt " + id + " " + kind + " " + status + " has no provider record");
                    }
                }
            }
        }
        long records = 0;
        long duplicates = 0;
        long duplicateMinor = 0;
        long withoutAttempt = 0;
        long onFailedAttempt = 0;
        for (var entry : new TreeMap<>(truth).entrySet()) {
            Truth row = entry.getValue();
            records += row.successes();
            if (row.successes() > 1) {
                duplicates += row.successes() - 1;
                duplicateMinor = Math.addExact(duplicateMinor, row.totalMinor() - row.minMinor());
                note(sample, row.kind() + " " + entry.getKey() + " succeeded " + row.successes() + " times");
            }
            String status = attempts.get(entry.getKey());
            if (status == null) {
                withoutAttempt++;
                note(sample, row.kind() + " success for " + entry.getKey() + ", which is no attempt");
            } else if (FAILED.contains(status)) {
                onFailedAttempt++;
                note(sample, row.kind() + " success for " + entry.getKey() + ", whose attempt is " + status);
            }
        }
        return Check.evaluated("I7", I7, duplicates + withoutRecord + withoutAttempt + onFailedAttempt, Check.metrics(
                "attempts", attempts.size(), "provider_success_records", records,
                "duplicate_charges", duplicates, "duplicate_minor", duplicateMinor,
                "success_without_provider_record", withoutRecord,
                "provider_success_without_attempt", withoutAttempt,
                "provider_success_on_failed_attempt", onFailedAttempt), sample);
    }

    /** I10, on the instruments database: nothing stuck in an uncertain state past the master's 5 minutes. */
    static Check i10(Connection instruments) throws SQLException {
        Check.Rows stuck = Check.rows(instruments, """
                SELECT attempt_id::text || ' ' || kind || ' ' || status || ' since ' || updated_at FROM payment_attempts
                WHERE status IN ('SUBMITTING','UNKNOWN') AND updated_at < now() - interval '5 minutes'
                ORDER BY updated_at""");
        Check.Rows review = Check.rows(instruments, """
                SELECT attempt_id::text || ' ' || kind || ' NEEDS_REVIEW since ' || updated_at FROM payment_attempts
                WHERE status = 'NEEDS_REVIEW' ORDER BY updated_at""");
        var sample = new ArrayList<>(stuck.sample());
        sample.addAll(review.sample());
        return Check.evaluated("I10", I10, stuck.count() + review.count(),
                Check.metrics("stuck_over_5_min", stuck.count(), "needs_review", review.count()), sample);
    }

    /**
     * I12: reconciliation breaks against the injected-discrepancy record in the fault log (§0.3 E2).
     *
     * <p>A break is <strong>explained</strong> only when it matches an injection of the mapped type, for the same
     * provider reference, in the same report (the report id is {@code rpt_YYYY_MM_DD}, so the same report is the same
     * report date). Its status does not explain it: an {@code OPEN} break nobody injected is still unexplained. A
     * {@code MISSING_IN_REPORT} break has no report line, so its provider reference is the attempt's.
     *
     * <p>Matched as multisets, so two injections on one key need two breaks.
     */
    static Check i12(Connection instruments, Connection providers) throws SQLException {
        var counts = new TreeMap<String, long[]>(); // key -> {breaks, injections}
        try (Statement statement = instruments.createStatement();
                ResultSet rows = statement.executeQuery("""
                        SELECT r.provider || ' ' || r.report_id || ' ' || b.break_type || ' '
                               || coalesce(b.provider_ref, a.provider_ref, 'null')
                        FROM reconciliation_breaks b JOIN reconciliation_runs r ON r.run_id = b.run_id
                        LEFT JOIN payment_attempts a ON a.attempt_id = b.attempt_id""")) {
            while (rows.next()) {
                counts.computeIfAbsent(rows.getString(1), k -> new long[2])[0]++;
            }
        }
        try (Statement statement = providers.createStatement();
                ResultSet rows = statement.executeQuery("""
                        SELECT provider, split_part(target, ':', 1), fault_type, split_part(target, ':', 2)
                        FROM fault_log WHERE fault_type IN ('report_missing_line','report_off_by_one',
                                                            'report_duplicate_line')""")) {
            while (rows.next()) {
                String key = rows.getString(1) + " " + rows.getString(2) + " " + INJECTED_BREAK.get(rows.getString(3))
                        + " " + rows.getString(4);
                counts.computeIfAbsent(key, k -> new long[2])[1]++;
            }
        }
        long breaks = 0;
        long injections = 0;
        long explained = 0;
        long unexplained = 0;
        long undetected = 0;
        var sample = new ArrayList<String>();
        for (var entry : counts.entrySet()) {
            long b = entry.getValue()[0];
            long i = entry.getValue()[1];
            breaks += b;
            injections += i;
            explained += Math.min(b, i);
            if (b > i) {
                unexplained += b - i;
                note(sample, "unexplained break: " + entry.getKey());
            } else if (i > b) {
                undetected += i - b;
                note(sample, "undetected injection: " + entry.getKey());
            }
        }
        return Check.evaluated("I12", I12, unexplained + undetected, Check.metrics(
                "reconciliation_runs", Check.scalar(instruments, "SELECT count(*) FROM reconciliation_runs"),
                "breaks", breaks, "injected_discrepancies", injections, "explained_breaks", explained,
                "unexplained_breaks", unexplained, "undetected_injections", undetected), sample);
    }

    private static String next(ResultSet rows) throws SQLException {
        return rows.next() ? rows.getString(1) : null;
    }

    /** Adds a sample row while there is room, so mass corruption cannot turn the sample into a copy of the table. */
    private static void note(List<String> sample, String row) {
        if (sample.size() < Check.SAMPLE) {
            sample.add(row);
        }
    }
}
