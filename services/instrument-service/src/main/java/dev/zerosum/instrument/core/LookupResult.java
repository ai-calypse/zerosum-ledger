package dev.zerosum.instrument.core;

/**
 * What the provider says about an attempt whose outcome we are unsure of (D05-1).
 *
 * <p>{@link NotFound} and {@link Unavailable} are deliberately distinct: "the provider has no record of this" permits
 * a resubmission, while "I could not ask" does not. Collapsing them would turn an outage into a double charge.
 */
public sealed interface LookupResult {

    record Found(ProviderStatus status, String providerRef) implements LookupResult {}

    /** The provider has no record of the attempt: it never landed. */
    record NotFound() implements LookupResult {}

    /** The lookup itself failed. The attempt's true state is still unknown. */
    record Unavailable(String reason) implements LookupResult {}
}
