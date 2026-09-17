package dev.zerosum.order.api;

import dev.zerosum.order.order.OrderStore;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Every failure becomes an RFC 9457 problem with a stable {@code code} (D03-2). Callers branch on {@code code}, never
 * on the human-readable title, and no response carries a stack trace or echoes the request.
 */
@RestControllerAdvice(assignableTypes = MoneyOrderController.class)
class ApiExceptionHandler {

    @ExceptionHandler(ApiException.class)
    ProblemDetail handle(ApiException failure) {
        return problem(failure.status(), failure.code(), failure.getMessage());
    }

    /** Unknown fields, duplicate keys, a float or string amount, or a body that is not JSON at all. */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    ProblemDetail handle(HttpMessageNotReadableException failure) {
        return problem(ApiException.Code.VALIDATION_FAILED,
                "the request body is not a valid money order: unknown fields, duplicate keys and non-integer amounts "
                        + "are rejected rather than coerced");
    }

    @ExceptionHandler(MissingServletRequestParameterException.class)
    ProblemDetail handle(MissingServletRequestParameterException failure) {
        return problem(ApiException.Code.VALIDATION_FAILED, failure.getParameterName() + " is required");
    }

    @ExceptionHandler(OrderStore.UnknownAdjustedOrderException.class)
    ProblemDetail handle(OrderStore.UnknownAdjustedOrderException failure) {
        return problem(ApiException.Code.UNKNOWN_ADJUSTED_ORDER, failure.getMessage());
    }

    @ExceptionHandler(OrderStore.AdjustmentGroupMismatchException.class)
    ProblemDetail handle(OrderStore.AdjustmentGroupMismatchException failure) {
        return problem(HttpStatus.UNPROCESSABLE_ENTITY, "unknown_adjusted_order", failure.getMessage());
    }

    /** PostgreSQL unreachable: fail fast after the pool connection timeout, storing nothing (master §6.6). */
    @ExceptionHandler(DataAccessResourceFailureException.class)
    ProblemDetail handle(DataAccessResourceFailureException failure) {
        return problem(ApiException.Code.DATABASE_UNAVAILABLE, "the orders database is unreachable");
    }

    /** Anything unexpected: a generic problem, never a stack trace or a request echo. */
    @ExceptionHandler(Exception.class)
    ProblemDetail handle(Exception failure) {
        return problem(ApiException.Code.INTERNAL_ERROR, "the request could not be processed");
    }

    private static ProblemDetail problem(ApiException.Code code, String detail) {
        return problem(code.status(), code.wireValue(), detail);
    }

    private static ProblemDetail problem(HttpStatus status, String code, String detail) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
        problem.setTitle(status.getReasonPhrase());
        problem.setProperty("code", code);
        return problem;
    }
}
