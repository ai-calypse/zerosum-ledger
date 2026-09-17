package dev.zerosum.ledger.kafka;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.zerosum.contracts.GoldenPayloads;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import tools.jackson.databind.json.JsonMapper;

/**
 * The freshness calculation against a real broker and a real consumer group (D04-5).
 *
 * <p>Shares the cached pipeline context with the other S04 tests, so it deliberately does <strong>not</strong> stop
 * the listener: doing so would break every test using that context. The "records produced while the listener is
 * stopped" case from the task is therefore not covered here and is recorded as such, rather than simulated in a way
 * that would prove something weaker while reading like the real thing.
 */
class FreshnessIT extends LedgerPipelineTestBase {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    @Autowired
    private FreshnessCalculator calculator;

    // No @AfterAll teardown: KAFKA and DB are statics on the shared base (see LedgerListenerIT).

    @Test
    void aCaughtUpLedgerReportsNoLagAndNoAge() {
        String payload = GoldenPayloads.byId("O1");
        publish(JSON.readTree(payload).get("order_group_id").asString(), payload);

        awaitUntil(() -> calculator.compute().totalLagRecords() == 0,
                "the listener never caught up; lag stayed at " + calculator.compute().totalLagRecords());

        FreshnessCalculator.Freshness freshness = calculator.compute();
        assertEquals(FreshnessCalculator.Status.OK, freshness.status(), freshness.error());
        assertEquals(0, freshness.totalLagRecords());
        assertEquals(0.0, freshness.oldestUnappliedAgeSeconds(),
                "no lag means no unapplied record, so the age is zero rather than a stale figure");
        assertNotNull(freshness.computedAt());
        assertEquals(TopicDefinitionsPartitions(), freshness.partitionLag().size(),
                "every partition is reported, so a single stuck one cannot hide behind a total");
    }

    @Test
    void theReportedShapeIsAlwaysComplete() {
        FreshnessCalculator.Freshness freshness = calculator.compute();

        assertNotNull(freshness.status());
        assertNotNull(freshness.computedAt());
        if (freshness.status() == FreshnessCalculator.Status.OK) {
            assertTrue(freshness.totalLagRecords() >= 0);
            assertTrue(freshness.oldestUnappliedAgeSeconds() >= 0,
                    "a future record timestamp must be clamped, never reported as a negative age");
            assertEquals(null, freshness.error());
        } else {
            assertNotNull(freshness.error(), "an error status must say why, or it is not actionable");
        }
    }

    @Test
    void theListenerPausedFlagIsReported() {
        // Not paused in this run; the flag must still be present and false rather than absent, because S05 reads it
        // as part of deciding whether a payout may proceed.
        assertEquals(false, calculator.compute().listenerPaused());
    }

    private static int TopicDefinitionsPartitions() {
        return dev.zerosum.contracts.kafka.TopicDefinitions.MONEY_ORDERS.partitions();
    }
}
