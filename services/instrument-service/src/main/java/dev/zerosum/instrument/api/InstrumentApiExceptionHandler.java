// decision: D05-13 — docs/step_05_instruments_fake_providers.md#decisions-and-outputs
package dev.zerosum.instrument.api;

import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Turns API failures into RFC 9457 problem details with a stable {@code code} (master §5.6). Callers branch on
 * {@code code}, never on the human-readable title.
 */
// Scoped to this package, not to a list of controllers: S05-T09 to T11 add three more controllers here, and the
// order-service copy bound with assignableTypes returned 500 for the first controller left off that list, with every
// problem code collapsing into internal_error.
@RestControllerAdvice(basePackageClasses = InstrumentApiExceptionHandler.class)
class InstrumentApiExceptionHandler {

    @ExceptionHandler(InstrumentApiException.class)
    ProblemDetail handle(InstrumentApiException failure) {
        return problem(failure.status(), failure.code(), failure.getMessage());
    }

    @ExceptionHandler(DataAccessResourceFailureException.class)
    ProblemDetail handle(DataAccessResourceFailureException failure) {
        return problem(HttpStatus.SERVICE_UNAVAILABLE, "database_unavailable",
                "the instruments database is unreachable");
    }

    private static ProblemDetail problem(HttpStatus status, String code, String detail) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
        problem.setTitle(status.getReasonPhrase());
        problem.setProperty("code", code);
        return problem;
    }
}
