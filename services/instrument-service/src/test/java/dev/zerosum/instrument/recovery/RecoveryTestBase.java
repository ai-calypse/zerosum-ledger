package dev.zerosum.instrument.recovery;

import dev.zerosum.testsupport.ZsTestDatabase;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * A real database, the real sweeper on its real schedule, and a provider that keeps ground truth, for the S05-T12
 * recovery tests.
 *
 * <p>Nothing stands in for the parts under test. The sweeper is not called by hand: it is {@code @Scheduled} and runs
 * on its own, because "the resolver resolves it when asked" and "the service recovers unattended" are different
 * claims, and only the second one is what M8(c) promises.
 *
 * <p><strong>The clock is controllable</strong>, which is what makes the ADR-0010 quiet period testable at its real
 * 60-second value. Shrinking the quiet period to a few hundred milliseconds instead would test a configuration no
 * deployment uses, and would quietly pass if the code compared against the wrong duration entirely.
 *
 * <p>The containers and the provider are static, so every subclass shares one database and one socket. The
 * properties are declared here and nowhere else, so all subclasses share a single Spring context rather than paying
 * for one each.
 *
 * <p>The broker is deliberately absent ({@code zs.policy.consumer.enabled=false}). These tests assert payment events
 * as outbox rows; that the relay then delivers them to Kafka is S05-T09's claim, proved against a real broker in
 * {@code CollectionPolicyIT}.
 */
@Tag("integration")
@SpringBootTest
// Imported explicitly rather than relied on as a nested @TestConfiguration: Spring Boot detects those on the test
// class it is running, and ControllableClock is declared on this abstract base, not on the subclasses. Left implicit,
// the application keeps its real clock, every quiet-period test silently measures against time that never moves, and
// the failure looks like a resolver bug rather than a wiring one — which is exactly how it first presented.
@Import(RecoveryTestBase.ControllableClock.class)
abstract class RecoveryTestBase {

    protected static final ZsTestDatabase DB = ZsTestDatabase.start(
            "instruments", "services/instrument-service/src/main/resources/db/migration");

    protected static final FakeProviderTruth PROVIDER = FakeProviderTruth.start();

    /** Short, so a submission becomes UNKNOWN in under a second rather than in five. */
    protected static final Duration READ_TIMEOUT = Duration.ofMillis(500);

    /**
     * The production value from {@code application.yml}, used unchanged (ADR-0010).
     *
     * <p>The tests move the clock rather than shorten this, so what they exercise is the rule a deployment runs.
     */
    protected static final Duration QUIET_PERIOD = Duration.ofSeconds(60);

    /** Advanced by tests; reset before each one, so a test that moves time cannot leak it into the next. */
    protected static final MutableClock CLOCK = new MutableClock();

    @Autowired
    protected JdbcClient db;

    /** The clock the application actually got, checked below against the one the tests move. */
    @Autowired
    private Clock applicationClock;

    @DynamicPropertySource
    static void context(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", DB::jdbcUrl);
        registry.add("spring.datasource.username", DB::app);
        registry.add("spring.datasource.password", () -> DB.password(DB.app()));
        // The fixture already migrated as the owner; the application role has no DDL rights, by design.
        registry.add("spring.flyway.enabled", () -> "false");
        // No broker in these tests: the policy consumer would dial one on startup.
        registry.add("zs.policy.consumer.enabled", () -> "false");
        registry.add("zs.auth.writer-tokens", () -> "trip-simulator:test-writer-token");
        registry.add("zs.auth.reader-token", () -> "test-reader-token");
        registry.add("zs.auth.admin-token", () -> "test-admin-token");
        registry.add("zs.webhooks.secrets", () -> "test-webhook-secret");
        registry.add("zs.instruments.fakecard-base-url", PROVIDER::baseUrl);
        registry.add("zs.instruments.fakebank-base-url", PROVIDER::baseUrl);
        registry.add("zs.instruments.read-timeout", () -> READ_TIMEOUT.toMillis() + "ms");
        registry.add("zs.instruments.quiet-period", () -> QUIET_PERIOD.toSeconds() + "s");

        // The D05-8 block, compressed only where the value is a waiting time rather than a rule. The resolution
        // schedule, the quiet period and the two-condition rule are all used exactly as configured in production.
        registry.add("zs.sweeper.interval", () -> "200ms");
        registry.add("zs.sweeper.submitting-margin", () -> "200ms");
        registry.add("zs.sweeper.created-threshold", () -> "500ms");
        registry.add("zs.sweeper.pending-payout-poll", () -> "1s");
        registry.add("zs.sweeper.batch-size", () -> "100");
    }

    /**
     * Takes precedence over the application's {@code Clock} bean, which every timestamp and every threshold reads.
     *
     * <p>The method is deliberately NOT called {@code clock}: the application already defines a bean by that name in
     * {@code InstrumentOutboxConfiguration}, and a second definition with the same name is a
     * {@code BeanDefinitionOverrideException} rather than an override, because Boot disallows overriding by default.
     * A different name plus {@code @Primary} leaves both beans registered and makes this one the injected choice —
     * so the application's real wiring is still exercised rather than removed, which is the same reason
     * {@code ReconciliationRunIT} prefers {@code @Primary} to bean overriding.
     */
    @TestConfiguration
    static class ControllableClock {

        @Bean
        @Primary
        Clock testClock() {
            return CLOCK;
        }
    }

    @BeforeEach
    void resetTheWorld() {
        // Fails loudly if the override ever stops applying. Without this the symptom is a quiet-period test that
        // times out against a clock that never moved, which reads as a resolver bug and costs an hour to trace.
        if (applicationClock != CLOCK) {
            throw new IllegalStateException("the application is not using the test clock (" + applicationClock
                    + "); the quiet-period tests would measure against time that never moves");
        }
        CLOCK.reset();
        PROVIDER.reset();
    }

    /** A clock the tests move, so the 60-second quiet period does not cost 60 seconds to observe. */
    static final class MutableClock extends Clock {

        private final AtomicReference<Duration> offset = new AtomicReference<>(Duration.ZERO);

        @Override
        public Instant instant() {
            return Instant.now().plus(offset.get());
        }

        @Override
        public ZoneId getZone() {
            return ZoneId.of("UTC");
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        void advance(Duration by) {
            offset.updateAndGet(current -> current.plus(by));
        }

        void reset() {
            offset.set(Duration.ZERO);
        }
    }

    // --- seeding ---------------------------------------------------------------------------------------------------

    /**
     * Inserts an attempt exactly as S05-T09 and S05-T10 do, then backdates it so the sweeper sees it as due.
     *
     * <p>Backdating rather than sleeping: the thresholds are what the production configuration says, and a test that
     * slept through them would be slow and would still not prove the threshold was read.
     */
    protected UUID seedAttempt(String kind, String provider, String status, String group) {
        UUID attemptId = UUID.randomUUID();
        db.sql("""
                INSERT INTO payment_attempts (attempt_id, kind, order_group_id, source_order_id, payout_run_id,
                                              entity_id, provider, instrument_token, currency, amount_minor, status,
                                              created_at, updated_at)
                VALUES (:id, :kind, :group, :order, null, :entity, :provider, :token, 'USD', 1000, :status,
                        now() - interval '1 hour', now() - interval '1 hour')
                """)
                .param("id", attemptId)
                .param("kind", kind)
                .param("group", group)
                .param("order", "PAYOUT".equals(kind) ? null : UUID.randomUUID())
                .param("entity", "PAYOUT".equals(kind) ? "driver:D" + attemptId.toString().substring(0, 8)
                        : "rider:R" + attemptId.toString().substring(0, 8))
                .param("provider", provider)
                .param("token", "tok_" + provider)
                .param("status", status)
                .update();
        return attemptId;
    }

    /**
     * Puts an attempt into {@code UNKNOWN} through its real history, so the resolver's schedule and quiet period
     * read the same transition rows they would in production.
     */
    protected UUID seedUnknown(String kind, String provider, String group, Duration submittedAgo) {
        UUID attemptId = seedAttempt(kind, provider, "UNKNOWN", group);
        history(attemptId, "CREATED", "SUBMITTING", submittedAgo);
        history(attemptId, "SUBMITTING", "UNKNOWN", submittedAgo);
        db.sql("UPDATE payment_attempts SET next_check_at = now() - interval '1 second' WHERE attempt_id = :id")
                .param("id", attemptId).update();
        return attemptId;
    }

    private void history(UUID attemptId, String from, String to, Duration ago) {
        db.sql("""
                INSERT INTO attempt_transitions (attempt_id, seq, from_status, to_status, cause, at)
                SELECT :id, coalesce(max(seq), 0) + 1, :from, :to, 'seeded by the recovery test',
                       now() - make_interval(secs => :ago)
                  FROM attempt_transitions WHERE attempt_id = :id
                """)
                .param("id", attemptId)
                .param("from", from)
                .param("to", to)
                .param("ago", (double) ago.toMillis() / 1000d)
                .update();
    }

    /**
     * Brings an attempt's next check forward so the resolver looks at it on the next tick.
     *
     * <p>Needed because the master's schedule widens on purpose: an attempt that has been {@code UNKNOWN} for ten
     * minutes is next checked half an hour later, so a test that changed the provider's behaviour and then waited
     * would be waiting for a boundary that is minutes away. <strong>The schedule itself is not what these tests are
     * for</strong> — {@code ResolutionScheduleTest} pins every boundary of it directly — so they bring the check
     * forward and assert the decision the resolver then makes, which is the part that moves money.
     */
    protected void makeDue(UUID attemptId) {
        db.sql("""
                UPDATE payment_attempts SET next_check_at = now() - interval '1 second'
                 WHERE attempt_id = :id AND status = 'UNKNOWN'
                """)
                .param("id", attemptId).update();
    }

    protected void registerToken(String entityId, String provider) {
        db.sql("""
                INSERT INTO instrument_tokens (entity_id, provider, token) VALUES (:entity, :provider, :token)
                ON CONFLICT (entity_id, provider) DO UPDATE SET token = excluded.token
                """)
                .param("entity", entityId).param("provider", provider).param("token", "tok_" + provider)
                .update();
    }

    // --- reading ---------------------------------------------------------------------------------------------------

    protected String status(UUID attemptId) {
        return db.sql("SELECT status FROM payment_attempts WHERE attempt_id = :id")
                .param("id", attemptId).query(String.class).single();
    }

    protected List<String> outboxEventTypes(UUID attemptId) {
        return db.sql("SELECT payload->>'event_type' FROM outbox WHERE payload->>'attempt_id' = :id ORDER BY id")
                .param("id", attemptId.toString()).query(String.class).list();
    }

    protected List<String> historyOf(UUID attemptId) {
        return db.sql("SELECT to_status FROM attempt_transitions WHERE attempt_id = :id ORDER BY seq")
                .param("id", attemptId).query(String.class).list();
    }

    /** Attempts of this group still in a status the sweeper is supposed to clear (M8(c)). */
    protected int stuckIn(String group, List<String> statuses) {
        return db.sql("""
                SELECT count(*) FROM payment_attempts
                 WHERE order_group_id = :group AND status IN (:statuses)
                """)
                .param("group", group)
                .param("statuses", statuses)
                .query(Integer.class).single();
    }

    /** Every attempt of a group, so a volume test can assert one provider payment per attempt. */
    protected List<UUID> attemptsOf(String group) {
        return db.sql("SELECT attempt_id FROM payment_attempts WHERE order_group_id = :group ORDER BY created_at")
                .param("group", group).query(UUID.class).list();
    }

    protected static String someGroup(String what) {
        return "trip_" + what + "_" + UUID.randomUUID().toString().substring(0, 8);
    }

    // --- waiting ---------------------------------------------------------------------------------------------------

    /** The sweeper is asynchronous, so every assertion about it needs a deadline rather than a sleep. */
    protected static void awaitUntil(BooleanSupplier condition, Supplier<String> whatWasExpected) {
        long deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos();
        while (System.nanoTime() < deadline) {
            if (condition.getAsBoolean()) {
                return;
            }
            sleep(Duration.ofMillis(100));
        }
        throw new AssertionError(whatWasExpected.get());
    }

    /** Holds a condition for a while: a duplicate that was going to appear would appear after the first, not before. */
    protected static void assertStays(BooleanSupplier condition, String message) {
        for (int i = 0; i < 10; i++) {
            if (!condition.getAsBoolean()) {
                throw new AssertionError(message);
            }
            sleep(Duration.ofMillis(200));
        }
    }

    protected static void sleep(Duration duration) {
        try {
            Thread.sleep(duration.toMillis());
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }
}
