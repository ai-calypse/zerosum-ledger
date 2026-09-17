// decision: D05-7, D05-11 — docs/step_05_instruments_fake_providers.md#decisions-and-outputs
package dev.zerosum.instrument.payouts;

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
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * The RPC phase of a payout run (D05-7): the only place in this service that asks a bank to send money out.
 *
 * <p>The S05-T09 phasing, unchanged, because the reasons for it are unchanged: the attempt and its run are
 * <strong>already committed</strong> when this starts, the provider call happens in <strong>no transaction at
 * all</strong>, and the outcome is written by {@link AttemptOutcomes} in a new one afterwards. Between those points
 * the attempt sits in {@code SUBMITTING}, which is the honest description — a payout is on the wire and we do not
 * yet know what became of it.
 *
 * <p><strong>FakeBank has no idempotency keys</strong> ({@code FakeBankInstrument} sends none, deliberately), so a
 * blind resubmission can pay a driver twice. Nothing here retries: the single {@code CREATED → SUBMITTING} guarded
 * transition is what makes a second caller — another run, the S05-T12 sweeper — lose rather than submit again, and
 * an uncertain outcome becomes {@code UNKNOWN} for the resolver to settle by lookup after the quiet period
 * (ADR-0010). This is the one method in the service where a retry loop would be a duplicate payout.
 *
 * <p>A separate class from {@code AttemptSubmitter} because both halves genuinely differ: the operation is
 * {@code disburse} rather than a charge or refund, and the kill switch is the payouts one — D05-11 keeps the two
 * independent precisely so one path can be frozen while the other runs.
 */
@Service
class PayoutSubmitter {

    private static final Logger log = LoggerFactory.getLogger(PayoutSubmitter.class);

    private final AttemptTransitions transitions;
    private final AttemptOutcomes outcomes;
    private final ProviderRegistry providers;
    private final ExecutorService submissions;
    private final Clock clock;
    private final Duration readTimeout;
    private final boolean payoutsEnabled;

    PayoutSubmitter(AttemptTransitions transitions, AttemptOutcomes outcomes, ProviderRegistry providers,
            // The policy's bounded pool, by type: one pool caps how many provider calls this service can have
            // outstanding at once, which is the property worth capping. Taken by property name rather than by
            // importing the adapter's configuration record, which no class outside ..instrument.adapter.. may name.
            ExecutorService policySubmissions, Clock clock,
            @Value("${zs.instruments.read-timeout}") Duration readTimeout,
            // decision: D05-11 — read at startup, because a switch that could change under a payout already on the
            // wire would make "was it on when we called the bank?" unanswerable.
            @Value("${zs.kill-switches.payouts-enabled}") boolean payoutsEnabled) {
        this.transitions = transitions;
        this.outcomes = outcomes;
        this.providers = providers;
        this.submissions = policySubmissions;
        this.clock = clock;
        this.readTimeout = readTimeout;
        this.payoutsEnabled = payoutsEnabled;
    }

    /** Hands attempts to the executor. Called after the run's transaction has committed, never inside it. */
    void submitLater(List<UUID> attemptIds) {
        for (UUID attemptId : attemptIds) {
            try {
                submissions.execute(() -> {
                    try {
                        submit(attemptId);
                    } catch (RuntimeException failure) {
                        // Durable in CREATED or SUBMITTING either way, so this is loud rather than lost.
                        log.error("payout attempt {} could not be submitted", attemptId, failure);
                    }
                });
            } catch (RejectedExecutionException full) {
                // The attempt keeps its place in CREATED and the S05-T12 old-CREATED sweep submits it. Dropping the
                // work is safe; dropping the record would not be.
                log.warn("submission queue full; payout attempt {} stays in CREATED for the sweeper", attemptId);
            }
        }
    }

    /** Submits one payout: claim it, call the bank outside any transaction, then write down what happened. */
    void submit(UUID attemptId) {
        PaymentEvents.Attempt attempt = transitions.read(attemptId);
        if (!"PAYOUT".equals(attempt.kind()) || !"CREATED".equals(attempt.status())) {
            return;   // already claimed, already finished, or not ours to submit
        }
        if (!payoutsEnabled) {
            // decision: D05-11 — the attempt stays in CREATED, so the run's record of what it intended is not lost;
            // the S05-T12 old-CREATED sweep submits it once the switch is on (§0.3 C24).
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
                "SUBMITTING", "submitted by payout run " + attempt.orderGroupId(), null, null, null));
        if (claimed instanceof AttemptTransitions.Result.LostRace) {
            // Another run or the sweeper is already submitting it. This is the guard that makes a non-idempotent
            // provider safe, so losing here is the mechanism working, not a failure.
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
        // The deadline is ours, not the adapter's: an adapter that blocked past it would park an executor thread.
        var deadline = clock.instant().plus(readTimeout);
        try {
            return instrument.disburse(new DisburseCommand(UUID.fromString(attempt.attemptId()),
                    attempt.instrumentToken(), amount, deadline));
        } catch (RuntimeException failure) {
            // The request was already on the wire, so the only honest answer is that we do not know. Anything else
            // is a guess about money that may have left; the resolver settles it by asking the bank.
            log.error("submission of payout attempt {} failed unexpectedly", attempt.attemptId(), failure);
            return new SubmitResult.Unknown("submission failed: " + failure.getClass().getSimpleName());
        }
    }
}
