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

    /**
     * The requested settlement day has not closed yet (D06-1).
     *
     * <p>Distinct from an empty report on purpose: "nothing settled that day" and "that day is still open" are the
     * same bytes but opposite facts, and booking the first for the second would settle zero against real captures.
     */
    public static class ReportNotReadyException extends RuntimeException {
        public ReportNotReadyException(ProviderId provider, java.time.LocalDate reportDate) {
            super(provider + " has not closed " + reportDate + " yet");
        }
    }

    /**
     * The provider could not be asked. Retryable, and never a reconciliation result: a run that could not fetch the
     * report must not commit, or an unreachable provider would look like a day with no settlement at all.
     */
    public static class ProviderUnavailableException extends RuntimeException {
        public ProviderUnavailableException(ProviderId provider, String reason) {
            super(provider + " is unavailable: " + reason);
        }
    }
}
