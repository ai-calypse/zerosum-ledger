// decision: D05-6 — docs/step_05_instruments_fake_providers.md#decisions-and-outputs
package dev.zerosum.instrument.policy;

import dev.zerosum.instrument.core.SubmitResult;
import dev.zerosum.instrument.policy.AttemptStore.AttemptRow;
import dev.zerosum.instrument.store.AttemptTransitions;
import dev.zerosum.instrument.store.PaymentEvents;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The post-RPC half (D05-6): what the provider said, written down, and the refunds that were waiting on it.
 *
 * <p>A separate bean from the submitter on purpose. Spring's {@code @Transactional} works through a proxy, so a
 * submitter calling its own annotated method would run this outside any transaction — the status change, its history
 * row, its payment event and the blocked-refund release would each commit on their own, and a crash between them
 * would leave a charge marked succeeded with the refund it should have released still blocked.
 *
 * <p>The attempt's status is read inside the transaction rather than passed in, so the result of a call that took
 * seconds is applied to the attempt as it is <em>now</em>. If a sweeper moved it meanwhile, the optimistic guard
 * returns {@code LostRace} and this writes nothing.
 */
@Service
public class AttemptOutcomes {

    private static final Logger log = LoggerFactory.getLogger(AttemptOutcomes.class);

    /** Where a charge stops moving. A refund released here can be sized against a settled capture. */
    private static final Set<String> CHARGE_TERMINAL = Set.of("SUCCEEDED", "DECLINED");

    private final AttemptTransitions transitions;
    private final AttemptStore attempts;
    private final PolicyMetrics metrics;
    private final Clock clock;

    AttemptOutcomes(AttemptTransitions transitions, AttemptStore attempts, PolicyMetrics metrics, Clock clock) {
        this.transitions = transitions;
        this.attempts = attempts;
        this.metrics = metrics;
        this.clock = clock;
    }

    /**
     * Applies a submit result and releases whatever was blocked on it.
     *
     * @return refunds that are now unblocked and should be submitted once this transaction has committed
     */
    @Transactional
    public List<UUID> apply(UUID attemptId, SubmitResult result) {
        PaymentEvents.Attempt attempt = transitions.read(attemptId);
        String from = attempt.status();
        String to = statusFor(attempt.kind(), result);
        String providerRef = result instanceof SubmitResult.Succeeded succeeded ? succeeded.providerRef() : null;
        String failureCode = result instanceof SubmitResult.Declined declined ? code(declined) : null;

        var applied = transitions.apply(new AttemptTransitions.Transition(attemptId, attempt.version(), from, to,
                cause(result), providerRef, failureCode,
                // Due immediately: the S05-T12 resolver owns the D05-8 schedule, and until it exists an UNKNOWN
                // attempt should at least be visible as overdue rather than as never scheduled.
                "UNKNOWN".equals(to) ? clock.instant() : null));

        if (applied instanceof AttemptTransitions.Result.LostRace) {
            // Normal: a sweeper or an operator got there first. Re-deciding here would move an attempt twice.
            log.info("attempt {} was already moved out of {} by another actor; the submit result is discarded",
                    attemptId, from);
            return List.of();
        }

        if ("CHARGE".equals(attempt.kind()) && CHARGE_TERMINAL.contains(to)) {
            return releaseBlocked(attempt.orderGroupId(), attempt.entityId(), attempt.currency());
        }
        return List.of();
    }

    /**
     * Releases the group's blocked refunds, in the same transaction as the capture that resolved them.
     *
     * <p>Sized here rather than when they were created, because until the capture resolved there was nothing to size
     * against. What is left after already-in-flight refunds is shared out oldest first; a refund with nothing left to
     * return is cancelled with the reason recorded, not left waiting for a capture that has already happened.
     */
    private List<UUID> releaseBlocked(String orderGroupId, String entityId, String currency) {
        List<AttemptRow> group = attempts.group(orderGroupId, entityId, currency);
        List<AttemptRow> blocked = group.stream()
                .filter(attempt -> attempt.isRefund() && attempt.blockedOnCapture() && "CREATED".equals(attempt.status()))
                .toList();
        if (blocked.isEmpty()) {
            return List.of();
        }

        long available = refundableExcludingBlocked(group);
        List<UUID> released = new ArrayList<>();
        for (AttemptRow refund : blocked) {
            long amount = Math.min(refund.amountMinor(), available);
            if (amount <= 0) {
                transitions.apply(new AttemptTransitions.Transition(refund.attemptId(), refund.version(), "CREATED",
                        "CANCELLED", "the originating charge returned nothing refundable", null, null, null));
                metrics.withheld("nothing_refundable");
                continue;
            }
            if (attempts.release(refund.attemptId(), amount) == 1) {
                available -= amount;
                released.add(refund.attemptId());
            }
        }
        return List.copyOf(released);
    }

    /** What the group can still return, counting refunds already on their way but not the blocked ones being sized. */
    private static long refundableExcludingBlocked(List<AttemptRow> group) {
        long captured = 0;
        long committed = 0;
        for (AttemptRow attempt : group) {
            if (attempt.isCharge() && "SUCCEEDED".equals(attempt.status())) {
                captured += attempt.amountMinor();
            } else if (attempt.isRefund() && !attempt.blockedOnCapture()
                    && ("SUCCEEDED".equals(attempt.status()) || GroupLedger.isInFlight(attempt.status()))) {
                committed += attempt.amountMinor();
            }
        }
        return Math.max(0, captured - committed);
    }

    private static String statusFor(String kind, SubmitResult result) {
        return switch (result) {
            case SubmitResult.Succeeded ignored -> "SUCCEEDED";
            // A refund uses FAILED in place of DECLINED (master §5.10); the machines encode the same alias.
            case SubmitResult.Declined ignored -> "CHARGE".equals(kind) ? "DECLINED" : "FAILED";
            case SubmitResult.Unknown ignored -> "UNKNOWN";
            // Neither machine has a PENDING state, so an adapter reporting one for a charge or refund has broken its
            // contract. Throwing rolls the transaction back and leaves the attempt in SUBMITTING for the sweeper,
            // which is better than inventing a status for money whose fate we would then be guessing at.
            case SubmitResult.Pending pending -> throw new IllegalStateException(
                    "a " + kind + " cannot be pending; the adapter returned " + pending);
        };
    }

    /** The D01-8 schema requires a non-empty failure code on a decline, and an event is built from the attempt. */
    private static String code(SubmitResult.Declined declined) {
        return declined.code() == null || declined.code().isBlank() ? "declined" : declined.code();
    }

    private static String cause(SubmitResult result) {
        return switch (result) {
            case SubmitResult.Succeeded succeeded -> "provider accepted the submission (" + succeeded.providerRef() + ")";
            case SubmitResult.Declined declined -> "provider refused the submission (" + code(declined) + ")";
            case SubmitResult.Unknown unknown -> "submission outcome unknown: " + unknown.reason();
            case SubmitResult.Pending pending -> "provider accepted the submission (" + pending.providerRef() + ")";
        };
    }
}
