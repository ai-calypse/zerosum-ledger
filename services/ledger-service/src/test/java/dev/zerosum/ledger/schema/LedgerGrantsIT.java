package dev.zerosum.ledger.schema;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import dev.zerosum.ledger.support.LedgerTestDatabase;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;

/** TB3/TB5: the runtime role writes only what it must; the verifier reads everything and writes nothing. */
@Tag("integration")
class LedgerGrantsIT {

    private static final List<String> TABLES =
            List.of("entities", "accounts", "applied_orders", "entity_changelog", "quarantined_orders");
    private static final String DENIED = "42501";

    private static LedgerTestDatabase db;

    @BeforeAll
    static void startAndSeed() throws SQLException {
        db = LedgerTestDatabase.start();
        try (Connection c = db.connect(LedgerTestDatabase.APP); Statement st = c.createStatement()) {
            st.execute("INSERT INTO entities (entity_id, kind) VALUES ('rider:R1', 'rider')");
            st.execute("INSERT INTO accounts (entity_id, account_code, currency, normal_side)"
                    + " VALUES ('rider:R1', 'receivable', 'USD', 'DEBIT')");
            st.execute("INSERT INTO quarantined_orders (payload, error_code) VALUES ('\\x00', 'UNDECODABLE')");
        }
    }

    @AfterAll
    static void stop() {
        db.close();
    }

    @Test
    void verifierSelectsFromEveryLedgerTableButCannotInsert() throws SQLException {
        try (Connection c = db.connect(LedgerTestDatabase.VERIFIER); Statement st = c.createStatement()) {
            for (String table : TABLES) {
                st.executeQuery("SELECT count(*) FROM " + table).close();
            }
            // default_transaction_read_only is defense in depth; the missing privilege must hold on its own.
            st.execute("SET SESSION CHARACTERISTICS AS TRANSACTION READ WRITE");
            assertDenied(() -> st.execute("INSERT INTO entities (entity_id, kind) VALUES ('rider:V1', 'rider')"),
                    "verifier insert");
            assertDenied(() -> st.execute("INSERT INTO quarantined_orders (payload, error_code) VALUES ('\\x00', 'X')"),
                    "verifier quarantine insert");
        }
    }

    @Test
    void runtimeRoleCannotDeleteFromMutableTables() throws SQLException {
        try (Connection c = db.connect(LedgerTestDatabase.APP); Statement st = c.createStatement()) {
            assertDenied(() -> st.execute("DELETE FROM accounts WHERE entity_id = 'rider:R1'"), "accounts delete");
            assertDenied(() -> st.execute("DELETE FROM entities WHERE entity_id = 'rider:R1'"), "entities delete");
            assertDenied(() -> st.execute("DELETE FROM quarantined_orders"), "quarantine delete");
            assertDenied(() -> st.execute("TRUNCATE entities CASCADE"), "entities truncate");
        }
    }

    @Test
    void runtimeRoleUpdatesMutableStateAndOnlyTheQuarantineResolution() throws SQLException {
        try (Connection c = db.connect(LedgerTestDatabase.APP); Statement st = c.createStatement()) {
            assertEquals(1, st.executeUpdate("UPDATE entities SET last_seq = last_seq WHERE entity_id = 'rider:R1'"));
            assertEquals(1, st.executeUpdate("UPDATE accounts SET balance_minor = 0 WHERE entity_id = 'rider:R1'"));
            assertEquals(1, st.executeUpdate("UPDATE quarantined_orders SET resolved_at = now() WHERE resolved_at IS NULL"));
            assertDenied(() -> st.execute("UPDATE quarantined_orders SET error_code = 'X'"), "quarantine error_code update");
        }
    }

    @Test
    void laterLedgerTablesDoNotInheritRuntimeUpdateOrDelete() throws SQLException {
        try (Connection owner = db.connect(LedgerTestDatabase.OWNER); Statement st = owner.createStatement()) {
            st.execute("CREATE TABLE future_probe (id int)");
            st.execute("INSERT INTO future_probe VALUES (1)");
        }
        try (Connection c = db.connect(LedgerTestDatabase.APP); Statement st = c.createStatement()) {
            assertEquals(1, st.executeUpdate("INSERT INTO future_probe VALUES (2)"));
            assertDenied(() -> st.execute("UPDATE future_probe SET id = 3"), "future table update");
            assertDenied(() -> st.execute("DELETE FROM future_probe"), "future table delete");
        }
        try (Connection c = db.connect(LedgerTestDatabase.VERIFIER); Statement st = c.createStatement()) {
            st.executeQuery("SELECT count(*) FROM future_probe").close();
        }
        try (Connection owner = db.connect(LedgerTestDatabase.OWNER); Statement st = owner.createStatement()) {
            st.execute("DROP TABLE future_probe");
        }
    }

    private static void assertDenied(Executable action, String what) {
        SQLException e = assertThrows(SQLException.class, action, what);
        assertEquals(DENIED, e.getSQLState(), what + ": " + e.getMessage());
    }
}
