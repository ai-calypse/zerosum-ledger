package dev.zerosum.instrument.recovery;

import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * S05-T12 evidence for a provider with <strong>no idempotency keys</strong>: FakeBank, where a blind resubmission
 * pays a driver twice and is therefore governed by ADR-0010's two-condition rule.
 *
 * <p>The three cases below are the three things the bank can tell us after an uncertain outcome, and they must lead
 * to three different actions:
 *
 * <ul>
 *   <li>it <strong>has</strong> the payout — settle our state, never resubmit;</li>
 *   <li>it has <strong>no record</strong> — resubmit, but only once the quiet period has also passed;</li>
 *   <li>it <strong>cannot answer</strong> — do nothing at all, however long we have waited.</li>
 * </ul>
 *
 * <p>Every count is taken from the bank's own ledger, because this is the path where a mistake is money gone.
 */
class ResolverFakeBankIT extends RecoveryTestBase {

    @Autowired
    private MeterRegistry meters;

    @Test
    @DisplayName("a payout that committed and then timed out is found by lookup and becomes PENDING, with no second payout")
    void commitThenTimeoutIsResolvedByLookup() {
        String group = someGroup("bank_timeout");
        // The bank accepted the payout and then went quiet. The money is committed; we simply do not know it.
        PROVIDER.fakebank.timeoutAfterCommit.set(1);

        UUID attemptId = seedAttempt("PAYOUT", "fakebank", "CREATED", group);

        awaitUntil(() -> "UNKNOWN".equals(status(attemptId)),
                () -> "the timed-out payout should have become UNKNOWN, was " + status(attemptId));
        awaitUntil(() -> "PENDING".equals(status(attemptId)),
                () -> "the lookup should have found the payout and settled our state, was " + status(attemptId));

        assertThat(PROVIDER.payoutsFor(attemptId.toString()))
                .as("ground truth: the driver was paid exactly once — a lookup that FINDS the payout forbids a resubmission")
                .isEqualTo(1);
        assertThat(outboxEventTypes(attemptId))
                .as("PAYOUT_ACCEPTED is emitted on the move into PENDING, so the ledger learns about committed money")
                .containsExactly("PAYOUT_ACCEPTED");
        // Held, because a duplicate would appear after the first payout rather than before it.
        assertStays(() -> PROVIDER.payoutsFor(attemptId.toString()) == 1,
                "no second payout may appear once the first has been found");
    }

    @Test
    @DisplayName("not found before the quiet period does not resubmit; after it, exactly one payout exists")
    void resubmissionWaitsForTheQuietPeriod() {
        String group = someGroup("bank_reset");
        // The connection dropped before the bank recorded anything, so the payout genuinely never landed. The
        // resolver cannot know that, and must not act on the guess until both conditions hold.
        PROVIDER.fakebank.resetBeforeCommit.set(1);

        UUID attemptId = seedAttempt("PAYOUT", "fakebank", "CREATED", group);

        awaitUntil(() -> "UNKNOWN".equals(status(attemptId)),
                () -> "the dropped payout should have become UNKNOWN, was " + status(attemptId));

        // Condition 2 holds — the bank has no record — but condition 1 does not. The resolver polls this attempt on
        // the D05-8 schedule throughout, so this is not "nothing happened": it is repeatedly deciding not to pay.
        assertStays(() -> PROVIDER.payoutsFor(attemptId.toString()) == 0 && "UNKNOWN".equals(status(attemptId)),
                "a not-found payout must NOT be resubmitted before the quiet period has elapsed");

        // The clock moves rather than the configuration, so what is under test is the real 60-second rule.
        CLOCK.advance(QUIET_PERIOD.plusSeconds(1));

        awaitUntil(() -> "PENDING".equals(status(attemptId)),
                () -> "once both conditions held the payout should have been resubmitted, was " + status(attemptId));
        assertThat(PROVIDER.payoutsFor(attemptId.toString()))
                .as("ground truth: exactly one payout — the first never landed, and the second is the only one sent")
                .isEqualTo(1);
        assertThat(historyOf(attemptId))
                .as("the resubmission goes back through CREATED, the one backward arrow the payout machine draws")
                .containsExactly("SUBMITTING", "UNKNOWN", "CREATED", "SUBMITTING", "PENDING");
        assertThat(outboxEventTypes(attemptId))
                .as("one payout, one acceptance event")
                .containsExactly("PAYOUT_ACCEPTED");
    }

    @Test
    @DisplayName("a lookup that cannot answer never causes a resubmission, however long the quiet period has passed")
    void unavailableNeverResubmits() {
        String group = someGroup("bank_unavailable");
        PROVIDER.fakebank.resetBeforeCommit.set(1);
        UUID attemptId = seedAttempt("PAYOUT", "fakebank", "CREATED", group);
        awaitUntil(() -> "UNKNOWN".equals(status(attemptId)),
                () -> "the dropped payout should have become UNKNOWN, was " + status(attemptId));

        // The bank is now unreachable. This is the distinction ADR-0010 decision 4 exists for: "I could not ask" is
        // not "there is no record", and collapsing the two turns an outage into a duplicate payout.
        PROVIDER.fakebank.lookupStatus = 503;
        // Both would-be conditions are as favourable as they can get: the quiet period is long past, and the last
        // answer we had was NotFound. Only the unavailable lookup stands between us and paying twice.
        CLOCK.advance(QUIET_PERIOD.multipliedBy(10));

        assertStays(() -> PROVIDER.payoutsFor(attemptId.toString()) == 0 && "UNKNOWN".equals(status(attemptId)),
                "an Unavailable lookup must never be treated as NotFound, so nothing may be resubmitted");

        // And when the bank answers again, the same attempt resolves normally — it was waiting, not abandoned.
        PROVIDER.fakebank.lookupStatus = 0;
        makeDue(attemptId);
        awaitUntil(() -> "PENDING".equals(status(attemptId)),
                () -> "once the bank could answer, the payout should have been resubmitted, was " + status(attemptId));
        assertThat(PROVIDER.payoutsFor(attemptId.toString())).isEqualTo(1);
    }

    @Test
    @DisplayName("a PENDING payout is polled, so a dropped webhook becomes a delay rather than a payout stuck for ever")
    void pendingPayoutsArePolled() {
        String group = someGroup("bank_poll");
        UUID attemptId = seedAttempt("PAYOUT", "fakebank", "CREATED", group);
        awaitUntil(() -> "PENDING".equals(status(attemptId)),
                () -> "the payout should have been accepted, was " + status(attemptId));

        // The bank settles it and the webhook never arrives (master §6.6). Nothing tells us but a lookup.
        PROVIDER.settlePayouts(attemptId.toString());

        awaitUntil(() -> "SETTLED".equals(status(attemptId)),
                () -> "the pending-payout poll should have found the settlement, was " + status(attemptId));
        assertThat(outboxEventTypes(attemptId))
                .as("each step emits its own event exactly once, in order")
                .containsExactly("PAYOUT_ACCEPTED", "PAYOUT_SETTLED");
    }

    @Nested
    @DisplayName("the §0.3 O9 alert signals")
    class AlertSignalGauges {

        @Test
        @DisplayName("the oldest-UNKNOWN gauge reports a deliberately stuck attempt, and drops once it resolves")
        void gaugesReportAndThenDrop() {
            String group = someGroup("bank_gauge");
            // Ten minutes old, so it cannot be confused with any other attempt this suite leaves behind: those are
            // seconds old, and the assertions below are all above that by two orders of magnitude.
            UUID attemptId = seedUnknown("PAYOUT", "fakebank", group, Duration.ofMinutes(10));
            PROVIDER.fakebank.lookupStatus = 503;   // held stuck, so the gauge has something to report

            awaitUntil(() -> oldestUnknownSeconds() >= 600,
                    () -> "the oldest-UNKNOWN gauge should report the stuck attempt, was " + oldestUnknownSeconds());
            // Deliberately no assertion on the NEEDS_REVIEW gauge here: it counts the whole database, which these
            // classes share, so any absolute claim about it belongs to the test that creates the attempt it counts.

            // Let it resolve: the bank answers again. No clock advance is needed — this attempt was submitted ten
            // minutes ago, so the quiet period is already long past. Leaving the clock alone also keeps the gauge
            // assertion below reading real ages for every other attempt in the shared database.
            PROVIDER.fakebank.lookupStatus = 0;
            makeDue(attemptId);
            awaitUntil(() -> "PENDING".equals(status(attemptId)),
                    () -> "the attempt should have resolved, was " + status(attemptId));

            awaitUntil(() -> oldestUnknownSeconds() < 600,
                    () -> "the gauge should drop once nothing that old is UNKNOWN, was " + oldestUnknownSeconds());
            awaitUntil(() -> oldestPendingSeconds() > 0,
                    () -> "and the oldest-PENDING-payout gauge should now report the payout waiting on the bank");
        }

        @Test
        @DisplayName("the NEEDS_REVIEW gauge counts attempts a human has to look at")
        void needsReviewIsCounted() {
            String group = someGroup("bank_review_gauge");
            double before = needsReview();
            seedUnknown("PAYOUT", "fakebank", group, Duration.ofHours(25));

            awaitUntil(() -> needsReview() > before,
                    () -> "an attempt past the cut-off must raise the NEEDS_REVIEW signal, was " + needsReview());
        }

        private double oldestUnknownSeconds() {
            return gauge(AlertSignals.OLDEST_UNKNOWN);
        }

        private double oldestPendingSeconds() {
            return gauge(AlertSignals.OLDEST_PENDING_PAYOUT);
        }

        private double needsReview() {
            return gauge(AlertSignals.NEEDS_REVIEW);
        }

        private double gauge(String name) {
            var found = meters.find(name).gauge();
            return found == null ? Double.NaN : found.value();
        }
    }
}
