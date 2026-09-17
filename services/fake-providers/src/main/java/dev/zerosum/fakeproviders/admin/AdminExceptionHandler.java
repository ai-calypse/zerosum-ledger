package dev.zerosum.fakeproviders.admin;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.server.ResponseStatusException;

/**
 * Renders admin failures as RFC 9457 problem details, with the reason in the body (D05-2).
 *
 * <p>Boot's default error body carries the status and the path but not the reason, so a rejected knob payload came
 * back as a bare 400 and an operator could not tell which field was wrong — the one thing that response exists to
 * say. Setting {@code server.error.include-message} was tried first and changed nothing under Boot 4.1; it would
 * also have exposed every message in the service, where this exposes exactly the responses the admin API controls.
 *
 * <p>Scoped to this package, so the provider APIs keep answering exactly as they did: their error shapes are what
 * the adapters were written against.
 */
@RestControllerAdvice(basePackageClasses = AdminExceptionHandler.class)
class AdminExceptionHandler {

    @ExceptionHandler(ResponseStatusException.class)
    ProblemDetail handle(ResponseStatusException failure) {
        HttpStatus status = HttpStatus.valueOf(failure.getStatusCode().value());
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status,
                failure.getReason() == null ? status.getReasonPhrase() : failure.getReason());
        problem.setTitle(status.getReasonPhrase());
        return problem;
    }
}
