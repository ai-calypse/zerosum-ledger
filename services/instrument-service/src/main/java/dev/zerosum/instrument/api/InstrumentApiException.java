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
     * decision: D05-3 (TB2) — the signature or its timestamp did not verify.
     *
     * <p>The detail deliberately does not say which: telling a caller "stale" rather than "wrong secret" tells a
     * forger which half to fix. Nothing is recorded, so a refused delivery leaves no trace an attacker can create.
     */
    static InstrumentApiException invalidSignature() {
        return new InstrumentApiException(HttpStatus.BAD_REQUEST, "invalid_signature",
                "the webhook signature did not verify within the 300 s tolerance");
    }

    /** Correctly signed, but not a payload this provider sends. Recorded nowhere: it could not be applied. */
    static InstrumentApiException invalidWebhook(String detail) {
        return new InstrumentApiException(HttpStatus.BAD_REQUEST, "invalid_webhook", detail);
    }

    /** A webhook path no adapter serves. 404 rather than 400: the path itself is what does not exist. */
    static InstrumentApiException unknownWebhookProvider(String provider) {
        return new InstrumentApiException(HttpStatus.NOT_FOUND, "unknown_provider",
                "no payment instrument is registered for provider " + provider);
    }

    /** Master §5.11 caps bodies at 64 KB; a larger one is refused before any HMAC is computed over it. */
    static InstrumentApiException webhookTooLarge(int capBytes) {
        return new InstrumentApiException(HttpStatus.PAYLOAD_TOO_LARGE, "payload_too_large",
                "webhook bodies are capped at " + capBytes + " bytes");
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
