package dev.zerosum.instrument.core;

import dev.zerosum.money.EntityKind;

/**
 * Identifies a payment provider (D05-1).
 *
 * <p>Holds the bare id — {@code fakecard} — and derives the ledger entity id from it. The chart of accounts already
 * names providers as {@code provider:<id>} ({@link EntityKind#PROVIDER}), so deriving it here keeps one convention
 * instead of letting a second one drift into existence.
 */
public record ProviderId(String id) {

    public ProviderId {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("provider id must not be blank");
        }
        if (!id.matches("[a-z][a-z0-9_-]{0,31}")) {
            // Same shape the payment-event schema requires of `provider`, so an adapter cannot register an id that
            // could never appear in a valid event.
            throw new IllegalArgumentException("provider id must match [a-z][a-z0-9_-]{0,31}: " + id);
        }
    }

    /** The ledger entity id for this provider's clearing accounts, e.g. {@code provider:fakecard}. */
    public String entityId() {
        return EntityKind.PROVIDER.prefix() + ":" + id;
    }

    @Override
    public String toString() {
        return id;
    }
}
