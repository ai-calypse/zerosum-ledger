package dev.zerosum.order.order;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.zerosum.money.OrderCandidate.Entry;
import dev.zerosum.money.generate.Seed;
import dev.zerosum.money.generate.SeededExtension;
import dev.zerosum.order.order.OrderStore.Status;
import dev.zerosum.order.support.OrderTestDatabase;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;

/** M2 (c) and M3: idempotency is a database property, and a replay returns the originally stored order (D03-1, D03-3). */
@Tag("integration")
@ExtendWith(SeededExtension.class)
class OrderStoreIT {

    private static OrderTestDatabase db;
    private static com.zaxxer.hikari.HikariDataSource pool;
    private static OrderStore store;

    @BeforeAll
    static void start() {
        db = OrderTestDatabase.start();
        pool = db.pooledDataSource(OrderTestDatabase.APP, 60);
        store = storeOn(pool);
    }

    @AfterAll
    static void stop() {
        pool.close();
        db.close();
    }

    private static OrderStore storeOn(DataSource dataSource) {
        return new OrderStore(JdbcClient.create(dataSource), new JdbcTemplate(dataSource), new RequestHasher(),
                new OrderStoreProperties(Duration.ofSeconds(2)), new DataSourceTransactionManager(dataSource));
    }

    private static NewOrder order(String sourceSystem, String key, String group, long fare) {
        return new NewOrder(sourceSystem, key, "COMMERCE", "trip.completed", group, null,
                List.of(Entry.of("rider:R1", "receivable", "USD", fare),
                        Entry.of("driver:D1", "payable", "USD", -(fare - 500)),
                        Entry.of("platform:main", "revenue", "USD", -500)),
                "{\"trip_id\":\"" + group + "\"}", Instant.parse("2026-09-15T10:04:11.201Z"));
    }

    @Test
    void createdThenReplayedReturnsTheOriginalIdsAndTimestamps() {
        NewOrder request = order("trip-simulator", "replay-key", "trip_replay", 2500);

        OrderStore.Result created = store.create(request);
        assertEquals(Status.CREATED, created.status());
        assertEquals(3, created.order().entries().size());

        OrderStore.Result replayed = store.create(request);
        assertEquals(Status.REPLAYED, replayed.status());
        assertEquals(created.order().orderId(), replayed.order().orderId(), "a replay returns the original id");
        assertEquals(created.order().createdAt(), replayed.order().createdAt(), "and the original timestamp");
        assertEquals(created.order().entries(), replayed.order().entries(), "and the original entries, in line order");
        assertEquals(1, count("SELECT count(*) FROM money_orders WHERE idempotency_key = 'replay-key'"));
    }

    @Test
    void theSameKeyWithADifferentBodyIsKeyReused() {
        NewOrder first = order("trip-simulator", "reuse-key", "trip_reuse", 2500);
        assertEquals(Status.CREATED, store.create(first).status());

        OrderStore.Result reused = store.create(order("trip-simulator", "reuse-key", "trip_reuse", 9900));
        assertEquals(Status.KEY_REUSED, reused.status());
        assertEquals(null, reused.order(), "a key reuse returns no order");
        assertEquals(1, count("SELECT count(*) FROM money_orders WHERE idempotency_key = 'reuse-key'"));
    }

    @Test
    void theSameKeyFromTwoPrincipalsCreatesTwoOrders() {
        // Uniqueness is scoped by source system, so two principals never collide on a shared key (D03-3).
        assertEquals(Status.CREATED, store.create(order("trip-simulator", "shared-key", "trip_a", 2500)).status());
        assertEquals(Status.CREATED, store.create(order("instrument-service", "shared-key", "trip_b", 2500)).status());
        assertEquals(2, count("SELECT count(*) FROM money_orders WHERE idempotency_key = 'shared-key'"));
    }

    @Test
    void fiftyConcurrentRequestsOnOneKeyLeaveExactlyOneOrder(Seed seed) throws Exception {
        int callers = 50;
        NewOrder request = order("trip-simulator", "concurrent-key", "trip_concurrent", 2500);
        var statuses = new ConcurrentLinkedQueue<Status>();
        var failures = new ConcurrentLinkedQueue<Throwable>();
        CountDownLatch ready = new CountDownLatch(callers);
        CountDownLatch go = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(callers);
        ExecutorService threads = Executors.newFixedThreadPool(callers);

        for (int i = 0; i < callers; i++) {
            threads.submit(() -> {
                try {
                    ready.countDown();
                    go.await();
                    statuses.add(store.create(request).status());
                } catch (Throwable failure) {
                    failures.add(failure);
                } finally {
                    done.countDown();
                }
            });
        }
        assertTrue(ready.await(30, TimeUnit.SECONDS));
        go.countDown();
        assertTrue(done.await(60, TimeUnit.SECONDS), "every caller must finish");
        threads.shutdownNow();

        System.out.printf("ZS-ORDERS concurrent key: callers=%d created=%d replayed=%d inProgress=%d seed=%d%n",
                callers, statuses.stream().filter(s -> s == Status.CREATED).count(),
                statuses.stream().filter(s -> s == Status.REPLAYED).count(),
                statuses.stream().filter(s -> s == Status.IN_PROGRESS).count(), seed.value());

        assertEquals(List.of(), failures.stream().map(Throwable::toString).toList(), "no caller may fail");
        assertEquals(1, count("SELECT count(*) FROM money_orders WHERE idempotency_key = 'concurrent-key'"),
                "exactly one order row (M3 (c))");
        assertEquals(1, statuses.stream().filter(s -> s == Status.CREATED).count(), "exactly one CREATED");
        assertTrue(statuses.stream().allMatch(s -> s == Status.CREATED || s == Status.REPLAYED
                || s == Status.IN_PROGRESS), "every other caller replays or reports in progress: " + statuses);
    }

    @Test
    void whenTheFirstWriterRollsBackTheWaiterStillCreates() throws Exception {
        // The first transaction takes the key and then rolls back. In production the deferred zero-sum trigger
        // (S03-T02) is one way that happens; here the rollback is explicit, because that trigger does not exist yet,
        // so this exercises the waiting path rather than the trigger.
        NewOrder request = order("trip-simulator", "rollback-key", "trip_rollback", 2500);
        CountDownLatch inserted = new CountDownLatch(1);
        CountDownLatch rollbackNow = new CountDownLatch(1);

        Thread holder = new Thread(() -> {
            try (Connection c = db.connect(OrderTestDatabase.APP)) {
                c.setAutoCommit(false);
                try (var ps = c.prepareStatement("""
                        INSERT INTO money_orders (order_group_id, type, reason, source_system, idempotency_key,
                                                  request_hash, request_hash_version, effective_at)
                        VALUES ('trip_rollback', 'COMMERCE', 'trip.completed', 'trip-simulator', 'rollback-key',
                                '\\x00', 1, now())""")) {
                    ps.executeUpdate();
                }
                inserted.countDown();
                rollbackNow.await(30, TimeUnit.SECONDS);
                c.rollback();
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        });
        holder.setDaemon(true);
        holder.start();
        assertTrue(inserted.await(30, TimeUnit.SECONDS), "the holder must take the key first");

        // Release the holder shortly, so the waiter's lock wait ends in a commit-visible outcome rather than a timeout.
        Thread releaser = new Thread(() -> {
            try {
                Thread.sleep(200);
                rollbackNow.countDown();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        releaser.setDaemon(true);
        releaser.start();

        OrderStore.Result result = store.create(request);
        holder.join(TimeUnit.SECONDS.toMillis(30));

        assertEquals(Status.CREATED, result.status(), "the abandoned key must become available to the waiter");
        assertEquals(1, count("SELECT count(*) FROM money_orders WHERE idempotency_key = 'rollback-key'"));
        assertEquals(3, result.order().entries().size(), "the waiter wrote its own entries");
    }

    @Test
    void anUnknownAdjustedOrderIsRejected() {
        NewOrder adjustment = new NewOrder("trip-simulator", "unknown-adjust", "COMMERCE", "fare.adjusted",
                "trip_unknown", UUID.fromString("01996a3e-8f10-7c2a-9a1e-3d5c7b2e4f01"),
                List.of(Entry.of("rider:R1", "receivable", "USD", -300),
                        Entry.of("platform:main", "revenue", "USD", 300)),
                null, Instant.parse("2026-09-15T10:04:11.201Z"));

        assertThrows(OrderStore.UnknownAdjustedOrderException.class, () -> store.create(adjustment), "M2 (c)");
        assertEquals(0, count("SELECT count(*) FROM money_orders WHERE idempotency_key = 'unknown-adjust'"));
    }

    @Test
    void anAdjustmentInAnotherGroupIsRejectedWithItsOwnError() {
        OrderStore.Result original = store.create(order("trip-simulator", "group-a-key", "trip_group_a", 2500));
        assertEquals(Status.CREATED, original.status());

        NewOrder wrongGroup = new NewOrder("trip-simulator", "group-b-key", "COMMERCE", "fare.adjusted",
                "trip_group_b", original.order().orderId(),
                List.of(Entry.of("rider:R1", "receivable", "USD", -300),
                        Entry.of("platform:main", "revenue", "USD", 300)),
                null, Instant.parse("2026-09-15T10:04:11.201Z"));

        // The adjusted order exists, so this is the same-group rule (§0.3 C2), not the existence rule: a distinct error
        // rather than a constraint violation surfacing as a 500.
        assertThrows(OrderStore.AdjustmentGroupMismatchException.class, () -> store.create(wrongGroup));
        assertEquals(0, count("SELECT count(*) FROM money_orders WHERE idempotency_key = 'group-b-key'"));
    }

    @Test
    void readsByIdAndByGroupReturnEntriesInLineOrder() {
        OrderStore.Result first = store.create(order("trip-simulator", "group-read-1", "trip_read", 2500));
        OrderStore.Result second = store.create(order("trip-simulator", "group-read-2", "trip_read", 3300));
        assertEquals(Status.CREATED, first.status());
        assertEquals(Status.CREATED, second.status());

        var byId = store.read(first.order().orderId()).orElseThrow();
        assertEquals("rider:R1", byId.entries().get(0).entityId(), "line order is preserved");
        assertEquals(3, byId.entries().size());

        List<UUID> group = store.readByGroup("trip_read").stream().map(OrderStore.StoredOrder::orderId).toList();
        assertTrue(group.containsAll(List.of(first.order().orderId(), second.order().orderId())));
        assertTrue(store.read(UUID.fromString("01996a3e-0000-7000-8000-000000000000")).isEmpty(), "unknown id is empty");
    }

    @Test
    void migrationsValidateAndRerunningThemIsANoOp() {
        var flyway = db.flyway();
        flyway.validate();
        assertEquals(0, flyway.migrate().migrationsExecuted, "re-running migrations executes nothing");
        // Derived from the migration directory rather than hard-coded: adding a migration must not break this test,
        // which is meant to assert that what is on disk is what is applied.
        List<String> onDisk;
        try (var files = java.nio.file.Files.list(
                OrderTestDatabase.ROOT.resolve("services/order-service/src/main/resources/db/migration"))) {
            onDisk = files.map(f -> f.getFileName().toString())
                    .filter(name -> name.startsWith("V") && name.endsWith(".sql"))
                    .map(name -> name.substring(1, name.indexOf("__")))
                    .sorted(java.util.Comparator.comparingInt(Integer::parseInt))
                    .toList();
        } catch (java.io.IOException e) {
            throw new IllegalStateException(e);
        }
        assertEquals(onDisk, java.util.Arrays.stream(flyway.info().applied())
                .map(i -> i.getVersion().getVersion()).toList(), "every migration on disk is applied, in order");
        assertTrue(onDisk.size() >= 2, "at least the baseline and the orders schema");
    }

    private static long count(String sql) {
        try (Connection c = db.connect(OrderTestDatabase.APP); Statement st = c.createStatement();
                var rs = st.executeQuery(sql)) {
            rs.next();
            return rs.getLong(1);
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }
}
