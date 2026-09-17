package dev.zerosum.ledger.api;

import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

/**
 * Turns read-API failures into RFC 9457 problem details with a stable {@code code}, per the master's REST conventions.
 * Callers branch on {@code code}, never on the human-readable title.
 */
// Scoped to this package rather than to a list of controllers. The order-service copy of this advice was bound with
// assignableTypes and the first controller added outside that list returned 500 where it documented 401, with every
// problem code collapsing into internal_error. S04-T04 adds a freshness controller to this service, so the same trap
// was already loaded here; a new controller is now covered by default instead of by remembering to edit this line.
@RestControllerAdvice(basePackageClasses = LedgerApiExceptionHandler.class)
class LedgerApiExceptionHandler {

    @ExceptionHandler(LedgerApiException.class)
    ProblemDetail handle(LedgerApiException failure) {
        return problem(failure.status(), failure.code(), failure.getMessage());
    }

    /** A non-numeric after_seq or limit never reaches the controller; it fails binding first. */
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    ProblemDetail handle(MethodArgumentTypeMismatchException failure) {
        String code = "limit".equals(failure.getName()) ? "invalid_limit" : "invalid_cursor";
        return problem(HttpStatus.BAD_REQUEST, code, failure.getName() + " must be a number");
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ProblemDetail handle(MethodArgumentNotValidException failure) {
        return problem(HttpStatus.BAD_REQUEST, "invalid_entity_id", failure.getMessage());
    }

    /**
     * The database is unreachable: fail with 503 after the pool connection timeout rather than returning a partial
     * page (master §6.6).
     */
    /**
     * An operational read that exceeds its own statement timeout is reported as a distinct problem, never as
     * "consistent": a caller must not read a timeout as a clean ledger (D02-8).
     */
    @ExceptionHandler(org.springframework.dao.QueryTimeoutException.class)
    ProblemDetail handle(org.springframework.dao.QueryTimeoutException failure) {
        return problem(HttpStatus.SERVICE_UNAVAILABLE, "operational_read_timeout",
                "the invariants or verify read exceeded its statement timeout before completing");
    }

    @ExceptionHandler(DataAccessResourceFailureException.class)
    ProblemDetail handle(DataAccessResourceFailureException failure) {
        return problem(HttpStatus.SERVICE_UNAVAILABLE, "database_unavailable", "the ledger database is unreachable");
    }

    private static ProblemDetail problem(HttpStatus status, String code, String detail) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
        problem.setTitle(status.getReasonPhrase());
        problem.setProperty("code", code);
        return problem;
    }
}
