package dev.zerosum.instrument.recovery;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * M8(c): "After quiesce, 0 attempts remain in {@code SUBMITTING} or {@code UNKNOWN} for more than 5 minutes."
 *
 * <p>The claim is about the service recovering <strong>unattended</strong>, so nothing here calls the sweeper: load
 * is created, the test stops touching it, and the scheduled sweeper drains it on its own. A test that drove the
 * resolver by hand would prove the resolver works and say nothing about whether anything would ever call it.
 *
 * <p>The volume is deliberately modest and the fault is applied by count rather than by rate, so the run is
 * deterministic: exactly half the submissions commit and then go silent, which is the condition that produces
 * {@code UNKNOWN} attempts in the first place. <strong>This is not M8(b)</strong> — that criterion is 10,000 charges
 * at a 0.2 timeout rate and belongs to the long-running CI job, which this task did not run.
 */
class QuiesceNoStuckAttemptsIT extends RecoveryTestBase {

    /** Enough attempts for several to be in flight at once against the 4-thread submission pool. */
    private static final int ATTEMPTS = 12;

    @Test
    @DisplayName("after load stops, nothing is left in SUBMITTING or UNKNOWN, and every attempt was charged once")
    void theSweeperDrainsEverythingItStarted() {
        String group = someGroup("quiesce");
        // Half of them take the money and then go quiet. Deterministic by count rather than by seeded rate, so the
        // run is reproducible without recording a seed, and the mix of "resolved by retry" and "succeeded outright"
        // is guaranteed rather than probable.
        PROVIDER.fakecard.timeoutAfterCommit.set(ATTEMPTS / 2);

        for (int i = 0; i < ATTEMPTS; i++) {
            seedAttempt("CHARGE", "fakecard", "CREATED", group);
        }

        List<UUID> attempts = attemptsOf(group);
        assertThat(attempts).as("the load actually exists").hasSize(ATTEMPTS);

        // Quiesce: stop doing anything and let the service recover by itself.
        awaitUntil(() -> stuckIn(group, List.of("CREATED", "SUBMITTING", "UNKNOWN")) == 0,
                () -> "the sweeper should have drained every attempt; still in flight: "
                        + stuckIn(group, List.of("CREATED", "SUBMITTING", "UNKNOWN")));

        assertThat(stuckIn(group, List.of("SUBMITTING", "UNKNOWN")))
                .as("M8(c): nothing remains in SUBMITTING or UNKNOWN once the resolver has drained")
                .isZero();
        assertThat(stuckIn(group, List.of("NEEDS_REVIEW")))
                .as("M8(c): and nothing was escalated — everything was genuinely resolved, not given up on")
                .isZero();
        assertThat(stuckIn(group, List.of("SUCCEEDED")))
                .as("every attempt reached a terminal, successful state")
                .isEqualTo(ATTEMPTS);

        // The claim that actually matters: our records agreeing is worth nothing if the provider charged twice.
        for (UUID attemptId : attempts) {
            assertThat(PROVIDER.chargesFor(attemptId.toString()))
                    .as("ground truth: exactly one charge for attempt %s, whatever it went through", attemptId)
                    .isEqualTo(1);
            assertThat(outboxEventTypes(attemptId))
                    .as("and exactly one payment event for it")
                    .containsExactly("CHARGE_SUCCEEDED");
        }
    }
}
