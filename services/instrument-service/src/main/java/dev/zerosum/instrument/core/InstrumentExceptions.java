package dev.zerosum.instrument.core;

/** Typed failures from the instrument layer (D05-1). */
public final class InstrumentExceptions {

    private InstrumentExceptions() {
    }

    /**
     * Thrown when core code calls an operation the adapter does not support.
     *
     * <p>A programming error, not a runtime branch: core code is required to consult {@link Capabilities} first, so
     * reaching this means the caller skipped that check.
     */
    public static class UnsupportedCapabilityException extends RuntimeException {
        public UnsupportedCapabilityException(ProviderId provider, String operation) {
            super(provider + " does not support " + operation + "; check capabilities() before calling");
        }
    }

    /** A webhook whose signature or timestamp did not verify. Never parsed further. */
    public static class InvalidSignatureException extends RuntimeException {
        public InvalidSignatureException(String reason) {
            super(reason);
        }
    }

    /** No adapter is registered for the requested provider. Callers translate this, never let it surface as an NPE. */
    public static class UnknownProviderException extends RuntimeException {
        public UnknownProviderException(String providerId) {
            super("no payment instrument registered for provider: " + providerId);
        }
    }
}
