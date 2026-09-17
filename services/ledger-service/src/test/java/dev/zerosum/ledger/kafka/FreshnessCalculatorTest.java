package dev.zerosum.ledger.kafka;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.zerosum.ledger.kafka.FreshnessCalculator.Lag;
import dev.zerosum.ledger.kafka.FreshnessCalculator.PartitionPosition;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * The lag and age arithmetic (D04-5), with no broker involved.
 *
 * <p>These branches were unreachable until the arithmetic was lifted out of the live admin path: every one of them sat
 * behind a real cluster call, so the cases below could not have been written honestly against the previous shape.
 */
@Tag("unit")
class FreshnessCalculatorTest {

    @Test
    void aPartitionWithNoCommittedOffsetCountsFromItsEarliestRecord() {
        // Not from zero: records retention has already removed were never ours to apply, and counting them would
        // report a backlog that cannot be drained.
        Lag lag = FreshnessCalculator.lagOf(List.of(new PartitionPosition(0, 100, 40, null)));

        assertNull(lag.error());
        assertEquals(60, lag.total());
        assertEquals(Map.of(0, 60L), lag.byPartition());
        assertEquals(List.of(0), lag.laggingPartitions());
    }

    @Test
    void aCaughtUpPartitionHasZeroLagAndIsNotListedAsLagging() {
        Lag lag = FreshnessCalculator.lagOf(List.of(new PartitionPosition(3, 250, 0, 250L)));

        assertNull(lag.error());
        assertEquals(0, lag.total());
        assertEquals(Map.of(3, 0L), lag.byPartition());
        assertTrue(lag.laggingPartitions().isEmpty(),
                "a caught-up partition must not be probed for an oldest-record timestamp it does not have");
    }

    @Test
    void lagIsSummedAcrossPartitionsAndReportedPerPartition() {
        // The per-partition figure matters: one stuck partition behind eleven healthy ones is invisible in a total.
        Lag lag = FreshnessCalculator.lagOf(List.of(
                new PartitionPosition(0, 100, 0, 100L),
                new PartitionPosition(1, 500, 0, 200L),
                new PartitionPosition(2, 80, 0, 80L)));

        assertEquals(300, lag.total());
        assertEquals(Map.of(0, 0L, 1, 300L, 2, 0L), lag.byPartition());
        assertEquals(List.of(1), lag.laggingPartitions());
    }

    @Test
    void aCommittedOffsetBelowTheLogStartIsAnErrorRatherThanASmallLag() {
        // Records were removed before being applied. Under replication factor 1 that is lost money data, and the
        // arithmetic would otherwise report a comfortable number for an unrecoverable gap.
        Lag lag = FreshnessCalculator.lagOf(List.of(new PartitionPosition(7, 900, 500, 120L)));

        assertNotNull(lag.error());
        assertTrue(lag.error().contains("below the log start"), lag.error());
        assertEquals(0, lag.total(), "a failed measurement reports nothing, not a plausible-looking zero");
        assertTrue(lag.byPartition().isEmpty());
    }

    @Test
    void oneBadPartitionFailsTheWholeMeasurement() {
        // Reporting the healthy partitions while silently dropping the broken one would understate the backlog.
        Lag lag = FreshnessCalculator.lagOf(List.of(
                new PartitionPosition(0, 100, 0, 50L),
                new PartitionPosition(1, 900, 500, 120L)));

        assertNotNull(lag.error());
        assertEquals(0, lag.total());
    }

    @Test
    void aRecordTimestampInTheFutureIsClampedToZero() {
        long now = 1_700_000_000_000L;

        assertEquals(0.0, FreshnessCalculator.ageSecondsFrom(now + 30_000, now),
                "a clock anomaly upstream must not produce a negative age");
        assertEquals(30.0, FreshnessCalculator.ageSecondsFrom(now - 30_000, now));
        assertEquals(0.0, FreshnessCalculator.ageSecondsFrom(now, now));
    }

    @Test
    void noPartitionsMeansNoLag() {
        Lag lag = FreshnessCalculator.lagOf(List.of());

        assertNull(lag.error());
        assertEquals(0, lag.total());
        assertTrue(lag.laggingPartitions().isEmpty());
    }
}
