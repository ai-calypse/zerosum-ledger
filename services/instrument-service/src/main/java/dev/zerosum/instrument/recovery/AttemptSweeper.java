// decision: D05-8 — docs/step_05_instruments_fake_providers.md#decisions-and-outputs (§0.3 C24, O9)
package dev.zerosum.instrument.recovery;

import dev.zerosum.instrument.policy.AttemptSubmitter;
import dev.zerosum.instrument.store.AttemptStateMachines;
import dev.zerosum.instrument.store.AttemptStateMachines.Event;
import dev.zerosum.instrument.store.AttemptStateMachines.Kind;
import dev.zerosum.instrument.store.AttemptStateMachines.Outcome;
import dev.zerosum.instrument.store.AttemptStateMachines.State;
import dev.zerosum.instrument.store.AttemptTransitions;
import dev.zerosum.instrument.store.PaymentEvents;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Finds attempts that have stopped moving and gets them moving again (D05-8, master §5.10 and §5.11).
 *
 * <p>Four sweeps, each for a way an attempt can get stuck, and the reason each exists is a real crash point from
 * master §5.7's W4 sequence:
 *
 * <ol>
 *   <li><strong>{@code SUBMITTING} past the threshold</strong> — the provider call is over, by read timeout plus a
 *       margin, and nobody wrote down what it did. The attempt becomes {@code UNKNOWN}, which is the honest
 *       description and the status the resolver works on. This is the crash-after-send case.</li>
 *   <li><strong>{@code CREATED} past the threshold</strong> (§0.3 C24) — a crash between committing the attempt and
 *       submitting it, or a kill switch that was off when it was created. S05-T09 and S05-T10 both create attempts
 *       before submitting, so this sweep is what finishes the job. It is also the <em>only</em> path by which a
 *       frozen-then-unfrozen collection or payout ever reaches a provider.</li>
 *   <li><strong>{@code UNKNOWN} whose next check is due</strong> — the D05-8 schedule, handed to the resolver.</li>
 *   <li><strong>{@code PENDING} payouts older than the poll threshold</strong> — so a dropped webhook still
 *       converges (master §6.6) instead of a payout sitting pending for ever because one delivery was lost.</li>
 * </ol>
 *
 * <p><strong>The tick dispatches; it does not do the work.</strong> Every provider call goes to the same bounded
 * executor as policy submissions, so one scheduler thread can never be parked on a socket — and when that queue is
 * full the work is simply dropped for this tick, because the attempt is durable and still due on the next one.
 * Dropping the work is safe; dropping the record would not be.
 *
 * <p><strong>Claiming is the optimistic guard, never a row lock.</strong> Nothing here holds a lock across a provider
 * call. Two instances running this sweep is therefore safe rather than forbidden: both may pick up an attempt, and
 * the guarded transition lets exactly one of them move it. The master calls for a single instance and the MVP runs
 * one, but the correctness does not depend on that being true.
 */
@Component
class AttemptSweeper {

    private static final Logger log = LoggerFactory.getLogger(AttemptSweeper.class);

    private final RecoveryQueries queries;
    private final AttemptResolver resolver;
    private final AttemptTransitions transitions;
    private final AttemptSubmitter charges;
    private final PayoutResubmission payouts;
    private final AlertSignals signals;
    private final ExecutorService work;
    private final Clock clock;
    private final SweeperProperties properties;
    private final Duration readTimeout;

    AttemptSweeper(RecoveryQueries queries, AttemptResolver resolver, AttemptTransitions transitions,
            AttemptSubmitter charges, PayoutResubmission payouts, AlertSignals signals,
            ExecutorService policySubmissions, Clock clock, SweeperProperties properties,
            @Value("${zs.instruments.read-timeout}") Duration readTimeout) {
        this.queries = queries;
        this.resolver = resolver;
        this.transitions = transitions;
        this.charges = charges;
        this.payouts = payouts;
        this.signals = signals;
        this.work = policySubmissions;
        this.clock = clock;
        this.properties = properties;
        this.readTimeout = readTimeout;
    }

    /**
     * One pass over all four sweeps.
     *
     * <p>{@code fixedDelay}, so a slow pass delays the next one rather than overlapping with it, and all four sweeps
     * share one tick so they cannot interleave against each other on the scheduler thread.
     */
    @Scheduled(fixedDelayString = "${zs.sweeper.interval}")
    void sweep() {
        Instant now = clock.instant();
        try {
            sweepTimedOutSubmissions(now);
            sweepOldCreated(now);
            resolveDueUnknown(now);
            pollPendingPayouts(now);
        } catch (RuntimeException failure) {
            // A failing sweep must not kill the scheduled task, or nothing would ever be swept again. The next tick
            // retries; the alert signals below still refresh, so a database that is down is visible rather than
            // silently frozen.
            log.error("sweeper pass failed; the next tick retries", failure);
        }
        refreshSignals();
    }

    /** master §5.11: read timeout plus a margin, after which a submission is over whatever the provider is doing. */
    private void sweepTimedOutSubmissions(Instant now) {
        Instant before = now.minus(properties.submittingThreshold(readTimeout));
        for (UUID attemptId : queries.timedOutSubmitting(before, properties.batchSize())) {
            PaymentEvents.Attempt attempt = transitions.read(attemptId);
            Kind kind = Kind.of(attempt.kind());
            Outcome outcome = AttemptStateMachines.decide(kind, State.SUBMITTING, Event.SWEEPER_TIMEOUT);
            if (!(outcome instanceof Outcome.Applied applied)) {
                log.error("the table refuses SWEEPER_TIMEOUT for {} attempt {} in SUBMITTING", kind, attemptId);
                continue;
            }
            // The first check is armed in the same transition, so an attempt can never arrive in UNKNOWN without a
            // schedule attached to it.
            Instant firstCheck = ResolutionSchedule.nextCheckAfter(now, now, properties).orElse(now);
            var result = transitions.apply(new AttemptTransitions.Transition(attemptId, attempt.version(),
                    State.SUBMITTING.name(), applied.next().name(),
                    "the sweeper found it in SUBMITTING past the read timeout", null, null, firstCheck));
            if (result instanceof AttemptTransitions.Result.Applied) {
                log.info("attempt {} was still SUBMITTING after {}; it is UNKNOWN until the provider is asked",
                        attemptId, properties.submittingThreshold(readTimeout));
            }
        }
    }

    /**
     * §0.3 C24: attempts that were written down but never sent.
     *
     * <p>Each kind goes to its own submitter. A payout handed to the charge submitter would be sent as a refund —
     * the provider would refuse an operation it does not support, and the attempt would cycle through
     * {@code UNKNOWN} for ever — so the dispatch is on {@code kind} and nothing else.
     */
    private void sweepOldCreated(Instant now) {
        Instant before = now.minus(properties.createdThreshold());
        for (RecoveryQueries.DueAttempt due : queries.oldCreated(before, properties.batchSize())) {
            // The kill switches are checked inside each submitter, not here: a frozen path must leave the attempt in
            // CREATED, and deciding that in two places is how the two decisions drift apart.
            Consumer<UUID> submitter = Kind.PAYOUT.name().equals(due.kind()) ? payouts::submit : charges::submit;
            dispatch(due.attemptId(), submitter, "submit");
        }
    }

    private void resolveDueUnknown(Instant now) {
        for (UUID attemptId : queries.dueUnknown(now, properties.batchSize())) {
            dispatch(attemptId, resolver::resolveUnknown, "resolve");
        }
    }

    /**
     * master §6.6: a payout still pending long after its webhooks should have arrived is looked up, so a dropped
     * delivery becomes a delay rather than a payout that is pending for ever.
     *
     * <p>This is the same catch-up the webhook path uses: ask the provider, then walk the attempt forward through
     * every intermediate status so each payment event is emitted exactly once.
     */
    private void pollPendingPayouts(Instant now) {
        Instant before = now.minus(properties.pendingPayoutPoll());
        for (UUID attemptId : queries.stalePendingPayouts(before, properties.batchSize())) {
            dispatch(attemptId, id -> resolver.resolve(id, null), "poll");
        }
    }

    private void dispatch(UUID attemptId, Consumer<UUID> action, String what) {
        try {
            work.execute(() -> {
                try {
                    action.accept(attemptId);
                } catch (RuntimeException failure) {
                    // The attempt is durable and still due, so this is loud rather than lost.
                    log.error("sweeper could not {} attempt {}", what, attemptId, failure);
                }
            });
        } catch (RejectedExecutionException full) {
            // Backpressure, not an error: the queue is the bound on how many provider calls are outstanding, and the
            // attempt stays due for the next tick.
            log.debug("work queue full; attempt {} stays due for the next tick", attemptId);
        }
    }

    /** §0.3 O9. Refreshed even when the sweeps failed, so a stuck sweeper is visible in the gauges it publishes. */
    private void refreshSignals() {
        try {
            signals.refresh(queries.oldestAges(), queries.stateCounts());
        } catch (RuntimeException failure) {
            // Leaving the previous values in place is the honest outcome: they are the last thing actually measured.
            log.warn("could not refresh the alert signals", failure);
        }
    }
}
