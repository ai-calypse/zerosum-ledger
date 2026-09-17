package dev.zerosum.instrument.core;

import dev.zerosum.money.Money;
import java.time.Instant;
import java.util.Optional;

/**
 * A provider webhook, normalized (D05-1).
 *
 * <p>Adapters parse their provider's own payload into this shape, so the webhook receiver and the state machines
 * never branch on which provider sent it.
 *
 * @param providerEventId the provider's own event id — the dedupe key, since webhooks are redelivered
 * @param clientReference our attempt id as the provider echoed it back
 * @param failureCode     present for failures and returns (R01/R02/R03 and the like), absent otherwise
 */
public record ProviderEvent(ProviderId provider, String providerEventId, String providerRef, String clientReference,
        ProviderStatus status, Money amount, Instant occurredAt, Optional<String> failureCode) {

    public ProviderEvent {
        if (providerEventId == null || providerEventId.isBlank()) {
            throw new IllegalArgumentException("a provider event needs its own id, or redelivery cannot be deduped");
        }
        if (status == null) {
            throw new IllegalArgumentException("a provider event needs a normalized status");
        }
        failureCode = failureCode == null ? Optional.empty() : failureCode;
    }
}
