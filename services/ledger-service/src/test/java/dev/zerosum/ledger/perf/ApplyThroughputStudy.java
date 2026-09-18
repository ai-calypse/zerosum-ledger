package dev.zerosum.ledger.perf;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.zaxxer.hikari.HikariDataSource;
import dev.zerosum.ledger.apply.ApplyBatchResult;
import dev.zerosum.ledger.apply.ApplyRecord;
import dev.zerosum.ledger.changelog.ChainVerifier;
import dev.zerosum.ledger.changelog.ChangelogHasher;
import dev.zerosum.ledger.store.LedgerStore;
import dev.zerosum.ledger.support.ApplyTestDriver;
import dev.zerosum.ledger.support.LedgerQueries;
import dev.zerosum.ledger.support.LedgerTestDatabase;
import dev.zerosum.money.generate.Seed;
import dev.zerosum.money.generate.SeededExtension;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Instant;
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
 * S07-T05/T06 apply throughput: the two measurements SP1 deliberately did not make (D07-5, D07-6).
 *
 * <ol>
 *   <li><strong>Batched versus per-order apply</strong>, which tests the master's ~5,000 orders/s batched ESTIMATE
 *       (<a href="../../../../../../../../docs/zerosum_ledger_mvp_plan.md">#bottlenecks</a>) and is SP4 option (a).</li>
 *   <li><strong>Throughput across N distinct platform entities</strong>, the workload-level emulation of SP4
 *       option (b) that S07-T04 instruction 2 asks for. SP1 measured the worst case, where every order serialises on
 *       one entity; this is the same measurement with the load spread.</li>
 * </ol>
 *
 * <p>Both reuse SP1's method, because a new number is only comparable to an old one if it was taken the same way:
 * payloads are pre-generated before every window so the client is never the bottleneck, every measured window is
 * preceded by an unmeasured warm-up, repetitions are reported as median-of-repetitions with the range, and a window
 * counts as a data point only when the invariants hold.
 *
 * <p><strong>Durability is intact.</strong> {@link LedgerTestDatabase#startDurable()} is the D00-3 configuration with
 * {@code fsync} on and the Compose memory limit, exactly as SP1 ran. Ordinary ledger tests use {@code fsync=off} for
 * speed; commit fsync is part of what an apply transaction costs, so a number measured that way would be a smoke check
 * rather than evidence.
 *
 * <p><strong>Deviation from SP1, stated here because it is the one that matters.</strong> SP1 swept I5 over the whole
 * ledger after every window. These grids are 36 windows against SP1's 12 and reach several million changelog rows, so
 * I2-I4 run after every window and the I5 chain sweep runs once per configuration block, covering every window in it.
 * A fresh database per block bounds what each sweep has to verify.
 *
 * <p>Tagged {@code study}, so no CI job runs it. Start it deliberately:
 * {@code ./gradlew :services:ledger-service:studyTest --tests '*ApplyThroughputStudy' --rerun -i}.
 */
@Tag("study")
@ExtendWith(SeededExtension.class)
class ApplyThroughputStudy {

    private static final Path RESULTS = LedgerTestDatabase.ROOT.resolve("docs/results/perf");

    /**
     * Evidence goes to {@code docs/results/perf/}; a smoke run goes to a scratch directory instead (S07-T04
     * instruction 8), so a three-second harness check can never be mistaken for, or overwrite, a measured result.
     */
    private static Path resultsDir(boolean fullStudy) {
        return fullStudy ? RESULTS.resolve(PerfParameters.runLabel())
                : LedgerTestDatabase.ROOT.resolve("services/ledger-service/build/perf-smoke");
    }

    @Test
    void batchedVersusPerOrderApply(Seed seed) throws Exception {
        PerfParameters fromFile = PerfParameters.fromClasspath();
        PerfParameters parameters = fromFile.withOverrides();
        boolean fullStudy = announce("batched-vs-per-order", parameters, fromFile, seed);

        List<PerfRun> runs = new ArrayList<>();
        Path raw = resultsDir(fullStudy).resolve("batch-size-runs.json");
        for (int writers : parameters.batchWriters()) {
            for (int batchSize : parameters.batchSizes()) {
                // Every order touches platform:main, which is SP1's workload: entityCount = 1.
                runBlock(runs, raw, parameters, seed, fullStudy, "batch-size", writers, batchSize, 1);
            }
        }
        report(runs, raw, "batched versus per-order apply");
    }

    @Test
    void throughputAcrossManyEntities(Seed seed) throws Exception {
        PerfParameters fromFile = PerfParameters.fromClasspath();
        PerfParameters parameters = fromFile.withOverrides();
        boolean fullStudy = announce("entity-spread", parameters, fromFile, seed);

        List<PerfRun> runs = new ArrayList<>();
        Path raw = resultsDir(fullStudy).resolve("entity-spread-runs.json");
        for (int entityCount : parameters.entityCounts()) {
            runBlock(runs, raw, parameters, seed, fullStudy, "entity-spread", parameters.entityWriters(),
                    parameters.entityBatchSize(), entityCount);
        }
        report(runs, raw, "throughput across N distinct platform entities");
    }

    /**
     * One configuration, measured {@code repetitions} times against its own database.
     *
     * <p>A fresh database per block keeps each block's invariant sweep proportional to that block's own writes. It also
     * means the blocks do not inherit each other's table sizes, so a later configuration is never penalised for running
     * after a faster one.
     */
    private void runBlock(List<PerfRun> runs, Path raw, PerfParameters parameters, Seed seed, boolean fullStudy,
            String measurement, int writers, int batchSize, int entityCount) throws Exception {
        System.out.printf("ZS-PERF block measurement=%s writers=%d batch=%d entities=%d%n",
                measurement, writers, batchSize, entityCount);
        int firstRunOfBlock = runs.size();
        try (LedgerTestDatabase db = startDurableWithRetry();
                HikariDataSource pool = db.pooledDataSource(LedgerTestDatabase.APP, writers + 2)) {
            ApplyTestDriver driver = ApplyTestDriver.create(pool);
            for (int repetition = 1; repetition <= parameters.repetitions(); repetition++) {
                long base = seed.value() + 31L * writers + 131L * batchSize + 1013L * entityCount + 7919L * repetition;
                PerfPayloads.Pool payloads = new PerfPayloads.Pool(Seed.random(base), parameters.payloadPoolSize(),
                        parameters.riderPool(), parameters.driverPool(), entityCount);

                drive(driver, payloads, writers, batchSize,
                        TimeUnit.SECONDS.toNanos(parameters.warmupSeconds()), null, base);   // warm-up, not measured

                PerfWindow window = new PerfWindow();
                Instant windowStart = Instant.now();
                drive(driver, payloads, writers, batchSize,
                        TimeUnit.SECONDS.toNanos(parameters.windowSeconds()), window, base + 1);
                Instant windowEnd = Instant.now();

                long[] invariants = i2ToI4(db);
                PerfRun run = new PerfRun(measurement, writers, batchSize, entityCount, repetition,
                        parameters.windowSeconds(), windowStart.toString(), windowEnd.toString(), window.applied.get(),
                        window.ordersPerSecond(parameters.windowSeconds()), window.batchP50Micros(),
                        window.batchP95Micros(), window.lockWaitP50Micros(), window.lockWaitP95Micros(),
                        window.batches.get(), window.duplicates.get(), window.quarantined.get(),
                        window.deadlockRetries.get(), window.lockTimeoutRetries.get(), window.connectionRetries.get(),
                        window.retriesExhausted.get(), invariants[0], invariants[1], invariants[2], -1);
                runs.add(run);
                System.out.println("ZS-PERF window " + run.summary());
                // Written after every window: SP1 lost a whole study's measurements by writing only at the end.
                writeRawData(raw, runs, parameters, seed, fullStudy, measurement);
            }
            long i5 = i5Violations(pool);
            for (int i = firstRunOfBlock; i < runs.size(); i++) {
                runs.set(i, runs.get(i).withI5(i5));
            }
            writeRawData(raw, runs, parameters, seed, fullStudy, measurement);
        }
    }

    /**
     * Starts a durable database, retrying a container that fails to come up.
     *
     * <p>This grid starts a database per configuration block on a Docker daemon the study does not have to itself, and
     * a container losing the race to become reachable is an environment failure, not a measurement. Retrying it is
     * honest; silently dropping the windows it would have produced would not be, and neither would reporting a
     * measurement taken on a half-started database. A start that never succeeds fails the study.
     */
    private static LedgerTestDatabase startDurableWithRetry() throws InterruptedException {
        RuntimeException lastFailure = null;
        for (int attempt = 1; attempt <= 3; attempt++) {
            try {
                return LedgerTestDatabase.startDurable();
            } catch (RuntimeException failure) {
                lastFailure = failure;
                System.out.println("ZS-PERF container start attempt " + attempt + " failed: " + failure.getMessage());
                Thread.sleep(TimeUnit.SECONDS.toMillis(10));
            }
        }
        throw lastFailure;
    }

    /**
     * Runs {@code writers} threads flat out for the given duration; {@code window} is null for warm-up, which records
     * nothing.
     *
     * <p>Each writer builds its batch from pre-rendered shapes and its own order-id stream, so no two writers can
     * submit the same order and the only shared state on the hot path is one atomic cursor into the pool.
     */
    private void drive(ApplyTestDriver driver, PerfPayloads.Pool payloads, int writers, int batchSize,
            long durationNanos, PerfWindow window, long seedValue) throws InterruptedException {
        AtomicBoolean running = new AtomicBoolean(true);
        AtomicLong cursor = new AtomicLong();
        CountDownLatch done = new CountDownLatch(writers);
        ExecutorService threads = Executors.newFixedThreadPool(writers);
        long start = System.nanoTime();

        for (int w = 0; w < writers; w++) {
            final int writerIndex = w;
            threads.submit(() -> {
                RandomGenerator ids = Seed.random(seedValue + 1_000_003L * (writerIndex + 1L));
                try {
                    while (running.get()) {
                        List<ApplyRecord> batch = new ArrayList<>(batchSize);
                        for (int i = 0; i < batchSize; i++) {
                            int slot = (int) (cursor.getAndIncrement() % payloads.size());
                            batch.add(ApplyRecord.of(payloads.payload(slot, new UUID(ids.nextLong(), ids.nextLong()))));
                        }
                        try {
                            ApplyBatchResult result = driver.apply(batch);
                            if (window != null) {
                                window.record(result);
                            }
                        } catch (RuntimeException failure) {
                            // Retries exhausted invalidates the window, so it is counted and named rather than hidden.
                            if (window != null) {
                                window.retriesExhausted.incrementAndGet();
                            }
                            System.out.println("ZS-PERF writer failure: " + failure.getClass().getSimpleName() + ": "
                                    + failure.getMessage());
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
        // A large batch under contention can sit behind the lock timeout and its backoff, so the drain is generous.
        assertTrue(done.await(5, TimeUnit.MINUTES), "writers must stop at the end of the window");
        threads.shutdownNow();
    }

    /** I2, I3 and I4 after the window, read through the read-only verifier role, exactly as SP1 checks them. */
    private static long[] i2ToI4(LedgerTestDatabase db) throws SQLException {
        try (Connection c = db.connect(LedgerTestDatabase.VERIFIER)) {
            return new long[] {LedgerQueries.i2Violations(c).size(), LedgerQueries.i3Violations(c).size(),
                    LedgerQueries.i4Violations(c).size()};
        }
    }

    /**
     * I5 over every entity in the block's ledger. The chain is streamed in bounded pages and discarded as it goes:
     * reading whole changelogs into memory is what exhausted the heap in SP1's discarded first attempt.
     */
    private static long i5Violations(HikariDataSource pool) {
        LedgerStore store = new LedgerStore(JdbcClient.create(pool), new JdbcTemplate(pool));
        ChainVerifier verifier = new ChainVerifier(new ChangelogHasher());
        long violations = 0;
        for (String entityId : store.allEntityIds()) {
            if (!verifier.verify(store.streamChangelog(entityId)).consistent()) {
                violations++;
            }
        }
        return violations;
    }

    private boolean announce(String measurement, PerfParameters parameters, PerfParameters fromFile, Seed seed) {
        boolean fullStudy = parameters.isFullStudy(fromFile);
        System.out.printf("ZS-PERF start measurement=%s runLabel=%s windowSeconds=%d warmupSeconds=%d repetitions=%d "
                        + "fullStudy=%s seed=%d image=%s%n",
                measurement, PerfParameters.runLabel(), parameters.windowSeconds(), parameters.warmupSeconds(),
                parameters.repetitions(), fullStudy, seed.value(), LedgerTestDatabase.postgresImage());
        if (!fullStudy) {
            System.out.println("ZS-PERF WARNING this run uses overrides and is a harness smoke check, not evidence");
        }
        assertEquals(PerfParameters.qualityOverridesRequested(), !fullStudy,
                "override properties must reach the test JVM. A Gradle -D sets them on the daemon only; the ledger "
                        + "build forwards zs.perf.* explicitly. Without that, a smoke check silently runs the full study.");
        return fullStudy;
    }

    private void report(List<PerfRun> runs, Path raw, String what) {
        System.out.println("ZS-PERF raw data: " + raw);
        for (PerfRun run : runs) {
            System.out.println("ZS-PERF " + run.summary());
        }
        assertTrue(runs.stream().allMatch(PerfRun::valid),
                "a window with an invariant violation, a duplicate, a quarantine or an exhausted retry is not a data "
                        + "point (" + what + "): "
                        + runs.stream().filter(r -> !r.valid()).map(PerfRun::summary).toList());
    }

    private Path writeRawData(Path file, List<PerfRun> runs, PerfParameters parameters, Seed seed, boolean fullStudy,
            String measurement) {
        try {
            Files.createDirectories(file.getParent());
            StringBuilder json = new StringBuilder("{\n");
            json.append("  \"study\": \"S07 ").append(measurement).append("\",\n")
                    .append("  \"run_label\": \"").append(PerfParameters.runLabel()).append("\",\n")
                    .append("  \"full_study\": ").append(fullStudy).append(",\n")
                    .append("  \"batch_sizes\": \"").append(parameters.batchSizes()).append("\",\n")
                    .append("  \"batch_writers\": \"").append(parameters.batchWriters()).append("\",\n")
                    .append("  \"entity_counts\": \"").append(parameters.entityCounts()).append("\",\n")
                    .append("  \"seed\": ").append(seed.value()).append(",\n")
                    .append("  \"window_seconds\": ").append(parameters.windowSeconds()).append(",\n")
                    .append("  \"warmup_seconds\": ").append(parameters.warmupSeconds()).append(",\n")
                    .append("  \"repetitions\": ").append(parameters.repetitions()).append(",\n")
                    .append("  \"payload_pool_size\": ").append(parameters.payloadPoolSize()).append(",\n")
                    .append("  \"rider_pool\": ").append(parameters.riderPool()).append(",\n")
                    .append("  \"driver_pool\": ").append(parameters.driverPool()).append(",\n")
                    .append("  \"image\": \"").append(LedgerTestDatabase.postgresImage()).append("\",\n")
                    .append("  \"durability\": \"fsync on (D00-3 configuration)\",\n")
                    .append("  \"i5_note\": \"i5_violations is -1 until the block's whole-ledger chain sweep runs\",\n")
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
