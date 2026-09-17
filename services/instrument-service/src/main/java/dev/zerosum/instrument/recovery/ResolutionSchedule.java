// decision: D05-8 — docs/step_05_instruments_fake_providers.md#decisions-and-outputs
package dev.zerosum.instrument.recovery;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * When to look at an {@code UNKNOWN} attempt next (master §5.10: 1 s, 5 s, 30 s, 2 min, 10 min, then every 30 min
 * until 24 h, then {@code NEEDS_REVIEW}).
 *
 * <p><strong>Derived from when the attempt entered {@code UNKNOWN}, not from a counter on the row.</strong> A stored
 * "checks so far" column would have to be incremented by every actor that looks at the attempt, and a resolver that
 * crashed between the provider call and the increment would either check twice at one delay or skip one — neither of
 * which is visible afterwards. The entry time is already recorded, once, in the append-only transition history, so
 * the whole schedule is a pure function of it and a restart resumes exactly where it left off with nothing to
 * reconcile.
 *
 * <p>It also means the schedule cannot drift under load: an attempt checked late is not pushed further out, because
 * the next boundary is computed from the original entry time rather than from the moment of the last check.
 */
final class ResolutionSchedule {

    private ResolutionSchedule() {
    }

    /**
     * The next check strictly after {@code now}, or empty when the attempt is past the review cut-off.
     *
     * <p>Empty means {@code NEEDS_REVIEW} and nothing else. It is deliberately not "check again later": an attempt
     * nobody could resolve in 24 hours is a human's problem, and the alert exists so that a human gets it.
     */
    static Optional<Instant> nextCheckAfter(Instant enteredUnknownAt, Instant now, SweeperProperties properties) {
        Instant cutoff = enteredUnknownAt.plus(properties.reviewCutoff());
        if (!now.isBefore(cutoff)) {
            return Optional.empty();
        }

        Instant at = enteredUnknownAt;
        for (Duration gap : properties.resolutionSchedule()) {
            at = at.plus(gap);
            if (at.isAfter(now)) {
                return Optional.of(earlier(at, cutoff));
            }
        }
        // The list is exhausted, so the tail repeats. Bounded by the cut-off above, which `now` is known to precede.
        while (!at.isAfter(now)) {
            at = at.plus(properties.resolutionInterval());
        }
        return Optional.of(earlier(at, cutoff));
    }

    /**
     * Never schedules past the cut-off: the last check lands exactly on it, and the check after that finds the
     * attempt out of time and moves it to {@code NEEDS_REVIEW}. Scheduling beyond the cut-off would leave an attempt
     * that is already overdue for review sitting quietly until its next 30-minute slot.
     */
    private static Instant earlier(Instant candidate, Instant cutoff) {
        return candidate.isAfter(cutoff) ? cutoff : candidate;
    }

    /** Whether the attempt has run out of time and belongs in {@code NEEDS_REVIEW}. */
    static boolean pastReviewCutoff(Instant enteredUnknownAt, Instant now, SweeperProperties properties) {
        return !now.isBefore(enteredUnknownAt.plus(properties.reviewCutoff()));
    }

    /**
     * The master's schedule as the gaps this class expects, for tests and for documentation.
     *
     * <p>Kept beside the logic that walks it so a change to one is read against the other.
     */
    static final List<Duration> MASTER_SCHEDULE = List.of(Duration.ofSeconds(1), Duration.ofSeconds(5),
            Duration.ofSeconds(30), Duration.ofMinutes(2), Duration.ofMinutes(10));
}
