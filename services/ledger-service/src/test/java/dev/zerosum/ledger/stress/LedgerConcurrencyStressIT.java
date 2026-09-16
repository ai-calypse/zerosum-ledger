package dev.zerosum.ledger.stress;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.zerosum.ledger.apply.ApplyBatchResult;
import dev.zerosum.ledger.apply.ApplyOutcome;
import dev.zerosum.ledger.apply.ApplyRecord;
import dev.zerosum.ledger.support.ApplyTestDriver;
import dev.zerosum.ledger.support.LedgerQueries;
import dev.zerosum.ledger.support.LedgerTestDatabase;
import dev.zerosum.ledger.support.MoneyOrderPayloads;
import dev.zerosum.money.Money;
import dev.zerosum.money.OrderCandidate;
import dev.zerosum.money.OrderCandidate.Entry;
import dev.zerosum.money.ZeroSumValidator;
import dev.zerosum.money.generate.Seed;
import dev.zerosum.money.generate.SeededExtension;
import dev.zerosum.money.generate.TripSequenceGenerator;
import dev.zerosum.money.generate.TripSequenceGenerator.TripOrder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.random.RandomGenerator;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

/**
 * Concurrency stress with duplicates (D02-11): many threads apply a seeded trip stream in per-order and batched mode.
 * Every order touches the hot {@code platform:main} entity, rider and driver sets overlap so lock sets intersect in
 * different combinations, some orders repeat an entity across entries, and the three currencies have different
 * minor-unit digits. A sampler reads the global per-currency sum while writers run (M5 (c)); the final state is checked
 * against an in-memory oracle and I2–I4 (M5 (b), (d)).
 */
@Tag("integration")
@ExtendWith(SeededExtension.class)
class LedgerConcurrencyStressIT {

    /** Different minor-unit digits (D01-1, D01-6): USD 2, JPY 0, KWD 3. All are on the D01-7 allow list. */
    private static final List<String> CURRENCIES = List.of("USD", "JPY", "KWD");

    private static final ZeroSumValidator VALIDATOR = ZeroSumValidator.defaults();

    @Test
    void perOrderMode(Seed seed) throws Exception {
        run(seed, 1);
    }

    @Test
    void batchedMode(Seed seed) throws Exception {
        run(seed, StressParameters.fromClasspath().batchSize());
    }

    private void run(Seed seed, int batchSize) throws Exception {
        StressParameters parameters = StressParameters.fromClasspath();
        String mode = batchSize == 1 ? "per-order" : "batched-" + batchSize;
        RandomGenerator rng = seed.random();
        List<Generated> orders = generate(rng, parameters);
        TreeMap<String, Long> oracle = oracle(orders);
        List<List<ApplyRecord>> batches = batches(rng, orders, parameters, batchSize);
        long submissions = batches.stream().mapToLong(List::size).sum();

        // Container starvation can look like lock contention, so the configuration this ran against is part of the output.
        System.out.printf("ZS-STRESS start mode=%s size=%s threads=%d orders=%d duplicates=%d%% submissions=%d "
                        + "batches=%d image=%s hostCpus=%d%n",
                mode, parameters.size(), parameters.threads(), orders.size(), parameters.duplicatePercent(),
                submissions, batches.size(), LedgerTestDatabase.postgresImage(), Runtime.getRuntime().availableProcessors());

        try (LedgerTestDatabase db = LedgerTestDatabase.start();
                var pool = db.pooledDataSource(LedgerTestDatabase.APP, parameters.threads() + 2)) {
            ApplyTestDriver driver = ApplyTestDriver.create(pool);
            var pending = new ConcurrentLinkedQueue<>(batches);
            var failures = new ConcurrentLinkedQueue<Throwable>();
            var badOutcomes = new ConcurrentLinkedQueue<String>();
            long[] retries = new long[3];
            AtomicBoolean running = new AtomicBoolean(true);
            AtomicLong samples = new AtomicLong();
            List<String> nonZeroSamples = Collections.synchronizedList(new ArrayList<>());

            Thread sampler = startSampler(db, running, samples, nonZeroSamples, parameters.samplerIntervalMillis());
            ExecutorService writers = Executors.newFixedThreadPool(parameters.threads());
            CountDownLatch done = new CountDownLatch(parameters.threads());
            long startedAt = System.nanoTime();
            for (int t = 0; t < parameters.threads(); t++) {
                writers.submit(() -> {
                    try {
                        List<ApplyRecord> batch;
                        while ((batch = pending.poll()) != null) {
                            ApplyBatchResult result = driver.apply(batch);
                            synchronized (retries) {
                                retries[0] += result.deadlockRetries();
                                retries[1] += result.lockTimeoutRetries();
                                retries[2] += result.connectionRetries();
                            }
                            for (ApplyOutcome outcome : result.outcomes()) {
                                if (outcome.status() == ApplyOutcome.Status.QUARANTINED) {
                                    badOutcomes.add(outcome.orderId() + " " + outcome.errorCode() + " " + outcome.detail());
                                }
                            }
                        }
                    } catch (Throwable failure) {
                        failures.add(failure);
                    } finally {
                        done.countDown();
                    }
                });
            }
            boolean finished = done.await(10, TimeUnit.MINUTES);
            writers.shutdownNow();
            running.set(false);
            sampler.join(TimeUnit.SECONDS.toMillis(10));
            long millis = (System.nanoTime() - startedAt) / 1_000_000;

            System.out.printf("ZS-STRESS done mode=%s size=%s durationMs=%d samples=%d "
                            + "deadlockRetries=%d lockTimeoutRetries=%d connectionRetries=%d seed=%d%n",
                    mode, parameters.size(), millis, samples.get(), retries[0], retries[1], retries[2], seed.value());

            // A retries-exhausted exception surfaces here; report the exception text, not just a count.
            assertEquals(List.of(), failures.stream().map(Throwable::toString).toList(), "no writer may fail");
            assertEquals(List.of(), List.copyOf(badOutcomes), "no generated order may be quarantined");
            assertTrue(finished, "writers did not finish inside the timeout");
            assertTrue(samples.get() > 0, "the sampler must have read the global sum at least once");
            assertEquals(List.of(), nonZeroSamples, "every sampled global per-currency sum must be zero (M5 (c))");

            try (Connection c = db.connect(LedgerTestDatabase.VERIFIER)) {
                TreeMap<String, Long> balances = LedgerQueries.balances(c);
                System.out.printf("ZS-DIGEST test=%s mode=%s balances=%s%n", getClass().getName(), mode, digest(balances));
                assertEquals(oracle, balances, "balances must equal the in-memory oracle");
                assertEquals(List.of(), LedgerQueries.i2Violations(c), "I2: global sum per currency is zero");
                assertEquals(List.of(), LedgerQueries.i3Violations(c), "I3: balances match changelog deltas (no lost updates)");
                assertEquals(List.of(), LedgerQueries.i4Violations(c), "I4: per-entity sequences are gapless");
                assertEquals(List.of(), LedgerQueries.chainLinkViolations(c), "changelog chain links");
                assertEquals(orders.size(), LedgerQueries.count(c, "SELECT count(*) FROM applied_orders"),
                        "one applied-order record per unique order");
                assertEquals(0, LedgerQueries.count(c, "SELECT count(*) FROM quarantined_orders"));
            }
            // A deadlock retry is a lock-ordering or lock-strength defect even when everything above passes: it means
            // a shared lock on an entity row was upgraded to FOR UPDATE, or two batches disagreed on lock order.
            assertEquals(0, retries[0], "deadlock retries must be zero (entity locks precede every FK child insert)");
        }
    }

    private static Thread startSampler(LedgerTestDatabase db, AtomicBoolean running, AtomicLong samples,
            List<String> nonZero, long intervalMillis) {
        Thread sampler = new Thread(() -> {
            try (Connection c = db.connect(LedgerTestDatabase.VERIFIER)) {
                while (running.get()) {
                    // One statement, so the sum is read from a single snapshot (M5 (c)).
                    try (Statement st = c.createStatement();
                            ResultSet rs = st.executeQuery(
                                    "SELECT currency, sum(balance_minor) FROM accounts GROUP BY currency")) {
                        while (rs.next()) {
                            if (rs.getLong(2) != 0) {
                                nonZero.add(rs.getString(1).strip() + "=" + rs.getLong(2));
                            }
                        }
                    }
                    samples.incrementAndGet();
                    Thread.sleep(intervalMillis);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (SQLException e) {
                nonZero.add("sampler failed: " + e);
            }
        }, "zero-sum-sampler");
        sampler.setDaemon(true);
        sampler.start();
        return sampler;
    }

    /** One generated order: the payload submitted and the candidate the oracle sums. */
    private record Generated(String payload, OrderCandidate order) {
    }

    /**
     * Seeded trips per currency (D01-10). Riders are remapped onto a smaller pool so rider sets overlap, and some
     * platform commissions are split into two entries so an entity repeats across entries (§0.3 C4).
     */
    private static List<Generated> generate(RandomGenerator rng, StressParameters parameters) {
        int perCurrency = (parameters.orders() + CURRENCIES.size() - 1) / CURRENCIES.size();
        int riderPool = Math.max(4, parameters.orders() / 50);
        List<Generated> out = new ArrayList<>(parameters.orders());
        for (String currency : CURRENCIES) {
            List<TripOrder> trips = TripSequenceGenerator.trips(rng, perCurrency, parameters.commissionBps(),
                    parameters.adjustmentPercent(), currency);
            UUID[] ids = new UUID[trips.size()];
            // A prefix of each block keeps every adjustment's original, which always precedes it.
            for (int i = 0; i < trips.size() && i < perCurrency; i++) {
                TripOrder trip = trips.get(i);
                OrderCandidate order = spread(trip.order(), riderPool, rng, parameters.platformSplitPercent());
                List<dev.zerosum.money.Violation> violations = VALIDATOR.validate(order);
                if (!violations.isEmpty()) {
                    throw new AssertionError("the harness generated an invalid order: " + violations + " " + order);
                }
                ids[i] = new UUID(rng.nextLong(), rng.nextLong());
                UUID adjusts = trip.adjustsIndex() >= 0 ? ids[trip.adjustsIndex()] : null;
                String groupId = "trip_" + currency + "_" + i;
                out.add(new Generated(MoneyOrderPayloads.render(ids[i], groupId, order, adjusts), order));
            }
        }
        return List.copyOf(out.subList(0, Math.min(out.size(), parameters.orders())));
    }

    /** Remaps riders onto the shared pool and optionally splits the platform entry in two; amounts still sum to zero. */
    private static OrderCandidate spread(OrderCandidate order, int riderPool, RandomGenerator rng, int splitPercent) {
        boolean split = rng.nextInt(100) < splitPercent;
        List<Entry> entries = new ArrayList<>(order.entries().size() + 1);
        for (Entry entry : order.entries()) {
            String entityId = entry.entityId();
            if (entityId.startsWith("rider:R")) {
                entityId = "rider:R" + Math.floorMod(Integer.parseInt(entityId.substring("rider:R".length())), riderPool);
            }
            long amount = entry.amountMinor();
            if (split && entityId.equals("platform:main") && Math.abs(amount) >= 2) {
                long half = amount / 2;
                entries.add(Entry.of(entityId, entry.account(), entry.currency(), half));
                entries.add(Entry.of(entityId, entry.account(), entry.currency(), amount - half));
            } else {
                entries.add(Entry.of(entityId, entry.account(), entry.currency(), amount));
            }
        }
        return new OrderCandidate(order.type(), order.reason(), List.copyOf(entries));
    }

    /** Expected balances from the unique orders only, summed with D01-1 arithmetic. */
    private static TreeMap<String, Long> oracle(List<Generated> orders) {
        Map<String, Money> totals = new TreeMap<>();
        for (Generated generated : orders) {
            for (Entry entry : generated.order().entries()) {
                String key = entry.entityId() + "/" + entry.account() + "/" + entry.currency();
                totals.merge(key, Money.of(entry.amountMinor(), entry.currency()), Money::plus);
            }
        }
        TreeMap<String, Long> out = new TreeMap<>();
        totals.forEach((key, money) -> out.put(key, money.amountMinor()));
        return out;
    }

    /**
     * Batches of submissions carrying duplicates in three forms: adjacent to the original so they land in one batch,
     * inserted earlier so two threads submit the same order concurrently, and appended so the original has committed.
     */
    private static List<List<ApplyRecord>> batches(RandomGenerator rng, List<Generated> orders,
            StressParameters parameters, int batchSize) {
        List<String> stream = new ArrayList<>(orders.size() * 2);
        List<String> resubmissions = new ArrayList<>();
        for (Generated generated : orders) {
            stream.add(generated.payload());
            if (rng.nextInt(100) < parameters.duplicatePercent()) {
                switch (rng.nextInt(3)) {
                    case 0 -> stream.add(generated.payload());
                    case 1 -> stream.add(rng.nextInt(stream.size()), generated.payload());
                    default -> resubmissions.add(generated.payload());
                }
            }
        }
        stream.addAll(resubmissions);
        List<List<ApplyRecord>> batches = new ArrayList<>();
        for (int i = 0; i < stream.size(); i += batchSize) {
            batches.add(stream.subList(i, Math.min(i + batchSize, stream.size())).stream()
                    .map(ApplyRecord::of)
                    .toList());
        }
        return List.copyOf(batches);
    }

    /** A short digest of the final balances, so two runs with the same seed can be compared from their transcripts. */
    private static String digest(TreeMap<String, Long> balances) {
        try {
            MessageDigest sha256 = MessageDigest.getInstance("SHA-256");
            balances.forEach((key, value) -> sha256.update((key + "=" + value + "\n").getBytes(StandardCharsets.UTF_8)));
            return HexFormat.of().formatHex(sha256.digest()).substring(0, 16);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
