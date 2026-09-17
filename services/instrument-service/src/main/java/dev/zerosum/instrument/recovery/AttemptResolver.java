// decision: D05-5, D05-8, D05-9 — docs/step_05_instruments_fake_providers.md#decisions-and-outputs (§0.3 C21)
package dev.zerosum.instrument.recovery;

import dev.zerosum.instrument.core.Capabilities;
import dev.zerosum.instrument.core.Commands.ChargeCommand;
import dev.zerosum.instrument.core.Commands.DisburseCommand;
import dev.zerosum.instrument.core.Commands.LookupQuery;
import dev.zerosum.instrument.core.Commands.RefundCommand;
import dev.zerosum.instrument.core.InstrumentExceptions.UnknownProviderException;
import dev.zerosum.instrument.core.LookupResult;
import dev.zerosum.instrument.core.PaymentInstrument;
import dev.zerosum.instrument.core.ProviderRegistry;
import dev.zerosum.instrument.core.ProviderStatus;
import dev.zerosum.instrument.core.SubmitResult;
import dev.zerosum.instrument.store.AttemptStateMachines;
import dev.zerosum.instrument.store.AttemptStateMachines.Event;
import dev.zerosum.instrument.store.AttemptStateMachines.Kind;
import dev.zerosum.instrument.store.AttemptStateMachines.Outcome;
import dev.zerosum.instrument.store.AttemptStateMachines.State;
import dev.zerosum.instrument.store.AttemptTransitions;
import dev.zerosum.instrument.store.IllegalTransitions;
import dev.zerosum.instrument.store.PaymentEvents;
import dev.zerosum.money.Money;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * Decides what an attempt we are unsure about is really worth, and moves it (D05-8, ADR-0010, master §5.10).
 *
 * <p>This is the class that answers the question the whole step exists for: <em>we do not know whether money moved,
 * so what do we do?</em> There are exactly three safe answers, and which one applies is decided by the provider's
 * {@code Capabilities}, never by its name:
 *
 * <ul>
 *   <li><strong>The provider has idempotency keys</strong> — retry with the same attempt id as the key. The key is
 *       what makes that harmless: a provider that already applied the request replays its stored result instead of
 *       applying it twice.</li>
 *   <li><strong>The provider has none</strong> — ask, never assume. A lookup that <em>finds</em> the payment settles
 *       it. A lookup that finds nothing permits a resubmission only once the quiet period has also elapsed: both
 *       conditions, never either alone (ADR-0010).</li>
 *   <li><strong>We could not ask</strong> — do nothing at all and come back later. {@code Unavailable} is not
 *       {@code NotFound}: "I could not reach the bank" is not evidence that the bank has no record, and treating it
 *       as such is how an outage becomes a duplicate payout.</li>
 * </ul>
 *
 * <p><strong>One resolver, two callers.</strong> It absorbed {@code webhooks.AheadOfStateResolver}, which was the
 * first consumer of {@link AttemptStateMachines#resolutionPath}: a webhook that outran our state and a sweeper tick
 * that found a stale attempt both end in the same place — ask the provider, then walk the attempt forward one
 * transition at a time so each payment event is emitted exactly once. Two implementations of that walk would be two
 * chances to emit {@code PAYOUT_SETTLED} twice, or not at all.
 *
 * <p><strong>It runs off the request thread</strong>, on the same bounded pool as policy submissions, because the
 * webhook handler has a two-second budget and a provider call does not fit inside it. If a resolution never runs — a
 * full queue, a crash — the attempt is still in a status the sweeper looks for, with its provider event on file.
 */
@Service
public class AttemptResolver {

    private static final Logger log = LoggerFactory.getLogger(AttemptResolver.class);

    /** Master §6.4 naming; provisional under D05-14. Absorbed {@code instrument_ahead_of_state_resolutions_total}. */
    public static final String COUNTER = "instrument_attempt_resolutions_total";

    /** Why a resolution ran, as a bounded tag: the webhook catch-up path, or the sweeper's schedule. */
    private static final String WEBHOOK_CATCH_UP = "webhook_catch_up";
    private static final String UNKNOWN_RESOLUTION = "unknown_resolution";

    private final ProviderRegistry providers;
    private final AttemptTransitions transitions;
    private final RecoveryQueries queries;
    private final PayoutResubmission payouts;
    private final IllegalTransitions illegal;
    private final MeterRegistry meters;
    private final ExecutorService resolutions;
    private final Clock clock;
    private final Duration readTimeout;
    private final SweeperProperties properties;

    AttemptResolver(ProviderRegistry providers, AttemptTransitions transitions, RecoveryQueries queries,
            PayoutResubmission payouts, IllegalTransitions illegal, MeterRegistry meters,
            // The policy's bounded pool, taken by type: one pool caps how many provider calls this service can have
            // outstanding, which is the property that matters. Named by property rather than by importing the
            // adapter's configuration record, which no class outside ..instrument.adapter.. may name.
            ExecutorService policySubmissions, Clock clock,
            @Value("${zs.instruments.read-timeout}") Duration readTimeout, SweeperProperties properties) {
        this.providers = providers;
        this.transitions = transitions;
        this.queries = queries;
        this.payouts = payouts;
        this.illegal = illegal;
        this.meters = meters;
        this.resolutions = policySubmissions;
        this.clock = clock;
        this.readTimeout = readTimeout;
        this.properties = properties;
    }

    // --- the webhook path (§0.3 C21), absorbed from AheadOfStateResolver ---------------------------------------

    /** Called after the receiving transaction has committed, never inside it. */
    public void resolveLater(UUID attemptId, String failureCode) {
        try {
            resolutions.execute(() -> {
                try {
                    resolve(attemptId, failureCode);
                } catch (RuntimeException failure) {
                    // The event is recorded and the attempt is where it was, so this is loud rather than lost.
                    log.error("could not resolve ahead-of-state attempt {}", attemptId, failure);
                    counted(WEBHOOK_CATCH_UP, "failed");
                }
            });
        } catch (RejectedExecutionException full) {
            log.warn("resolution queue full; attempt {} keeps its recorded event for the sweeper", attemptId);
            counted(WEBHOOK_CATCH_UP, "rejected");
        }
    }

    /**
     * Catches an attempt up with what the provider says is true now.
     *
     * <p>Used by the webhook path, where an event arrived ahead of our state, and by the pending-payout poll, where
     * no event arrived at all. Both ask the same question, and in both cases <strong>the lookup decides, not the
     * message</strong>: a webhook says what the provider believed when it sent it; a lookup is the provider
     * answering a question we asked just now.
     */
    public void resolve(UUID attemptId, String failureCode) {
        PaymentEvents.Attempt attempt = transitions.read(attemptId);
        PaymentInstrument instrument;
        try {
            instrument = providers.get(attempt.provider());
        } catch (UnknownProviderException unknown) {
            log.error("attempt {} names provider {}, which no adapter serves", attemptId, attempt.provider());
            counted(WEBHOOK_CATCH_UP, "unknown_provider");
            return;
        }

        LookupResult lookup = instrument.lookup(new LookupQuery(attemptId, clock.instant().plus(readTimeout)));
        if (!(lookup instanceof LookupResult.Found found)) {
            // NotFound and Unavailable both leave the true state unknown, and neither permits moving money on it.
            log.warn("attempt {} stays as it is: the lookup answered {}", attemptId,
                    lookup.getClass().getSimpleName());
            counted(WEBHOOK_CATCH_UP, "unresolved");
            return;
        }
        applyFound(attemptId, attempt, found, failureCode, "resolved by lookup", WEBHOOK_CATCH_UP);
    }

    // --- the sweeper path (D05-8) --------------------------------------------------------------------------------

    /**
     * Resolves one {@code UNKNOWN} attempt on the D05-8 schedule.
     *
     * <p>The next check is armed <strong>before</strong> the provider call, not after. A tick that dispatched the
     * same attempt again while this one was still waiting on a socket would have two resolutions in flight for one
     * attempt; re-arming first means the attempt stops being due the moment it is picked up, and the optimistic
     * guard handles whatever still races.
     */
    void resolveUnknown(UUID attemptId) {
        PaymentEvents.Attempt attempt = transitions.read(attemptId);
        if (!State.UNKNOWN.name().equals(attempt.status())) {
            return;   // somebody resolved it between the query and here, which is a normal race
        }
        Kind kind = Kind.of(attempt.kind());
        Instant now = clock.instant();

        RecoveryQueries.AttemptTimes times = queries.timesOf(attemptId).orElse(null);
        Instant enteredUnknownAt = times == null || times.enteredUnknownAt() == null ? now : times.enteredUnknownAt();

        if (ResolutionSchedule.pastReviewCutoff(enteredUnknownAt, now, properties)) {
            escalate(attemptId, attempt, kind);
            return;
        }
        rescheduleFrom(attemptId, enteredUnknownAt, now);

        PaymentInstrument instrument;
        try {
            instrument = providers.get(attempt.provider());
        } catch (UnknownProviderException unknown) {
            log.error("attempt {} names provider {}, which no adapter serves; it waits for review",
                    attemptId, attempt.provider());
            counted(UNKNOWN_RESOLUTION, "unknown_provider");
            return;
        }

        Capabilities capabilities = instrument.capabilities();
        if (capabilities.supportsIdempotentRetry()) {
            retry(instrument, attempt, kind);
            return;
        }
        lookThenResubmit(instrument, attempt, kind, capabilities, times, now);
    }

    /**
     * An idempotent provider: send the same request again, under the same key.
     *
     * <p>Safe precisely because of the key — the provider replays a stored result rather than applying the request a
     * second time — and the attempt id has been that key since the command records were written, so there is no
     * mapping table to get wrong here.
     */
    private void retry(PaymentInstrument instrument, PaymentEvents.Attempt attempt, Kind kind) {
        UUID attemptId = UUID.fromString(attempt.attemptId());
        Optional<SubmitResult> result = reissue(instrument, attempt, kind);
        if (result.isEmpty()) {
            counted(UNKNOWN_RESOLUTION, "not_retryable");
            return;
        }

        Event event = retryEvent(result.get());
        switch (AttemptStateMachines.decide(kind, State.UNKNOWN, event)) {
            case Outcome.Applied applied -> {
                var transition = new AttemptTransitions.Transition(attemptId, attempt.version(),
                        State.UNKNOWN.name(), applied.next().name(), "idempotent retry: " + cause(result.get()),
                        providerRef(result.get()), failureCode(result.get()), null);
                if (transitions.apply(transition) instanceof AttemptTransitions.Result.LostRace) {
                    counted(UNKNOWN_RESOLUTION, "overtaken");
                    return;
                }
                log.info("attempt {} resolved to {} by idempotent retry", attemptId, applied.next());
                counted(UNKNOWN_RESOLUTION, "retried_" + applied.next().name().toLowerCase(java.util.Locale.ROOT));
            }
            // The retry was itself uncertain, or claimed a status the attempt is already at. Either way nothing
            // moves and the next check is already armed.
            case Outcome.IgnoredStale ignored -> counted(UNKNOWN_RESOLUTION, "still_unknown");
            case Outcome.AheadOfState ahead ->
                    applyFoundState(attemptId, attempt, kind, ahead.claimed(), providerRef(result.get()),
                            failureCode(result.get()), "idempotent retry outran our state", UNKNOWN_RESOLUTION);
            case Outcome.Illegal ignored -> {
                illegal.record(attemptId, kind, State.UNKNOWN, event);
                counted(UNKNOWN_RESOLUTION, "illegal");
            }
        }
    }

    /**
     * A provider without idempotency keys: the two-condition rule from ADR-0010.
     *
     * <p>Both conditions, or nothing happens. They are folded into the single {@code QUIET_PERIOD_ELAPSED} event so
     * that "the timer expired" can never reach the state machine on its own.
     */
    private void lookThenResubmit(PaymentInstrument instrument, PaymentEvents.Attempt attempt, Kind kind,
            Capabilities capabilities, RecoveryQueries.AttemptTimes times, Instant now) {
        UUID attemptId = UUID.fromString(attempt.attemptId());
        LookupResult lookup = instrument.lookup(new LookupQuery(attemptId, now.plus(readTimeout)));

        switch (lookup) {
            case LookupResult.Found found -> {
                // The provider has it. That settles the question and forbids a resubmission outright — including
                // when it found several, which means a duplicate already happened and a third would only deepen it.
                applyFound(attemptId, attempt, found, null, "resolved by lookup after an uncertain outcome",
                        UNKNOWN_RESOLUTION);
            }
            case LookupResult.NotFound ignored -> {
                Instant submittedAt = times == null ? null : times.lastSubmittedAt();
                Duration quietPeriod = capabilities.safeResubmitQuietPeriod();
                if (submittedAt == null || quietPeriod == null) {
                    // Nothing to measure the quiet period from. Refusing to resubmit is the only safe reading.
                    log.warn("attempt {} has no recorded submission to measure the quiet period from", attemptId);
                    counted(UNKNOWN_RESOLUTION, "no_submission_time");
                    return;
                }
                Instant quietUntil = submittedAt.plus(quietPeriod);
                if (now.isBefore(quietUntil)) {
                    // Condition 1 has not held yet. NotFound this soon means the provider has not recorded the
                    // request *yet*, not that it never will.
                    queries.reschedule(attemptId, quietUntil);
                    log.info("attempt {} is not found, but the quiet period runs until {}; no resubmission",
                            attemptId, quietUntil);
                    counted(UNKNOWN_RESOLUTION, "within_quiet_period");
                    return;
                }
                resubmit(attemptId, attempt, kind);
            }
            case LookupResult.Unavailable unavailable -> {
                // "I could not ask" is not "it never happened". Nothing moves; the next check is already armed.
                log.warn("attempt {} could not be looked up ({}); it stays UNKNOWN and will be checked again",
                        attemptId, unavailable.reason());
                counted(UNKNOWN_RESOLUTION, "lookup_unavailable");
            }
        }
    }

    /** Both conditions held: take the one arrow back to {@code CREATED} and submit again. */
    private void resubmit(UUID attemptId, PaymentEvents.Attempt attempt, Kind kind) {
        Outcome outcome = AttemptStateMachines.decide(kind, State.UNKNOWN, Event.QUIET_PERIOD_ELAPSED);
        if (!(outcome instanceof Outcome.Applied applied)) {
            // Only the payout machine draws UNKNOWN -> CREATED. A charge at a provider with neither idempotency keys
            // nor that arrow cannot be resubmitted at all, and waits for review rather than being guessed at.
            log.warn("attempt {} ({}) has no resubmission path; it waits for the review cut-off", attemptId, kind);
            counted(UNKNOWN_RESOLUTION, "no_resubmission_path");
            return;
        }

        var transition = new AttemptTransitions.Transition(attemptId, attempt.version(), State.UNKNOWN.name(),
                applied.next().name(), "quiet period elapsed and the provider had no record of it (ADR-0010)",
                null, null, null);
        if (transitions.apply(transition) instanceof AttemptTransitions.Result.LostRace) {
            counted(UNKNOWN_RESOLUTION, "overtaken");
            return;
        }
        log.info("attempt {} is back in CREATED for resubmission: quiet period elapsed and lookup found nothing",
                attemptId);
        counted(UNKNOWN_RESOLUTION, "resubmitted");
        // Submitted straight away rather than left for the old-CREATED sweep: the attempt has already waited a
        // whole quiet period, and the sweep's threshold would add another wait on top of it.
        payouts.submit(attemptId);
    }

    /** Unresolved past the cut-off. A human decides; nothing here guesses a terminal state. */
    private void escalate(UUID attemptId, PaymentEvents.Attempt attempt, Kind kind) {
        Outcome outcome = AttemptStateMachines.decide(kind, State.UNKNOWN, Event.REVIEW_CUTOFF);
        if (!(outcome instanceof Outcome.Applied applied)) {
            illegal.record(attemptId, kind, State.UNKNOWN, Event.REVIEW_CUTOFF);
            counted(UNKNOWN_RESOLUTION, "illegal");
            return;
        }
        var transition = new AttemptTransitions.Transition(attemptId, attempt.version(), State.UNKNOWN.name(),
                applied.next().name(), "unresolved after the review cut-off", null, null, null);
        if (transitions.apply(transition) instanceof AttemptTransitions.Result.LostRace) {
            counted(UNKNOWN_RESOLUTION, "overtaken");
            return;
        }
        // Loud on purpose: NEEDS_REVIEW has no automatic exit, so this line and the gauge beside it are the whole
        // of how anybody finds out.
        log.error("attempt {} is NEEDS_REVIEW: unresolved after {} and no automatic path out of it",
                attemptId, properties.reviewCutoff());
        counted(UNKNOWN_RESOLUTION, "needs_review");
    }

    private void rescheduleFrom(UUID attemptId, Instant enteredUnknownAt, Instant now) {
        ResolutionSchedule.nextCheckAfter(enteredUnknownAt, now, properties)
                .ifPresent(next -> queries.reschedule(attemptId, next));
    }

    // --- applying what the provider said --------------------------------------------------------------------------

    private void applyFound(UUID attemptId, PaymentEvents.Attempt attempt, LookupResult.Found found,
            String failureCode, String cause, String operation) {
        Kind kind = Kind.of(attempt.kind());
        State current = State.valueOf(attempt.status());
        // The table, not this class, decides what the lookup means — including the machine's own aliases, which is
        // why the target state comes back from decide() rather than from a second mapping here.
        switch (AttemptStateMachines.decide(kind, current, lookupEvent(found.status()))) {
            case Outcome.Applied applied ->
                    walk(attemptId, kind, current, List.of(applied.next()), found.providerRef(), failureCode, cause,
                            operation);
            case Outcome.AheadOfState ahead ->
                    applyFoundState(attemptId, attempt, kind, ahead.claimed(), found.providerRef(), failureCode,
                            cause, operation);
            case Outcome.IgnoredStale ignored -> {
                log.info("attempt {} was already caught up to {} by another actor", attemptId, current);
                counted(operation, "already_caught_up");
            }
            case Outcome.Illegal ignored -> {
                illegal.record(attemptId, kind, current, lookupEvent(found.status()));
                counted(operation, "illegal");
            }
        }
    }

    /** The §0.3 C21 catch-up: walk the intermediate statuses in order so every payment event is emitted once. */
    private void applyFoundState(UUID attemptId, PaymentEvents.Attempt attempt, Kind kind, State claimed,
            String providerRef, String failureCode, String cause, String operation) {
        State current = State.valueOf(attempt.status());
        List<State> path = AttemptStateMachines.resolutionPath(kind, current, claimed);
        if (path.isEmpty()) {
            // No forward path is unresolvable, not "nothing to do": say so rather than reporting success.
            log.error("no {} path from {} to {} for attempt {}", kind, current, claimed, attemptId);
            counted(operation, "no_path");
            return;
        }
        walk(attemptId, kind, current, path, providerRef, failureCode, cause, operation);
    }

    /**
     * Applies the path one transition at a time, each in its own transaction, so each emits its payment event.
     *
     * <p>The attempt is re-read before every step: a sweeper or a later webhook may have moved it while this walked,
     * and continuing from a stale version would either lose the race silently or apply a step from a status the
     * attempt has left.
     */
    private void walk(UUID attemptId, Kind kind, State from, List<State> path, String providerRef,
            String failureCode, String cause, String operation) {
        State at = from;
        for (int step = 0; step < path.size(); step++) {
            State next = path.get(step);
            PaymentEvents.Attempt attempt = transitions.read(attemptId);
            if (!attempt.status().equals(at.name())) {
                log.info("attempt {} moved to {} while catching up; leaving the rest to whoever moved it",
                        attemptId, attempt.status());
                counted(operation, "overtaken");
                return;
            }
            var result = transitions.apply(new AttemptTransitions.Transition(attemptId, attempt.version(),
                    at.name(), next.name(), cause, providerRef,
                    // Only the step that lands on the failure or return carries the code; an earlier step is not a
                    // failure and its payment event must not claim one.
                    step == path.size() - 1 ? failureCode : null, null));
            if (result instanceof AttemptTransitions.Result.LostRace) {
                counted(operation, "overtaken");
                return;
            }
            at = next;
        }
        log.info("attempt {} caught up from {} to {} ({} transitions)", attemptId, from, at, path.size());
        counted(operation, "resolved");
    }

    // --- re-issuing the original request --------------------------------------------------------------------------

    /**
     * Builds and sends the same command again, under the same key.
     *
     * @return empty when the request cannot be rebuilt — a refund whose captured charge cannot be found — in which
     *         case the attempt simply waits for its next check rather than being sent without the charge it reverses
     */
    private Optional<SubmitResult> reissue(PaymentInstrument instrument, PaymentEvents.Attempt attempt, Kind kind) {
        UUID attemptId = UUID.fromString(attempt.attemptId());
        Money amount = Money.of(attempt.amountMinor(), attempt.currency());
        // The deadline is ours, not the adapter's: an adapter that blocked past it would park an executor thread.
        Instant deadline = clock.instant().plus(readTimeout);
        try {
            return Optional.of(switch (kind) {
                case CHARGE -> instrument.charge(
                        new ChargeCommand(attemptId, attempt.instrumentToken(), amount, deadline));
                case PAYOUT -> instrument.disburse(
                        new DisburseCommand(attemptId, attempt.instrumentToken(), amount, deadline));
                case REFUND -> {
                    Optional<String> originalRef = queries.capturedChargeRef(
                            attempt.orderGroupId(), attempt.entityId(), attempt.currency());
                    if (originalRef.isEmpty()) {
                        log.warn("attempt {} is a refund whose captured charge has no provider reference; "
                                + "it cannot be retried and waits for its next check", attemptId);
                        yield null;
                    }
                    yield instrument.refund(new RefundCommand(attemptId, originalRef.get(), amount, deadline));
                }
            });
        } catch (RuntimeException failure) {
            // The request was already on the wire, so the only honest answer is that we do not know.
            log.error("retry of attempt {} failed unexpectedly", attemptId, failure);
            return Optional.of(new SubmitResult.Unknown("retry failed: " + failure.getClass().getSimpleName()));
        }
    }

    private void counted(String operation, String outcome) {
        Counter.builder(COUNTER)
                .description("Attempts resolved after an uncertain outcome or an event ahead of our state")
                .tag("operation", operation)
                .tag("outcome", outcome)
                .register(meters)
                .increment();
    }

    private static Event retryEvent(SubmitResult result) {
        return switch (result) {
            case SubmitResult.Succeeded ignored -> Event.RETRY_SUCCEEDED;
            case SubmitResult.Pending ignored -> Event.RETRY_PENDING;
            case SubmitResult.Declined ignored -> Event.RETRY_DECLINED;
            case SubmitResult.Unknown ignored -> Event.RETRY_UNKNOWN;
        };
    }

    private static String providerRef(SubmitResult result) {
        return switch (result) {
            case SubmitResult.Succeeded succeeded -> succeeded.providerRef();
            case SubmitResult.Pending pending -> pending.providerRef();
            case SubmitResult.Declined ignored -> null;
            case SubmitResult.Unknown ignored -> null;
        };
    }

    /** The D01-8 schema requires a non-empty failure code on a decline, and an event is built from the attempt. */
    private static String failureCode(SubmitResult result) {
        if (result instanceof SubmitResult.Declined declined) {
            return declined.code() == null || declined.code().isBlank() ? "declined" : declined.code();
        }
        return null;
    }

    private static String cause(SubmitResult result) {
        return switch (result) {
            case SubmitResult.Succeeded succeeded -> "provider replayed " + succeeded.providerRef();
            case SubmitResult.Pending pending -> "provider replayed " + pending.providerRef();
            case SubmitResult.Declined declined -> "provider refused it (" + failureCode(declined) + ")";
            case SubmitResult.Unknown unknown -> "still unknown: " + unknown.reason();
        };
    }

    private static Event lookupEvent(ProviderStatus status) {
        return switch (status) {
            case SUCCEEDED -> Event.LOOKUP_FOUND_SUCCEEDED;
            case PENDING -> Event.LOOKUP_FOUND_PENDING;
            case DECLINED -> Event.LOOKUP_FOUND_DECLINED;
            case FAILED -> Event.LOOKUP_FOUND_FAILED;
            case SETTLED -> Event.LOOKUP_FOUND_SETTLED;
            case RETURNED -> Event.LOOKUP_FOUND_RETURNED;
        };
    }
}
