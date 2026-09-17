// decision: D05-6 — docs/step_05_instruments_fake_providers.md#decisions-and-outputs
package dev.zerosum.instrument.policy;

import dev.zerosum.instrument.core.InstrumentExceptions.UnknownProviderException;
import dev.zerosum.instrument.core.PaymentInstrument;
import dev.zerosum.instrument.core.ProviderRegistry;
import dev.zerosum.instrument.policy.AttemptStore.AttemptRow;
import dev.zerosum.instrument.policy.InstrumentTokens.TokenRow;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;

/**
 * The pre-RPC half of the collection policy (D05-6): decide, insert, commit — and nothing else.
 *
 * <p><strong>No provider is called from here.</strong> The transaction that creates attempts commits before anything
 * reaches a provider, so a crash at any moment leaves either no attempt or an attempt in {@code CREATED} that the
 * S05-T12 sweeper picks up. The forbidden ordering is the reverse: a provider call inside the transaction would let a
 * rollback erase the record of money that has already moved ("no network calls inside transactions").
 *
 * <p>The returned ids are attempts the caller should submit, <em>after</em> this transaction has committed. A blocked
 * refund is never in that list: it waits for its capture to resolve.
 */
@Service
public class CollectionPolicyService {

    private final AttemptStore attempts;
    private final InstrumentTokens tokens;
    private final ProviderRegistry providers;
    private final PolicyMetrics metrics;

    CollectionPolicyService(AttemptStore attempts, InstrumentTokens tokens, ProviderRegistry providers,
            PolicyMetrics metrics) {
        this.attempts = attempts;
        this.tokens = tokens;
        this.providers = providers;
        this.metrics = metrics;
    }

    /**
     * Turns one money order into at most one attempt per (rider, currency).
     *
     * @return the attempts to submit once this transaction has committed
     */
    @Transactional
    public List<UUID> plan(JsonNode order) {
        List<CollectionPolicy.RiderDelta> deltas = CollectionPolicy.riderDeltas(order);
        if (deltas.isEmpty()) {
            // Not an error: most orders in this pipeline are the ledger's record of money this service already moved,
            // and acting on those would collect the same fare twice.
            metrics.orderSeen("no_action");
            return List.of();
        }

        UUID orderId = UUID.fromString(order.get("order_id").asString());
        String groupId = order.get("order_group_id").asString();
        List<UUID> toSubmit = new ArrayList<>();

        for (CollectionPolicy.RiderDelta delta : deltas) {
            List<AttemptRow> group = attempts.group(groupId, delta.entityId(), delta.currency());
            for (CollectionPolicy.Intent intent : CollectionPolicy.decide(delta, GroupLedger.stateOf(group))) {
                create(orderId, groupId, intent, group).ifPresent(attempt -> {
                    if (!attempt.blockedOnCapture()) {
                        toSubmit.add(attempt.attemptId());
                    }
                });
            }
        }
        metrics.orderSeen(toSubmit.isEmpty() ? "no_submission" : "planned");
        return List.copyOf(toSubmit);
    }

    /**
     * Inserts the attempt, or loads the one a previous delivery created.
     *
     * <p>Empty means the policy could not act: no registered token, or a token at a provider no adapter serves. That
     * is recorded and counted rather than thrown, because failing the record would stop the partition and every
     * other rider's order behind it for a configuration problem affecting one.
     */
    private Optional<AttemptRow> create(UUID orderId, String groupId, CollectionPolicy.Intent intent,
            List<AttemptRow> group) {
        Optional<Instrument> instrument = resolve(intent, group);
        if (instrument.isEmpty()) {
            metrics.policyError(intent.kind().equals("CHARGE") ? "no_usable_instrument" : "no_refundable_instrument",
                    intent.entityId(), String.valueOf(orderId));
            return Optional.empty();
        }

        AttemptRow attempt = attempts.insertOrLoad(intent.kind(), orderId, groupId, intent.entityId(),
                instrument.get().provider(), instrument.get().token(), intent.currency(), intent.amountMinor(),
                intent.blockedOnCapture());
        metrics.attemptCreated(intent.kind(), attempt.blockedOnCapture());
        return Optional.of(attempt);
    }

    /** Which provider and token an attempt should use. */
    private record Instrument(String provider, String token) {
    }

    /**
     * A refund goes back to the instrument that paid, so it reuses the charge's provider and token rather than
     * whatever is registered now — re-registration replaces the token for future attempts only, and refunding to a
     * newly registered instrument would return the money to a different card.
     */
    private Optional<Instrument> resolve(CollectionPolicy.Intent intent, List<AttemptRow> group) {
        if ("REFUND".equals(intent.kind())) {
            Optional<Instrument> fromCharge = group.stream()
                    .filter(AttemptRow::isCharge)
                    .reduce((first, second) -> second)
                    .map(charge -> new Instrument(charge.provider(), charge.instrumentToken()));
            if (fromCharge.isPresent()) {
                return fromCharge;
            }
        }
        return tokens.forEntity(intent.entityId()).stream()
                .filter(token -> supports(token, intent.kind()))
                .findFirst()
                .map(token -> new Instrument(token.provider(), token.token()));
    }

    /**
     * Capabilities decide, not the provider's name. That is what keeps a third adapter from needing a change here:
     * it declares what it can do, and this asks.
     */
    private boolean supports(TokenRow token, String kind) {
        try {
            PaymentInstrument instrument = providers.get(token.provider());
            return "CHARGE".equals(kind) ? instrument.capabilities().charge() : instrument.capabilities().refund();
        } catch (UnknownProviderException unknown) {
            // A token registered for a provider this build has no adapter for. Skipping it lets another registered
            // token serve the entity instead of failing the whole order.
            return false;
        }
    }
}
