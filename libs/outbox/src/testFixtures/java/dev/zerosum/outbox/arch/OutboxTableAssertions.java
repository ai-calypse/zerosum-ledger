package dev.zerosum.outbox.arch;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeSet;

/**
 * Asserts a service's copy of the outbox table matches what the library expects (D03-5).
 *
 * <p>Migration ownership is candidate A: each producing service keeps its own copy of the DDL, which avoids version
 * collisions in a shared Flyway history but lets the copies drift. This fixture is the counterweight — every service
 * that owns a copy runs it, so a missing column or a dropped partial index fails that service's own test run rather
 * than surfacing as a relay that quietly scans the whole table.
 */
public final class OutboxTableAssertions {

    /** Column name to SQL type, as the writer and relay require. */
    private static final Map<String, String> REQUIRED_COLUMNS = new LinkedHashMap<>(Map.of(
            "id", "bigint",
            "topic", "text",
            "message_key", "text",
            "payload", "jsonb",
            "headers", "jsonb",
            "created_at", "timestamp with time zone",
            "published_at", "timestamp with time zone"));

    private OutboxTableAssertions() {
    }

    public static void assertTableShape(Connection connection) throws SQLException {
        var actual = new LinkedHashMap<String, String>();
        try (var st = connection.createStatement();
                var rs = st.executeQuery("""
                        SELECT column_name, data_type FROM information_schema.columns
                        WHERE table_name = 'outbox' ORDER BY ordinal_position""")) {
            while (rs.next()) {
                actual.put(rs.getString(1), rs.getString(2));
            }
        }
        assertTrue(actual.keySet().containsAll(REQUIRED_COLUMNS.keySet()),
                "outbox is missing columns the library requires: expected at least " + REQUIRED_COLUMNS.keySet()
                        + " but found " + actual.keySet());
        REQUIRED_COLUMNS.forEach((column, type) ->
                assertEquals(type, actual.get(column), "outbox." + column + " has the wrong type"));
    }

    /**
     * The relay only ever scans unpublished rows, so the index must be partial. A plain index would still work and
     * would grow without bound as published rows accumulate, which is exactly the kind of drift a copied migration
     * invites.
     */
    public static void assertPartialIndexOnUnpublished(Connection connection) throws SQLException {
        var definitions = new TreeSet<String>();
        try (var st = connection.createStatement();
                var rs = st.executeQuery("SELECT indexdef FROM pg_indexes WHERE tablename = 'outbox'")) {
            while (rs.next()) {
                definitions.add(rs.getString(1));
            }
        }
        assertTrue(definitions.stream().anyMatch(d -> d.contains("published_at IS NULL")),
                "outbox needs a partial index on unpublished rows, found: " + definitions);
    }
}
