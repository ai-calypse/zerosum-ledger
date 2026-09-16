package dev.zerosum.ledger.sp1;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.zerosum.ledger.apply.ApplyBatchResult;
import dev.zerosum.ledger.apply.ApplyOutcome;
import dev.zerosum.ledger.apply.ApplyRecord;
import dev.zerosum.ledger.changelog.ChainVerifier;
import dev.zerosum.ledger.changelog.ChangelogHasher;
import dev.zerosum.ledger.store.LedgerStore;
import dev.zerosum.ledger.support.ApplyTestDriver;
import dev.zerosum.ledger.support.LedgerQueries;
import dev.zerosum.ledger.support.LedgerTestDatabase;
import dev.zerosum.ledger.support.MoneyOrderPayloads;
import dev.zerosum.money.OrderCandidate;
import dev.zerosum.money.OrderCandidate.Entry;
import dev.zerosum.money.generate.Seed;
import dev.zerosum.money.generate.SeededExtension;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * SP1: per-order apply throughput when every order locks {@code platform:main} (D02-10).
 *
 * <p>Tagged {@code study}, so no CI job runs it (CR-S02-05 to D00-10): the full study is roughly an hour of measured
 * windows and warm-ups. Start it deliberately with
 * {@code ./gradlew :services:ledger-service:studyTest --tests '*Sp1LockStudy' -i} and commit the raw data it writes.
 *
 * <p>The database is {@link LedgerTestDatabase#startDurable()}: the D00-3 configuration with durability intact, because
 * commit fsync is part of what the lock hold time is made of. A number measured against {@code fsync=off} would be a
 * smoke check, not SP1 data.
 */
@Tag("study")
@ExtendWith(SeededExtension.class)
class Sp1LockStudy {

    /** Smoke overrides, for checking the harness itself rather than producing study data. */
    private static final String WRITERS_OVERRIDE = System.getProperty("zs.sp1.writers");
    private static final String WINDOW_OVERRIDE = System.getProperty("zs.sp1.windowSeconds");
    private static final String WARMUP_OVERRIDE = System.getProperty("zs.sp1.warmupSeconds");
    private static final String REPETITIONS_OVERRIDE = System.getProperty("zs.sp1.repetitions");

    @Test
    void measurePerOrderApplyThroughputAgainstOneHotEntity(Seed seed) throws Exception {
        Sp1Parameters fromFile = Sp1Parameters.fromClasspath();
        Sp1Parameters parameters = fromFile.withOverrides(WRITERS_OVERRIDE, WINDOW_OVERRIDE, WARMUP_OVERRIDE,
                REPETITIONS_OVERRIDE);
        boolean fullStudy = parameters.isFullStudy(fromFile);

        System.out.printf("ZS-SP1 start writers=%s windowSeconds=%d warmupSeconds=%d repetitions=%d fullStudy=%s "
                        + "seed=%d image=%s%n",
                parameters.writers(), parameters.windowSeconds(), parameters.warmupSeconds(), parameters.repetitions(),
                fullStudy, seed.value(), LedgerTestDatabase.postgresImage());
        if (!fullStudy) {
            System.out.println("ZS-SP1 WARNING this run uses overrides and is a harness smoke check, not SP1 data");
        }
        boolean overridesRequested = WRITERS_OVERRIDE != null || WINDOW_OVERRIDE != null || WARMUP_OVERRIDE != null
                || REPETITIONS_OVERRIDE != null;
        assertEquals(overridesRequested, !fullStudy,
                "override properties must reach the test JVM. A Gradle -D sets them on the daemon only; the ledger "
                        + "build forwards zs.sp1.* explicitly. Without that, a smoke check silently runs the full study.");

        List<Sp1Run> runs = new ArrayList<>();
        // One durable database for the whole study: every window then sees a table that is already non-trivial, which
        // is closer to steady state than a fresh database per window.
        try (LedgerTestDatabase db = LedgerTestDatabase.startDurable()) {
            for (int writers : parameters.writers()) {
                for (int repetition = 1; repetition <= parameters.repetitions(); repetition++) {
                    runs.add(runOneWindow(db, parameters, writers, repetition, seed));
                    // Written after every window: an earlier crash lost a whole study's measurements at the last step.
                    writeRawData(runs, parameters, seed, fullStudy);
                }
            }
        }

        Path raw = writeRawData(runs, parameters, seed, fullStudy);
        System.out.println("ZS-SP1 raw data: " + raw);
        for (Sp1Run run : runs) {
            System.out.println("ZS-SP1 " + run.summary());
        }
        System.out.printf("ZS-SP1 ceiling=%.1f orders/s (highest median across writer counts)%n", ceiling(runs));

        assertTrue(runs.stream().allMatch(Sp1Run::valid),
                "a run with an invariant violation or an exhausted retry is not a data point: " + runs);
    }

    private Sp1Run runOneWindow(LedgerTestDatabase db, Sp1Parameters parameters, int writers, int repetition, Seed seed)
            throws Exception {
        RandomGenerator rng = Seed.random(seed.value() + 31L * writers + repetition);
        List<String> payloads = generate(rng, parameters);

        // Each writer gets its own connection, so the study measures lock contention rather than pool waits.
        try (var pool = db.pooledDataSource(LedgerTestDatabase.APP, writers + 1)) {
            ApplyTestDriver driver = ApplyTestDriver.create(pool);

            drive(driver, payloads, writers, TimeUnit.SECONDS.toNanos(parameters.warmupSeconds()), null);  // not measured

            Window window = new Window();
            drive(driver, payloads, writers, TimeUnit.SECONDS.toNanos(parameters.windowSeconds()), window);

            long[] invariants = invariantViolations(db);
            Sp1Run run = new Sp1Run(writers, repetition, parameters.windowSeconds(), window.applied.get(),
                    window.ordersPerSecond(parameters.windowSeconds()), window.applyP50Micros(), window.applyP95Micros(),
                    window.lockWaitP50Micros(), window.lockWaitP95Micros(), window.deadlockRetries.get(),
                    window.lockTimeoutRetries.get(), window.connectionRetries.get(), window.retriesExhausted.get(),
                    invariants[0], invariants[1], invariants[2], invariants[3]);
            System.out.println("ZS-SP1 window " + run.summary());
            return run;
        }
    }

    /** Runs writers flat out for the given duration; {@code window} is null for warm-up, which records nothing. */
    private void drive(ApplyTestDriver driver, List<String> payloads, int writers, long durationNanos, Window window)
            throws InterruptedException {
        AtomicBoolean running = new AtomicBoolean(true);
        AtomicLong cursor = new AtomicLong();
        CountDownLatch done = new CountDownLatch(writers);
        ExecutorService threads = Executors.newFixedThreadPool(writers);
        long start = System.nanoTime();

        for (int i = 0; i < writers; i++) {
            threads.submit(() -> {
                try {
                    while (running.get()) {
                        String payload = payloads.get((int) (cursor.getAndIncrement() % payloads.size()));
                        try {
                            ApplyBatchResult result = driver.apply(List.of(ApplyRecord.of(payload)));
                            if (window != null) {
                                window.record(result);
                            }
                        } catch (RuntimeException failure) {
                            // A typed retries-exhausted failure invalidates the run (instruction 5), so it is counted
                            // and named rather than hidden behind a number.
                            if (window != null) {
                                window.retriesExhausted.incrementAndGet();
                            }
                            System.out.println("ZS-SP1 writer failure: " + failure.getClass().getSimpleName()
                                    + ": " + failure.getMessage());
                        }
                    }
                } finally {
                    done.countDown();
                }
            });
        }
        while (System.nanoTime() - start < durationNanos) {
            Thread.sleep(50);
        }
        running.set(false);
        assertTrue(done.await(2, TimeUnit.MINUTES), "writers must stop at the end of the window");
        threads.shutdownNow();
    }

    /** Every order touches platform:main; riders and drivers vary so only the platform entity is hot by construction. */
    private static List<String> generate(RandomGenerator rng, Sp1Parameters parameters) {
        List<String> payloads = new ArrayList<>(parameters.payloadPoolSize());
        for (int i = 0; i < parameters.payloadPoolSize(); i++) {
            long fare = 100 + rng.nextLong(9_900);
            long commission = fare / 5;
            OrderCandidate order = new OrderCandidate("COMMERCE", "trip.completed", List.of(
                    Entry.of("rider:R" + rng.nextInt(parameters.riderPool()), "receivable", "USD", fare),
                    Entry.of("driver:D" + rng.nextInt(parameters.driverPool()), "payable", "USD", -(fare - commission)),
                    Entry.of("platform:main", "revenue", "USD", -commission)));
            payloads.add(MoneyOrderPayloads.render(UUID.randomUUID(), "sp1_" + (i % 1000), order));
        }
        return payloads;
    }

    /**
     * I2, I3, I4 and I5 after the window: a run with any violation is investigated, not reported.
     *
     * <p>I5 streams each entity's chain and discards the rows as it goes. Reading whole changelogs into memory
     * exhausted the heap once the study had applied a few hundred thousand orders, which killed the run and lost every
     * measurement with it.
     */
    private static long[] invariantViolations(LedgerTestDatabase db) throws SQLException {
        try (Connection c = db.connect(LedgerTestDatabase.VERIFIER)) {
            long i5 = 0;
            LedgerStore store = new LedgerStore(JdbcClient.create(db.dataSource(LedgerTestDatabase.APP)),
                    new JdbcTemplate(db.dataSource(LedgerTestDatabase.APP)));
            ChainVerifier verifier = new ChainVerifier(new ChangelogHasher());
            for (String entityId : store.allEntityIds()) {
                if (!verifier.verify(store.streamChangelog(entityId)).consistent()) {
                    i5++;
                }
            }
            return new long[] {LedgerQueries.i2Violations(c).size(), LedgerQueries.i3Violations(c).size(),
                    LedgerQueries.i4Violations(c).size(), i5};
        }
    }

    private static double ceiling(List<Sp1Run> runs) {
        return runs.stream()
                .collect(java.util.stream.Collectors.groupingBy(Sp1Run::writers,
                        java.util.stream.Collectors.collectingAndThen(
                                java.util.stream.Collectors.mapping(Sp1Run::ordersPerSecond, java.util.stream.Collectors.toList()),
                                Sp1Run::median)))
                .values().stream().mapToDouble(Double::doubleValue).max().orElse(0);
    }

    private Path writeRawData(List<Sp1Run> runs, Sp1Parameters parameters, Seed seed, boolean fullStudy) {
        Path directory = LedgerTestDatabase.ROOT.resolve("docs/results/sp1");
        try {
            Files.createDirectories(directory);
            Path file = directory.resolve("sp1-runs.json");
            StringBuilder json = new StringBuilder("{\n");
            json.append("  \"study\": \"SP1 lock study\",\n")
                    .append("  \"full_study\": ").append(fullStudy).append(",\n")
                    .append("  \"seed\": ").append(seed.value()).append(",\n")
                    .append("  \"window_seconds\": ").append(parameters.windowSeconds()).append(",\n")
                    .append("  \"warmup_seconds\": ").append(parameters.warmupSeconds()).append(",\n")
                    .append("  \"repetitions\": ").append(parameters.repetitions()).append(",\n")
                    .append("  \"image\": \"").append(LedgerTestDatabase.postgresImage()).append("\",\n")
                    .append("  \"durability\": \"fsync on (D00-3 configuration)\",\n")
                    .append("  \"runs\": [\n");
            for (int i = 0; i < runs.size(); i++) {
                json.append("    ").append(runs.get(i).toJson()).append(i + 1 < runs.size() ? ",\n" : "\n");
            }
            json.append("  ]\n}\n");
            Files.writeString(file, json);
            return file;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
