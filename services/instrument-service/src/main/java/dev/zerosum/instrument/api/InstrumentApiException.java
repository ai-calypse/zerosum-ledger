// decision: D05-13 — docs/step_05_instruments_fake_providers.md#decisions-and-outputs
package dev.zerosum.instrument.api;

import java.util.UUID;
import org.springframework.http.HttpStatus;

/** An API failure carrying the stable {@code code} the master's REST conventions require (master §5.6). */
class InstrumentApiException extends RuntimeException {

    private final HttpStatus status;
    private final String code;

    private InstrumentApiException(HttpStatus status, String code, String detail) {
        super(detail);
        this.status = status;
        this.code = code;
    }

    HttpStatus status() {
        return status;
    }

    String code() {
        return code;
    }

    static InstrumentApiException unauthorized() {
        return new InstrumentApiException(HttpStatus.UNAUTHORIZED, "unauthorized", "a valid bearer token is required");
    }

    static InstrumentApiException forbidden(String detail) {
        return new InstrumentApiException(HttpStatus.FORBIDDEN, "forbidden", detail);
    }

    static InstrumentApiException invalidAttemptId(String attemptId) {
        return new InstrumentApiException(HttpStatus.BAD_REQUEST, "invalid_attempt_id",
                "attempt_id must be a UUID, was " + attemptId);
    }

    static InstrumentApiException attemptNotFound(UUID attemptId) {
        return new InstrumentApiException(HttpStatus.NOT_FOUND, "attempt_not_found", "no such attempt: " + attemptId);
    }

    /** decision: D05-6 — a registration body that could never produce a usable attempt. */
    static InstrumentApiException invalidRegistration(String detail) {
        return new InstrumentApiException(HttpStatus.BAD_REQUEST, "invalid_registration", detail);
    }

    /**
     * A provider no adapter serves. Refused at registration rather than accepted and discovered later, when the
     * symptom would be a rider who was simply never charged and nothing in the logs to say why.
     */
    static InstrumentApiException unknownProvider(String provider) {
        return new InstrumentApiException(HttpStatus.BAD_REQUEST, "unknown_provider",
                "no payment instrument is registered for provider " + provider);
    }

    /**
     * Cancellation is allowed only from {@code CREATED} (master §5.11). The current status is in the detail, because
     * the caller's next decision depends on which way the race went: an attempt already {@code SUBMITTING} may still
     * decline, while one already {@code SUCCEEDED} has moved money and needs a refund rather than a cancellation.
     */
    static InstrumentApiException notCancellable(String status) {
        return new InstrumentApiException(HttpStatus.CONFLICT, "not_cancellable",
                "an attempt can only be cancelled while CREATED; this one is " + status);
    }

    /** decision: D03-3 — every POST that creates money state requires a key (master §5.6). */
    static InstrumentApiException idempotencyKeyMissing() {
        return new InstrumentApiException(HttpStatus.BAD_REQUEST, "idempotency_key_missing",
                "Idempotency-Key is required, and must be at most 255 characters");
    }

    /** decision: D03-3 — the same key with a different request is a caller bug, never a replay. */
    static InstrumentApiException idempotencyKeyReused(String detail) {
        return new InstrumentApiException(HttpStatus.UNPROCESSABLE_CONTENT, "idempotency_key_reused", detail);
    }

    static InstrumentApiException invalidReportDate(String detail) {
        return new InstrumentApiException(HttpStatus.BAD_REQUEST, "invalid_report_date", detail);
    }

    /** The provider publishes no settlement reports, so there is nothing to reconcile against (D05-1). */
    static InstrumentApiException settlementReportsUnsupported(String detail) {
        return new InstrumentApiException(HttpStatus.UNPROCESSABLE_CONTENT, "settlement_reports_unsupported", detail);
    }

    /** The day has not closed. Retryable on the next cycle, and nothing was persisted. */
    static InstrumentApiException reportNotReady(String detail) {
        return new InstrumentApiException(HttpStatus.CONFLICT, "report_not_ready", detail);
    }

    /**
     * The provider could not be asked. Deliberately not a reconciliation result: a run that could not fetch its
     * report must leave no trace, or an outage would be recorded as a day on which nothing settled.
     */
    static InstrumentApiException providerUnavailable(String detail) {
        return new InstrumentApiException(HttpStatus.SERVICE_UNAVAILABLE, "provider_unavailable", detail);
    }

    static InstrumentApiException unsupportedCurrency(String detail) {
        return new InstrumentApiException(HttpStatus.UNPROCESSABLE_CONTENT, "unsupported_currency", detail);
    }

    static InstrumentApiException runNotFound(String runId) {
        return new InstrumentApiException(HttpStatus.NOT_FOUND, "reconciliation_run_not_found",
                "no such reconciliation run: " + runId);
    }
}
