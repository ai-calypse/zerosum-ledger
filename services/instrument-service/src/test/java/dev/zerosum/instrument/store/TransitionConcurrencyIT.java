package dev.zerosum.instrument.store;

import static org.assertj.core.api.Assertions.assertThat;

import dev.zerosum.testsupport.ZsTestDatabase;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * S05-T07: the optimistic guard, under the concurrency it exists for.
 *
 * <p>The claim being tested is not "the update works" but "only one of them works". A read-then-write would pass a
 * single-threaded test and then, in production, let a webhook and a sweeper both move the same attempt and both emit
 * an event — which downstream becomes two money orders for one movement of money.
 */
@Tag("integration")
@SpringBootTest
class TransitionConcurrencyIT {

    private static final ZsTestDatabase DB = ZsTestDatabase.start(
            "instruments", "services/instrument-service/src/main/resources/db/migration");

    /** Recorded in the evidence note: how many threads race one transition. */
    private static final int RACERS = 16;

    @Autowired
    private AttemptTransitions transitions;

    @Autowired
    private JdbcClient db;

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", DB::jdbcUrl);
        registry.add("spring.datasource.username", DB::app);
        registry.add("spring.datasource.password", () -> DB.password(DB.app()));
        // The fixture already migrated as the owner; the application role has no DDL rights, by design.
        registry.add("spring.flyway.enabled", () -> "false");
    }

    private UUID createAttempt() {
        UUID attemptId = UUID.randomUUID();
        db.sql("""
                INSERT INTO payment_attempts (attempt_id, kind, order_group_id, source_order_id, entity_id, provider,
                                              instrument_token, currency, amount_minor, status)
                VALUES (:id, 'CHARGE', :group, :order, 'rider:R1', 'fakecard', 'tok_card_ok', 'USD', 2500,
                        'SUBMITTING')
                """)
                .param("id", attemptId)
                .param("group", "trip_" + attemptId.toString().substring(0, 8))
                .param("order", UUID.randomUUID())
                .update();
        return attemptId;
    }

    @Test
    @DisplayName("many threads racing one transition: one winner, one history row, one event")
    void onlyOneRacerApplies() throws Exception {
        UUID attemptId = createAttempt();
        var transition = new AttemptTransitions.Transition(attemptId, 0, "SUBMITTING", "SUCCEEDED",
                "raced by the concurrency test", "ch_1", null, null);

        List<Future<AttemptTransitions.Result>> results;
        try (ExecutorService pool = Executors.newFixedThreadPool(RACERS)) {
            List<Callable<AttemptTransitions.Result>> calls =
                    java.util.Collections.nCopies(RACERS, () -> transitions.apply(transition));
            results = pool.invokeAll(calls);
        }

        long applied = 0;
        long lost = 0;
        for (Future<AttemptTransitions.Result> result : results) {
            if (result.get() instanceof AttemptTransitions.Result.Applied) {
                applied++;
            } else {
                lost++;
            }
        }

        assertThat(applied).as("exactly one racer applied the transition").isEqualTo(1);
        assertThat(lost).as("every other racer was told it lost, rather than getting an error").isEqualTo(RACERS - 1);

        assertThat(historyRows(attemptId)).as("one transition, one history row").isEqualTo(1);
        // The event is appended in the same transaction as the status change, so the losers cannot have written one.
        assertThat(outboxRows(attemptId)).as("one transition, one payment event").isEqualTo(1);
        assertThat(status(attemptId)).isEqualTo("SUCCEEDED");
        assertThat(version(attemptId)).as("the version moved exactly once").isEqualTo(1);
    }

    @Test
    @DisplayName("a transition from a status the attempt is not in writes nothing at all")
    void staleFromStatusWritesNothing() {
        UUID attemptId = createAttempt();

        // The attempt is SUBMITTING; this claims it is CREATED. Nothing may be written, not even history.
        var result = transitions.apply(new AttemptTransitions.Transition(attemptId, 0, "CREATED", "SUCCEEDED",
                "stale caller", null, null, null));

        assertThat(result).isInstanceOf(AttemptTransitions.Result.LostRace.class);
        assertThat(historyRows(attemptId)).isZero();
        assertThat(outboxRows(attemptId)).isZero();
        assertThat(status(attemptId)).isEqualTo("SUBMITTING");
    }

    @Test
    @DisplayName("a transition that emits no event still records its history")
    void bookkeepingTransitionRecordsHistoryOnly() {
        UUID attemptId = createAttempt();

        var result = transitions.apply(new AttemptTransitions.Transition(attemptId, 0, "SUBMITTING", "UNKNOWN",
                "read timeout", null, null, null));

        assertThat(result).isInstanceOf(AttemptTransitions.Result.Applied.class);
        assertThat(((AttemptTransitions.Result.Applied) result).eventType()).isEmpty();
        assertThat(historyRows(attemptId)).isEqualTo(1);
        // UNKNOWN says nothing about money, so nothing may reach the ledger.
        assertThat(outboxRows(attemptId)).isZero();
    }

    private int historyRows(UUID attemptId) {
        return db.sql("SELECT count(*) FROM attempt_transitions WHERE attempt_id = :id")
                .param("id", attemptId).query(Integer.class).single();
    }

    private int outboxRows(UUID attemptId) {
        return db.sql("SELECT count(*) FROM outbox WHERE payload->>'attempt_id' = :id")
                .param("id", attemptId.toString()).query(Integer.class).single();
    }

    private String status(UUID attemptId) {
        return db.sql("SELECT status FROM payment_attempts WHERE attempt_id = :id")
                .param("id", attemptId).query(String.class).single();
    }

    private long version(UUID attemptId) {
        return db.sql("SELECT version FROM payment_attempts WHERE attempt_id = :id")
                .param("id", attemptId).query(Long.class).single();
    }
}
