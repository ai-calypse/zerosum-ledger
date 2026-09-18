// decision: D05-6, D05-11 — docs/step_05_instruments_fake_providers.md#decisions-and-outputs
package dev.zerosum.instrument.policy;

import dev.zerosum.auth.ChaosGuard;
import dev.zerosum.instrument.core.Commands.ChargeCommand;
import dev.zerosum.instrument.core.Commands.RefundCommand;
import dev.zerosum.instrument.core.InstrumentExceptions.UnknownProviderException;
import dev.zerosum.instrument.core.PaymentInstrument;
import dev.zerosum.instrument.core.ProviderRegistry;
import dev.zerosum.instrument.core.SubmitResult;
import dev.zerosum.instrument.policy.AttemptStore.AttemptRow;
import dev.zerosum.instrument.store.AttemptTransitions;
import dev.zerosum.money.Money;
import java.time.Clock;
import java.time.Duration;
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
 * The RPC phase (D05-6): the only place in this service that asks a provider to move a rider's money.
 *
 * <p><strong>Nothing here runs in a transaction.</strong> The attempt is already committed when this starts, and the
 * outcome is written by {@link AttemptOutcomes} in a new one afterwards. Between those two points the attempt sits in
 * {@code SUBMITTING}, which is precisely the honest description of its state: a request is on the wire and we do not
 * yet know what it did.
 *
 * <p>Submission runs on a bounded executor so a slow provider cannot park the Kafka listener thread. When the queue
 * is full the attempt simply stays in {@code CREATED}: it is durable, and the S05-T12 sweeper submits attempts left
 * there past the D05-8 threshold. Dropping the work is safe; dropping the record would not be.
 *
 * <p>The one guard that makes redelivery harmless is the {@code CREATED → SUBMITTING} transition. Two threads
 * handling two deliveries of the same order both reach it; the optimistic version lets exactly one through, so the
 * provider is called once however many times the order arrives.
 */
@Service
public class AttemptSubmitter {

    private static final Logger log = LoggerFactory.getLogger(AttemptSubmitter.class);

    private final AttemptStore attempts;
    private final AttemptTransitions transitions;
    private final AttemptOutcomes outcomes;
    private final ProviderRegistry providers;
    private final PolicyMetrics metrics;
    private final ExecutorService submissions;
    private final Clock clock;
    private final Duration readTimeout;
    private final boolean collectionsEnabled;
    /** decision: D08-3 — the A3 ablation and the F3 crash hook. Both off outside the guarded chaos profile. */
    private final boolean freshKeyOnUnknown;
    private final boolean crashHook;

    AttemptSubmitter(AttemptStore attempts, AttemptTransitions transitions, AttemptOutcomes outcomes,
            ProviderRegistry providers, PolicyMetrics metrics, ExecutorService policySubmissions, Clock clock,
            // Named by property, not by importing the adapter's configuration record: no class outside
            // ..instrument.adapter.. may name one (ADR-0010, enforced by InstrumentBoundaryTest).
            @Value("${zs.instruments.read-timeout}") Duration readTimeout,
            // decision: D05-11 — the collections kill switch. Read at startup, because a switch that could change
            // under a submission in flight would make "was it on when we called?" unanswerable.
            @Value("${zs.kill-switches.collections-enabled}") boolean collectionsEnabled, ChaosGuard.Active chaos) {
        this.freshKeyOnUnknown = chaos.on("A3");
        this.crashHook = chaos.on("F3");
        this.attempts = attempts;
        this.transitions = transitions;
        this.outcomes = outcomes;
        this.providers = providers;
        this.metrics = metrics;
        this.submissions = policySubmissions;
        this.clock = clock;
        this.readTimeout = readTimeout;
        this.collectionsEnabled = collectionsEnabled;
    }

    /** Hands attempts to the executor. Called after the creating transaction has committed, never inside it. */
    public void submitLater(List<UUID> attemptIds) {
        for (UUID attemptId : attemptIds) {
            try {
                submissions.execute(() -> submit(attemptId));
            } catch (RejectedExecutionException full) {
                // The attempt keeps its place in CREATED; the S05-T12 sweeper picks it up. Counted because a queue
                // that is regularly full means collections are running behind, which is invisible otherwise.
                metrics.withheld("submission_queue_full");
                log.warn("submission queue full; attempt {} stays in CREATED for the sweeper", attemptId);
            }
        }
    }

    /**
     * Submits one attempt: claim it, call the provider outside any transaction, then write down what happened.
     *
     * <p>Public because the S05-T12 sweeper and resolver will drive the same path. There is deliberately one
     * submission code path in this service, so the S08 A3 and F3 seams (§0.3 E7, E9) stay small.
     */
    public void submit(UUID attemptId) {
        Optional<AttemptRow> found = attempts.find(attemptId);
        if (found.isEmpty()) {
            return;
        }
        AttemptRow attempt = found.get();
        if (!"CREATED".equals(attempt.status()) || attempt.blockedOnCapture()) {
            return;   // already claimed, already finished, or still waiting for its capture
        }
        if (!collectionsEnabled) {
            // decision: D05-11 — the attempt is still created, so no order is lost; it waits in CREATED and the
            // S05-T12 old-CREATED sweep submits it once the switch is on (§0.3 C24).
            metrics.withheld("collections_disabled");
            log.warn("collections are disabled; attempt {} stays in CREATED", attemptId);
            return;
        }

        PaymentInstrument instrument;
        try {
            instrument = providers.get(attempt.provider());
        } catch (UnknownProviderException unknown) {
            metrics.policyError("unknown_provider", attempt.entityId(), String.valueOf(attemptId));
            return;
        }

        String originalRef = null;
        if (attempt.isRefund()) {
            originalRef = GroupLedger.capturedChargeRef(
                    attempts.group(attempt.orderGroupId(), attempt.entityId(), attempt.currency())).orElse(null);
            if (originalRef == null) {
                // A refund must name the charge it reverses; without one the provider would refuse it and the ledger
                // could not explain it. Left in CREATED rather than failed: the capture may still resolve.
                metrics.withheld("no_captured_charge");
                return;
            }
        }

        var claimed = transitions.apply(new AttemptTransitions.Transition(attemptId, attempt.version(), "CREATED",
                "SUBMITTING", "submitted by the collection policy", null, null, null));
        if (claimed instanceof AttemptTransitions.Result.LostRace) {
            return;   // another delivery of the same order is already submitting it
        }

        SubmitResult result = call(instrument, attempt, originalRef, attempt.attemptId().toString());

        // decision: D08-3 — A3 ablation (master §8.5, §0.3 E7): no UNKNOWN handling. A charge whose outcome is unknown
        // is resubmitted at once, as the same attempt, under a fresh provider idempotency key. Bounded, so a provider
        // that keeps timing out still ends in UNKNOWN rather than spinning; each resubmission may be a second charge.
        for (int resubmits = 0; freshKeyOnUnknown && attempt.isCharge() && result instanceof SubmitResult.Unknown
                && resubmits < 3; resubmits++) {
            log.warn("ZS-CHAOS A3 resubmitting attempt {} under a fresh key after: {}", attemptId,
                    ((SubmitResult.Unknown) result).reason());
            result = call(instrument, attempt, originalRef, UUID.randomUUID().toString());
        }

        // decision: D08-3 — F3 (master §8.4, §0.3 E9), chaos only, armed once per injection by the harness: the
        // provider has answered and nothing is written down, so the attempt is left in SUBMITTING for the sweeper.
        if (crashHook && ChaosGuard.consumeArm("F3")) {
            log.warn("ZS-CHAOS F3 halting between the provider call and the state update of attempt {}", attemptId);
            ChaosGuard.halt();
        }

        try {
            submitLater(outcomes.apply(attemptId, result));
        } catch (RuntimeException failure) {
            // The money may already have moved and we could not write it down. The attempt stays in SUBMITTING,
            // which is exactly what the sweeper looks for, so this is loud but not lost.
            log.error("could not record the outcome of attempt {}; it stays in SUBMITTING for the sweeper",
                    attemptId, failure);
        }
    }

    private SubmitResult call(PaymentInstrument instrument, AttemptRow attempt, String originalRef,
            String idempotencyKey) {
        Money amount = Money.of(attempt.amountMinor(), attempt.currency());
        // The deadline is ours, not the adapter's: an adapter that blocked past it would turn a slow provider into a
        // parked executor thread.
        var deadline = clock.instant().plus(readTimeout);
        try {
            return attempt.isCharge()
                    ? instrument.charge(new ChargeCommand(attempt.attemptId(), attempt.instrumentToken(), amount,
                            deadline, idempotencyKey))
                    : instrument.refund(new RefundCommand(attempt.attemptId(), originalRef, amount, deadline));
        } catch (RuntimeException failure) {
            // The request was already on the wire, so the only honest answer is that we do not know. Anything else
            // here is a guess about money; the resolver settles it by asking the provider.
            log.error("submission of attempt {} failed unexpectedly", attempt.attemptId(), failure);
            return new SubmitResult.Unknown("submission failed: " + failure.getClass().getSimpleName());
        }
    }
}
