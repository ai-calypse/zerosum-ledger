package dev.zerosum.order.order;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.zerosum.contracts.GoldenPayloads;
import dev.zerosum.money.OrderCandidate.Entry;
import dev.zerosum.order.order.OrderStore.Status;
import dev.zerosum.order.support.AppendOnlyAssertions;
import dev.zerosum.order.support.AppendOnlyAssertions.Mutation;
import dev.zerosum.order.support.OrderTestDatabase;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * M2: the orders database rejects mutation for every role, and rejects unbalanced orders at COMMIT even when the
 * application never validated them (D02-2 pattern, D03-1 triggers).
 */
@Tag("integration")
class OrdersImmutabilityIT {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static OrderTestDatabase db;
    private static OrderStore store;

    @BeforeAll
    static void start() {
        db = OrderTestDatabase.start();
        var dataSource = db.dataSource(OrderTestDatabase.APP);
        store = new OrderStore(JdbcClient.create(dataSource), new JdbcTemplate(dataSource), new RequestHasher(),
                new OrderStoreProperties(Duration.ofSeconds(2)), new DataSourceTransactionManager(dataSource));
        // One committed order, so the row triggers have something to fire on.
        assertEquals(Status.CREATED, store.create(new NewOrder("trip-simulator", "immutability-seed", "COMMERCE",
                "trip.completed", "trip_immutable", null,
                List.of(Entry.of("rider:R1", "receivable", "USD", 2500),
                        Entry.of("driver:D1", "payable", "USD", -2000),
                        Entry.of("platform:main", "revenue", "USD", -500)),
                null, Instant.parse("2026-09-15T10:04:11.201Z"))).status());
    }

    @AfterAll
    static void stop() {
        db.close();
    }

    /**
     * M2 (a): UPDATE and DELETE on both tables, and TRUNCATE on the entries table, for both roles — all 42501.
     *
     * <p>TRUNCATE of {@code money_orders} alone is not here: PostgreSQL refuses it because
     * {@code money_order_entries} references it, so the foreign key stops it before any trigger runs. The two
     * tests below cover that separately, so each rejection is attributed to the mechanism that actually causes
     * it rather than to the trigger by assumption.
     */
    @ParameterizedTest(name = "{0} {1} as {2}")
    @CsvSource({
        "money_orders,        UPDATE,   orders_app",
        "money_orders,        UPDATE,   orders_owner",
        "money_orders,        DELETE,   orders_app",
        "money_orders,        DELETE,   orders_owner",
        "money_order_entries, UPDATE,   orders_app",
        "money_order_entries, UPDATE,   orders_owner",
        "money_order_entries, DELETE,   orders_app",
        "money_order_entries, DELETE,   orders_owner",
        "money_order_entries, TRUNCATE, orders_app",
        "money_order_entries, TRUNCATE, orders_owner",
    })
    void everyMutationIsRejectedForEveryRole(String table, Mutation mutation, String role) throws SQLException {
        String setClause = table.equals("money_orders") ? "order_group_id = order_group_id" : "entity_id = entity_id";
        try (Connection c = db.connect(role)) {
            AppendOnlyAssertions.assertRejected(c, table, mutation, setClause, "true");
        }
    }

    @ParameterizedTest(name = "truncating both tables together as {0}")
    @CsvSource({"orders_app", "orders_owner"})
    void truncatingBothTablesTogetherIsRejectedByTheTrigger(String role) throws SQLException {
        // The only form PostgreSQL would otherwise permit, since it satisfies the foreign key. This is where the D02-2
        // statement trigger is genuinely the thing stopping the truncate, for the owner as well as the runtime role.
        try (Connection c = db.connect(role); var st = c.createStatement()) {
            long before = count("SELECT count(*) FROM money_orders");
            SQLException rejected = assertThrows(SQLException.class,
                    () -> st.execute("TRUNCATE money_orders, money_order_entries"));
            assertEquals(AppendOnlyAssertions.APPEND_ONLY_SQLSTATE, rejected.getSQLState(), rejected.getMessage());
            assertEquals(before, count("SELECT count(*) FROM money_orders"), "nothing was truncated");
        }
    }

    @Test
    void truncatingTheHeaderTableAloneIsRefusedByTheForeignKey() throws SQLException {
        // Recorded for what it is: a second, independent protection. The table is untruncatable on its own because the
        // entries table references it, which is a different mechanism from the append-only trigger.
        try (Connection c = db.connect(OrderTestDatabase.OWNER); var st = c.createStatement()) {
            SQLException rejected = assertThrows(SQLException.class, () -> st.execute("TRUNCATE money_orders"));
            assertTrue(rejected.getMessage().contains("foreign key"), rejected.getMessage());
            assertTrue(count("SELECT count(*) FROM money_orders") > 0, "nothing was truncated");
        }
    }

    @Test
    void anUnbalancedOrderInsertedBehindTheStoreFailsAtCommit() throws SQLException {
        // M2 (b): raw JDBC, bypassing every application check. The imbalance is only visible once both rows exist,
        // which is why the trigger is deferred to COMMIT rather than checked per statement.
        SQLException rejected = assertThrows(SQLException.class,
                () -> insertRaw("unbalanced", List.of(new long[] {2500}, new long[] {-2000})));
        assertEquals("23514", rejected.getSQLState(), rejected.getMessage());
        assertEquals(0, count("SELECT count(*) FROM money_orders WHERE idempotency_key = 'unbalanced'"));
    }

    @Test
    void aHeaderWithNoEntriesFailsAtCommit() throws SQLException {
        // §0.3 C3: the entries trigger can never fire for a header with nothing attached, so the same check is also
        // attached to the header table.
        SQLException rejected = assertThrows(SQLException.class, () -> insertRaw("no-entries", List.of()));
        assertEquals("23514", rejected.getSQLState(), rejected.getMessage());
        assertEquals(0, count("SELECT count(*) FROM money_orders WHERE idempotency_key = 'no-entries'"));
    }

    @Test
    void aPerCurrencyImbalanceFailsAtCommitEvenWhenTheTotalIsZero() throws SQLException {
        // Sums to zero overall but not per currency: +2500 USD and -2500 EUR. A naive total check would pass this.
        SQLException rejected = assertThrows(SQLException.class, () -> insertRawMixedCurrency("mixed-currency"));
        assertEquals("23514", rejected.getSQLState(), rejected.getMessage());
        assertEquals(0, count("SELECT count(*) FROM money_orders WHERE idempotency_key = 'mixed-currency'"));
    }

    @Test
    void theStoreReportsACommitTimeRejectionAsNotZeroSumRatherThanFailing() {
        // The store performs no validation of its own (that is S03-T03), so an unbalanced order reaches the trigger.
        // It must come back as a typed outcome the API can map to a 422, never as an unhandled exception.
        OrderStore.Result result = store.create(new NewOrder("trip-simulator", "store-unbalanced", "COMMERCE",
                "trip.completed", "trip_unbalanced", null,
                List.of(Entry.of("rider:R1", "receivable", "USD", 2500),
                        Entry.of("driver:D1", "payable", "USD", -2000)),
                null, Instant.parse("2026-09-15T10:04:11.201Z")));

        assertEquals(Status.NOT_ZERO_SUM, result.status());
        assertEquals(0, count("SELECT count(*) FROM money_orders WHERE idempotency_key = 'store-unbalanced'"));
    }

    @Test
    void theCommerceGoldensCommitSuccessfully() {
        // D01-9 goldens are the worked example. Only the COMMERCE ones without an adjustment are used: a golden's
        // adjusts_order_id is a fixed UUID that cannot match a database-assigned id here.
        int created = 0;
        for (int i = 1; i <= 8; i++) {
            JsonNode golden = JSON.readTree(GoldenPayloads.byId("O" + i));
            if (!"COMMERCE".equals(golden.get("type").asString()) || !golden.get("adjusts_order_id").isNull()) {
                continue;
            }
            List<Entry> entries = new java.util.ArrayList<>();
            for (JsonNode entry : golden.get("entries")) {
                entries.add(Entry.of(entry.get("entity_id").asString(), entry.get("account").asString(),
                        entry.get("currency").asString(), entry.get("amount_minor").asLong()));
            }
            OrderStore.Result result = store.create(new NewOrder("trip-simulator", "golden-O" + i,
                    golden.get("type").asString(), golden.get("reason").asString(),
                    golden.get("order_group_id").asString(), null, entries,
                    golden.get("metadata").toString(), Instant.parse(golden.get("effective_at").asString())));
            assertEquals(Status.CREATED, result.status(), "golden O" + i + " must commit");
            created++;
        }
        assertTrue(created > 0, "at least one COMMERCE golden must have been exercised");
    }

    @Test
    void anUnknownAdjustedOrderIsStillRejected() {
        // M2 (c), re-asserted here against the migrated schema rather than only in the store's own test.
        assertThrows(OrderStore.UnknownAdjustedOrderException.class, () -> store.create(new NewOrder("trip-simulator",
                "immutability-unknown-adjust", "COMMERCE", "fare.adjusted", "trip_immutable",
                UUID.fromString("01996a3e-8f10-7c2a-9a1e-3d5c7b2e4f01"),
                List.of(Entry.of("rider:R1", "receivable", "USD", -300),
                        Entry.of("platform:main", "revenue", "USD", 300)),
                null, Instant.parse("2026-09-15T10:04:11.201Z"))));
    }

    /** Inserts a header and entries with raw JDBC, bypassing the store, and commits. */
    private static void insertRaw(String key, List<long[]> amounts) throws SQLException {
        try (Connection c = db.connect(OrderTestDatabase.APP)) {
            c.setAutoCommit(false);
            UUID orderId;
            try (var ps = c.prepareStatement("""
                    INSERT INTO money_orders (order_group_id, type, reason, source_system, idempotency_key,
                                              request_hash, request_hash_version, effective_at)
                    VALUES ('trip_raw', 'COMMERCE', 'trip.completed', 'trip-simulator', ?, '\\x00', 1, now())
                    RETURNING order_id""")) {
                ps.setString(1, key);
                try (var rs = ps.executeQuery()) {
                    rs.next();
                    orderId = rs.getObject(1, UUID.class);
                }
            }
            short line = 1;
            for (long[] amount : amounts) {
                try (var ps = c.prepareStatement("""
                        INSERT INTO money_order_entries (order_id, line_no, entity_id, account_code, currency, amount_minor)
                        VALUES (?, ?, 'rider:R1', 'receivable', 'USD', ?)""")) {
                    ps.setObject(1, orderId);
                    ps.setShort(2, line++);
                    ps.setLong(3, amount[0]);
                    ps.executeUpdate();
                }
            }
            c.commit();   // the deferred trigger fires here, not on the inserts above
        }
    }

    private static void insertRawMixedCurrency(String key) throws SQLException {
        try (Connection c = db.connect(OrderTestDatabase.APP)) {
            c.setAutoCommit(false);
            UUID orderId;
            try (var ps = c.prepareStatement("""
                    INSERT INTO money_orders (order_group_id, type, reason, source_system, idempotency_key,
                                              request_hash, request_hash_version, effective_at)
                    VALUES ('trip_raw', 'COMMERCE', 'trip.completed', 'trip-simulator', ?, '\\x00', 1, now())
                    RETURNING order_id""")) {
                ps.setString(1, key);
                try (var rs = ps.executeQuery()) {
                    rs.next();
                    orderId = rs.getObject(1, UUID.class);
                }
            }
            try (var ps = c.prepareStatement("""
                    INSERT INTO money_order_entries (order_id, line_no, entity_id, account_code, currency, amount_minor)
                    VALUES (?, 1, 'rider:R1', 'receivable', 'USD', 2500),
                           (?, 2, 'platform:main', 'revenue', 'EUR', -2500)""")) {
                ps.setObject(1, orderId);
                ps.setObject(2, orderId);
                ps.executeUpdate();
            }
            c.commit();
        }
    }

    private static long count(String sql) {
        try (Connection c = db.connect(OrderTestDatabase.APP); var st = c.createStatement();
                var rs = st.executeQuery(sql)) {
            rs.next();
            return rs.getLong(1);
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }
}
