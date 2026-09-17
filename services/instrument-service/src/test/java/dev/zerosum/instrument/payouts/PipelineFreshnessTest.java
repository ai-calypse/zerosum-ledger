package dev.zerosum.instrument.payouts;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The freshness arithmetic M10(c) refuses on, with fixed component values (D05-7, §0.3 C12).
 *
 * <p>A unit test because the sum is the part that is easy to get quietly wrong, and it needs no network to check.
 * The case that matters is {@link #eachStageFreshDoesNotMakeThePipelineFresh}: the stages compose, so a pipeline
 * whose every stage is individually well inside the threshold can still be far outside it in total, and an
 * implementation that took the worst stage instead of the sum would pass every other test here.
 */
class PipelineFreshnessTest {

    /** decision: D05-7 — the M10(c) threshold. Stated literally so a config change cannot quietly redefine M10(c). */
    private static final Duration THRESHOLD = Duration.ofSeconds(5);

    @Test
    @DisplayName("the three stages are summed, not maxed")
    void stagesAreSummed() {
        var freshness = new PipelineFreshness(1.5, 2.0, 0.5);

        assertThat(freshness.totalSeconds()).isEqualTo(4.0);
        assertThat(freshness.exceeds(THRESHOLD)).isFalse();
    }

    @Test
    @DisplayName("every stage individually fresh still adds up to a stale pipeline")
    void eachStageFreshDoesNotMakeThePipelineFresh() {
        // An order sitting 3 s in order-service's outbox has not reached the ledger, whose own 3 s starts only once
        // it has: sequential stages, so 3 + 3 + 3 is nine seconds of staleness, not three.
        var freshness = new PipelineFreshness(3.0, 3.0, 3.0);

        assertThat(freshness.totalSeconds()).isEqualTo(9.0);
        assertThat(freshness.exceeds(THRESHOLD))
                .as("a payout run must refuse this, though no single stage is over the threshold").isTrue();
    }

    @Test
    @DisplayName("M10(c) refuses when freshness *exceeds* 5 s, so exactly 5 s still runs")
    void thresholdIsExclusive() {
        assertThat(new PipelineFreshness(2.0, 2.0, 1.0).exceeds(THRESHOLD)).isFalse();
        assertThat(new PipelineFreshness(2.0, 2.0, 1.001).exceeds(THRESHOLD)).isTrue();
    }

    @Test
    @DisplayName("an empty pipeline is zero, not absent: every stage reports 0 when it has nothing waiting")
    void emptyPipelineIsFresh() {
        var freshness = new PipelineFreshness(0, 0, 0);

        assertThat(freshness.totalSeconds()).isZero();
        assertThat(freshness.exceeds(THRESHOLD)).isFalse();
    }
}
