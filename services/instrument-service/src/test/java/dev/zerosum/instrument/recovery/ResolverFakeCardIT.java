package dev.zerosum.instrument.recovery;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * S05-T12 evidence for an <strong>idempotent</strong> provider: FakeCard declares idempotency keys, so an uncertain
 * outcome is resolved by sending the same request again under the same key.
 *
 * <p>Every assertion that matters is made against the provider's own record rather than against ours. "The attempt
 * says {@code SUCCEEDED}" and "the payer was charged exactly once" are different claims, and a resolver that retried
 * without a key would satisfy the first while breaking the second.
 */
class ResolverFakeCardIT extends RecoveryTestBase {

    @Test
    @DisplayName("a charge that committed and then timed out ends SUCCEEDED, and the payer is charged exactly once")
    void commitThenTimeoutIsResolvedByIdempotentRetry() {
        String group = someGroup("card_timeout");
        // The provider takes the money and then goes quiet past the read timeout. This is the case where our records
        // and reality disagree, and the only one where a careless retry charges a rider twice.
        PROVIDER.fakecard.timeoutAfterCommit.set(1);

        UUID attemptId = seedAttempt("CHARGE", "fakecard", "CREATED", group);

        awaitUntil(() -> "UNKNOWN".equals(status(attemptId)),
                () -> "the timed-out submission should have become UNKNOWN, was " + status(attemptId));
        awaitUntil(() -> "SUCCEEDED".equals(status(attemptId)),
                () -> "the idempotent retry should have resolved it, was " + status(attemptId));

        assertThat(PROVIDER.chargesFor(attemptId.toString()))
                .as("ground truth: the provider holds exactly one charge, however many times we asked")
                .isEqualTo(1);
        assertThat(historyOf(attemptId))
                .as("the attempt went through UNKNOWN rather than jumping straight to success")
                .containsExactly("SUBMITTING", "UNKNOWN", "SUCCEEDED");
        assertThat(outboxEventTypes(attemptId))
                .as("one movement of money, one payment event — a second would be a second money order")
                .containsExactly("CHARGE_SUCCEEDED");
    }

    @Test
    @DisplayName("an attempt left in CREATED is submitted by the sweep, which is the only way a freeze ever recovers")
    void attemptsLeftInCreatedAreSubmitted() {
        // §0.3 C24. S05-T09 and S05-T10 both commit the attempt before submitting it, so a crash in between — or a
        // kill switch that was off when it was created and is on now — leaves exactly this row behind.
        String group = someGroup("card_created");
        UUID attemptId = seedAttempt("CHARGE", "fakecard", "CREATED", group);

        awaitUntil(() -> "SUCCEEDED".equals(status(attemptId)),
                () -> "the old-CREATED sweep should have submitted it, was " + status(attemptId));

        assertThat(PROVIDER.chargesFor(attemptId.toString()))
                .as("the sweep submits once; it does not re-send on every tick")
                .isEqualTo(1);
        assertThat(outboxEventTypes(attemptId)).containsExactly("CHARGE_SUCCEEDED");
    }

    @Test
    @DisplayName("an attempt stuck in SUBMITTING past the threshold becomes UNKNOWN, then resolves")
    void stuckSubmissionsBecomeUnknown() {
        // The crash-after-send case: the request went out and nobody recorded what came back. master §5.11 says the
        // call is over once the read timeout plus the margin has passed.
        String group = someGroup("card_submitting");
        UUID attemptId = seedAttempt("CHARGE", "fakecard", "SUBMITTING", group);

        awaitUntil(() -> "SUCCEEDED".equals(status(attemptId)),
                () -> "the sweeper should have taken it through UNKNOWN to a resolution, was " + status(attemptId));

        assertThat(historyOf(attemptId))
                .as("the sweeper's first move is UNKNOWN — never a guessed terminal state")
                .containsExactly("UNKNOWN", "SUCCEEDED");
        assertThat(PROVIDER.chargesFor(attemptId.toString()))
                .as("the original submission never landed, so the retry created exactly one charge")
                .isEqualTo(1);
    }

    @Test
    @DisplayName("unresolved past the cut-off becomes NEEDS_REVIEW, which has no automatic way out")
    void unresolvedAttemptsEscalateToReview() {
        String group = someGroup("card_review");
        // Entered UNKNOWN 25 hours ago: past the 24 h cut-off, so no provider is called at all — the cut-off is
        // checked before any retry, because asking again cannot change what a day of asking has not resolved.
        UUID attemptId = seedUnknown("CHARGE", "fakecard", group, Duration.ofHours(25));

        awaitUntil(() -> "NEEDS_REVIEW".equals(status(attemptId)),
                () -> "an attempt past the cut-off should be NEEDS_REVIEW, was " + status(attemptId));

        assertThat(PROVIDER.chargesFor(attemptId.toString()))
                .as("escalating must never move money")
                .isZero();
        assertThat(outboxEventTypes(attemptId))
                .as("NEEDS_REVIEW says nothing about money, so nothing may reach the ledger")
                .isEmpty();
        // The master is explicit that this state has no automatic exit: a human decides. If the sweeper kept working
        // on it, a guessed terminal state would eventually reach the ledger as a real money order.
        assertStays(() -> "NEEDS_REVIEW".equals(status(attemptId)),
                "NEEDS_REVIEW must have no automatic exit");
        assertThat(stuckIn(group, List.of("UNKNOWN", "SUBMITTING")))
                .as("and it is no longer counted as work in progress")
                .isZero();
    }
}
