package dev.zerosum.instrument.recovery;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * The D05-8 resolution schedule, against the master's own wording (§5.10): "Checks run at 1 s, 5 s, 30 s, 2 min and
 * 10 min, then every 30 min until 24 h. After that the attempt moves to {@code NEEDS_REVIEW}."
 *
 * <p>A unit test because the schedule is a pure function of when the attempt entered {@code UNKNOWN}, and that is the
 * property worth pinning: every one of these cases would otherwise need a container and a wait to observe.
 */
class ResolutionScheduleTest {

    private static final Instant ENTERED = Instant.parse("2026-09-17T12:00:00Z");

    private static final SweeperProperties PROPERTIES = new SweeperProperties(Duration.ofSeconds(1),
            Duration.ofSeconds(10), Duration.ofSeconds(30), ResolutionSchedule.MASTER_SCHEDULE,
            Duration.ofMinutes(30), Duration.ofHours(24), Duration.ofMinutes(10), 100);

    private static Optional<Instant> nextAt(String elapsed) {
        return ResolutionSchedule.nextCheckAfter(ENTERED, ENTERED.plus(Duration.parse(elapsed)), PROPERTIES);
    }

    @Nested
    @DisplayName("the master's schedule, boundary by boundary")
    class TheSchedule {

        @Test
        @DisplayName("the first five checks are at 1 s, 5 s, 30 s, 2 min and 10 min after entering UNKNOWN")
        void theFirstFiveChecks() {
            // Each case asks "it is now just after the previous check; when is the next one?", which is exactly the
            // question the resolver asks, and the answers are the master's cumulative times rather than its gaps.
            assertThat(nextAt("PT0S")).contains(ENTERED.plusSeconds(1));
            assertThat(nextAt("PT1.5S")).contains(ENTERED.plusSeconds(6));
            assertThat(nextAt("PT7S")).contains(ENTERED.plusSeconds(36));
            assertThat(nextAt("PT40S")).contains(ENTERED.plusSeconds(156));
            assertThat(nextAt("PT3M")).contains(ENTERED.plusSeconds(756));
        }

        @Test
        @DisplayName("a check due exactly now is not the next one; the schedule always moves forward")
        void aCheckDueExactlyNowIsNotTheNextOne() {
            // Returning the current boundary would re-arm the attempt for the instant it is already being checked
            // at, and the sweeper would spin on it for the whole tick.
            assertThat(nextAt("PT1S")).contains(ENTERED.plusSeconds(6));
            assertThat(nextAt("PT6S")).contains(ENTERED.plusSeconds(36));
        }

        @Test
        @DisplayName("after the list is exhausted it repeats every 30 minutes")
        void thenEveryThirtyMinutes() {
            assertThat(nextAt("PT12M36S")).contains(ENTERED.plus(Duration.ofSeconds(756)).plus(Duration.ofMinutes(30)));
            assertThat(nextAt("PT1H")).contains(ENTERED.plus(Duration.ofSeconds(756)).plus(Duration.ofMinutes(60)));
        }

        @Test
        @DisplayName("a check is never scheduled past the review cut-off")
        void neverPastTheCutOff() {
            // 23 h 59 m in, the next 30-minute slot would fall after the cut-off. Scheduling it there would leave an
            // attempt that is already overdue for review sitting quietly until a slot that must never arrive.
            Optional<Instant> next = nextAt("PT23H59M");
            assertThat(next).contains(ENTERED.plus(Duration.ofHours(24)));
        }
    }

    @Nested
    @DisplayName("the review cut-off")
    class TheCutOff {

        @Test
        @DisplayName("at and after 24 h there is no next check: the attempt is NEEDS_REVIEW")
        void pastTheCutOffThereIsNoNextCheck() {
            assertThat(nextAt("PT24H")).isEmpty();
            assertThat(nextAt("PT48H")).isEmpty();
            assertThat(ResolutionSchedule.pastReviewCutoff(ENTERED, ENTERED.plus(Duration.ofHours(24)), PROPERTIES))
                    .as("empty must mean NEEDS_REVIEW, never 'check again later'")
                    .isTrue();
        }

        @Test
        @DisplayName("one second before the cut-off the attempt is still being resolved")
        void justBeforeTheCutOff() {
            Instant now = ENTERED.plus(Duration.ofHours(24)).minusSeconds(1);
            assertThat(ResolutionSchedule.pastReviewCutoff(ENTERED, now, PROPERTIES)).isFalse();
            assertThat(ResolutionSchedule.nextCheckAfter(ENTERED, now, PROPERTIES)).isPresent();
        }
    }

    @Test
    @DisplayName("the schedule is measured from entry, so a late check does not push the rest of it back")
    void lateChecksDoNotDriftTheSchedule() {
        // An attempt checked 20 s late still has its next check at the original 36 s boundary, not at 20 s + 30 s.
        // Measuring from the last check instead would let a busy sweeper stretch a 24-hour cut-off indefinitely,
        // which is how an attempt never reaches review at all.
        assertThat(nextAt("PT25S")).contains(ENTERED.plusSeconds(36));
    }

    @Test
    @DisplayName("a configured schedule replaces the master's rather than extending it")
    void aConfiguredScheduleIsUsedAsGiven() {
        var fast = new SweeperProperties(Duration.ofSeconds(1), Duration.ofSeconds(10), Duration.ofSeconds(30),
                List.of(Duration.ofMillis(200), Duration.ofMillis(300)), Duration.ofSeconds(5),
                Duration.ofMinutes(1), Duration.ofMinutes(10), 100);

        assertThat(ResolutionSchedule.nextCheckAfter(ENTERED, ENTERED, fast))
                .contains(ENTERED.plusMillis(200));
        assertThat(ResolutionSchedule.nextCheckAfter(ENTERED, ENTERED.plusMillis(250), fast))
                .contains(ENTERED.plusMillis(500));
        assertThat(ResolutionSchedule.nextCheckAfter(ENTERED, ENTERED.plusMillis(600), fast))
                .contains(ENTERED.plusMillis(5500));
    }

    @Test
    @DisplayName("an empty or absent schedule falls back to the master's, rather than to no checks at all")
    void defaultsAreTheMasterSchedule() {
        var defaulted = new SweeperProperties(null, null, null, List.of(), null, null, null, 0);

        assertThat(defaulted.resolutionSchedule()).isEqualTo(ResolutionSchedule.MASTER_SCHEDULE);
        assertThat(defaulted.reviewCutoff()).isEqualTo(Duration.ofHours(24));
        assertThat(defaulted.resolutionInterval()).isEqualTo(Duration.ofMinutes(30));
        // master §5.11: the sweeper threshold is the read timeout plus the margin, derived rather than configured.
        assertThat(defaulted.submittingThreshold(Duration.ofSeconds(5))).isEqualTo(Duration.ofSeconds(15));
    }
}
