package dev.zerosum.instrument.policy;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * The collections kill switch, off (D05-11).
 *
 * <p>A separate class because the switch is read at startup: a flag that could change under a submission already in
 * flight would make "was it on when we called the provider?" unanswerable, which is not a question to leave open
 * about money. The containers are shared with the other policy tests; only the Spring context differs.
 *
 * <p>The assertion that matters is the negative one, and it is made against the provider's own request count rather
 * than against our records. "We did not write down a charge" and "we did not charge anybody" are different claims,
 * and only the second one is what a freeze promises.
 */
class CollectionKillSwitchIT extends PolicyPipelineTestBase {

    @Autowired
    private JdbcClient db;

    @DynamicPropertySource
    static void killSwitchOff(DynamicPropertyRegistry registry) {
        registry.add("zs.kill-switches.collections-enabled", () -> "false");
    }

    @Test
    @DisplayName("with collections frozen the attempt is still created, stays in CREATED, and no provider is called")
    void frozenCollectionsCreateTheAttemptButSubmitNothing() {
        db.sql("""
                INSERT INTO instrument_tokens (entity_id, provider, token) VALUES (:entity, 'fakecard', 'tok_frozen')
                ON CONFLICT (entity_id, provider) DO UPDATE SET token = excluded.token
                """)
                .param("entity", "rider:R1")
                .update();

        int callsBefore = PROVIDER.requestCount();
        String group = "trip_frozen_" + UUID.randomUUID();
        String order = goldenOrder("O1", group, UUID.randomUUID(), null);
        UUID orderId = orderIdOf(order);

        publish(group, order);

        // The order is not lost: that is the whole point of checking the switch before submission rather than before
        // creation. It waits in CREATED for the S05-T12 old-CREATED sweep to submit once the switch is on (§0.3 C24).
        awaitUntil(() -> status(orderId) != null, () -> "the attempt was never created");
        assertStays(() -> "CREATED".equals(status(orderId)), "a frozen attempt must not leave CREATED");

        assertThat(PROVIDER.requestCount()).as("ground truth: a frozen collection reaches no provider at all")
                .isEqualTo(callsBefore);
        assertThat(outboxRowsFor(orderId)).as("nothing moved, so there is no payment event to publish").isZero();
        assertThat(amountOf(orderId)).as("the attempt is still the full fare, ready to submit when unfrozen")
                .isEqualTo(2_500);
    }

    private String status(UUID sourceOrderId) {
        return db.sql("SELECT status FROM payment_attempts WHERE source_order_id = :id")
                .param("id", sourceOrderId).query(String.class).optional().orElse(null);
    }

    private long amountOf(UUID sourceOrderId) {
        return db.sql("SELECT amount_minor FROM payment_attempts WHERE source_order_id = :id")
                .param("id", sourceOrderId).query(Long.class).single();
    }

    private int outboxRowsFor(UUID sourceOrderId) {
        return db.sql("""
                SELECT count(*) FROM outbox
                 WHERE payload->>'attempt_id' IN (
                     SELECT attempt_id::text FROM payment_attempts WHERE source_order_id = :id)
                """)
                .param("id", sourceOrderId).query(Integer.class).single();
    }
}
