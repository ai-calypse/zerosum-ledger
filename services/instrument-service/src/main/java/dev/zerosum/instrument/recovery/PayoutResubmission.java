// decision: D05-7, D05-8, D05-11 — docs/step_05_instruments_fake_providers.md#decisions-and-outputs
package dev.zerosum.instrument.recovery;

import dev.zerosum.instrument.core.Commands.DisburseCommand;
import dev.zerosum.instrument.core.InstrumentExceptions.UnknownProviderException;
import dev.zerosum.instrument.core.PaymentInstrument;
import dev.zerosum.instrument.core.ProviderRegistry;
import dev.zerosum.instrument.core.SubmitResult;
import dev.zerosum.instrument.policy.AttemptOutcomes;
import dev.zerosum.instrument.store.AttemptTransitions;
import dev.zerosum.instrument.store.PaymentEvents;
import dev.zerosum.money.Money;
import java.time.Clock;
import java.time.Duration;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * Sends a {@code CREATED} payout to the bank, for the two paths S05-T12 owns: the old-{@code CREATED} sweep (§0.3
 * C24) and the resubmission after the ADR-0010 quiet period.
 *
 * <p><strong>Why this is not simply a call to {@code payouts.PayoutSubmitter}.</strong> That class does exactly this
 * and is the natural home for it, but it and its {@code submit} method are package-private to
 * {@code ..instrument.payouts}, and S05-T12's brief forbids editing that package. So the call is restated here
 * rather than the package being widened without its owner's say-so. <strong>This is a seam worth closing:</strong>
 * making {@code PayoutSubmitter#submit} public (or extracting one submission port both callers use) would leave one
 * payout submission path instead of two, and is recorded as follow-up work.
 *
 * <p><strong>What is not duplicated is the part that matters.</strong> The guarantee that a payout is sent once is
 * not in this class or in that one — it is the guarded {@code CREATED → SUBMITTING} transition in
 * {@code AttemptTransitions}, which both callers go through. Two callers of one guard cannot both win; that is the
 * whole design, and it is why a second call site is a maintenance cost rather than a duplicate-payout risk.
 *
 * <p>The phasing is S05-T09's, unchanged and for unchanged reasons: the attempt is already committed, the bank is
 * called in <strong>no transaction at all</strong>, and the outcome is written afterwards by {@link AttemptOutcomes}.
 * Nothing retries here — FakeBank has no idempotency keys, so a retry loop in this method would be a duplicate
 * payout.
 */
@Service
class PayoutResubmission {

    private static final Logger log = LoggerFactory.getLogger(PayoutResubmission.class);

    private final AttemptTransitions transitions;
    private final AttemptOutcomes outcomes;
    private final ProviderRegistry providers;
    private final Clock clock;
    private final Duration readTimeout;
    private final boolean payoutsEnabled;

    PayoutResubmission(AttemptTransitions transitions, AttemptOutcomes outcomes, ProviderRegistry providers,
            Clock clock, @Value("${zs.instruments.read-timeout}") Duration readTimeout,
            // decision: D05-11 — the same switch PayoutSubmitter reads, and read at startup for the same reason: a
            // flag that could change under a payout already on the wire would make "was it on when we called the
            // bank?" unanswerable. A recovery path that ignored the freeze would make the freeze meaningless.
            @Value("${zs.kill-switches.payouts-enabled}") boolean payoutsEnabled) {
        this.transitions = transitions;
        this.outcomes = outcomes;
        this.providers = providers;
        this.clock = clock;
        this.readTimeout = readTimeout;
        this.payoutsEnabled = payoutsEnabled;
    }

    /** Submits one payout attempt: claim it, call the bank outside any transaction, then write down what happened. */
    void submit(UUID attemptId) {
        PaymentEvents.Attempt attempt = transitions.read(attemptId);
        if (!"PAYOUT".equals(attempt.kind()) || !"CREATED".equals(attempt.status())) {
            return;   // already claimed, already finished, or not ours to submit
        }
        if (!payoutsEnabled) {
            // The attempt stays in CREATED, so the run's record of what it intended is not lost; this sweep submits
            // it once the switch is on (§0.3 C24).
            log.warn("payouts are disabled; attempt {} stays in CREATED", attemptId);
            return;
        }

        PaymentInstrument instrument;
        try {
            instrument = providers.get(attempt.provider());
        } catch (UnknownProviderException unknown) {
            log.error("payout attempt {} names provider {}, which no adapter serves; it stays in CREATED",
                    attemptId, attempt.provider());
            return;
        }
        if (!instrument.capabilities().disburse()) {
            // Capabilities decide, never the provider's name (D05-1). Checked before the claim, so an attempt that
            // could never be submitted is not left stranded in SUBMITTING.
            log.error("payout attempt {} names provider {}, which cannot disburse; it stays in CREATED",
                    attemptId, attempt.provider());
            return;
        }

        var claimed = transitions.apply(new AttemptTransitions.Transition(attemptId, attempt.version(), "CREATED",
                "SUBMITTING", "submitted by the recovery sweep", null, null, null));
        if (claimed instanceof AttemptTransitions.Result.LostRace) {
            // Another run, another tick or the payout submitter is already sending it. This is the guard that makes
            // a non-idempotent provider safe, so losing here is the mechanism working, not a failure.
            return;
        }

        SubmitResult result = call(instrument, attempt);

        try {
            outcomes.apply(attemptId, result);
        } catch (RuntimeException failure) {
            // The bank may already hold the payout and we could not write it down. The attempt stays in SUBMITTING,
            // which is exactly what the sweeper looks for.
            log.error("could not record the outcome of payout attempt {}; it stays in SUBMITTING for the sweeper",
                    attemptId, failure);
        }
    }

    private SubmitResult call(PaymentInstrument instrument, PaymentEvents.Attempt attempt) {
        Money amount = Money.of(attempt.amountMinor(), attempt.currency());
        var deadline = clock.instant().plus(readTimeout);
        try {
            return instrument.disburse(new DisburseCommand(UUID.fromString(attempt.attemptId()),
                    attempt.instrumentToken(), amount, deadline));
        } catch (RuntimeException failure) {
            // The request was already on the wire, so the only honest answer is that we do not know. The resolver
            // settles it by asking the bank, after the quiet period.
            log.error("resubmission of payout attempt {} failed unexpectedly", attempt.attemptId(), failure);
            return new SubmitResult.Unknown("resubmission failed: " + failure.getClass().getSimpleName());
        }
    }
}
