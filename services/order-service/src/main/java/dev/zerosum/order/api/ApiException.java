package dev.zerosum.order.api;

import org.springframework.http.HttpStatus;

/** An API failure carrying the stable {@code code} the master's REST conventions require (D03-2). */
class ApiException extends RuntimeException {

    private final HttpStatus status;
    private final String code;

    ApiException(HttpStatus status, String code, String detail) {
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

    static ApiException unauthorized() {
        return new ApiException(HttpStatus.UNAUTHORIZED, "unauthorized", "a valid bearer token is required");
    }

    static ApiException forbidden(String detail) {
        return new ApiException(HttpStatus.FORBIDDEN, "forbidden", detail);
    }

    static ApiException idempotencyKeyMissing() {
        return new ApiException(HttpStatus.BAD_REQUEST, "idempotency_key_missing",
                "the Idempotency-Key header is required on this endpoint");
    }

    static ApiException validationFailed(String detail) {
        return new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "validation_failed", detail);
    }

    static ApiException notZeroSum(String detail) {
        return new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "not_zero_sum", detail);
    }

    static ApiException keyReused() {
        return new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "idempotency_key_reused",
                "this idempotency key was already used with a different request body");
    }

    static ApiException keyInProgress() {
        return new ApiException(HttpStatus.CONFLICT, "idempotency_key_in_progress",
                "another request with this idempotency key is still in flight");
    }

    static ApiException unknownAdjustedOrder(String detail) {
        return new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "unknown_adjusted_order", detail);
    }

    static ApiException notFound() {
        return new ApiException(HttpStatus.NOT_FOUND, "order_not_found", "no such order");
    }
}
