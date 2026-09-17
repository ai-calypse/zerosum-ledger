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

    /**
     * Cancellation is allowed only from {@code CREATED} (master §5.11). The current status is in the detail, because
     * the caller's next decision depends on which way the race went: an attempt already {@code SUBMITTING} may still
     * decline, while one already {@code SUCCEEDED} has moved money and needs a refund rather than a cancellation.
     */
    static InstrumentApiException notCancellable(String status) {
        return new InstrumentApiException(HttpStatus.CONFLICT, "not_cancellable",
                "an attempt can only be cancelled while CREATED; this one is " + status);
    }
}
