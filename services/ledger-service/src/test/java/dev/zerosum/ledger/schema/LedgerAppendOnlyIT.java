package dev.zerosum.ledger.schema;

import static org.junit.jupiter.api.Assertions.assertEquals;

import dev.zerosum.ledger.support.AppendOnlyAssertions;
import dev.zerosum.ledger.support.AppendOnlyAssertions.Mutation;
import dev.zerosum.ledger.support.LedgerTestDatabase;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/** Every append-only table × {update, delete, truncate} × {application role, owner role} fails with the D02-2 code (§0.3 O3). */
@Tag("integration")
class LedgerAppendOnlyIT {

    private static final String ORDER_ID = "01996a3e-8f10-7c2a-9a1e-3d5c7b2e4f01";

    private record Table(String name, String setClause, String where) {
    }

    private static final List<Table> APPEND_ONLY = List.of(
            new Table("applied_orders", "order_group_id = order_group_id", "order_id = '" + ORDER_ID + "'"),
            new Table("entity_changelog", "delta_minor = delta_minor", "entity_id = 'rider:R1' AND seq = 1"));

    private static LedgerTestDatabase db;

    @BeforeAll
    static void start() {
        db = LedgerTestDatabase.start();
    }

    /** Every cell starts from its own seeded row, so a mutation that wrongly succeeds can't break later cells. */
    @BeforeEach
    void seed() throws SQLException {
        try (Connection c = db.connect(LedgerTestDatabase.APP); Statement st = c.createStatement()) {
            st.execute(appliedOrderInsert() + " ON CONFLICT (order_id) DO NOTHING");
            st.execute(changelogInsert() + " ON CONFLICT (entity_id, seq) DO NOTHING");
        }
    }

    @AfterAll
    static void stop() {
        db.close();
    }

    static Stream<Arguments> cells() {
        List<Arguments> cells = new ArrayList<>();
        for (Table table : APPEND_ONLY) {
            for (Mutation mutation : Mutation.values()) {
                for (String role : List.of(LedgerTestDatabase.APP, LedgerTestDatabase.OWNER)) {
                    cells.add(Arguments.of(table.name(), mutation, role));
                }
            }
        }
        return cells.stream();
    }

    @ParameterizedTest(name = "{0} {1} as {2}")
    @MethodSource("cells")
    void mutationIsRejected(String tableName, Mutation mutation, String role) throws SQLException {
        Table table = APPEND_ONLY.stream().filter(t -> t.name().equals(tableName)).findFirst().orElseThrow();
        try (Connection c = db.connect(role)) {
            AppendOnlyAssertions.assertRejected(c, table.name(), mutation, table.setClause(), table.where());
        }
    }

    @Test
    void conflictSkippingDuplicateInsertsSucceedWithoutFiringTheTriggers() throws SQLException {
        for (String role : List.of(LedgerTestDatabase.APP, LedgerTestDatabase.OWNER)) {
            try (Connection c = db.connect(role); Statement st = c.createStatement()) {
                assertEquals(0, st.executeUpdate(appliedOrderInsert() + " ON CONFLICT (order_id) DO NOTHING"), role);
                assertEquals(0, st.executeUpdate(changelogInsert() + " ON CONFLICT (entity_id, seq) DO NOTHING"), role);
            }
        }
    }

    private static String appliedOrderInsert() {
        return "INSERT INTO applied_orders (order_id, order_group_id, source_system, idempotency_key, order_created_at)"
                + " VALUES ('" + ORDER_ID + "', 'trip_8f2c', 'trip-simulator', 'trip_8f2c:completed', now())";
    }

    private static String changelogInsert() {
        return "INSERT INTO entity_changelog (entity_id, seq, order_id, account_code, currency, delta_minor,"
                + " balance_after_minor, hash_version, prev_hash, row_hash)"
                + " VALUES ('rider:R1', 1, '" + ORDER_ID + "', 'receivable', 'USD', 2500, 2500, 1, NULL, '\\x00')";
    }
}
