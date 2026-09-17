// decision: D05-6 — docs/step_05_instruments_fake_providers.md#decisions-and-outputs
package dev.zerosum.instrument.policy;

import dev.zerosum.instrument.policy.AttemptStore.AttemptRow;
import java.util.List;
import java.util.Set;

/**
 * Reads a group's attempts into the state the policy reasons about (D05-6).
 *
 * <p>Separated from both the store and the policy so the classification of a status — is this attempt still in
 * flight, or is it a refusal? — is stated once. Two copies of this judgement would eventually disagree, and the
 * disagreement would be a refund sized against a capture that had not happened.
 */
final class GroupLedger {

    /**
     * Statuses where the provider's answer is still outstanding.
     *
     * <p>{@code NEEDS_REVIEW} is in here deliberately. It means "unresolved after the cut-off, a human must look":
     * treating it as a refusal would refund money the provider may well have taken, so a refund against such a
     * group waits instead. The cost is a refund that stays blocked until the review resolves the charge, which is
     * the right way round for money.
     */
    private static final Set<String> IN_FLIGHT = Set.of("CREATED", "SUBMITTING", "UNKNOWN", "NEEDS_REVIEW");

    /** Statuses that say nothing was taken and nothing will be. */
    private static final Set<String> REFUSED = Set.of("DECLINED", "FAILED", "CANCELLED");

    private GroupLedger() {
    }

    static CollectionPolicy.GroupState stateOf(List<AttemptRow> group) {
        long captured = 0;
        long refunded = 0;
        long refundsInFlight = 0;
        boolean chargeInFlight = false;
        boolean chargeSucceeded = false;
        boolean chargeRefused = false;
        boolean chargeExists = false;

        for (AttemptRow attempt : group) {
            if (attempt.isCharge()) {
                chargeExists = true;
                if ("SUCCEEDED".equals(attempt.status())) {
                    captured += attempt.amountMinor();
                    chargeSucceeded = true;
                } else if (IN_FLIGHT.contains(attempt.status())) {
                    chargeInFlight = true;
                } else if (REFUSED.contains(attempt.status())) {
                    chargeRefused = true;
                }
            } else if (attempt.isRefund()) {
                if ("SUCCEEDED".equals(attempt.status())) {
                    refunded += attempt.amountMinor();
                } else if (IN_FLIGHT.contains(attempt.status())) {
                    // A blocked refund counts too: it is money already promised back, and leaving it out would let a
                    // second adjustment claim the same capture.
                    refundsInFlight += attempt.amountMinor();
                }
            }
        }

        // "Refused" only when nothing in the group succeeded: a group whose first charge declined and whose retry
        // succeeded has money to return.
        return new CollectionPolicy.GroupState(captured, refunded, refundsInFlight, chargeInFlight,
                chargeRefused && !chargeSucceeded, chargeExists);
    }

    static boolean isInFlight(String status) {
        return IN_FLIGHT.contains(status);
    }

    /** The reference of the charge a refund reverses; a refund without one is an unattached credit. */
    static java.util.Optional<String> capturedChargeRef(List<AttemptRow> group) {
        return group.stream()
                .filter(attempt -> attempt.isCharge() && "SUCCEEDED".equals(attempt.status()))
                .map(AttemptRow::providerRef)
                .filter(ref -> ref != null && !ref.isBlank())
                .findFirst();
    }
}
