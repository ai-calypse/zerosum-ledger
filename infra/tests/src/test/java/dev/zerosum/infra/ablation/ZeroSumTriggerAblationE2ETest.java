package dev.zerosum.infra.ablation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import dev.zerosum.infra.Stack;
import java.net.http.HttpResponse;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * A4 (zero-sum validation), PARTIAL: only the database layer is ablated, and only inside a transaction that is
 * rolled back.
 *
 * <p>A4 disables three layers (application rule, deferred DB trigger, ledger re-check; master §8.5, §0.3 E8). The
 * first and third are code in order-service and ledger-service, whose S08-T03 seams do not exist and which this task
 * may not edit, so the application layer stays ON — the test shows it rejecting the same unbalanced body with 422 —
 * and the insert below bypasses it by writing SQL as the schema owner.
 *
 * <p>Why a rolled-back transaction: {@code money_orders} is append-only, so an unbalanced order committed to the
 * shared stack could never be removed and would be an I1 violation in it for ever. PostgreSQL DDL is transactional,
 * so {@code DISABLE TRIGGER} and the insert are both undone by the rollback; the deferred trigger is forced to run
 * inside the transaction with {@code SET CONSTRAINTS ALL IMMEDIATE}, which is exactly the check COMMIT would make.
 */
@Tag("e2e")
class ZeroSumTriggerAblationE2ETest {

    record Outcome(int rep, String controlSqlState, String ablatedSqlState, long ablatedStoredSum,
            String triggersAfterRollback, long rowsAfterRollback) {
    }

    @Test
    @DisplayName("A4 (DB layer only): with the zero-sum triggers disabled an unbalanced order is storable; enabled, it is not")
    void withoutTheTriggersAnUnbalancedOrderIsStorable() throws SQLException {
        // The application layer, which A4 would also disable and this test cannot: it refuses the same imbalance.
        String run = "a4" + UUID.randomUUID().toString().replace("-", "").substring(0, 10);
        String unbalancedBody = Stack.tripOrder("trip_" + run, "rider:" + run, "driver:" + run, 1_000, 200)
                .replace("\"amount_minor\":1000", "\"amount_minor\":1001");
        HttpResponse<String> refused = Stack.post(Stack.ORDERS + "/v1/money-orders", Stack.writerToken(),
                run + "-api", unbalancedBody);
        assertEquals(422, refused.statusCode(), "application layer ON: " + refused.body());

        var outcomes = new ArrayList<Outcome>();
        int repetitions = Integer.getInteger("zs.ablation.repetitions", 5);
        for (int r = 1; r <= repetitions; r++) {
            outcomes.add(once(r));
        }
        String report = "A4 partial (DB trigger layer only, rolled back)\napplication layer: POST unbalanced -> "
                + refused.statusCode() + "\n| rep | trigger ON: commit check | trigger OFF: commit check "
                + "| stored entries sum (OFF) | triggers after rollback | rows after rollback |\n|---|---|---|---|---|---|\n"
                + String.join("\n", outcomes.stream().map(o -> "| %d | %s | %s | %d | %s | %d |".formatted(o.rep(),
                o.controlSqlState(), o.ablatedSqlState(), o.ablatedStoredSum(), o.triggersAfterRollback(),
                o.rowsAfterRollback())).toList()) + "\n";
        System.out.println(report);
        Stack.writeEvidence("a4-zero-sum-trigger-ablation.txt", report);

        for (Outcome o : outcomes) {
            assertEquals("23514", o.controlSqlState(), "protection ON: the deferred trigger rejects the imbalance");
            assertEquals("none", o.ablatedSqlState(), "protection OFF: nothing rejects it");
            assertNotEquals(0, o.ablatedStoredSum(), "predicted class: a stored order that does not sum to zero (I1)");
            assertEquals("O,O", o.triggersAfterRollback(), "the rollback re-enabled both triggers");
            assertEquals(0, o.rowsAfterRollback(), "and nothing reached the shared database");
        }
    }

    private static Outcome once(int rep) throws SQLException {
        try (Connection owner = DriverManager.getConnection("jdbc:postgresql://127.0.0.1:5432/orders",
                "orders_owner", Stack.env("ZS_ORDERS_OWNER_DB_PASSWORD"))) {
            owner.setAutoCommit(false);
            try (Statement s = owner.createStatement()) {
                s.execute("SET LOCAL lock_timeout = '3s'");
            }

            UUID control = UUID.randomUUID();
            SQLException rejected = assertThrows(SQLException.class, () -> {
                insertUnbalanced(owner, control);
                try (Statement s = owner.createStatement()) {
                    s.execute("SET CONSTRAINTS ALL IMMEDIATE");
                }
            });
            owner.rollback();

            UUID ablated = UUID.randomUUID();
            String ablatedState = "none";
            long sum;
            try (Statement s = owner.createStatement()) {
                s.execute("SET LOCAL lock_timeout = '3s'");
                s.execute("ALTER TABLE money_order_entries DISABLE TRIGGER money_order_entries_zero_sum");
                s.execute("ALTER TABLE money_orders DISABLE TRIGGER money_orders_have_entries");
                insertUnbalanced(owner, ablated);
                try {
                    s.execute("SET CONSTRAINTS ALL IMMEDIATE");
                } catch (SQLException e) {
                    ablatedState = e.getSQLState();
                }
                sum = ((Number) Stack.query(owner, "SELECT coalesce(sum(amount_minor), 0) AS s FROM "
                        + "money_order_entries WHERE order_id = ?", ablated).getFirst().get("s")).longValue();
            } finally {
                owner.rollback();
            }

            String triggers = String.join(",", Stack.query(owner, "SELECT tgenabled::text AS e FROM pg_trigger WHERE "
                    + "tgname IN ('money_order_entries_zero_sum', 'money_orders_have_entries') ORDER BY tgname")
                    .stream().map(row -> (String) row.get("e")).toList());
            long rows = ((Number) Stack.query(owner, "SELECT count(*) AS c FROM money_orders WHERE order_id IN (?, ?)",
                    control, ablated).getFirst().get("c")).longValue();
            owner.rollback();
            return new Outcome(rep, rejected.getSQLState(), ablatedState, sum, triggers, rows);
        }
    }

    /** A trip order whose rider line is one minor unit too large — A4's ±1 fare-split bug, master §8.5. */
    private static void insertUnbalanced(Connection c, UUID orderId) throws SQLException {
        String key = "a4-ablation-" + orderId;
        try (var order = c.prepareStatement("""
                INSERT INTO money_orders (order_id, order_group_id, type, reason, source_system, idempotency_key,
                                          request_hash, request_hash_version, effective_at)
                VALUES (?, ?, 'COMMERCE', 'trip.completed', 'a4-ablation', ?, '\\x00'::bytea, 1, now())""")) {
            order.setObject(1, orderId);
            order.setString(2, "trip_" + key);
            order.setString(3, key);
            order.executeUpdate();
        }
        List<Object[]> lines = List.of(new Object[] {"rider:" + key.substring(12, 30), "receivable", 1_001L},
                new Object[] {"driver:" + key.substring(12, 30), "payable", -800L},
                new Object[] {"platform:main", "revenue", -200L});
        try (var entry = c.prepareStatement("INSERT INTO money_order_entries (order_id, line_no, entity_id, "
                + "account_code, currency, amount_minor) VALUES (?, ?, ?, ?, 'USD', ?)")) {
            for (int i = 0; i < lines.size(); i++) {
                entry.setObject(1, orderId);
                entry.setInt(2, i + 1);
                entry.setString(3, (String) lines.get(i)[0]);
                entry.setString(4, (String) lines.get(i)[1]);
                entry.setLong(5, (Long) lines.get(i)[2]);
                entry.executeUpdate();
            }
        }
    }
}
