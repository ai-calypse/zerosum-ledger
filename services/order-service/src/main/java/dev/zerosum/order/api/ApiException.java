package dev.zerosum.order.api;

import org.springframework.http.HttpStatus;

/** An API failure carrying the stable {@code code} the master's REST conventions require (D03-2). */
class ApiException extends RuntimeException {

    /**
     * Every problem code this service can return, with the status it carries (D03-2).
     *
     * <p>This enum is the single source: the advice builds responses from it and
     * {@code OpenApiSpecConsistencyTest} checks the specification against it, so a code cannot be added in one place
     * and forgotten in the other.
     */
    enum Code {
        UNAUTHORIZED("unauthorized", HttpStatus.UNAUTHORIZED),
        FORBIDDEN("forbidden", HttpStatus.FORBIDDEN),
        IDEMPOTENCY_KEY_MISSING("idempotency_key_missing", HttpStatus.BAD_REQUEST),
        VALIDATION_FAILED("validation_failed", HttpStatus.UNPROCESSABLE_ENTITY),
        NOT_ZERO_SUM("not_zero_sum", HttpStatus.UNPROCESSABLE_ENTITY),
        IDEMPOTENCY_KEY_REUSED("idempotency_key_reused", HttpStatus.UNPROCESSABLE_ENTITY),
        UNKNOWN_ADJUSTED_ORDER("unknown_adjusted_order", HttpStatus.UNPROCESSABLE_ENTITY),
        IDEMPOTENCY_KEY_IN_PROGRESS("idempotency_key_in_progress", HttpStatus.CONFLICT),
        ORDER_NOT_FOUND("order_not_found", HttpStatus.NOT_FOUND),
        DATABASE_UNAVAILABLE("database_unavailable", HttpStatus.SERVICE_UNAVAILABLE),
        INTERNAL_ERROR("internal_error", HttpStatus.INTERNAL_SERVER_ERROR);

        private final String wireValue;
        private final HttpStatus status;

        Code(String wireValue, HttpStatus status) {
            this.wireValue = wireValue;
            this.status = status;
        }

        String wireValue() {
            return wireValue;
        }

        HttpStatus status() {
            return status;
        }
    }

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

    private ApiException(Code code, String detail) {
        this(code.status(), code.wireValue(), detail);
    }

    static ApiException unauthorized() {
        return new ApiException(Code.UNAUTHORIZED, "a valid bearer token is required");
    }

    static ApiException forbidden(String detail) {
        return new ApiException(Code.FORBIDDEN, detail);
    }

    static ApiException idempotencyKeyMissing() {
        return new ApiException(Code.IDEMPOTENCY_KEY_MISSING,
                "the Idempotency-Key header is required on this endpoint");
    }

    static ApiException validationFailed(String detail) {
        return new ApiException(Code.VALIDATION_FAILED, detail);
    }

    static ApiException notZeroSum(String detail) {
        return new ApiException(Code.NOT_ZERO_SUM, detail);
    }

    static ApiException keyReused() {
        return new ApiException(Code.IDEMPOTENCY_KEY_REUSED,
                "this idempotency key was already used with a different request body");
    }

    static ApiException keyInProgress() {
        return new ApiException(Code.IDEMPOTENCY_KEY_IN_PROGRESS,
                "another request with this idempotency key is still in flight");
    }

    static ApiException unknownAdjustedOrder(String detail) {
        return new ApiException(Code.UNKNOWN_ADJUSTED_ORDER, detail);
    }

    static ApiException notFound() {
        return new ApiException(Code.ORDER_NOT_FOUND, "no such order");
    }
}
