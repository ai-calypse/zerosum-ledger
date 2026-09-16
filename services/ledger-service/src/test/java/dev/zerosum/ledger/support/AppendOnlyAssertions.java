package dev.zerosum.ledger.support;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;

/**
 * Reusable assertions for the D02-2 append-only pattern. S03 and S05 copy this helper next to their own copy of the
 * migration block (there is no shared test library; D00-2).
 */
public final class AppendOnlyAssertions {

    /** decision: D02-2 — the single error code for rejected mutations (privilege check and trigger alike). */
    public static final String APPEND_ONLY_SQLSTATE = "42501";

    public enum Mutation {
        UPDATE,
        DELETE,
        TRUNCATE
    }

    private AppendOnlyAssertions() {
    }

    /**
     * Asserts that {@code mutation} on {@code table} fails with {@link #APPEND_ONLY_SQLSTATE} and changes nothing.
     *
     * @param setClause a no-op assignment for UPDATE, e.g. {@code "order_group_id = order_group_id"}
     * @param where     a predicate matching at least one existing row, so row triggers actually fire
     */
    public static void assertRejected(Connection connection, String table, Mutation mutation, String setClause, String where)
            throws SQLException {
        long before = count(connection, table);
        assertTrue(before > 0, table + " needs at least one row for a meaningful " + mutation + " check");
        String sql = switch (mutation) {
            case UPDATE -> "UPDATE " + table + " SET " + setClause + " WHERE " + where;
            case DELETE -> "DELETE FROM " + table + " WHERE " + where;
            case TRUNCATE -> "TRUNCATE " + table;
        };
        try (Statement st = connection.createStatement()) {
            SQLException e = assertThrows(SQLException.class, () -> st.execute(sql), sql);
            assertEquals(APPEND_ONLY_SQLSTATE, e.getSQLState(), sql + ": " + e.getMessage());
        }
        assertEquals(before, count(connection, table), table + " row count changed after rejected " + mutation);
    }

    private static long count(Connection connection, String table) throws SQLException {
        try (Statement st = connection.createStatement(); var rs = st.executeQuery("SELECT count(*) FROM " + table)) {
            rs.next();
            return rs.getLong(1);
        }
    }
}
