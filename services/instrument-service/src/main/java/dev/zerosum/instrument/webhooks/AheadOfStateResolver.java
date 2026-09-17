// decision: D05-5 — docs/step_05_instruments_fake_providers.md#decisions-and-outputs (§0.3 C21)
package dev.zerosum.instrument.webhooks;

import dev.zerosum.instrument.core.Commands.LookupQuery;
import dev.zerosum.instrument.core.LookupResult;
import dev.zerosum.instrument.core.ProviderRegistry;
import dev.zerosum.instrument.core.ProviderStatus;
import dev.zerosum.instrument.store.AttemptStateMachines;
import dev.zerosum.instrument.store.AttemptStateMachines.Event;
import dev.zerosum.instrument.store.AttemptStateMachines.Kind;
import dev.zerosum.instrument.store.AttemptStateMachines.Outcome;
import dev.zerosum.instrument.store.AttemptStateMachines.State;
import dev.zerosum.instrument.store.AttemptTransitions;
import dev.zerosum.instrument.store.IllegalTransitions;
import dev.zerosum.instrument.store.PaymentEvents;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * Catches an attempt up with an event that arrived ahead of it (§0.3 C21, master §5.10).
 *
 * <p>The master's own example: {@code payout.returned} while the attempt is still {@code PENDING}. The event is
 * already recorded by {@link WebhookReceiver}; this resolves the attempt's <em>true</em> state with a provider
 * lookup and then applies the intermediate transitions in order, so {@code PAYOUT_SETTLED} and
 * {@code PAYOUT_RETURNED} are each emitted exactly once. Applying the claimed status directly would skip the
 * intermediate payment event, and the ledger would be asked to reverse a payout it never saw settle.
 *
 * <p><strong>The lookup decides, not the webhook.</strong> The message says what the provider believes; the lookup
 * is the provider answering a question we asked, and it is the only one of the two that can be trusted to describe
 * the state now.
 *
 * <p>It runs off the request thread, on the same bounded executor as policy submissions, because the master gives
 * the webhook handler a two-second budget and a provider call does not fit inside it. The endpoint has already
 * returned 2xx by then, which is honest: the event is durably recorded, which is exactly what the 2xx promises
 * (TB2). If this never runs — a full queue, a crash — the attempt is still in a state the S05-T12 sweeper looks
 * for, with the provider event on file.
 */
@Service
public class AheadOfStateResolver {

    private static final Logger log = LoggerFactory.getLogger(AheadOfStateResolver.class);

    /** Master §6.4 naming; provisional under D05-14. */
    public static final String COUNTER = "instrument_ahead_of_state_resolutions_total";

    private final ProviderRegistry providers;
    private final AttemptTransitions transitions;
    private final IllegalTransitions illegal;
    private final MeterRegistry meters;
    private final ExecutorService resolutions;
    private final Clock clock;
    private final Duration readTimeout;

    AheadOfStateResolver(ProviderRegistry providers, AttemptTransitions transitions, IllegalTransitions illegal,
            MeterRegistry meters,
            // The policy's bounded pool, taken by type: one pool caps how many provider calls this service can have
            // outstanding, which is the property that matters. Named by property rather than by importing the
            // adapter's configuration record, which no class outside ..instrument.adapter.. may name.
            ExecutorService policySubmissions, Clock clock,
            @Value("${zs.instruments.read-timeout}") Duration readTimeout) {
        this.providers = providers;
        this.transitions = transitions;
        this.illegal = illegal;
        this.meters = meters;
        this.resolutions = policySubmissions;
        this.clock = clock;
        this.readTimeout = readTimeout;
    }

    /** Called after the receiving transaction has committed, never inside it. */
    public void resolveLater(UUID attemptId, String failureCode) {
        try {
            resolutions.execute(() -> {
                try {
                    resolve(attemptId, failureCode);
                } catch (RuntimeException failure) {
                    // The event is recorded and the attempt is where it was, so this is loud rather than lost.
                    log.error("could not resolve ahead-of-state attempt {}", attemptId, failure);
                    counted("failed");
                }
            });
        } catch (RejectedExecutionException full) {
            log.warn("resolution queue full; attempt {} keeps its recorded event for the sweeper", attemptId);
            counted("rejected");
        }
    }

    /** Public so the S05-T12 resolver and the tests can drive the same path rather than a parallel one. */
    public void resolve(UUID attemptId, String failureCode) {
        PaymentEvents.Attempt attempt = transitions.read(attemptId);
        LookupResult lookup = providers.get(attempt.provider())
                .lookup(new LookupQuery(attemptId, clock.instant().plus(readTimeout)));
        if (!(lookup instanceof LookupResult.Found found)) {
            // NotFound and Unavailable both leave the true state unknown, and neither permits moving money on it.
            log.warn("attempt {} stays as it is: the lookup answered {}", attemptId,
                    lookup.getClass().getSimpleName());
            counted("unresolved");
            return;
        }

        Kind kind = Kind.of(attempt.kind());
        State current = State.valueOf(attempt.status());
        // The table, not this class, decides what the lookup means — including the machine's own aliases, which is
        // why the target state comes back from decide() rather than from a second mapping here.
        switch (AttemptStateMachines.decide(kind, current, lookupEvent(found.status()))) {
            case Outcome.Applied applied ->
                    walk(attemptId, kind, current, List.of(applied.next()), found.providerRef(), failureCode);
            case Outcome.AheadOfState ahead -> {
                List<State> path = AttemptStateMachines.resolutionPath(kind, current, ahead.claimed());
                if (path.isEmpty()) {
                    // No forward path is unresolvable, not "nothing to do": say so rather than reporting success.
                    log.error("no {} path from {} to {} for attempt {}", kind, current, ahead.claimed(), attemptId);
                    counted("no_path");
                    return;
                }
                walk(attemptId, kind, current, path, found.providerRef(), failureCode);
            }
            case Outcome.IgnoredStale ignored -> {
                log.info("attempt {} was already caught up to {} by another actor", attemptId, current);
                counted("already_caught_up");
            }
            case Outcome.Illegal ignored -> {
                illegal.record(attemptId, kind, current, lookupEvent(found.status()));
                counted("illegal");
            }
        }
    }

    /**
     * Applies the path one transition at a time, each in its own transaction, so each emits its payment event.
     *
     * <p>The attempt is re-read before every step: a sweeper or a later webhook may have moved it while this walked,
     * and continuing from a stale version would either lose the race silently or apply a step from a status the
     * attempt has left.
     */
    private void walk(UUID attemptId, Kind kind, State from, List<State> path, String providerRef,
            String failureCode) {
        State at = from;
        for (int step = 0; step < path.size(); step++) {
            State next = path.get(step);
            PaymentEvents.Attempt attempt = transitions.read(attemptId);
            if (!attempt.status().equals(at.name())) {
                log.info("attempt {} moved to {} while catching up; leaving the rest to whoever moved it",
                        attemptId, attempt.status());
                counted("overtaken");
                return;
            }
            var result = transitions.apply(new AttemptTransitions.Transition(attemptId, attempt.version(),
                    at.name(), next.name(), "resolved by lookup after an event arrived ahead of state", providerRef,
                    // Only the step that lands on the failure or return carries the code; an earlier step is not a
                    // failure and its payment event must not claim one.
                    step == path.size() - 1 ? failureCode : null, null));
            if (result instanceof AttemptTransitions.Result.LostRace) {
                counted("overtaken");
                return;
            }
            at = next;
        }
        log.info("attempt {} caught up from {} to {} ({} transitions)", attemptId, from, at, path.size());
        counted("resolved");
    }

    private void counted(String outcome) {
        Counter.builder(COUNTER)
                .description("Attempts caught up after a provider event arrived ahead of our state (§0.3 C21)")
                .tag("outcome", outcome)
                .register(meters)
                .increment();
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
