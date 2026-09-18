package dev.zerosum.verifier;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * One invariant's result, in the shape the JSON output carries: a status, a violation count, named metrics and a
 * bounded sample of offending rows.
 *
 * <p>{@link Status#SKIPPED} exists so that a check whose database was not given is reported as not evaluated. It is
 * never folded into a pass: "nothing looked" and "everything held" must not read the same.
 */
record Check(String id, String what, Status status, long violations, Map<String, Long> metrics, List<String> sample,
        String note) {

    /** Offending rows reported per check. The count is always exact; only the listing is bounded. */
    static final int SAMPLE = 10;

    enum Status { PASS, FAIL, SKIPPED }

    static Check evaluated(String id, String what, long violations, Map<String, Long> metrics, List<String> sample) {
        return new Check(id, what, violations == 0 ? Status.PASS : Status.FAIL, violations, metrics,
                List.copyOf(sample.subList(0, Math.min(SAMPLE, sample.size()))), null);
    }

    static Check skipped(String id, String what, String note) {
        return new Check(id, what, Status.SKIPPED, 0, Map.of(), List.of(), note);
    }

    String detail() {
        return switch (status) {
            case PASS -> "OK";
            case SKIPPED -> "SKIPPED (" + note + ")";
            case FAIL -> "VIOLATED, " + violations + ": " + String.join(", ", sample)
                    + (violations > sample.size() ? ", … (" + (violations - sample.size()) + " more not listed)" : "");
        };
    }

    // --- SQL helpers shared by every check ------------------------------------------------------------------------

    /** Every row's first column, counted exactly; only the first {@link #SAMPLE} are kept. Streamed, never buffered. */
    record Rows(long count, List<String> sample) {
    }

    static Rows rows(Connection connection, String sql) throws SQLException {
        var sample = new ArrayList<String>();
        long count = 0;
        try (Statement statement = connection.createStatement()) {
            statement.setFetchSize(10_000);
            try (ResultSet rows = statement.executeQuery(sql)) {
                while (rows.next()) {
                    if (count++ < SAMPLE) {
                        sample.add(rows.getString(1));
                    }
                }
            }
        }
        return new Rows(count, sample);
    }

    static long scalar(Connection connection, String sql) throws SQLException {
        try (Statement statement = connection.createStatement(); ResultSet rows = statement.executeQuery(sql)) {
            rows.next();
            return rows.getLong(1);
        }
    }

    /** An insertion-ordered metrics map from alternating name/value pairs. */
    static Map<String, Long> metrics(Object... pairs) {
        var metrics = new LinkedHashMap<String, Long>();
        for (int i = 0; i < pairs.length; i += 2) {
            metrics.put((String) pairs[i], ((Number) pairs[i + 1]).longValue());
        }
        return metrics;
    }
}
