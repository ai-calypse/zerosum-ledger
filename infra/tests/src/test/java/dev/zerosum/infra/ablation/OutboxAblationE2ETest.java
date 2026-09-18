package dev.zerosum.infra.ablation;

import static dev.zerosum.infra.Stack.KAFKA;
import static dev.zerosum.infra.Stack.ORDER_SERVICE;
import static dev.zerosum.infra.Stack.docker;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.zerosum.evidence.Provenance;
import dev.zerosum.infra.Stack;
import java.net.http.HttpResponse;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * A2 (outbox → dual write), EMULATED by state, not by the S08-T03 seam.
 *
 * <p><strong>What is and is not ablated.</strong> The A2 seam — order-service sending directly after commit, with no
 * outbox row — lives in {@code libs/outbox} and does not exist; this task may not add it. What A2 predicts, though, is
 * a statement about a <em>state</em>: after a crash between commit and send, a dual-writing service holds a committed
 * order and no durable intent to publish it. That state can be produced exactly: the M4 (a) scenario (broker paused,
 * orders committed, {@code kill -9}) followed by deleting the crashed orders' outbox rows, which is the one thing the
 * outbox adds. {@code orders_app} may delete outbox rows (the cleanup job does), so no privilege is borrowed.
 *
 * <p><strong>Predicted failure (master §8.5):</strong> committed orders never applied — I6. <strong>Restore:</strong>
 * the deleted rows are re-inserted unchanged; the relay publishes them, the ledger applies the missing orders and
 * dedupes the ones that did get through, and the shared stack is left consistent. The un-ablated control is
 * {@code OrderPublishAfterCrashE2ETest}, which runs the same scenario without the deletion.
 */
@Tag("chaos")
class OutboxAblationE2ETest {

    private static final long FARE = 1_000;
    private static final long FEE = 200;
    /** Far beyond M4 (a)'s 5 s: an order not applied by now is not late, it is lost. */
    private static final Duration GRACE = Duration.ofSeconds(20);

    record Row(String topic, String key, String payload, String headers) {
    }

    record Run(int index, String group, int orders, int unpublishedAtKill, int outboxRowsDeleted, int appliedAfterGrace,
            int lostAfterGrace, boolean ledgerSelfCheckAfterGrace, int appliedAfterRestore, long riderAfterGrace,
            long riderAfterRestore, boolean invariantsAfterRestore) {
    }

    @AfterEach
    void leaveTheStackRunning() {
        if ("true".equals(docker("inspect", "-f", "{{.State.Paused}}", KAFKA))) {
            docker("unpause", KAFKA);
        }
        if (!"true".equals(docker("inspect", "-f", "{{.State.Running}}", ORDER_SERVICE))) {
            docker("start", ORDER_SERVICE);
        }
    }

    @Test
    @DisplayName("A2 (emulated): without the outbox row, a crash between commit and publish loses committed orders")
    void withoutTheOutboxACrashLosesCommittedOrders() throws SQLException {
        int repetitions = Integer.getInteger("zs.ablation.repetitions", 5);
        int orders = Integer.getInteger("zs.ablation.orders", 20);
        var runs = new ArrayList<Run>();
        for (int r = 1; r <= repetitions; r++) {
            runs.add(run(r, orders));
        }

        var provenance = Provenance.capture(Stack.ROOT, List.of(), Map.of(
                "order-service image", docker("inspect", "-f", "{{.Image}}", ORDER_SERVICE)));
        String report = "A2 emulated (outbox rows deleted after a kill -9 between commit and publish)\n"
                + provenance.markdownTable("../../adr/0002-stack-and-pinned-versions.md") + "\n"
                + "| rep | group | orders | unpublished at kill | outbox rows deleted | applied after 20 s | LOST (I6) "
                + "| ledger self-check while lost | applied after restore | rider after 20 s | rider after restore "
                + "| invariants after restore |\n"
                + "|---|---|---|---|---|---|---|---|---|---|---|---|\n"
                + runs.stream().map(r -> "| %d | %s | %d | %d | %d | %d | %d | %s | %d | %d | %d | %s |".formatted(
                        r.index(), r.group(), r.orders(), r.unpublishedAtKill(), r.outboxRowsDeleted(),
                        r.appliedAfterGrace(), r.lostAfterGrace(),
                        r.ledgerSelfCheckAfterGrace() ? "consistent" : "INCONSISTENT", r.appliedAfterRestore(),
                        r.riderAfterGrace(), r.riderAfterRestore(), r.invariantsAfterRestore()))
                .collect(Collectors.joining("\n")) + "\n";
        System.out.println(report);
        Stack.writeEvidence("a2-outbox-ablation.txt", report);

        long failed = runs.stream().filter(r -> r.lostAfterGrace() > 0).count();
        // The master's validity rule: the predicted failure class in at least half the runs.
        assertTrue(failed * 2 >= runs.size(), "A2 predicted I6 in >= 50% of runs; it happened in " + failed);
        for (Run r : runs) {
            assertEquals(r.orders(), r.appliedAfterRestore(), "restore: every order applied");
            assertEquals(r.orders() * FARE, r.riderAfterRestore(), "restore: nothing lost, nothing doubled");
            assertTrue(r.invariantsAfterRestore(), "restore: ledger invariants hold");
        }
    }

    private Run run(int index, int orders) throws SQLException {
        String run = "a2" + UUID.randomUUID().toString().replace("-", "").substring(0, 10);
        String rider = "rider:" + run;
        String group = "trip_" + run;
        var orderIds = new ArrayList<String>();
        List<Row> deleted;
        int unpublishedAtKill;

        docker("pause", KAFKA);
        try {
            for (int i = 0; i < orders; i++) {
                HttpResponse<String> created = Stack.post(Stack.ORDERS + "/v1/money-orders", Stack.writerToken(),
                        run + "-" + i, Stack.tripOrder(group, rider, "driver:" + run, FARE, FEE));
                assertEquals(201, created.statusCode(), created.body());
                orderIds.add(Stack.json(created.body()).get("order_id").asString());
            }
            unpublishedAtKill = (int) Stack.count("orders", "SELECT count(*) FROM outbox WHERE "
                    + "headers->>'order_id' = ANY (?) AND published_at IS NULL", (Object) ids(orderIds));
            docker("kill", "-s", "KILL", ORDER_SERVICE);
            // ABLATE: the dual-write state. Committed orders, no durable intent to publish them.
            deleted = deleteOutboxRows(orderIds);
        } finally {
            docker("unpause", KAFKA);
        }

        docker("start", ORDER_SERVICE);
        awaitHealthy();
        Stack.sleep(GRACE);
        int appliedAfterGrace = applied(orderIds);
        Long riderAfterGrace = Stack.balance(rider, "receivable");
        // The ledger cannot see an order it never received; only the cross-store count above (I6) can.
        boolean selfCheck = Stack.ledgerInvariants().get("consistent").asBoolean();

        // RESTORE: the intent comes back exactly as it was written.
        reinsert(deleted);
        Instant deadline = Instant.now().plus(Duration.ofSeconds(60));
        while (applied(orderIds) < orders && Instant.now().isBefore(deadline)) {
            Stack.sleep(Duration.ofMillis(250));
        }
        long riderAfterRestore = awaitBalance(rider, orders * FARE);
        return new Run(index, group, orders, unpublishedAtKill, deleted.size(), appliedAfterGrace,
                orders - appliedAfterGrace, selfCheck, applied(orderIds),
                riderAfterGrace == null ? 0 : riderAfterGrace,
                riderAfterRestore, Stack.ledgerInvariants().get("consistent").asBoolean());
    }

    private static List<Row> deleteOutboxRows(List<String> orderIds) throws SQLException {
        try (Connection c = Stack.connect("orders")) {
            c.setAutoCommit(false);
            var rows = Stack.query(c, "SELECT topic, message_key, payload::text AS payload, headers::text AS headers "
                    + "FROM outbox WHERE headers->>'order_id' = ANY (?) ORDER BY id", (Object) ids(orderIds)).stream()
                    .map(m -> new Row((String) m.get("topic"), (String) m.get("message_key"),
                            (String) m.get("payload"), (String) m.get("headers"))).toList();
            try (PreparedStatement delete = c.prepareStatement(
                    "DELETE FROM outbox WHERE headers->>'order_id' = ANY (?)")) {
                delete.setObject(1, ids(orderIds));
                assertEquals(rows.size(), delete.executeUpdate());
            }
            c.commit();
            return rows;
        }
    }

    private static void reinsert(List<Row> rows) throws SQLException {
        try (Connection c = Stack.connect("orders");
                PreparedStatement insert = c.prepareStatement("INSERT INTO outbox (topic, message_key, payload, "
                        + "headers) VALUES (?, ?, cast(? AS jsonb), cast(? AS jsonb))")) {
            for (Row row : rows) {
                insert.setString(1, row.topic());
                insert.setString(2, row.key());
                insert.setString(3, row.payload());
                insert.setString(4, row.headers());
                insert.executeUpdate();
            }
        }
    }

    private static int applied(List<String> orderIds) {
        return (int) Stack.count("ledger", "SELECT count(*) FROM applied_orders WHERE order_id::text = ANY (?)",
                (Object) ids(orderIds));
    }

    private static long awaitBalance(String rider, long expected) {
        Instant deadline = Instant.now().plus(Duration.ofSeconds(30));
        Long seen = null;
        while (Instant.now().isBefore(deadline)) {
            seen = Stack.balance(rider, "receivable");
            if (seen != null && seen == expected) {
                return seen;
            }
            Stack.sleep(Duration.ofMillis(250));
        }
        return seen == null ? Long.MIN_VALUE : seen;
    }

    private static void awaitHealthy() {
        Instant deadline = Instant.now().plus(Duration.ofSeconds(90));
        while (Instant.now().isBefore(deadline)) {
            if ("healthy".equals(docker("inspect", "-f", "{{.State.Health.Status}}", ORDER_SERVICE))) {
                return;
            }
            Stack.sleep(Duration.ofSeconds(1));
        }
        throw new AssertionError("order-service did not return to healthy");
    }

    private static String[] ids(List<String> orderIds) {
        return orderIds.toArray(String[]::new);
    }
}
