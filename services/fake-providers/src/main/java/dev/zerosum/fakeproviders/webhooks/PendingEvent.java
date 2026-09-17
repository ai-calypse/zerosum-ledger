package dev.zerosum.fakeproviders.webhooks;

import java.time.Instant;

/**
 * One undelivered provider event, with the delivery state the sender needs (D05-3).
 *
 * @param siblingHeld whether another undelivered event for the same object is currently held by the reorder knob.
 *                    Read in the same query as the event, because holding a second event of a pair would delay both
 *                    and reorder nothing.
 */
record PendingEvent(String eventId, String provider, String eventType, String providerRef, String clientReference,
        long amountMinor, String currency, String failureCode, Instant occurredAt, int attempts, boolean reorderHeld,
        boolean siblingHeld) {
}
