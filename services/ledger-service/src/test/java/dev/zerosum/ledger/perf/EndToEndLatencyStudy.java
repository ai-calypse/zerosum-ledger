package dev.zerosum.ledger.perf;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import dev.zerosum.contracts.kafka.TopicDefinitions;
import dev.zerosum.ledger.changelog.ChainVerifier;
import dev.zerosum.ledger.changelog.ChangelogHasher;
import dev.zerosum.ledger.store.LedgerStore;
import dev.zerosum.ledger.support.LedgerQueries;
import dev.zerosum.ledger.support.LedgerTestDatabase;
import dev.zerosum.money.generate.Seed;
import dev.zerosum.money.generate.SeededExtension;
import dev.zerosum.outbox.OutboxFactory;
import dev.zerosum.outbox.OutboxProperties;
import dev.zerosum.outbox.OutboxRelay;
import dev.zerosum.outbox.OutboxRelayLoop;
import dev.zerosum.outbox.OutboxWriter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.LockSupport;
import java.util.random.RandomGenerator;
import java.util.regex.Pattern;
import javax.sql.DataSource;
import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.StringSerializer;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * S07-T05 end-to-end order-to-balance latency (P2) across the real outbox → Kafka → apply path (D07-5).
 *
 * <p><strong>What is real here.</strong> The real {@code libs/outbox} writer and relay, against order-service's own
 * migrated {@code outbox} table; a real broker; and the real ledger listener inside a Spring context, applying each
 * poll batch through the real engine. Nothing stands in for a pipeline stage.
 *
 * <p><strong>Why this is not the simulator's number.</strong> {@code tools/simulator} disclaims its own figure — "apply
 * time is a polling observation, not a latency measurement" — because it watches for an order to appear. This measures
 * timestamps instead, and both ends come from the <em>same PostgreSQL server clock</em>: {@code outbox.created_at} in
 * the {@code orders} database and {@code applied_orders.applied_at} in the {@code ledger} database are two databases in
 * one container, so there is no host-to-VM clock offset to estimate and no polling interval to subtract. That is the
 * master's own P2 definition, {@code applied_orders.applied_at − order created_at}.
 *
 * <p><strong>The two edges, stated because a latency number without them is not checkable.</strong>
 * <ul>
 *   <li>{@code outbox.created_at} defaults to {@code now()}, the append transaction's <em>start</em>, a little before
 *       the commit that makes the order durable and lets the API answer. The measurement therefore <em>overstates</em>
 *       by that transaction's duration.</li>
 *   <li>{@code applied_orders.applied_at} is {@code clock_timestamp()} when the row is inserted <em>inside</em> the
 *       apply transaction, a little before the commit that makes the balance visible. The measurement therefore
 *       <em>understates</em> by the rest of that transaction, which is bounded by the apply batch duration the run
 *       records from the engine's own {@code ledger_apply} timer.</li>
 *   <li>The HTTP layer is <em>not</em> included: order-service is a different module and this harness appends to the
 *       outbox directly. P1, the client-observed acknowledgement, is a separate metric and is not measured here.</li>
 * </ul>
 *
 * <p>Durability is intact ({@link LedgerTestDatabase#startDurable()}), and the invariant gauges keep running during the
 * window exactly as they do in production; neither is relaxed to improve a number.
 *
 * <p>Tagged {@code study}, so no CI job runs it:
 * {@code ./gradlew :services:ledger-service:studyTest --tests '*EndToEndLatencyStudy' --rerun -i}.
 */
@Tag("study")
@SpringBootTest
@ExtendWith(SeededExtension.class)
class EndToEndLatencyStudy {

    private static final String ORDERS_OWNER = "orders_owner";
    private static final String ORDERS_APP = "orders_app";
    private static final Path RESULTS = LedgerTestDatabase.ROOT.resolve("docs/results/perf");

    /**
     * Relay settings copied from {@code services/order-service/src/main/resources/application.yml} ({@code zs.outbox}),
     * so this measures the deployed relay behaviour — above all the 50 ms poll interval, which the master's P2 stage
     * budget allocates 25 ms p50 / 60 ms p95 to.
     */
    private static final OutboxProperties OUTBOX = new OutboxProperties(500, Duration.ofMillis(50),
            Duration.ofSeconds(10), Duration.ofMillis(100), Duration.ofSeconds(5), Duration.ofHours(1),
            Duration.ofMinutes(10), 1_000);

    private static final LedgerTestDatabase DB = LedgerTestDatabase.startDurable();
    private static final org.testcontainers.kafka.KafkaContainer KAFKA = startKafka();

    @Autowired
    private MeterRegistry meters;

    @DynamicPropertySource
    static void pipeline(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", DB::jdbcUrl);
        registry.add("spring.datasource.username", () -> LedgerTestDatabase.APP);
        registry.add("spring.datasource.password", () -> DB.password(LedgerTestDatabase.APP));
        registry.add("spring.flyway.enabled", () -> "false");
        registry.add("spring.kafka.bootstrap-servers", KAFKA::getBootstrapServers);
        registry.add("zs.auth.reader-token", () -> "study-reader-token");
        registry.add("ledger.consumer.enabled", () -> "true");
    }

    /** The orders database of the same container, migrated with order-service's real migrations. */
    @BeforeAll
    static void migrateOrdersDatabase() {
        Flyway.configure()
                .dataSource(ordersJdbcUrl(), ORDERS_OWNER, DB.password(ORDERS_OWNER))
                .locations("filesystem:"
                        + LedgerTestDatabase.ROOT.resolve("services/order-service/src/main/resources/db/migration"))
                .load()
                .migrate();
    }

    @Test
    void orderToApplyLatencyAcrossTheRealPipeline(Seed seed) throws Exception {
        PerfParameters fromFile = PerfParameters.fromClasspath();
        PerfParameters parameters = fromFile.withOverrides();
        boolean fullStudy = parameters.isFullStudy(fromFile);
        System.out.printf("ZS-PERF start measurement=e2e-latency rates=%s windowSeconds=%d warmupSeconds=%d "
                        + "repetitions=%d appenders=%d fullStudy=%s seed=%d image=%s kafka=%s%n",
                parameters.e2eRates(), parameters.e2eWindowSeconds(), parameters.e2eWarmupSeconds(),
                parameters.repetitions(), parameters.e2eAppenders(), fullStudy, seed.value(),
                LedgerTestDatabase.postgresImage(), composeKafkaImage());
        if (!fullStudy) {
            System.out.println("ZS-PERF WARNING this run uses overrides and is a harness smoke check, not evidence");
        }
        assertEquals(PerfParameters.qualityOverridesRequested(), !fullStudy,
                "override properties must reach the test JVM; the ledger build forwards zs.perf.* explicitly");

        List<E2eRun> runs = new ArrayList<>();
        // Evidence goes to docs/results/perf/<run label>/; a smoke run goes to a scratch directory (S07-T04
        // instruction 8), so it can never be mistaken for, or overwrite, a measured result.
        Path raw = (fullStudy ? RESULTS.resolve(PerfParameters.runLabel())
                : LedgerTestDatabase.ROOT.resolve("services/ledger-service/build/perf-smoke"))
                .resolve("e2e-latency-runs.json");

        try (HikariDataSource orders = ordersDataSource(parameters.e2eAppenders() + 2)) {
            PlatformTransactionManager ordersTx = new DataSourceTransactionManager(orders);
            JdbcTemplate ordersTemplate = new JdbcTemplate(orders);
            OutboxWriter writer = OutboxFactory.writer(JdbcClient.create(orders));
            KafkaTemplate<String, String> kafka = kafkaTemplate();
            OutboxRelay relay = OutboxFactory.relay(ordersTemplate, kafka, OUTBOX,
                    OutboxFactory.metrics(new SimpleMeterRegistry(),
                            () -> ordersTemplate.queryForObject("SELECT COALESCE(EXTRACT(EPOCH FROM (now() - MIN("
                                    + "created_at))), 0) FROM outbox WHERE published_at IS NULL", Double.class)),
                    ordersTx);
            OutboxRelayLoop loop = OutboxFactory.loop(relay, OUTBOX);
            loop.start();
            try {
                for (int rate : parameters.e2eRates()) {
                    for (int repetition = 1; repetition <= parameters.repetitions(); repetition++) {
                        E2eRun run = measureOneWindow(parameters, seed, orders, ordersTx, ordersTemplate, writer,
                                rate, repetition);
                        runs.add(run);
                        System.out.println("ZS-PERF window " + run.summary());
                        writeRawData(raw, runs, parameters, seed, fullStudy);
                    }
                }
                long i5 = i5Violations();
                for (int i = 0; i < runs.size(); i++) {
                    runs.set(i, runs.get(i).withI5(i5));
                }
                writeRawData(raw, runs, parameters, seed, fullStudy);
            } finally {
                loop.destroy();
                kafka.destroy();
            }
        }

        System.out.println("ZS-PERF raw data: " + raw);
        runs.forEach(run -> System.out.println("ZS-PERF " + run.summary()));
        assertTrue(runs.stream().allMatch(E2eRun::valid),
                "a window that lost an order, quarantined one, violated an invariant or could not hold its arrival "
                        + "rate is not a latency result: "
                        + runs.stream().filter(run -> !run.valid()).map(E2eRun::summary).toList());
    }

    private E2eRun measureOneWindow(PerfParameters parameters, Seed seed, DataSource orders,
            PlatformTransactionManager ordersTx, JdbcTemplate ordersTemplate, OutboxWriter writer, int rate,
            int repetition) throws Exception {
        long base = seed.value() + 31L * rate + 7919L * repetition;

        // Warm-up at the test rate, not measured (master #cold-warm).
        append(ordersTx, writer, parameters, rate, parameters.e2eWarmupSeconds(), base);
        awaitQuiesce(ordersTemplate);

        long firstId = maxOutboxId(ordersTemplate);
        Instant windowStart = Instant.now();
        Timer applyTimer = meters.find("ledger_apply").timer();
        long applyCountBefore = applyTimer == null ? 0 : applyTimer.count();
        double applyMicrosBefore = applyTimer == null ? 0 : applyTimer.totalTime(TimeUnit.MICROSECONDS);

        Arrivals arrivals = append(ordersTx, writer, parameters, rate, parameters.e2eWindowSeconds(), base + 1);
        Instant windowEnd = Instant.now();
        long lastId = maxOutboxId(ordersTemplate);
        awaitQuiesce(ordersTemplate);
        // The window's orders keep flowing through the relay and the listener until the pipeline quiesces, so a
        // check for overlapping load has to cover this instant too, not just the end of the arrivals.
        Instant quiesced = Instant.now();

        long applyBatches = (applyTimer == null ? 0 : applyTimer.count()) - applyCountBefore;
        double applyMicros = (applyTimer == null ? 0 : applyTimer.totalTime(TimeUnit.MICROSECONDS)) - applyMicrosBefore;

        // Both ends of every measurement come from the same PostgreSQL clock; nothing is corrected or interpolated.
        Map<UUID, long[]> outboxRows = new HashMap<>();
        ordersTemplate.query("SELECT payload->>'order_id' AS order_id, created_at, published_at FROM outbox "
                        + "WHERE id > ? AND id <= ?",
                rs -> {
                    outboxRows.put(UUID.fromString(rs.getString("order_id")), new long[] {
                        micros(rs.getTimestamp("created_at")),
                        rs.getTimestamp("published_at") == null ? -1 : micros(rs.getTimestamp("published_at"))});
                }, firstId, lastId);

        Map<UUID, Long> appliedAt = new HashMap<>();
        new JdbcTemplate(DB.dataSource(LedgerTestDatabase.APP)).query(
                "SELECT order_id, applied_at FROM applied_orders WHERE order_created_at >= ?",
                rs -> {
                    appliedAt.put((UUID) rs.getObject("order_id"), micros(rs.getTimestamp("applied_at")));
                }, java.sql.Timestamp.from(windowStart.minusSeconds(5)));

        List<Long> endToEnd = new ArrayList<>(outboxRows.size());
        List<Long> outboxStage = new ArrayList<>(outboxRows.size());
        List<Long> kafkaApplyStage = new ArrayList<>(outboxRows.size());
        long missing = 0;
        for (Map.Entry<UUID, long[]> row : outboxRows.entrySet()) {
            Long applied = appliedAt.get(row.getKey());
            if (applied == null) {
                missing++;
                continue;
            }
            long createdAt = row.getValue()[0];
            long publishedAt = row.getValue()[1];
            endToEnd.add(applied - createdAt);
            if (publishedAt >= 0) {
                outboxStage.add(publishedAt - createdAt);
                kafkaApplyStage.add(applied - publishedAt);
            }
        }

        long[] invariants = i2ToI4();
        long quarantined = countInLedger("SELECT count(*) FROM quarantined_orders");
        return new E2eRun(rate, repetition, parameters.e2eWindowSeconds(), windowStart.toString(),
                windowEnd.toString(), quiesced.toString(), outboxRows.size(), endToEnd.size(), missing,
                arrivals.appended(), arrivals.late(),
                percentiles(endToEnd), percentiles(outboxStage), percentiles(kafkaApplyStage),
                applyBatches, applyBatches == 0 ? 0 : applyMicros / applyBatches, quarantined,
                invariants[0], invariants[1], invariants[2], -1, parameters.e2eMaxLatePercent());
    }

    /**
     * Appends money orders at a constant arrival rate, open-model, spread over {@code e2eAppenders} threads.
     *
     * <p>An arrival that starts more than the configured threshold after its scheduled time is counted late. That is
     * this harness's equivalent of k6's dropped iterations: it is the signal that the generator, not the system under
     * test, was the limit, and too many of them invalidate the window rather than being reported as latency.
     */
    private Arrivals append(PlatformTransactionManager ordersTx, OutboxWriter writer, PerfParameters parameters,
            int rate, int seconds, long seedValue) throws InterruptedException {
        int appenders = parameters.e2eAppenders();
        long durationNanos = TimeUnit.SECONDS.toNanos(seconds);
        long perThreadIntervalNanos = TimeUnit.SECONDS.toNanos(1) * appenders / rate;
        long lateThresholdNanos = TimeUnit.MILLISECONDS.toNanos(parameters.e2eLateThresholdMillis());
        AtomicLong appended = new AtomicLong();
        AtomicLong late = new AtomicLong();
        CountDownLatch done = new CountDownLatch(appenders);
        ExecutorService threads = Executors.newFixedThreadPool(appenders);

        for (int a = 0; a < appenders; a++) {
            final int index = a;
            threads.submit(() -> {
                RandomGenerator rng = Seed.random(seedValue + 1_000_003L * (index + 1L));
                TransactionTemplate transaction = new TransactionTemplate(ordersTx);
                long begin = System.nanoTime();
                long offset = TimeUnit.SECONDS.toNanos(1) * index / rate;
                try {
                    for (long i = 0; ; i++) {
                        long due = begin + offset + i * perThreadIntervalNanos;
                        long now = System.nanoTime();
                        if (now < due) {
                            LockSupport.parkNanos(due - now);
                        } else if (now - due > lateThresholdNanos) {
                            late.incrementAndGet();
                        }
                        if (System.nanoTime() - begin >= durationNanos) {
                            return;
                        }
                        UUID orderId = new UUID(rng.nextLong(), rng.nextLong());
                        String group = "e2e_" + index + "_" + i;
                        String payload = PerfPayloads.render(orderId, group,
                                PerfPayloads.trip(rng, parameters.riderPool(), parameters.driverPool(),
                                        "platform:main"),
                                Instant.now());
                        transaction.executeWithoutResult(status ->
                                writer.append(TopicDefinitions.MONEY_ORDERS.name(), group, payload,
                                        Map.of("schema", "zerosum.money_order.v1", "order_id", orderId.toString())));
                        appended.incrementAndGet();
                    }
                } finally {
                    done.countDown();
                }
            });
        }
        assertTrue(done.await(seconds + 120L, TimeUnit.SECONDS), "appenders must finish");
        threads.shutdownNow();
        return new Arrivals(appended.get(), late.get());
    }

    /** Waits until the relay has published everything and the listener has caught up. */
    private void awaitQuiesce(JdbcTemplate ordersTemplate) {
        long deadline = System.nanoTime() + Duration.ofMinutes(5).toNanos();
        long lastApplied = -1;
        while (System.nanoTime() < deadline) {
            Long unpublished = ordersTemplate.queryForObject(
                    "SELECT count(*) FROM outbox WHERE published_at IS NULL", Long.class);
            long applied = countInLedger("SELECT count(*) FROM applied_orders");
            if (unpublished != null && unpublished == 0 && applied == lastApplied) {
                return;
            }
            lastApplied = applied;
            sleep(500);
        }
        throw new AssertionError("the pipeline did not quiesce within 5 minutes");
    }

    private static long[] percentiles(List<Long> micros) {
        long[] sorted = micros.stream().mapToLong(Long::longValue).sorted().toArray();
        return new long[] {PerfWindow.percentileOf(sorted, 50), PerfWindow.percentileOf(sorted, 95),
            PerfWindow.percentileOf(sorted, 99)};
    }

    private static long micros(java.sql.Timestamp timestamp) {
        Instant instant = timestamp.toInstant();
        return instant.getEpochSecond() * 1_000_000L + instant.getNano() / 1_000L;
    }

    private long maxOutboxId(JdbcTemplate ordersTemplate) {
        Long id = ordersTemplate.queryForObject("SELECT COALESCE(max(id), 0) FROM outbox", Long.class);
        return id == null ? 0 : id;
    }

    private long countInLedger(String sql) {
        try (Connection c = DB.connect(LedgerTestDatabase.VERIFIER)) {
            return LedgerQueries.count(c, sql);
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    private static long[] i2ToI4() throws SQLException {
        try (Connection c = DB.connect(LedgerTestDatabase.VERIFIER)) {
            return new long[] {LedgerQueries.i2Violations(c).size(), LedgerQueries.i3Violations(c).size(),
                LedgerQueries.i4Violations(c).size()};
        }
    }

    private static long i5Violations() {
        DataSource dataSource = DB.dataSource(LedgerTestDatabase.APP);
        LedgerStore store = new LedgerStore(JdbcClient.create(dataSource), new JdbcTemplate(dataSource));
        ChainVerifier verifier = new ChainVerifier(new ChangelogHasher());
        long violations = 0;
        for (String entityId : store.allEntityIds()) {
            if (!verifier.verify(store.streamChangelog(entityId)).consistent()) {
                violations++;
            }
        }
        return violations;
    }

    private static HikariDataSource ordersDataSource(int poolSize) {
        var config = new HikariConfig();
        config.setJdbcUrl(ordersJdbcUrl());
        config.setUsername(ORDERS_APP);
        config.setPassword(DB.password(ORDERS_APP));
        config.setMaximumPoolSize(poolSize);
        config.setPoolName("perf-orders");
        return new HikariDataSource(config);
    }

    /** The orders database on the same server as the ledger database, which is what puts both ends on one clock. */
    private static String ordersJdbcUrl() {
        String ledger = DB.jdbcUrl();
        return ledger.substring(0, ledger.lastIndexOf('/') + 1) + "orders";
    }

    /** Producer settings from order-service's application.yml: acks=all and idempotence, set explicitly. */
    private static KafkaTemplate<String, String> kafkaTemplate() {
        Map<String, Object> config = new HashMap<>();
        config.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers());
        config.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        config.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        config.put(ProducerConfig.ACKS_CONFIG, "all");
        config.put(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, true);
        return new KafkaTemplate<>(new DefaultKafkaProducerFactory<>(config));
    }

    private static org.testcontainers.kafka.KafkaContainer startKafka() {
        var container = new org.testcontainers.kafka.KafkaContainer(
                org.testcontainers.utility.DockerImageName.parse(composeKafkaImage())
                        .asCompatibleSubstituteFor("apache/kafka"));
        container.start();
        // The contracted partition count (ADR-0007), not an auto-created single partition: partition count changes
        // how much the apply path can do in parallel, so a study must run against the real one.
        try (var admin = Admin.create(Map.of("bootstrap.servers", container.getBootstrapServers()))) {
            admin.createTopics(List.of(new NewTopic(TopicDefinitions.MONEY_ORDERS.name(),
                            TopicDefinitions.MONEY_ORDERS.partitions(), TopicDefinitions.LOCAL_REPLICATION_FACTOR)))
                    .all().get(60, TimeUnit.SECONDS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(interrupted);
        } catch (Exception failure) {
            throw new IllegalStateException("could not create the money-orders topic", failure);
        }
        return container;
    }

    private static String composeKafkaImage() {
        var pattern = Pattern.compile("^\\s*image:\\s*(apache/kafka:\\S+)\\s*$");
        try {
            return Files.readAllLines(LedgerTestDatabase.ROOT.resolve("docker-compose.yml")).stream()
                    .map(pattern::matcher)
                    .filter(java.util.regex.Matcher::matches)
                    .map(m -> m.group(1))
                    .findFirst()
                    .orElseThrow(() -> new IllegalStateException("no apache/kafka image in docker-compose.yml"));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    private Path writeRawData(Path file, List<E2eRun> runs, PerfParameters parameters, Seed seed, boolean fullStudy) {
        try {
            Files.createDirectories(file.getParent());
            StringBuilder json = new StringBuilder("{\n");
            json.append("  \"study\": \"S07 end-to-end order-to-apply latency (P2)\",\n")
                    .append("  \"run_label\": \"").append(PerfParameters.runLabel()).append("\",\n")
                    .append("  \"full_study\": ").append(fullStudy).append(",\n")
                    .append("  \"rates\": \"").append(parameters.e2eRates()).append("\",\n")
                    .append("  \"seed\": ").append(seed.value()).append(",\n")
                    .append("  \"window_seconds\": ").append(parameters.e2eWindowSeconds()).append(",\n")
                    .append("  \"warmup_seconds\": ").append(parameters.e2eWarmupSeconds()).append(",\n")
                    .append("  \"repetitions\": ").append(parameters.repetitions()).append(",\n")
                    .append("  \"appenders\": ").append(parameters.e2eAppenders()).append(",\n")
                    .append("  \"postgres_image\": \"").append(LedgerTestDatabase.postgresImage()).append("\",\n")
                    .append("  \"kafka_image\": \"").append(composeKafkaImage()).append("\",\n")
                    .append("  \"partitions\": ").append(TopicDefinitions.MONEY_ORDERS.partitions()).append(",\n")
                    .append("  \"durability\": \"fsync on (D00-3 configuration)\",\n")
                    .append("  \"clock\": \"both ends are PostgreSQL server timestamps from one container: ")
                    .append("outbox.created_at (orders db) and applied_orders.applied_at (ledger db)\",\n")
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

    /** What the arrival generator managed: how many orders it appended and how many started late. */
    private record Arrivals(long appended, long late) {
    }

    /**
     * One measured latency window. {@code i5Violations == -1} until the whole-ledger chain sweep runs at the end.
     *
     * @param endToEnd       p50/p95/p99 micros of {@code applied_at − created_at}
     * @param outboxStage    p50/p95/p99 micros of {@code published_at − created_at} (outbox wait plus relay send)
     * @param kafkaApply     p50/p95/p99 micros of {@code applied_at − published_at} (broker hop plus apply)
     * @param applyBatchMeanMicros mean apply batch duration from the engine's own timer, which bounds how much
     *                             {@code applied_at} understates the moment the balance became visible
     */
    private record E2eRun(int rate, int repetition, int windowSeconds, String windowStartUtc, String windowEndUtc,
            String quiescedUtc, long windowOrders, long measuredOrders,
            long missingOrders, long appended, long lateArrivals, long[] endToEnd, long[] outboxStage,
            long[] kafkaApply, long applyBatches, double applyBatchMeanMicros, long quarantined, long i2Violations,
            long i3Violations, long i4Violations, long i5Violations, int maxLatePercent) {

        boolean heldTheRate() {
            return appended > 0 && (double) lateArrivals / appended * 100 <= maxLatePercent;
        }

        boolean valid() {
            return missingOrders == 0 && quarantined == 0 && heldTheRate() && measuredOrders > 0
                    && i2Violations == 0 && i3Violations == 0 && i4Violations == 0 && i5Violations == 0;
        }

        E2eRun withI5(long violations) {
            return new E2eRun(rate, repetition, windowSeconds, windowStartUtc, windowEndUtc, quiescedUtc, windowOrders,
                    measuredOrders, missingOrders, appended,
                    lateArrivals, endToEnd, outboxStage, kafkaApply, applyBatches, applyBatchMeanMicros, quarantined,
                    i2Violations, i3Violations, i4Violations, violations, maxLatePercent);
        }

        String summary() {
            return String.format("e2e window=" + windowStartUtc + ".." + quiescedUtc
                            + " rate=%d rep=%d appended=%d measured=%d missing=%d late=%d "
                            + "p50=%.1fms p95=%.1fms p99=%.1fms outboxP95=%.1fms kafkaApplyP95=%.1fms "
                            + "applyBatches=%d applyMeanMs=%.1f quar=%d i2=%d i3=%d i4=%d i5=%d valid=%s",
                    rate, repetition, appended, measuredOrders, missingOrders, lateArrivals,
                    endToEnd[0] / 1000.0, endToEnd[1] / 1000.0, endToEnd[2] / 1000.0,
                    outboxStage[1] / 1000.0, kafkaApply[1] / 1000.0, applyBatches, applyBatchMeanMicros / 1000.0,
                    quarantined, i2Violations, i3Violations, i4Violations, i5Violations, valid());
        }

        String toJson() {
            return String.format("{\"rate_orders_per_second\": %d, \"repetition\": %d, \"window_seconds\": %d, "
                            + "\"window_start_utc\": \"" + windowStartUtc + "\", \"window_end_utc\": \"" + windowEndUtc
                            + "\", \"quiesced_utc\": \"" + quiescedUtc + "\", "
                            + "\"window_orders\": %d, \"measured_orders\": %d, \"missing_orders\": %d, "
                            + "\"appended\": %d, \"late_arrivals\": %d, \"held_the_rate\": %s, "
                            + "\"end_to_end_p50_micros\": %d, \"end_to_end_p95_micros\": %d, "
                            + "\"end_to_end_p99_micros\": %d, \"outbox_stage_p50_micros\": %d, "
                            + "\"outbox_stage_p95_micros\": %d, \"outbox_stage_p99_micros\": %d, "
                            + "\"kafka_apply_p50_micros\": %d, \"kafka_apply_p95_micros\": %d, "
                            + "\"kafka_apply_p99_micros\": %d, \"apply_batches\": %d, "
                            + "\"apply_batch_mean_micros\": %.1f, \"quarantined\": %d, \"i2_violations\": %d, "
                            + "\"i3_violations\": %d, \"i4_violations\": %d, \"i5_violations\": %d, \"valid\": %s}",
                    rate, repetition, windowSeconds, windowOrders, measuredOrders, missingOrders, appended,
                    lateArrivals, heldTheRate(), endToEnd[0], endToEnd[1], endToEnd[2], outboxStage[0], outboxStage[1],
                    outboxStage[2], kafkaApply[0], kafkaApply[1], kafkaApply[2], applyBatches, applyBatchMeanMicros,
                    quarantined, i2Violations, i3Violations, i4Violations, i5Violations, valid());
        }
    }
}
