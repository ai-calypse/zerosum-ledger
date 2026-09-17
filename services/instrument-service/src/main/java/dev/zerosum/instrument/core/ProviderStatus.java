package dev.zerosum.instrument.core;

/**
 * The normalized status vocabulary core state machines consume (D05-1).
 *
 * <p>Adapters map provider-native statuses onto these. Core code never sees a provider's own wording, which is what
 * lets a third adapter be added without touching the state machines (master H4).
 */
public enum ProviderStatus {
    /** Accepted and final: the money moved. */
    SUCCEEDED,
    /** Accepted, outcome still to come — FakeBank's `202` payouts live here until a webhook resolves them. */
    PENDING,
    /** Definitively refused. Never retried: a decline is an answer, not a failure. */
    DECLINED,
    /** Attempted and failed after acceptance, e.g. a payout the bank could not complete. */
    FAILED,
    /** Settled by the provider — a payout that reached the beneficiary. */
    SETTLED,
    /** Reversed after settlement, e.g. a returned payout (R01/R02/R03). */
    RETURNED
}
