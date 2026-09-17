package dev.zerosum.instrument.core;

/**
 * The outcome of submitting a charge, disbursement or refund (D05-1).
 *
 * <p>Sealed so a {@code switch} over it is exhaustive without a default branch: when a new outcome is added, every
 * state machine that must handle it stops compiling, which is the point.
 *
 * <p>{@link Unknown} is the one that shapes the design. A timeout or reset after sending means the provider may or
 * may not have moved money, and the only wrong response is to guess.
 */
public sealed interface SubmitResult {

    /** Final and successful. */
    record Succeeded(String providerRef) implements SubmitResult {}

    /** Accepted; the final outcome arrives by webhook. */
    record Pending(String providerRef) implements SubmitResult {}

    /** Definitively refused — never retried. */
    record Declined(String code) implements SubmitResult {}

    /** The request may or may not have been applied. Resolve by lookup, never by assuming. */
    record Unknown(String reason) implements SubmitResult {}
}
