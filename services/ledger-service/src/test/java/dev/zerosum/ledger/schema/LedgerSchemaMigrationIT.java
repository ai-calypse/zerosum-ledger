package dev.zerosum.ledger.schema;

import static org.junit.jupiter.api.Assertions.assertEquals;

import dev.zerosum.ledger.support.LedgerTestDatabase;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import org.flywaydb.core.api.MigrationInfo;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("integration")
class LedgerSchemaMigrationIT {

    private static LedgerTestDatabase db;

    @BeforeAll
    static void start() {
        db = LedgerTestDatabase.start();
    }

    @AfterAll
    static void stop() {
        db.close();
    }

    @Test
    void migratesFromTheBaselineAndValidatesOnASecondStart() {
        List<String> applied = new ArrayList<>();
        for (MigrationInfo info : db.flyway().info().applied()) {
            applied.add(info.getVersion() + " " + info.getState() + " " + info.getInstalledBy());
        }
        assertEquals(List.of("1 SUCCESS ledger_owner", "2 SUCCESS ledger_owner"), applied);

        // A second start: nothing pending, validation passes (checksums unchanged).
        assertEquals(0, db.flyway().migrate().migrationsExecuted);
        db.flyway().validate();
    }

    @Test
    void quarantineInsertIsANoOpForARepeatedKafkaPositionButNullPositionsNeverConflict() throws SQLException {
        String insert = "INSERT INTO quarantined_orders (payload, error_code, kafka_topic, kafka_partition, kafka_offset)"
                + " VALUES (?, 'UNDECODABLE', ?, ?, ?) ON CONFLICT (kafka_topic, kafka_partition, kafka_offset) DO NOTHING";
        try (Connection c = db.connect(LedgerTestDatabase.APP)) {
            assertEquals(1, quarantine(c, insert, "payments.money-orders.v1", 3, 42L));
            assertEquals(0, quarantine(c, insert, "payments.money-orders.v1", 3, 42L));
            assertEquals(1, quarantine(c, insert, null, null, null));
            assertEquals(1, quarantine(c, insert, null, null, null));
            assertEquals(1, count(c, "SELECT count(*) FROM quarantined_orders WHERE kafka_offset = 42"));
            assertEquals(2, count(c, "SELECT count(*) FROM quarantined_orders WHERE kafka_offset IS NULL"));
        }
    }

    @Test
    void keyColumnsUseByteOrderCollationMatchingJavaStringOrder() throws SQLException {
        try (Connection c = db.connect(LedgerTestDatabase.OWNER); Statement st = c.createStatement()) {
            try (ResultSet rs = st.executeQuery("SELECT table_name || '.' || column_name || '=' || collation_name"
                    + " FROM information_schema.columns WHERE table_schema = 'public'"
                    + " AND column_name IN ('entity_id', 'account_code', 'currency')"
                    + " AND table_name IN ('entities', 'accounts', 'entity_changelog') ORDER BY 1")) {
                List<String> collations = new ArrayList<>();
                while (rs.next()) {
                    collations.add(rs.getString(1));
                }
                assertEquals(List.of("accounts.account_code=C", "accounts.currency=C", "accounts.entity_id=C",
                        "entities.entity_id=C", "entity_changelog.account_code=C", "entity_changelog.currency=C",
                        "entity_changelog.entity_id=C"), collations);
            }
            List<String> ids = List.of("rider:b", "rider:B", "rider:_x", "rider:-x", "rider:9", "provider:fakecard",
                    "platform:main", "platform:main_07", "driver:D1", "driver:d1");
            st.execute("CREATE TEMP TABLE collation_probe (id text COLLATE \"C\")");
            for (String id : ids) {
                st.execute("INSERT INTO collation_probe VALUES ('" + id + "')");
            }
            List<String> databaseOrder = new ArrayList<>();
            try (ResultSet rs = st.executeQuery("SELECT id FROM collation_probe ORDER BY id")) {
                while (rs.next()) {
                    databaseOrder.add(rs.getString(1));
                }
            }
            assertEquals(ids.stream().sorted().toList(), databaseOrder);
        }
    }

    private static int quarantine(Connection c, String sql, String topic, Integer partition, Long offset) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setBytes(1, "not json".getBytes());
            ps.setObject(2, topic);
            ps.setObject(3, partition);
            ps.setObject(4, offset);
            return ps.executeUpdate();
        }
    }

    private static long count(Connection c, String sql) throws SQLException {
        try (Statement st = c.createStatement(); ResultSet rs = st.executeQuery(sql)) {
            rs.next();
            return rs.getLong(1);
        }
    }
}
