// decision: D05-8 — docs/step_05_instruments_fake_providers.md#decisions-and-outputs
package dev.zerosum.instrument.recovery;

import java.time.Duration;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Sweeper thresholds and the {@code UNKNOWN} resolution schedule (D05-8, master §5.10 and §5.11).
 *
 * <p>Every value here decides how long money stays in an unknown state, so each is configuration rather than a
 * constant, and each is stated in {@code application.yml} even where it matches the default below — a framework
 * default that changed silently would change how long a rider waits to learn whether they were charged.
 *
 * @param submittingMargin   master §5.11: "Sweeper threshold = read timeout + 10 s". The margin is configured and the
 *                           threshold is derived, because the read timeout is the thing that actually decides when a
 *                           call is over, and two independently configured values would drift apart
 * @param createdThreshold   how long an attempt may sit in {@code CREATED} before the sweep submits it (§0.3 C24):
 *                           long enough that the normal path — submit straight after the creating transaction commits
 *                           — always wins the race, so the sweep only ever picks up genuine crashes and freezes
 * @param resolutionSchedule the master's schedule, as gaps between checks: 1 s, 5 s, 30 s, 2 min, 10 min
 * @param resolutionInterval what the schedule repeats at once the list is exhausted (every 30 min)
 * @param reviewCutoff       unresolved after this, the attempt becomes {@code NEEDS_REVIEW}, which raises an alert
 * @param pendingPayoutPoll  how old a {@code PENDING} payout must be before it is looked up, so a dropped webhook
 *                           still converges (master §6.6)
 * @param batchSize          how many attempts one tick claims, so a backlog is worked through in bounded pieces
 *                           rather than in one transaction that holds a connection for minutes
 */
@ConfigurationProperties(prefix = "zs.sweeper")
public record SweeperProperties(Duration interval, Duration submittingMargin, Duration createdThreshold,
        List<Duration> resolutionSchedule, Duration resolutionInterval, Duration reviewCutoff,
        Duration pendingPayoutPoll, int batchSize) {

    public SweeperProperties {
        interval = interval == null ? Duration.ofSeconds(1) : interval;
        submittingMargin = submittingMargin == null ? Duration.ofSeconds(10) : submittingMargin;
        createdThreshold = createdThreshold == null ? Duration.ofSeconds(30) : createdThreshold;
        resolutionSchedule = resolutionSchedule == null || resolutionSchedule.isEmpty()
                ? List.of(Duration.ofSeconds(1), Duration.ofSeconds(5), Duration.ofSeconds(30),
                        Duration.ofMinutes(2), Duration.ofMinutes(10))
                : List.copyOf(resolutionSchedule);
        resolutionInterval = resolutionInterval == null ? Duration.ofMinutes(30) : resolutionInterval;
        reviewCutoff = reviewCutoff == null ? Duration.ofHours(24) : reviewCutoff;
        pendingPayoutPoll = pendingPayoutPoll == null ? Duration.ofMinutes(10) : pendingPayoutPoll;
        batchSize = batchSize <= 0 ? 100 : batchSize;

        // A zero or negative repeat would spin forever in ResolutionSchedule's tail loop rather than misbehave
        // visibly, so it is refused at startup instead.
        if (resolutionInterval.isZero() || resolutionInterval.isNegative()) {
            throw new IllegalArgumentException("resolution-interval must be positive, was " + resolutionInterval);
        }
        if (reviewCutoff.isZero() || reviewCutoff.isNegative()) {
            throw new IllegalArgumentException("review-cutoff must be positive, was " + reviewCutoff);
        }
    }

    /** master §5.11: the sweeper calls a submission over when the read timeout plus a margin has passed. */
    public Duration submittingThreshold(Duration readTimeout) {
        return readTimeout.plus(submittingMargin);
    }
}
